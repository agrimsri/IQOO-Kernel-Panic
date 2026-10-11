package com.dpdpxray.core.apk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Split bundles (.xapk, .apks, .apkm) the same way the developer CLI reads them (src/traceveil/apkfile.py). */
class ApkBundlesTest {
    /** A compiled AndroidManifest.xml with one <manifest> element; Int values become typed ints, Strings raw strings. */
    private fun axml(attrs: Map<String, Any>, utf8: Boolean = false): ByteArray {
        val strings = listOf("manifest") + attrs.keys + attrs.values.filterIsInstance<String>()
        val pool = strings.distinct()
        val idx = pool.withIndex().associate { it.value to it.index }
        val data = ByteArrayOutputStream()
        val offsets = mutableListOf<Int>()
        pool.forEach { s ->
            offsets += data.size()
            if (utf8) {
                val b = s.toByteArray(Charsets.UTF_8)
                data.write(s.length); data.write(b.size); data.write(b); data.write(0)
            } else {
                data.write(le16(s.length)); data.write(s.toByteArray(Charsets.UTF_16LE)); data.write(byteArrayOf(0, 0))
            }
        }
        while (data.size() % 4 != 0) data.write(0)
        val headerSize = 28
        val stringsStart = headerSize + 4 * pool.size
        val sp = buf(headerSize + 4 * pool.size + data.size()).apply {
            putShort(0x0001); putShort(headerSize.toShort()); putInt(stringsStart + data.size()); putInt(pool.size); putInt(0)
            putInt(if (utf8) 0x100 else 0); putInt(stringsStart); putInt(0)
            offsets.forEach { putInt(it) }
            put(data.toByteArray())
        }.array()
        val attrBytes = ByteArrayOutputStream()
        attrs.forEach { (k, v) ->
            attrBytes.write(buf(20).apply {
                putInt(-1); putInt(idx.getValue(k))
                if (v is Int) { putInt(-1); putShort(8); put(0); put(0x10); putInt(v) }
                else { putInt(idx.getValue(v as String)); putShort(8); put(0); put(0x03); putInt(idx.getValue(v)) }
            }.array())
        }
        val ext = buf(20).apply { putInt(-1); putInt(idx.getValue("manifest")); putShort(20); putShort(20); putShort(attrs.size.toShort()); putShort(0); putShort(0); putShort(0) }.array()
        val el = buf(16).apply { putShort(0x0102); putShort(16); putInt(16 + ext.size + attrBytes.size()); putInt(1); putInt(-1) }.array() + ext + attrBytes.toByteArray()
        val body = sp + el
        return buf(8).apply { putShort(0x0003); putShort(8); putInt(8 + body.size) }.array() + body
    }

    private fun buf(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte())

    @Test fun `manifest attributes are read from UTF-16 and UTF-8 string pools`() {
        listOf(false, true).forEach { utf8 ->
            val m = BinaryManifest.attrs(axml(mapOf("versionCode" to 42, "package" to "com.x.y", "versionName" to "1.2"), utf8))!!
            assertEquals("com.x.y", m["package"])
            assertEquals(42, m["versionCode"])
            assertEquals("1.2", m["versionName"])
        }
    }

    @Test fun `a malformed or non-binary manifest gives nothing, never a guess`() {
        assertNull(BinaryManifest.attrs(ByteArray(0)))
        assertNull(BinaryManifest.attrs("<manifest package=\"x\"/>".toByteArray()))
        val good = axml(mapOf("package" to "com.x.y"))
        assertNull(BinaryManifest.attrs(good.copyOf(good.size / 2)))
    }

    @Test fun `a zip with AndroidManifest_xml is an APK, one with APKs inside is a bundle, anything else is neither`() {
        assertEquals(ApkBundles.Kind.APK, ApkBundles.kind(listOf("AndroidManifest.xml", "classes.dex")))
        assertEquals(ApkBundles.Kind.BUNDLE, ApkBundles.kind(listOf("manifest.json", "com.gt.game.apk", "config.arm64_v8a.apk", "icon.png")))
        assertEquals(ApkBundles.Kind.BUNDLE, ApkBundles.kind(listOf("splits/base-master.apk", "splits/base-arm64_v8a.apk")))
        assertEquals(ApkBundles.Kind.NEITHER, ApkBundles.kind(listOf("readme.txt", "dir.apk/")))
        assertEquals(listOf("com.gt.game.apk", "config.arm64_v8a.apk"), ApkBundles.innerApks(listOf("manifest.json", "config.arm64_v8a.apk", "com.gt.game.apk", "x/")))
        assertEquals(listOf(".xapk", ".apks", ".apkm"), ApkBundles.SUFFIXES)
        assertEquals(true, ApkBundles.looksLikeBundle("Game.XAPK"))
        assertEquals(false, ApkBundles.looksLikeBundle("game.apk"))
    }

    @Test fun `the base APK is the one without a split attribute`() {
        val base = BinaryManifest.attrs(axml(mapOf("versionCode" to 7, "versionName" to "0.7", "package" to "com.gt.game")))
        val split = BinaryManifest.attrs(axml(mapOf("package" to "com.gt.game", "split" to "config.arm64_v8a")))
        assertEquals("b.apk", ApkBundles.base(listOf("s.apk" to split, "b.apk" to base)))
        assertNull("every APK a split: the base is missing", ApkBundles.base(listOf("s.apk" to split)))
        assertNull("unreadable manifests are never taken as the base", ApkBundles.base(listOf("x.apk" to null)))
        assertEquals(listOf("config.arm64_v8a"), ApkBundles.splitNames(listOf(split, base, null)))
    }

    @Test fun `bundle metadata names the app when the base APK does not`() {
        val xapk = ApkBundles.meta("""{"package_name":"com.gt.game","name":"Game","version_code":"7","version_name":"0.7"}""")
        assertEquals(ApkBundles.Meta("com.gt.game", "Game", 7, "0.7"), xapk)
        val apkm = ApkBundles.meta("""{"pname":"com.x","app_name":"X","versioncode":3,"release_version":"1.0"}""")
        assertEquals(ApkBundles.Meta("com.x", "X", 3, "1.0"), apkm)
        assertEquals(ApkBundles.Meta(null, null, null, null), ApkBundles.meta("not json"))
        assertEquals(ApkBundles.Meta(null, null, null, null), ApkBundles.meta("[1,2]"))
    }
}
