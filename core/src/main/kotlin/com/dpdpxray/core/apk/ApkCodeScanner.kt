package com.dpdpxray.core.apk

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Class-name signature of a vendor SDK: package paths (DEX descriptor form without the leading "L", for example
 * "com/facebook/appevents/") or one exact class ("com/google/android/gms/ads/MobileAds;"). [entity] is the tracker
 * catalogue's name for the service, so a code match lines up with the network evidence and the fix templates.
 */
@Serializable
data class SdkCodeSignature(
    val entity: String,
    val classPrefixes: List<String>,
    /** Vendor documentation that shows these package or class names. */
    val source: String,
    val note: String = "",
)

/** The bundled signatures (`sdk/code_signatures.json`). */
class SdkCodeSignatures(val entries: List<SdkCodeSignature>) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(text: String) = SdkCodeSignatures(json.decodeFromString<List<SdkCodeSignature>>(text))

        fun loadDefault(): SdkCodeSignatures {
            val text = SdkCodeSignatures::class.java.getResourceAsStream("/sdk/code_signatures.json")
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("sdk/code_signatures.json missing from resources")
            return fromJson(text)
        }

        val default: SdkCodeSignatures by lazy { loadDefault() }
    }
}

/** Work caps for one scan: a DEX larger than [maxDexBytes] is skipped, and the scan stops after the total caps. */
data class CodeScanLimits(
    val maxDexBytes: Long = 64L * 1024 * 1024,
    val maxTotalBytes: Long = 768L * 1024 * 1024,
    val maxDexFiles: Int = 200,
)

/**
 * What a scan found. [found] maps a catalogue entity to the package (or class) names seen, dotted
 * ("com.facebook.appevents"). Present in code is not the same as called at runtime; a name not found is not proof
 * the SDK is absent (code shrinkers can rename classes), so absence is "not established", never "not bundled".
 */
data class CodeScanResult(
    val found: Map<String, List<String>>,
    val dexFilesRead: Int,
    val dexFilesSkipped: Int,
    val apksUnreadable: Int,
) {
    /** Every DEX of every APK was read. */
    val complete: Boolean get() = dexFilesRead > 0 && dexFilesSkipped == 0 && apksUnreadable == 0
}

/**
 * Reads the string table of a DEX file (header string_ids_size at 0x38, string_ids_off at 0x3C; each string_id is a
 * u4 offset to a uleb128 UTF-16 length followed by MUTF-8 bytes). Class names appear there as type descriptors such as
 * "Lcom/facebook/appevents/AppEventsLogger;". Prefixes are ASCII, and MUTF-8 encodes ASCII as itself, so matching
 * compares raw bytes and never decodes a string. Every offset is bounds-checked; malformed input gives no match.
 */
object DexStrings {
    private const val HEADER_SIZE = 0x70
    private const val ENDIAN_CONSTANT = 0x12345678

    fun isDex(bytes: ByteArray): Boolean =
        bytes.size >= HEADER_SIZE && bytes[0] == 'd'.code.toByte() && bytes[1] == 'e'.code.toByte() &&
            bytes[2] == 'x'.code.toByte() && bytes[3] == '\n'.code.toByte() && bytes[7] == 0.toByte() &&
            u4(bytes, 0x28) == ENDIAN_CONSTANT

    /** Indexes into [prefixes] that at least one string of [dex] starts with. */
    fun prefixesFound(dex: ByteArray, prefixes: List<String>): Set<Int> {
        if (prefixes.isEmpty() || !isDex(dex)) return emptySet()
        val wanted = prefixes.map { it.toByteArray(Charsets.US_ASCII) }
        val count = u4(dex, 0x38)
        val idsOff = u4(dex, 0x3C)
        if (count <= 0 || idsOff < HEADER_SIZE || idsOff >= dex.size) return emptySet()
        // A header may claim more ids than the file holds: stop at the end of the file.
        val readable = minOf(count.toLong(), (dex.size - idsOff) / 4L).toInt()
        val found = HashSet<Int>()
        for (i in 0 until readable) {
            val dataOff = u4(dex, idsOff + 4 * i)
            if (dataOff < HEADER_SIZE || dataOff >= dex.size) continue
            val start = skipUleb128(dex, dataOff)
            if (start < 0) continue
            for (p in wanted.indices) {
                if (p !in found && startsWith(dex, start, wanted[p])) found += p
            }
            if (found.size == wanted.size) break
        }
        return found
    }

