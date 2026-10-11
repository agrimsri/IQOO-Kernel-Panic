package com.dpdpxray.app.apkguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Split bundles picked in APK Guard are unpacked privately and read with the developer CLI's rules. */
class BundleReaderTest {
    private val tmp: File = Files.createTempDirectory("bundle-test").toFile().apply { deleteOnExit() }

    /** A minimal compiled AndroidManifest.xml (UTF-16 pool) with string attributes only. */
    private fun axml(attrs: Map<String, String>): ByteArray {
        val pool = (listOf("manifest") + attrs.keys + attrs.values).distinct()
        val idx = pool.withIndex().associate { it.value to it.index }
        val data = ByteArrayOutputStream()
        val offsets = pool.map { s ->
            data.size().also {
                data.write(byteArrayOf((s.length and 0xFF).toByte(), (s.length shr 8).toByte()))
                data.write(s.toByteArray(Charsets.UTF_16LE)); data.write(byteArrayOf(0, 0))
            }
        }
        while (data.size() % 4 != 0) data.write(0)
        fun buf(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
        val start = 28 + 4 * pool.size
        val sp = buf(start + data.size()).apply {
            putShort(1); putShort(28); putInt(start + data.size()); putInt(pool.size); putInt(0); putInt(0); putInt(start); putInt(0)
            offsets.forEach { putInt(it) }; put(data.toByteArray())
        }.array()
        val attrBytes = attrs.entries.fold(ByteArray(0)) { acc, (k, v) ->
            acc + buf(20).apply { putInt(-1); putInt(idx.getValue(k)); putInt(idx.getValue(v)); putShort(8); put(0); put(3); putInt(idx.getValue(v)) }.array()
        }
        val ext = buf(20).apply { putInt(-1); putInt(idx.getValue("manifest")); putShort(20); putShort(20); putShort(attrs.size.toShort()); putShort(0); putShort(0); putShort(0) }.array()
        val el = buf(16).apply { putShort(0x0102); putShort(16); putInt(16 + ext.size + attrBytes.size); putInt(1); putInt(-1) }.array() + ext + attrBytes
        val body = sp + el
        return buf(8).apply { putShort(3); putShort(8); putInt(8 + body.size) }.array() + body
    }

    private fun zip(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
    }.toByteArray()

    private fun apk(attrs: Map<String, String>) = zip(mapOf("AndroidManifest.xml" to axml(attrs), "classes.dex" to ByteArray(8)))

    private fun file(name: String, bytes: ByteArray) = File(tmp, name).apply { writeBytes(bytes) }

    @Test fun `an xapk is unpacked, its base APK found by its manifest, and its metadata read`() {
        val bundle = file("game.xapk", zip(mapOf(
            "config.arm64_v8a.apk" to apk(mapOf("package" to "com.gt.game", "split" to "config.arm64_v8a")),
            "com.gt.game.apk" to apk(mapOf("package" to "com.gt.game")),
            "manifest.json" to """{"package_name":"com.gt.game","name":"Game","version_code":"7"}""".toByteArray(),
            "icon.png" to ByteArray(4),
        )))
        val out = File(tmp, "out1")
        val opened = BundleReader.open(bundle, out, 10_000_000) as BundleOpen.Ok
        assertTrue(opened.base.name.endsWith("com.gt.game.apk"))
        assertEquals(2, opened.apks.size)
        assertEquals(opened.base, opened.apks.first())
        assertEquals(BundleInfo(2, listOf("config.arm64_v8a")), opened.info)
        assertEquals("Game", opened.meta.name)
        assertTrue(opened.apks.all { it.parentFile == out })
    }

    @Test fun `entry paths never leave the private folder`() {
        val bundle = file("evil.apks", zip(mapOf("../../outside/base.apk" to apk(mapOf("package" to "com.x")))))
        val out = File(tmp, "out2")
        val opened = BundleReader.open(bundle, out, 10_000_000) as BundleOpen.Ok
        assertEquals(out, opened.base.parentFile)
        assertTrue(!File(tmp, "outside").exists())
    }

    @Test fun `a plain APK, a non-zip and a zip without APKs are not bundles`() {
        assertEquals(BundleOpen.NotBundle, BundleReader.open(file("a.apk", apk(mapOf("package" to "com.x"))), File(tmp, "o3"), 10_000_000))
        assertEquals(BundleOpen.NotBundle, BundleReader.open(file("b.bin", ByteArray(10)), File(tmp, "o4"), 10_000_000))
        assertEquals(BundleOpen.NotBundle, BundleReader.open(file("c.zip", zip(mapOf("readme.txt" to ByteArray(3)))), File(tmp, "o5"), 10_000_000))
    }

    @Test fun `a bundle of splits only, or one that unpacks too large, is refused with a reason`() {
        val splits = file("s.apkm", zip(mapOf("split.apk" to apk(mapOf("package" to "com.x", "split" to "config.xx")))))
        assertTrue((BundleReader.open(splits, File(tmp, "o6"), 10_000_000) as BundleOpen.Failed).reason.contains("no base APK"))
        val big = file("big.xapk", zip(mapOf("base.apk" to apk(mapOf("package" to "com.x")))))
        assertTrue((BundleReader.open(big, File(tmp, "o7"), 16) as BundleOpen.Failed).reason.contains("more than"))
    }
}
