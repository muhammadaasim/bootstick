package dev.aasim.bootstick

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.annotation.StringRes
import org.tukaani.xz.XZInputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import kotlin.random.Random

// Image tools that need no root: they work on files picked through the system file picker.

/** Bytes needed by [kindOf]: the ISO 9660 signature ends at offset 32774. */
const val HEAD_SIZE = 32774

private val IMAGE = Regex("(?i)\\.(iso|img|bin|raw|ima|dsk|vhd)$")
private val PACKED = Regex("(?i)\\.(iso|img)\\.(xz|gz|zip)$")

/** An .iso or .img packed as .xz, .gz or .zip (Raspberry Pi OS, Armbian, Memtest86+...). Same rule as `kind` in bootstick.sh. */
fun isPacked(name: String) = PACKED.containsMatchIn(name)

fun isImageName(name: String) = IMAGE.containsMatchIn(name) || isPacked(name)

/** Same rules as `kind` in bootstick.sh. */
fun kindOf(head: ByteArray): String {
    val mbr = head.size >= 512 && head[510] == 0x55.toByte() && head[511] == 0xAA.toByte()
    val iso = head.size >= HEAD_SIZE && String(head, 32769, 5, Charsets.ISO_8859_1) == "CD001"
    return when {
        iso && mbr -> "hybrid"
        iso -> "iso"
        mbr -> "disk"
        head.take(4096).all { it == 0.toByte() } -> "blank"
        else -> "other"
    }
}

/** Safest mode that still boots: hybrid ISOs as read-only disks, plain ISOs as CD-ROMs, disks writable. */
fun suggestedMode(kind: String) = when (kind) {
    "hybrid", "other" -> "ro"
    "iso" -> "cdrom"
    else -> "disk"
}

/**
 * ISOs are offered only as read-only disk or CD-ROM: ISO 9660 can't be changed in place, and a computer offering to
 * "format" a writable ISO would destroy it. Only .img files can be mounted writable.
 */
fun modesFor(name: String) = if (name.endsWith(".iso", ignoreCase = true)) listOf("ro", "cdrom") else listOf("ro", "cdrom", "disk")

fun suggestedFor(name: String, kind: String) = suggestedMode(kind).takeIf { it in modesFor(name) } ?: "ro"

@StringRes
fun kindLabel(kind: String) = when (kind) {
    "hybrid" -> R.string.kind_hybrid
    "iso" -> R.string.kind_iso
    "disk" -> R.string.kind_disk
    "blank" -> R.string.kind_blank
    "compressed" -> R.string.kind_compressed
    else -> R.string.kind_other
}

@StringRes
fun kindHint(kind: String) = when (kind) {
    "hybrid" -> R.string.hint_hybrid
    "iso" -> R.string.hint_iso
    "disk" -> R.string.hint_disk
    "blank" -> R.string.hint_blank
    "compressed" -> R.string.hint_compressed
    else -> R.string.hint_other
}

/**
 * Same rule as `bios` in bootstick.sh: a bootable ISO whose El Torito catalog has no UEFI (platform 0xEF) entry.
 * UEFI-only PCs (most since ~2020) leave these out of the boot menu. [read] returns up to n bytes at a position.
 */
fun biosOnly(read: (pos: Long, n: Int) -> ByteArray): Boolean {
    val br = read(17 * 2048L, 75)
    if (br.size < 75 || String(br, 7, 23, Charsets.ISO_8859_1) != "EL TORITO SPECIFICATION") return false
    val cat = read((ByteBuffer.wrap(br, 71, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL) * 2048, 2048)
    // Catalog entries are 32 bytes; headers (01, 90, 91) carry the platform in their second byte.
    return (0 until cat.size - 1 step 32).none { cat[it].toInt() and 0xff in setOf(0x01, 0x90, 0x91) && cat[it + 1] == 0xEF.toByte() }
}

class PickedFile(val name: String, val size: Long, val kind: String, val biosOnly: Boolean)

fun inspect(cr: ContentResolver, uri: Uri): PickedFile {
    var name = uri.lastPathSegment ?: "file"
    var size = -1L
    if (uri.scheme == "file") size = java.io.File(uri.path!!).length()
    cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
        if (it.moveToFirst()) {
            name = it.getString(0) ?: name
            if (!it.isNull(1)) size = it.getLong(1)
        }
    }
    if (isPacked(name)) return PickedFile(name, size, "compressed", false)
    val head = ByteArray(HEAD_SIZE)
    var n = 0
    cr.openInputStream(uri)!!.use { input ->
        while (n < head.size) {
            val r = input.read(head, n, head.size - n)
            if (r < 0) break
            n += r
        }
    }
    val kind = kindOf(head.copyOf(n))
    // Needs random access; providers that only stream (rare) just get no warning.
    val bios = kind in setOf("hybrid", "iso") && runCatching {
        cr.openFileDescriptor(uri, "r")!!.use { pfd ->
            val ch = FileInputStream(pfd.fileDescriptor).channel
            biosOnly { pos, len -> ByteBuffer.allocate(len).also { ch.read(it, pos) }.let { it.array().copyOf(it.position()) } }
        }
    }.getOrDefault(false)
    return PickedFile(name, size, kind, bios)
}

