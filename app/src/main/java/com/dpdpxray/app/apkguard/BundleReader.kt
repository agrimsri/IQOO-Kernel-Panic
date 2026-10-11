package com.dpdpxray.app.apkguard

import com.dpdpxray.core.apk.ApkBundles
import com.dpdpxray.core.apk.BinaryManifest
import java.io.File
import java.util.zip.ZipFile

/** A split bundle (.xapk, .apks, .apkm) as APK Guard read it. */
data class BundleInfo(val apkCount: Int, val splitNames: List<String>)

/** What opening a file as a split bundle gave. */
internal sealed interface BundleOpen {
    data object NotBundle : BundleOpen
    data class Failed(val reason: String) : BundleOpen
    data class Ok(val base: File, val apks: List<File>, val info: BundleInfo, val meta: ApkBundles.Meta) : BundleOpen
}

/**
 * Unpacks a split bundle's APK files into a private folder so APK Guard can read the base APK (permissions, signer) and
 * scan the code of every APK in it, with the developer CLI's rules ([ApkBundles]). Read-only: nothing is installed or
 * run. Entry names are reduced to their last path part, and the total size and the number of APKs are capped.
 */
internal object BundleReader {
    private const val MAX_APKS = 64
    private const val MAX_META_BYTES = 1_000_000L
    private val SAFE = Regex("[^A-Za-z0-9._-]+")

    fun open(file: File, into: File, maxBytes: Long): BundleOpen {
        val zip = runCatching { ZipFile(file) }.getOrNull() ?: return BundleOpen.NotBundle
        return zip.use { z ->
            val names = runCatching { z.entries().asSequence().map { it.name }.toList() }.getOrElse { return BundleOpen.NotBundle }
            if (ApkBundles.kind(names) != ApkBundles.Kind.BUNDLE) return BundleOpen.NotBundle
            val inner = ApkBundles.innerApks(names)
            if (inner.size > MAX_APKS) return BundleOpen.Failed("This app bundle holds more than $MAX_APKS APK files, so APK Guard did not unpack it.")
            into.mkdirs()
            var total = 0L
            val files = inner.mapIndexed { i, name ->
                val entry = z.getEntry(name)
                val target = File(into, "$i-" + name.substringAfterLast('/').replace(SAFE, "_"))
                z.getInputStream(entry).use { input ->
                    target.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > maxBytes) return BundleOpen.Failed("This app bundle unpacks to more than ${ApkFactsMapper.size(maxBytes)}, so APK Guard stopped reading it.")
                            out.write(buf, 0, n)
                        }
                    }
                }
                target
            }
            val manifests = files.map { f -> f.name to runCatching { ZipFile(f).use { a -> a.getEntry("AndroidManifest.xml")?.let { e -> a.getInputStream(e).use { it.readBytes() } } } }.getOrNull()?.let(BinaryManifest::attrs) }
            val baseName = ApkBundles.base(manifests)
                ?: return BundleOpen.Failed("This app bundle has no base APK (every APK inside is a split or unreadable), so it cannot be checked.")
            val base = files.first { it.name == baseName }
            // XAPK's manifest.json or APKM's info.json; small metadata only.
            val metaText = listOf("manifest.json", "info.json").firstNotNullOfOrNull { n ->
                z.getEntry(n)?.takeIf { it.size in 0..MAX_META_BYTES }?.let { e -> z.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } }
            }
            val meta = ApkBundles.meta(metaText)
            BundleOpen.Ok(base, listOf(base) + (files - base), BundleInfo(files.size, ApkBundles.splitNames(manifests.map { it.second })), meta)
        }
    }
}