    private fun startsWith(bytes: ByteArray, at: Int, prefix: ByteArray): Boolean {
        if (at + prefix.size > bytes.size) return false
        for (k in prefix.indices) if (bytes[at + k] != prefix[k]) return false
        return true
    }

    /** Position after a uleb128 (at most 5 bytes), or -1 when it runs off the end or is too long. */
    private fun skipUleb128(bytes: ByteArray, at: Int): Int {
        var pos = at
        repeat(5) {
            if (pos >= bytes.size) return -1
            val b = bytes[pos++].toInt()
            if (b and 0x80 == 0) return pos
        }
        return -1
    }

    /** Little-endian unsigned 32-bit value as Int; values above Int.MAX_VALUE read as -1 (never a valid offset here). */
    private fun u4(b: ByteArray, at: Int): Int {
        if (at < 0 || at + 4 > b.size) return -1
        val v = (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or
            ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)
        return if (v > Int.MAX_VALUE) -1 else v.toInt()
    }
}

/**
 * "Found in the app's code (static)": reads the classes*.dex files inside an installed app's APK files and reports
 * which bundled SDK signatures appear in their string tables. Read-only, offline, one DEX in memory at a time, bounded by
 * [CodeScanLimits]. Blocking: call it off the main thread.
 */
object ApkCodeScanner {
    private val DEX_ENTRY = Regex("classes\\d*\\.dex")

    fun scan(apks: List<File>, signatures: SdkCodeSignatures = SdkCodeSignatures.default, limits: CodeScanLimits = CodeScanLimits()): CodeScanResult {
        val flat = signatures.entries.flatMap { s -> s.classPrefixes.map { s.entity to it } }
        val descriptors = flat.map { "L" + it.second }
        val hits = LinkedHashSet<Int>()
        var read = 0
        var skipped = 0
        var unreadable = 0
        var total = 0L
        for (apk in apks) {
            val zip = runCatching { ZipFile(apk) }.getOrNull()
            if (zip == null) { unreadable++; continue }
            zip.use { z ->
                val dexEntries = runCatching { z.entries().asSequence().filter { !it.isDirectory && DEX_ENTRY.matches(it.name) }.toList() }
                    .getOrElse { unreadable++; emptyList() }
                    .sortedWith(compareBy({ it.name.length }, { it.name }))
                for (entry in dexEntries) {
                    val size = entry.size
                    if (read >= limits.maxDexFiles || size < 0 || size > limits.maxDexBytes || total + size > limits.maxTotalBytes) {
                        skipped++
                        continue
                    }
                    val bytes = runCatching { z.getInputStream(entry).use { readExactly(it, size.toInt()) } }.getOrNull()
                    if (bytes == null) { skipped++; continue }
                    total += size
                    read++
                    hits += DexStrings.prefixesFound(bytes, descriptors)
                }
            }
        }
        val found = LinkedHashMap<String, MutableList<String>>()
        hits.sorted().forEach { i ->
            val (entity, prefix) = flat[i]
            found.getOrPut(entity) { mutableListOf() } += dotted(prefix)
        }
        return CodeScanResult(found.mapValues { it.value.distinct() }, read, skipped, unreadable)
    }

    /** "com/facebook/appevents/" → "com.facebook.appevents"; "com/google/android/gms/ads/MobileAds;" → "com.google.android.gms.ads.MobileAds". */
    fun dotted(prefix: String): String = prefix.trimEnd('/', ';').replace('/', '.')

    /** Reads exactly [size] bytes, or null when the stream ends early or holds more. */
    private fun readExactly(input: InputStream, size: Int): ByteArray? {
        val out = ByteArray(size)
        var off = 0
        while (off < size) {
            val n = input.read(out, off, size - off)
            if (n < 0) return null
            off += n
        }
        return if (input.read() == -1) out else null
    }
}