/**
 * The real file behind a picked [uri], for root to open, from the descriptor's /proc link. Null when there isn't one,
 * for example a cloud file streamed through a pipe.
 */
fun realPath(cr: ContentResolver, uri: Uri): String? {
    if (uri.scheme == "file") return uri.path
    val path = runCatching { cr.openFileDescriptor(uri, "r")!!.use { Os.readlink("/proc/self/fd/${it.fd}") } }.getOrNull()
    return path?.takeIf { it.startsWith("/") && !it.startsWith("/proc/") && !it.endsWith(" (deleted)") }
}

private val HEX = Regex("(?<![0-9a-fA-F])([0-9a-fA-F]{128}|[0-9a-fA-F]{64}|[0-9a-fA-F]{40}|[0-9a-fA-F]{32})(?![0-9a-fA-F])")

/** Every checksum-shaped run of hex digits in [text], lowercased. */
fun hashesIn(text: String) = HEX.findAll(text).map { it.value.lowercase() }

/** MD5, SHA-1, SHA-256 or SHA-512: published checksums are told apart by their length. */
fun algoFor(hex: String) = when (hex.length) {
    32 -> "MD5"
    40 -> "SHA-1"
    64 -> "SHA-256"
    128 -> "SHA-512"
    else -> null
}

/** Lowercase hex digest; [progress] gets bytes read so far. */
fun digest(input: InputStream, algo: String, progress: (Long) -> Unit): String {
    val md = MessageDigest.getInstance(algo)
    copy(input, null, progress) { buf, n -> md.update(buf, 0, n) }
    return md.digest().joinToString("") { "%02x".format(it) }
}

/** Copies [input] to [out] (and to [also]); returns the bytes copied. */
fun copy(input: InputStream, out: OutputStream?, progress: (Long) -> Unit, also: (ByteArray, Int) -> Unit = { _, _ -> }): Long {
    val buf = ByteArray(1 shl 20)
    var done = 0L
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        out?.write(buf, 0, n)
        also(buf, n)
        done += n
        progress(done)
    }
    return done
}

/** The image inside a packed one: "raspios.img.xz" to "raspios.img". */
fun unpackedName(name: String) = name.substringBeforeLast('.')

/** Decompresses a packed image ([isPacked], told apart by [name]) from [input] into [out]; returns its size. */
fun unpack(name: String, input: InputStream, out: OutputStream, progress: (Long) -> Unit): Long {
    val src = when (name.substringAfterLast('.').lowercase()) {
        "xz" -> XZInputStream(input.buffered(1 shl 16))
        "gz" -> GZIPInputStream(input, 1 shl 16)
        else -> ZipInputStream(input.buffered(1 shl 16)).also { z ->
            // Zips often carry a README or checksum next to the image; skip to the image itself.
            while (true) {
                val e = z.nextEntry ?: throw IOException("There's no .iso or .img file inside ${name.substringAfterLast('/')}")
                if (!e.isDirectory && IMAGE.containsMatchIn(e.name)) break
            }
        }
    }
    return copy(src, out, progress)
}

const val MIB = 1L shl 20

// Decimal, like the sizes Android shows (Formatter): a "1 GB" image is shown as 1 GB, not 1.1 GB.
const val MB = 1_000_000L
const val GB = 1_000_000_000L

/** Largest image BootStick makes: the MBR partition limit, and far beyond any phone's storage. */
const val MAX_IMAGE = 1L shl 41

/** Smallest image [formatFat32] accepts; FAT32 needs at least 65525 clusters. */
const val MIN_FAT32 = 64 * MB

/** Bytes for "1.5" of [unit] (1 for bytes, [MB], [GB]), rounded up to whole 512-byte sectors, which USB drives are made of. */
fun parseSize(text: String, unit: Long): Long? {
    val v = text.trim().replace(',', '.').toBigDecimalOrNull() ?: return null
    if (v.signum() <= 0) return null
    val bytes = v.multiply(unit.toBigDecimal()).setScale(0, RoundingMode.CEILING)
    if (bytes > MAX_IMAGE.toBigDecimal()) return null
    return (bytes.toLong() + 511) / 512 * 512
}

/**
 * Makes [uri] a zero-filled file of [bytes], formatted as FAT32 when [fat32Label] is set. Space is reserved up front
 * so the computer can't run the phone out of storage halfway through writing to it.
 */
