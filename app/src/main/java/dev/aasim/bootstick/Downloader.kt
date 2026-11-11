package dev.aasim.bootstick

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.os.Environment
import android.os.StatFs
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** A byte range of a download; [end] is inclusive, or -1 for "until the server stops" (size unknown). */
class Seg(val start: Long, val end: Long, done: Long = 0) {
    val done = AtomicLong(done)
    val complete get() = end >= 0 && start + done.get() > end
}

/**
 * One download into [dir]. [page] is where the link was found, for finding its published checksum.
 * [check] is the checksum result once done: "ok" (with [algo]), "bad", "none" (nothing published), or null.
 */
class Task(
    val id: Long, val url: String, name: String, val dir: File, val page: String? = null,
    total: Long = -1, status: String = "paused", segs: List<Seg> = emptyList(), check: String? = null, algo: String? = null,
) {
    var name by mutableStateOf(name)
    var total by mutableLongStateOf(total)
    var done by mutableLongStateOf(segs.sumOf { it.done.get() })
    var speed by mutableLongStateOf(0L)

    /** queued, running, paused, failed, finishing (checking and unpacking) or done. */
    var status by mutableStateOf(status)
    var note by mutableStateOf<String?>(null)
    var check by mutableStateOf(check)
    var algo by mutableStateOf(algo)
    @Volatile var segs: List<Seg> = segs
    var job: Job? = null
    val conns = CopyOnWriteArrayList<HttpURLConnection>()
    val file get() = File(dir, name)
    val part get() = File(dir, "$name.part")
}

private class Fatal(message: String) : Exception(message)

/**
 * Downloads into Download/BootStick (or BootStick's folder on the SD card) with up to [PARTS] parallel ranged
 * connections, [SLOTS] files at a time. Progress is saved, so a download can be paused, survive the app being
 * closed, and resume or retry later from where it stopped. Finished files are checked against their published
 * checksum, and packed images are unpacked.
 */
object Downloader {
    private const val PARTS = 4
    private const val SLOTS = 2
    private const val MIN_PART = 16L shl 20
    private const val RETRIES = 6

