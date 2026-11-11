package dev.aasim.bootstick

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * A downloadable image. Without [pattern], [url] is the file itself. With it, [url] is a page or folder and the
 * newest match of [pattern] in it is used, after first stepping into the newest folder matching [dir] (then [sub])
 * when set. So new point releases (26.04.1, 13.7.0, 22.3, ...) need no app update.
 */
class Distro(
    val name: String,
    val group: String,
    val note: String,
    val url: String,
    val pattern: String? = null,
    val dir: String? = null,
    val sub: String = "",
)

/**
 * The download list, from assets/catalog.json. A copy with a higher "version" at [REMOTE] replaces it, so a project
 * that moves its downloads (Ubuntu 28.04, Rocky 11) only needs that file updated, not the app.
 */
object Catalog {
    private const val REMOTE = "$APP_PAGE/catalog.json"
    var distros by mutableStateOf(emptyList<Distro>())
        private set
    val groups get() = distros.map { it.group }.distinct()

    fun init(ctx: Context) {
        if (distros.isNotEmpty()) return
        val cache = File(ctx.filesDir, "catalog.json")
        val bundled = parseCatalog(ctx.assets.open("catalog.json").reader().readText())
        var best = runCatching { parseCatalog(cache.readText()) }.getOrNull()?.takeIf { it.first > bundled.first } ?: bundled
        distros = best.second
        // Checked at most daily. A missing or broken copy (or no internet) just keeps the list we have.
        if (System.currentTimeMillis() - cache.lastModified() > 86_400_000L) thread(isDaemon = true) {
            runCatching {
                val text = get(REMOTE)
                val remote = parseCatalog(text)
                cache.writeText(text)
                if (remote.first > best.first) { best = remote; distros = remote.second }
            }
        }
    }
}

/** Version and entries of a catalog file. Throws unless every entry is complete, uses https and has valid patterns. */
fun parseCatalog(text: String): Pair<Int, List<Distro>> {
    val o = JSONObject(text)
    val arr = o.getJSONArray("distros")
    val list = (0 until arr.length()).map { i ->
        val d = arr.getJSONObject(i)
        fun opt(k: String) = d.optString(k).ifEmpty { null }
        Distro(d.getString("name"), d.getString("group"), d.getString("note"), d.getString("url"), opt("pattern"), opt("dir"), d.optString("sub"))
            .also { require(it.url.startsWith("https://")) { "${it.name}: not https" }; it.pattern?.let(::Regex); it.dir?.let(::Regex) }
    }
    require(list.isNotEmpty())
    return o.getInt("version") to list
}

/** Catalog groups are data, but the common ones are translated. */
@StringRes
fun groupLabel(group: String) = when (group) {
    "Desktop" -> R.string.group_desktop
    "Server" -> R.string.group_server
    "Lightweight" -> R.string.group_lightweight
    "Rescue & tools" -> R.string.group_rescue
    "Security & privacy" -> R.string.group_security
    "BSD & more" -> R.string.group_bsd
    else -> null
}

/**
 * Newest by version: first by the leading version (22.3 beats 22, 26.04.1 beats 26.04, without the 64 in "amd64"
 * interfering), then by every number in the name (proxmox-ve_9.2-2 beats 9.2-1).
 */
fun newest(names: Sequence<String>): String? {
    fun nums(s: String) = Regex("\\d+").findAll(s).map { it.value.toBigInteger() }.toList()
    fun cmp(x: List<java.math.BigInteger>, y: List<java.math.BigInteger>) =
        x.zip(y).map { (p, q) -> p.compareTo(q) }.firstOrNull { it != 0 } ?: x.size.compareTo(y.size)
    return names.distinct().maxWithOrNull { a, b ->
        val lead = cmp(nums(Regex("\\d+(\\.\\d+)*").find(a)?.value.orEmpty()), nums(Regex("\\d+(\\.\\d+)*").find(b)?.value.orEmpty()))
        if (lead != 0) lead else cmp(nums(a), nums(b))
    }
}

/** A direct download: its URL, file name, and the page it was found on (where its checksum may be too). */
class Resolved(val url: String, val name: String, val page: String?)