fun createBlank(cr: ContentResolver, uri: Uri, bytes: Long, fat32Label: String?, progress: (Long) -> Unit) {
    cr.openFileDescriptor(uri, "rw")!!.use { pfd ->
        val ch = FileOutputStream(pfd.fileDescriptor).channel
        try {
            Os.posix_fallocate(pfd.fileDescriptor, 0, bytes)
            progress(bytes)
        } catch (e: ErrnoException) {
            if (e.errno == OsConstants.ENOSPC) throw e
            // Filesystems without fallocate (some SD cards): write the zeros ourselves.
            val zeros = ByteBuffer.allocate(1 shl 20)
            var done = 0L
            while (done < bytes) {
                zeros.clear().limit(minOf(zeros.capacity().toLong(), bytes - done).toInt())
                done += ch.write(zeros, done)
                progress(done)
            }
        }
        fat32Label?.let { formatFat32(ch, bytes, it) }
    }
}

/**
 * Writes an MBR with one FAT32 partition from 1 MiB to the end, so every computer can use the image straight away.
 * [ch] must hold [bytes] of zeros. The layout is Windows': 32 reserved sectors, two FATs, cluster size by volume size.
 */
fun formatFat32(ch: FileChannel, bytes: Long, label: String) {
    val start = 2048L
    val sectors = bytes / 512 - start
    val spc = when {
        sectors <= 532_480 -> 1
        sectors <= 16_777_216 -> 8
        sectors <= 33_554_432 -> 16
        sectors <= 67_108_864 -> 32
        else -> 64
    }
    val reserved = 32
    val fatSize = (sectors - reserved + 128L * spc) / (128L * spc + 1)
    val clusters = (sectors - reserved - 2 * fatSize) / spc
    require(bytes <= MAX_IMAGE && clusters >= 65525) { "FAT32 needs an image of at least 64 MB" }

    fun sector() = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN).apply { put(510, 0x55).put(511, 0xAA.toByte()) }
    fun write(at: Long, b: ByteBuffer) {
        b.rewind()
        var pos = at * 512
        while (b.hasRemaining()) pos += ch.write(b, pos)
    }

    // Boot code is just "int 18h": a BIOS that tries to boot this disk moves on to the next device instead of hanging.
    val mbr = sector().put(0, 0xCD.toByte()).put(1, 0x18).putInt(440, Random.nextInt())
    mbr.put(447, 0xFE.toByte()).put(448, 0xFF.toByte()).put(449, 0xFF.toByte()) // CHS unused: LBA only
    mbr.put(450, 0x0C).put(451, 0xFE.toByte()).put(452, 0xFF.toByte()).put(453, 0xFF.toByte()) // FAT32 (LBA)
    mbr.putInt(454, start.toInt()).putInt(458, sectors.toInt())

    val name = label.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' || it in " _-" }.take(11).ifBlank { "BOOTSTICK" }.padEnd(11)
    val boot = sector().put(0xEB.toByte()).put(0x58).put(0x90.toByte()).put("MSWIN4.1".toByteArray())
    boot.putShort(11, 512).put(13, spc.toByte()).putShort(14, reserved.toShort()).put(16, 2)
    boot.put(21, 0xF8.toByte()).putShort(24, 63).putShort(26, 255).putInt(28, start.toInt()).putInt(32, sectors.toInt())
    boot.putInt(36, fatSize.toInt()).putInt(44, 2).putShort(48, 1).putShort(50, 6)
    boot.put(64, 0x80.toByte()).put(66, 0x29).putInt(67, Random.nextInt())
    boot.position(71); boot.put(name.toByteArray()).put("FAT32   ".toByteArray())
    boot.put(90, 0xCD.toByte()).put(91, 0x18) // where the jump at 0 lands

    val fsInfo = sector().putInt(0, 0x41615252).putInt(484, 0x61417272)
        .putInt(488, (clusters - 1).toInt()).putInt(492, 3).putInt(508, 0xAA550000.toInt())
    // FAT entries 0 and 1 are reserved; 2 is the root directory, one cluster, end of chain.
    val fat = sector().putInt(0, 0x0FFFFFF8).putInt(4, 0x0FFFFFFF).putInt(8, 0x0FFFFFFF).put(510, 0).put(511, 0)
    val root = sector().put(name.toByteArray()).put(11, 0x08).put(510, 0).put(511, 0) // volume label entry

    write(0, mbr)
    write(start, boot); write(start + 1, fsInfo)
    write(start + 6, boot); write(start + 7, fsInfo) // backups
    write(start + reserved, fat); write(start + reserved + fatSize, fat)
    write(start + reserved + 2 * fatSize, root)
}
