package dev.aasim.bootstick

import org.junit.Assert.assertEquals
import org.junit.Test

// Same fixtures as test-kind.sh, so the Kotlin and shell copies of the rules can't drift apart.
class InspectTest {
    private fun image(vararg marks: Pair<Int, ByteArray>) = ByteArray(40 * 1024).also { b ->
        marks.forEach { (at, bytes) -> bytes.copyInto(b, at) }
    }

    private val mbr = 510 to byteArrayOf(0x55, 0xAA.toByte())
    private val cd = 32769 to "CD001".toByteArray()

    @Test
    fun kinds() {
        assertEquals("blank", kindOf(image()))
        assertEquals("disk", kindOf(image(mbr)))
        assertEquals("iso", kindOf(image(cd)))
        assertEquals("hybrid", kindOf(image(mbr, cd)))
        assertEquals("other", kindOf(image(0 to "ANDROID!".toByteArray())))
        assertEquals("blank", kindOf(ByteArray(0)))
    }

    @Test
    fun biosOnlyIsos() {
        val torito = 34823 to "EL TORITO SPECIFICATION".toByteArray()
        val catalog = 34887 to byteArrayOf(19)
        val bios = image(cd, torito, catalog, 38912 to byteArrayOf(0x01))
        val efi = image(cd, torito, catalog, 38912 to byteArrayOf(0x01), 38976 to byteArrayOf(0x91.toByte(), 0xEF.toByte()))
        fun reader(b: ByteArray) = { pos: Long, n: Int -> b.copyOfRange(minOf(pos.toInt(), b.size), minOf(pos.toInt() + n, b.size)) }
        assertEquals(true, biosOnly(reader(bios)))
        assertEquals(false, biosOnly(reader(efi)))
        assertEquals(false, biosOnly(reader(image(mbr, cd))))
    }

    @Test
    fun isosAreNeverWritable() {
        assertEquals(listOf("ro", "cdrom"), modesFor("CorePlus.ISO"))
        assertEquals("ro", suggestedFor("odd.iso", "blank"))
        assertEquals("disk", suggestedFor("save.img", "blank"))
        assertEquals("cdrom", suggestedFor("win11.iso", "iso"))
    }

    @Test
    fun newestRelease() {
        assertEquals("22.3/", newest(sequenceOf("21.3/", "22/", "22.3/", "22.1/")))
        assertEquals("ubuntu-26.04.1-desktop-amd64.iso", newest(sequenceOf("ubuntu-26.04-desktop-amd64.iso", "ubuntu-26.04.1-desktop-amd64.iso")))
    }

    @Test
    fun sizes() {
        assertEquals(1_000_000_000L, parseSize("1", GB))
        assertEquals(1_500_000_256L, parseSize("1,5", GB)) // next whole sector
        assertEquals(1024L, parseSize("1000", 1)) // rounded up to two sectors
        assertEquals(512L, parseSize("0.0001", MB))
        assertEquals(null, parseSize("0", MB))
        assertEquals(null, parseSize("abc", MB))
        assertEquals(null, parseSize("3", 1L shl 40)) // past MAX_IMAGE
    }

    @Test
    fun checksums() {
        val sha256 = "a".repeat(64)
        assertEquals(sha256, hashesIn("${sha256.uppercase()}  ubuntu.iso").first())
        assertEquals(sha256, hashesIn("SHA256 (Fedora.iso) = $sha256").first())
        assertEquals(listOf<String>(), hashesIn("b".repeat(65)).toList()) // not a checksum length
        assertEquals("SHA-512", algoFor("c".repeat(128)))
        assertEquals("MD5", algoFor("d".repeat(32)))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", digest("abc".byteInputStream(), "MD5") {})
    }

    @Test
    fun packed() {
        assertEquals(true, isPacked("raspios.IMG.xz"))
        assertEquals(true, isPacked("mt86plus_9.00_x86_64.iso.zip"))
        assertEquals(false, isPacked("notes.zip"))
        assertEquals("raspios.img", unpackedName("raspios.img.xz"))
        val image = ByteArray(300_000) { (it * 7).toByte() }
        fun roundTrip(name: String, pack: (java.io.OutputStream) -> java.io.OutputStream) {
            val packed = java.io.ByteArrayOutputStream().also { o -> pack(o).use { it.write(image) } }.toByteArray()
            val out = java.io.ByteArrayOutputStream()
            assertEquals(image.size.toLong(), unpack(name, packed.inputStream(), out) {})
            assertEquals(image.toList(), out.toByteArray().toList())
        }
        roundTrip("a.img.gz") { java.util.zip.GZIPOutputStream(it) }
        roundTrip("a.img.xz") { org.tukaani.xz.XZOutputStream(it, org.tukaani.xz.LZMA2Options()) }
        roundTrip("a.iso.zip") { o ->
            java.util.zip.ZipOutputStream(o).apply {
                putNextEntry(java.util.zip.ZipEntry("README.txt")); write("hi".toByteArray()); closeEntry()
                putNextEntry(java.util.zip.ZipEntry("memtest.iso"))
            }
        }
    }

    @Test
    fun fat32() {
        val f = java.io.File.createTempFile("fat32", ".img")
        try {
            val bytes = 100 * MB
            java.io.RandomAccessFile(f, "rw").use { it.setLength(bytes); formatFat32(it.channel, bytes, "save disk") }
            val b = java.nio.ByteBuffer.wrap(f.readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            assertEquals("disk", kindOf(f.readBytes().copyOf(HEAD_SIZE)))
            assertEquals(0x0C.toByte(), b.get(450))
            assertEquals(2048, b.getInt(454))
            val vbr = 2048 * 512
            assertEquals("FAT32   ", String(f.readBytes(), vbr + 82, 8))
            assertEquals("SAVE DISK  ", String(f.readBytes(), vbr + 71, 11))
            assertEquals(0x0FFFFFF8, b.getInt((2048 + 32) * 512))
        } finally {
            f.delete()
        }
    }

    @Test
    fun bundledCatalog() {
        val (version, list) = parseCatalog(java.io.File("src/main/assets/catalog.json").readText())
        assert(version >= 2)
        assertEquals(list.size, list.map { it.name }.distinct().size)
    }
}