/** Finds the current file for [d]; does network I/O for [Distro.pattern] entries. */
fun resolve(d: Distro): Resolved {
    var base = d.url
    d.dir?.let { re ->
        base += (newest(Regex(re).findAll(get(base)).map { it.value }) ?: throw IOException("no release folder at $base")) + d.sub
    }
    val p = d.pattern ?: return Resolved(base, base.substringAfterLast('/'), null)
    val file = newest(Regex(p).findAll(get(base)).map { it.value }) ?: throw IOException("no matching file at $base")
    val url = URL(URL(base), file).toString()
    return Resolved(url, url.substringAfterLast('/'), base)
}

private val QUOTED = Regex("""["']([^"'<>\s]+)["']""")
private val SUMS_NAME = Regex("(?i)(sha\\d*sums?|\\.sha\\d+|md5|checksum|hashes)")

/**
 * The published checksum of [file], downloaded from [link] (found on [page]), or null. It's taken from a checksum
 * file listed next to it (SHA256SUMS, CHECKSUM, x.iso.sha256...), or from the page itself when that's an API answer
 * carrying it (Pop!_OS). The strongest one wins where several are published.
 */
fun publishedChecksum(link: String, file: String, page: String?): String? {
    val named = Regex(Regex.escape(file) + "(?![\\w.-])")
    fun strongest(text: String) = text.lineSequence().filter { named.containsMatchIn(it) }.flatMap { hashesIn(it) }.maxByOrNull { it.length }
    val pages = listOfNotNull(page, link.substringBeforeLast('/') + "/").distinct()
        .mapNotNull { u -> runCatching { u to get(u) }.getOrNull() }
    pages.firstNotNullOfOrNull { (_, text) -> if (text.contains("<html", true)) null else strongest(text) }?.let { return it }
    val listed = pages.flatMap { (u, text) ->
        QUOTED.findAll(text).map { it.groupValues[1] }.filter { SUMS_NAME.containsMatchIn(it.substringAfterLast('/')) }
            .mapNotNull { runCatching { URL(URL(u), it).toString() }.getOrNull() }.toList()
    }.sortedBy {
        // Strongest first: Kali, for one, lists SHA1SUMS before SHA256SUMS.
        val n = it.substringAfterLast('/').lowercase()
        when {
            "512" in n -> 0
            "256" in n -> 1
            "sha1" in n || "md5" in n -> 3
            else -> 2
        }
    }
    val guesses = listOf(".sha256", ".sha512", ".sha256sum", ".sha512sum", ".sha1", ".md5").map { link + it }
    // Files named after this one first, then the usual "<file>.sha256" names, then the folder's SHA256SUMS and the like.
    // Bounded, so a slow server can't hold a finished download for long.
    val until = System.currentTimeMillis() + 60_000
    for (u in (listed.filter { file in it } + guesses + listed).distinct().take(16)) {
        if (System.currentTimeMillis() > until) break
        val text = runCatching { get(u, 1 shl 20) }.getOrNull() ?: continue
        strongest(text)?.let { return it }
        // "<file>.sha256" files sometimes hold just the hash, or name the file differently.
        if (u.substringAfterLast('/').startsWith(file)) hashesIn(text).distinct().singleOrNull()?.let { return it }
    }
    return null
}

/** GETs [u] as text, up to [max] bytes. */
fun get(u: String, max: Int = 4 shl 20): String {
    val c = URL(u).openConnection() as HttpURLConnection
    c.connectTimeout = 15_000
    c.readTimeout = 30_000
    c.setRequestProperty("User-Agent", USER_AGENT)
    try {
        if (c.responseCode !in 200..299) throw IOException("The server answered ${c.responseCode}")
        val out = ByteArrayOutputStream()
        c.inputStream.use { input ->
            val buf = ByteArray(1 shl 16)
            while (out.size() < max) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        }
        return out.toString("ISO-8859-1")
    } finally {
        c.disconnect()
    }
}

const val USER_AGENT = "BootStick/1.0 (Android)"