    val tasks = mutableStateListOf<Task>()
    private val internal: File get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "BootStick")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val slots = Semaphore(SLOTS)
    private lateinit var app: Context
    private val state get() = File(app.filesDir, "downloads.json")
    private val settings get() = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        runCatching { load() }
    }

    val active get() = tasks.filter { it.status == "running" || it.status == "queued" || it.status == "finishing" }

    /** Only download on unmetered networks (Wi-Fi); downloads wait for one. */
    private var wifi by mutableStateOf(false)
    var wifiOnly: Boolean
        get() = wifi
        set(on) {
            wifi = on
            settings.edit().putBoolean("wifiOnly", on).apply()
        }

    /**
     * BootStick's own folder on the SD card. Apps need no permission there, but Android deletes it when the app is
     * uninstalled. Null without an SD card.
     */
    fun sdDir(): File? = app.getExternalFilesDirs(null).drop(1)
        .firstOrNull { it != null && Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }

    private var sd by mutableStateOf(false)
    var toSd: Boolean
        get() = sd
        set(on) {
            sd = on
            settings.edit().putBoolean("toSd", on).apply()
        }

    private fun target() = (if (toSd) sdDir() else null) ?: internal

    fun add(url: String, name: String, page: String? = null) {
        // The same link again resumes the existing download instead of starting a second copy.
        tasks.firstOrNull { it.url == url && it.status != "done" }?.let { return start(it) }
        val t = Task(System.currentTimeMillis(), url, name, target(), page)
        tasks.add(0, t)
        start(t)
    }

    fun start(t: Task) {
        if (t.job?.isActive == true || t.status == "done") return
        t.status = "queued"
        t.note = null
        save()
        ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java))
        t.job = scope.launch { run(t) }
    }

    fun pause(t: Task) {
        if (t.status != "running" && t.status != "queued") return
        stop(t)
        t.status = "paused"
        t.speed = 0
        save()
    }

    fun pauseAll() = active.forEach(::pause)

    /** Stops the download and deletes what was downloaded so far. */
    fun cancel(t: Task) {
        stop(t)
        t.part.delete()
        tasks.remove(t)
        save()
    }

    /** Forgets a finished download; the file stays. */
    fun forget(t: Task) {
        tasks.remove(t)
        save()
    }

    /** Deletes a finished file that failed its checksum and downloads it again from the start. */
    fun again(t: Task) {
        t.file.delete()
        t.segs = emptyList()
        t.done = 0
        t.check = null
        t.status = "paused"
        start(t)
    }

    private fun stop(t: Task) {
        t.job?.cancel()
        // Blocking socket reads ignore cancellation; closing the connections makes them return.
        t.conns.forEach { runCatching { it.disconnect() } }
    }

    private fun metered() = app.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered

    private suspend fun run(t: Task) {
        if (!slots.tryAcquire()) {
            t.note = app.getString(R.string.dl_waiting_turn)
            slots.acquire()
        }
        try {
            var attempt = 0
            while (true) {
                while (wifiOnly && metered()) {
                    t.status = "queued"
                    t.speed = 0
                    t.note = app.getString(R.string.dl_waiting_wifi)
                    delay(5_000)
                }
                val before = t.done
                try {
                    t.status = "running"
                    t.note = null
                    session(t)
                    finish(t)
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Pausing closes the sockets, which surfaces here as an IOException; that's not a failure.
                    currentCoroutineContext().ensureActive()
                    // Nor is Wi-Fi going away when only Wi-Fi is allowed: wait for it at the top of the loop.
                    if (wifiOnly && metered()) continue
                    if (t.done > before) attempt = 0
                    if (e is Fatal || ++attempt > RETRIES) {
                        t.status = "failed"
                        t.speed = 0
                        t.note = message(e)
                        save()
                        return
                    }
                    t.note = app.getString(R.string.dl_retrying, message(e), attempt, RETRIES)
                    save()
                    delay(minOf(30_000L, 2_000L shl attempt))
                }
            }
        } finally {
            slots.release()
        }
    }

    private suspend fun session(t: Task) = coroutineScope {
        val (url, total, ranges) = probe(t.url)
        // Links like SourceForge's ".../latest/download" or NixOS's "latest-..." only reveal the real file name after
        // the redirect, and the published checksums use that name.
        url.path.substringAfterLast('/').takeIf { isImageName(it) && it != t.name }?.let {
            t.part.renameTo(File(t.dir, "$it.part"))
            t.name = it
        }
        // Resume only when the server still serves the same size and supports ranges; otherwise start over.
        if (t.segs.isEmpty() || t.total != total || !ranges || !t.part.exists()) {
            if (t.segs.isNotEmpty()) t.note = app.getString(R.string.dl_restarted)
            t.total = total
            t.segs = plan(total, ranges)
            t.done = 0
            t.part.delete()
            t.dir.mkdirs()
            if (total > 0) reserve(t.dir, t.part, total)
            save()
        }
        RandomAccessFile(t.part, "rw").channel.use { ch ->
            val ticker = launch { tick(t) }
            try {
                t.segs.filter { !it.complete }.map { seg -> async { fetch(t, url, seg, ranges, ch) } }.awaitAll()
            } finally {
                ticker.cancel()
            }
        }
        if (t.total < 0) t.total = t.part.length()
        t.done = t.total
    }

    private fun plan(total: Long, ranges: Boolean): List<Seg> {
        if (!ranges || total <= 0) return listOf(Seg(0, -1))
        val n = (total / MIN_PART).coerceIn(1, PARTS.toLong())
        val size = total / n
        return (0 until n).map { i -> Seg(i * size, if (i == n - 1) total - 1 else (i + 1) * size - 1) }
    }

    /** Reserves the space up front, so a full phone fails now rather than at 90%. */
    private fun reserve(dir: File, f: File, total: Long) {
        val free = StatFs(dir.path).availableBytes
        if (free < total) throw Fatal(app.getString(R.string.dl_no_space_detail, size(app, total), size(app, free)))
        RandomAccessFile(f, "rw").use {
            try {
                Os.posix_fallocate(it.fd, 0, total)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.ENOSPC) throw Fatal(app.getString(R.string.dl_no_space))
            }
        }
    }

    private fun open(t: Task?, url: URL) = (url.openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", USER_AGENT)
        // Without this Android asks for gzip, which breaks byte ranges and sizes.
        setRequestProperty("Accept-Encoding", "identity")
        t?.conns?.add(this)
    }

    /** Final URL after redirects, total size (-1 if unknown), and whether byte ranges work. */
    private fun probe(link: String): Triple<URL, Long, Boolean> {
        val c = open(null, URL(link))
        c.setRequestProperty("Range", "bytes=0-0")
        try {
            return when (val code = c.responseCode) {
                206 -> {
                    val total = c.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1
                    Triple(c.url, total, total > 0)
                }
                200 -> Triple(c.url, c.contentLengthLong, false)
                in 400..499 -> throw Fatal(app.getString(R.string.dl_link_outdated, code))
                else -> throw IOException(app.getString(R.string.dl_server_answered, code))
            }
        } finally {
            c.disconnect()
        }
    }

    private suspend fun fetch(t: Task, url: URL, seg: Seg, ranges: Boolean, ch: FileChannel) = coroutineScope {
        val from = seg.start + seg.done.get()
        val c = open(t, url)
        try {
            if (ranges) c.setRequestProperty("Range", "bytes=$from-${seg.end}")
            val code = c.responseCode
            if (ranges && code != 206) throw IOException(app.getString(R.string.dl_no_resume, code))
            if (!ranges && code != 200) throw IOException(app.getString(R.string.dl_server_answered, code))
            c.inputStream.use { input ->
                val buf = ByteArray(256 shl 10)
                var pos = from
                while (isActive && (seg.end < 0 || pos <= seg.end)) {
                    val n = input.read(buf)
                    if (n < 0) break
                    val bb = ByteBuffer.wrap(buf, 0, n)
                    while (bb.hasRemaining()) pos += ch.write(bb, pos)
                    seg.done.addAndGet(n.toLong())
                }
            }
            if (seg.end >= 0 && !seg.complete && isActive) throw IOException(app.getString(R.string.dl_closed_early))
        } finally {
            c.disconnect()
            t.conns.remove(c)
        }
    }

    private suspend fun tick(t: Task) {
        var last = t.segs.sumOf { it.done.get() }
        var saved = System.nanoTime()
        while (true) {
            delay(500)
            val now = t.segs.sumOf { it.done.get() }
            t.done = now
            t.speed = (t.speed * 3 + (now - last) * 2) / 4 // bytes/s, smoothed over ~2 s
            if (now > last) t.note = null
            last = now
            if (System.nanoTime() - saved > 3_000_000_000) { save(); saved = System.nanoTime() }
            // Wi-Fi went away: drop the connections, and run() waits for it to come back.
            if (wifiOnly && metered()) t.conns.forEach { runCatching { it.disconnect() } }
        }
    }

    /** [f], or "name (2).iso" and so on if it exists; packed images keep both extensions ("name (2).img.xz"). */
    private fun unique(f: File): File {
        var target = f
        var i = 2
        val ext = Regex("(?i)(\\.(iso|img)\\.(xz|gz|zip)|\\.[^.]*)$").find(f.name)?.value.orEmpty()
        val base = f.name.removeSuffix(ext)
        while (target.exists()) target = File(f.parentFile, "$base ($i)$ext").also { i++ }
        return target
    }

    private suspend fun finish(t: Task) {
        val served = t.name
        val target = unique(t.file)
        if (!t.part.renameTo(target)) throw Fatal(app.getString(R.string.dl_cant_save, target.name))
        t.name = target.name
        t.status = "finishing"
        t.speed = 0
        save()
        // The file is complete from here on: a failed check or unpack becomes a note on it, never a retry, which would
        // start a new, empty .part.
        val note = try {
            verifyAndUnpack(t, target, served)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message(e)
        }
        t.status = "done"
        t.note = note
        save()
        MediaScannerConnection.scanFile(app, arrayOf(t.file.path), null, null)
        DownloadService.notifyDone(app, t.name, t.check)
    }

    /** Compares [target] with its published checksum, then unpacks it if it's a packed image. Returns a note, if any. */
    private suspend fun verifyAndUnpack(t: Task, target: File, served: String): String? {
        val job = currentCoroutineContext()
        // A missing checksum file or a network hiccup here mustn't fail a finished download.
        t.note = app.getString(R.string.dl_finding_checksum)
        val want = runCatching { publishedChecksum(t.url, served, t.page) }.getOrNull()
        val algo = want?.let(::algoFor)
        if (want != null && algo != null) {
            var pct = -1
            val got = FileInputStream(target).use { input ->
                digest(input, algo) { n ->
                    job.ensureActive()
                    val p = (n * 100 / target.length().coerceAtLeast(1)).toInt()
                    if (p != pct) { pct = p; t.note = app.getString(R.string.dl_verifying, algo, p) }
                }
            }
            t.check = if (got == want) "ok" else "bad"
            t.algo = algo
        } else {
            t.check = "none"
        }
        if (t.check == "bad" || !isPacked(t.name)) return null

        val out = unique(File(t.dir, unpackedName(t.name)))
        // Into .part, so a kill mid-unpack never leaves a truncated image that looks mountable.
        val part = File(t.dir, out.name + ".part")
        try {
            var shown = 0L
            FileInputStream(target).use { input ->
                FileOutputStream(part).use { o ->
                    unpack(t.name, input, o) { n ->
                        job.ensureActive()
                        if (n - shown > 32 * MIB) { shown = n; t.note = app.getString(R.string.dl_unpacking, size(app, n)) }
                    }
                }
            }
            if (!part.renameTo(out)) throw IOException(app.getString(R.string.dl_cant_save, out.name))
        } catch (e: Exception) {
            part.delete()
            if (e is CancellationException) throw e
            return app.getString(R.string.dl_unpack_failed, message(e))
        }
        target.delete()
        t.name = out.name
        t.total = out.length()
        return null
    }

    private fun message(e: Exception) = when (e) {
        is UnknownHostException -> app.getString(R.string.dl_no_internet)
        is SocketTimeoutException -> app.getString(R.string.dl_timed_out)
        else -> e.message ?: e.toString()
    }

    @Synchronized
    private fun save() {
        val arr = JSONArray()
        tasks.toList().forEach { t ->
            arr.put(
                JSONObject().put("id", t.id).put("url", t.url).put("name", t.name).put("total", t.total)
                    .put("status", t.status).put("dir", t.dir.path).put("page", t.page).put("check", t.check).put("algo", t.algo)
                    .put("segs", JSONArray(t.segs.map { JSONArray(listOf(it.start, it.end, it.done.get())) })),
            )
        }
        state.writeText(arr.toString())
    }

    private fun load() {
        wifi = settings.getBoolean("wifiOnly", false)
        sd = settings.getBoolean("toSd", false)
        val arr = JSONArray(state.readText())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            fun opt(k: String) = o.optString(k).ifEmpty { null }
            val segs = o.getJSONArray("segs").let { s -> (0 until s.length()).map { j -> s.getJSONArray(j).let { Seg(it.getLong(0), it.getLong(1), it.getLong(2)) } } }
            // Anything that was mid-download when the app last stopped is paused, ready to resume. A file that was being
            // checked or unpacked is complete, just unchecked.
            val status = when (val s = o.getString("status")) {
                "running", "queued" -> "paused"
                "finishing" -> "done"
                else -> s
            }
            tasks.add(
                Task(
                    o.getLong("id"), o.getString("url"), o.getString("name"), opt("dir")?.let(::File) ?: internal, opt("page"),
                    o.getLong("total"), status, segs, opt("check"), opt("algo"),
                ),
            )
        }
    }
}
