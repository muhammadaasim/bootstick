package dev.aasim.bootstick

import android.content.Context
import androidx.annotation.StringRes
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** One image found by `bootstick.sh scan`, or picked by hand. */
class Image(val path: String, val size: Long, val kind: String, val biosOnly: Boolean) {
    val name get() = path.substringAfterLast('/')
}

/** An image shown to the computer, and how; each one is its own USB drive. */
data class Mount(val path: String, val mode: String)

private val LUN = Regex("lun\\d+")

/** Output of one engine call; most commands print `key=value` lines. */
class Output(val code: Int, val out: String) {
    val ok get() = code == 0
    val values: Map<String, String> by lazy {
        out.lines().mapNotNull { line ->
            val i = line.indexOf('=')
            if (i > 0) line.substring(0, i) to line.substring(i + 1) else null
        }.toMap()
    }
    val hasRoot get() = values["root"] == "1"
    val error get() = values["error"] ?: out.trim().ifEmpty { "Failed with exit code $code" }

    /** Every drive, in order. A path is empty once the computer has ejected that drive. */
    val luns: List<Mount>
        get() = values.keys.filter { it.matches(LUN) }.sortedBy { it.drop(3).toInt() }
            .map { values.getValue(it).let { v -> Mount(v.substringAfter(':'), v.substringBefore(':')) } }
}

/** Runs the bundled bootstick.sh as root, and remembers what the user wants mounted. */
object Engine {
    @Synchronized
    fun run(ctx: Context, vararg args: String): Output = try {
        val p = start(ctx, *args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        Output(p.waitFor(), out)
    } catch (e: IOException) {
        // KernelSU hides su from apps it hasn't granted, so a missing binary also means "not granted".
        Output(-1, "error=no_su")
    }

    @Volatile private var script: File? = null

    private fun start(ctx: Context, vararg args: String): ProcessBuilder {
        // Copied once per process, so an app update (which restarts it) runs its own script. Not on every call:
        // sh reads a script as it goes, and a long `read` or `put` would see it being rewritten.
        val s = script ?: synchronized(this) {
            script ?: File(ctx.filesDir, "bootstick.sh").also { f ->
                ctx.assets.open("bootstick.sh").use { input -> f.outputStream().use { input.copyTo(it) } }
                script = f
            }
        }
        val cmd = (listOf("sh", s.path) + args).joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
        return ProcessBuilder("su", "-c", cmd)
    }

    /** Reads an image file through root, for files other apps made. Closing it throws if root couldn't read it all. */
    fun read(ctx: Context, path: String): InputStream {
        val p = start(ctx, "read", path).redirectError(File("/dev/null")).start()
        p.outputStream.close()
        return object : FilterInputStream(p.inputStream) {
            override fun close() {
                super.close()
                if (p.waitFor() != 0) throw IOException("Couldn't read $path")
            }
        }
    }

    /** Writes a new image file at [path] through root. [write] returns the bytes it wrote; anything less is deleted. */
    fun put(ctx: Context, path: String, write: (OutputStream) -> Long) {
        val p = start(ctx, "put", path).redirectErrorStream(true).start()
        val written = runCatching { p.outputStream.use(write) }
        val out = Output(p.waitFor(), p.inputStream.bufferedReader().readText())
        val done = run(ctx, "commit", path, (written.getOrNull() ?: -1).toString())
        if (!out.ok) throw IOException(out.error)
        written.exceptionOrNull()?.let { throw it }
        if (!done.ok) throw IOException(done.error)
    }

    fun scan(ctx: Context): List<Image> = run(ctx, "scan").out.lines().mapNotNull { line ->
        val f = line.split('\t', limit = 4)
        if (f.size == 4) Image(f[3], f[0].toLongOrNull() ?: 0, f[1], f[2] == "1") else null
    }

    fun attach(ctx: Context, mounts: List<Mount>): Output {
        val r = run(ctx, "attach", *mounts.flatMap { listOf(it.path, it.mode) }.toTypedArray())
        if (r.ok) {
            prefs(ctx).edit().putString("wanted", encode(mounts)).apply()
            settings(ctx).edit().apply {
                putString("last", encode(mounts))
                mounts.forEach { putString("mode:" + it.path, it.mode) }
            }.apply()
        }
        return r
    }

    fun detach(ctx: Context): Output {
        forget(ctx)
        return run(ctx, "detach")
    }

    /** The images the user mounted, kept so they can be re-attached; empty when nothing is. */
    fun wanted(ctx: Context) = decode(prefs(ctx).getString("wanted", null))

    fun forget(ctx: Context) = prefs(ctx).edit().clear().apply()

    /** What was mounted last time, for the quick settings tile and the "Mount last" shortcut. */
    fun last(ctx: Context) = decode(settings(ctx).getString("last", null))

    /** The mode this image was last mounted in. */
    fun lastMode(ctx: Context, path: String): String? = settings(ctx).getString("mode:$path", null)

    /** Mount the images again when the phone restarts. */
    fun autoMount(ctx: Context) = settings(ctx).getBoolean("autoMount", false)
    fun setAutoMount(ctx: Context, on: Boolean) = settings(ctx).edit().putBoolean("autoMount", on).apply()

    private fun encode(m: List<Mount>) = m.joinToString("\n") { it.mode + "\t" + it.path }
    private fun decode(s: String?) = s.orEmpty().lines().filter { '\t' in it }.map { Mount(it.substringAfter('\t'), it.substringBefore('\t')) }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("mount", Context.MODE_PRIVATE)
    private fun settings(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
}

/** A root manager BootStick can give exact steps for. The packages are also listed under <queries> in the manifest. */
class RootApp(val pkg: String, val name: String, @StringRes val steps: Int)

private val rootApps = listOf(
    RootApp("com.rifsxd.ksunext", "KernelSU Next", R.string.root_steps_ksunext),
    RootApp("me.weishu.kernelsu", "KernelSU", R.string.root_steps_kernelsu),
    RootApp("me.bmax.apatch", "APatch", R.string.root_steps_apatch),
    RootApp("com.topjohnwu.magisk", "Magisk", R.string.root_steps_magisk),
)

fun installedRootApp(ctx: Context) = rootApps.firstOrNull {
    runCatching { ctx.packageManager.getPackageInfo(it.pkg, 0) }.isSuccess
}

@StringRes
fun modeLabel(mode: String) = when (mode) {
    "ro" -> R.string.mode_ro
    "cdrom" -> R.string.mode_cdrom
    else -> R.string.mode_disk
}
