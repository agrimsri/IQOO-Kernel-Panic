package com.dpdpxray.core.apk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * The root `<manifest>` attributes (package, versionCode, versionName, split, ...) of a compiled AndroidManifest.xml,
 * read the same way as the developer CLI (`src/traceveil/apkfile.py`, `manifest_attrs`). Values are a String or an Int.
 * Every offset is bounds-checked; malformed input gives null, never a guess.
 */
object BinaryManifest {
    private const val XML = 0x0003
    private const val STRING_POOL = 0x0001
    private const val START_ELEMENT = 0x0102
    private const val UTF8_FLAG = 0x100
    private const val TYPE_STRING = 0x03

    fun attrs(axml: ByteArray): Map<String, Any>? = try {
        read(ByteBuffer.wrap(axml).order(ByteOrder.LITTLE_ENDIAN))
    } catch (e: RuntimeException) {
        null // IndexOutOfBounds, BufferUnderflow, IllegalArgument: malformed
    }

    private fun read(b: ByteBuffer): Map<String, Any>? {
        if (b.limit() < 8 || u16(b, 0) != XML) return null
        var pos = u16(b, 2)
        var strings = emptyList<String>()
        while (pos + 8 <= b.limit()) {
            val type = u16(b, pos)
            val size = b.getInt(pos + 4)
            if (size < 8) break
            when (type) {
                STRING_POOL -> strings = strings(b, pos)
                START_ELEMENT -> {
                    // The first start element is <manifest>.
                    val ext = pos + 16
                    val attrStart = u16(b, ext + 8)
                    val attrSize = u16(b, ext + 10)
                    val attrCount = u16(b, ext + 12)
                    val out = LinkedHashMap<String, Any>()
                    for (i in 0 until attrCount) {
                        val a = ext + attrStart + i * attrSize
                        val name = b.getInt(a + 4)
                        val raw = b.getInt(a + 8)
                        val dataType = b.get(a + 15).toInt() and 0xFF
                        val value = b.getInt(a + 16)
                        val key = strings.getOrNull(name) ?: ""
                        when {
                            raw != -1 && raw in strings.indices -> out[key] = strings[raw]
                            dataType == TYPE_STRING && value in strings.indices -> out[key] = strings[value]
                            dataType in 0x10..0x1F -> out[key] = value
                        }
                    }
                    return out
                }
            }
            pos += size
        }
        return null
    }

    private fun strings(b: ByteBuffer, off: Int): List<String> {
        val headerSize = u16(b, off + 2)
        val count = b.getInt(off + 8)
        val flags = b.getInt(off + 16)
        val start = b.getInt(off + 20)
        require(count in 0..1_000_000)
        val utf8 = flags and UTF8_FLAG != 0
        return (0 until count).map { i ->
            var p = off + start + b.getInt(off + headerSize + 4 * i)
            if (utf8) {
                p += if (u8(b, p) and 0x80 != 0) 2 else 1 // length in UTF-16 units (unused)
                var n = u8(b, p)
                if (n and 0x80 != 0) { n = ((n and 0x7F) shl 8) or u8(b, p + 1); p += 2 } else p += 1
                String(bytes(b, p, n), Charsets.UTF_8)
            } else {
                var n = u16(b, p)
                if (n and 0x8000 != 0) { n = ((n and 0x7FFF) shl 16) or u16(b, p + 2); p += 4 } else p += 2
                String(bytes(b, p, 2 * n), Charsets.UTF_16LE)
            }
        }
    }

    private fun u8(b: ByteBuffer, at: Int) = b.get(at).toInt() and 0xFF
    private fun u16(b: ByteBuffer, at: Int) = b.getShort(at).toInt() and 0xFFFF
    private fun bytes(b: ByteBuffer, at: Int, n: Int): ByteArray {
        require(n >= 0 && at >= 0 && at + n <= b.limit())
        return ByteArray(n).also { out -> for (i in 0 until n) out[i] = b.get(at + i) }
    }
}

/**
 * Split bundles (.xapk, .apks, .apkm): a zip of APKs, one base plus configuration splits. Same rules as the developer
 * CLI: a zip with AndroidManifest.xml at its root is an APK; one with APK files inside is a bundle; the base APK is the
 * one whose manifest has no `split` attribute; the bundle's own metadata (manifest.json or info.json) only fills gaps.
 */
object ApkBundles {
    val SUFFIXES = listOf(".xapk", ".apks", ".apkm")

    enum class Kind { APK, BUNDLE, NEITHER }

    fun kind(entryNames: List<String>): Kind = when {
        "AndroidManifest.xml" in entryNames -> Kind.APK
        innerApks(entryNames).isNotEmpty() -> Kind.BUNDLE
        else -> Kind.NEITHER
    }

    /** The APK files inside a bundle, by name. */
    fun innerApks(entryNames: List<String>): List<String> =
        entryNames.filter { !it.endsWith("/") && it.lowercase(Locale.ROOT).endsWith(".apk") }.sorted()

    fun looksLikeBundle(fileName: String?): Boolean = fileName?.lowercase(Locale.ROOT)?.let { n -> SUFFIXES.any { n.endsWith(it) } } == true

    /** The name of the base APK among (name, manifest attributes); null when every readable APK is a split. */
    fun base(manifests: List<Pair<String, Map<String, Any>?>>): String? =
        manifests.firstOrNull { (_, attrs) -> attrs != null && (attrs["split"] as? String).isNullOrEmpty() }?.first

    fun splitNames(manifests: List<Map<String, Any>?>): List<String> = manifests.mapNotNull { (it?.get("split") as? String)?.takeIf(String::isNotEmpty) }

    data class Meta(val packageName: String?, val name: String?, val versionCode: Long?, val versionName: String?)

    /** manifest.json (XAPK) or info.json (APKM); every field null when absent or unreadable. */
    fun meta(json: String?): Meta {
        val obj = runCatching { Json.parseToJsonElement(json.orEmpty()) as? JsonObject }.getOrNull() ?: return Meta(null, null, null, null)
        fun str(vararg keys: String) = keys.firstNotNullOfOrNull { (obj[it] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotEmpty) }
        return Meta(
            packageName = str("package_name", "pname"),
            name = str("name", "app_name"),
            versionCode = str("version_code", "versioncode")?.toLongOrNull(),
            versionName = str("version_name", "release_version"),
        )
    }
}
