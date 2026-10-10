package com.dpdpxray.app.voice.wake

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * The offline wake-word model. It is never bundled in the APK and never downloaded without an explicit tap.
 *
 * Checksums: alphacephei.com's official model list (https://alphacephei.com/vosk/models/model-list.json) publishes only
 * an MD5 for each archive. The SHA-256 below was computed on 10 October 2026 from a copy downloaded from the official
 * URL whose size and MD5 matched that list. The app requires BOTH to match before it unpacks anything.
 */
data class WakeModelSpec(
    val name: String,
    val url: String,
    val zipBytes: Long,
    val sha256: String,
    val md5: String,
    /** Paths inside the model folder that must exist before the engine is started. */
    val requiredFiles: List<String>,
    val maxUnzippedBytes: Long,
    val maxEntries: Int,
) {
    val zipName: String get() = "$name.zip"

    companion object {
        /** Vosk small US-English model 0.15 (Apache 2.0, ≈39 MiB download, ≈68 MiB unpacked). */
        val SMALL_EN_US = WakeModelSpec(
            name = "vosk-model-small-en-us-0.15",
            url = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
            zipBytes = 41_205_931L,
            sha256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498",
            md5 = "09ab50ccd62b674cbaa231b825f9c1cb",
            requiredFiles = listOf(
                "am/final.mdl", "conf/model.conf", "conf/mfcc.conf",
                "graph/HCLr.fst", "graph/Gr.fst", "graph/disambig_tid.int", "graph/phones/word_boundary.int",
                "ivector/final.ie", "ivector/final.dubm", "ivector/final.mat", "ivector/global_cmvn.stats",
                "ivector/online_cmvn.conf", "ivector/splice.conf",
            ),
            maxUnzippedBytes = 80L * 1024 * 1024, // the official archive unpacks to 70,898,967 bytes
            maxEntries = 64,
        )
    }
}

data class FileDigests(val sha256: String, val md5: String)

enum class ArchiveCheck { OK, MISSING, WRONG_SIZE, WRONG_CHECKSUM }

sealed interface InstallResult {
    data object Installed : InstallResult
    data class Rejected(val reason: String) : InstallResult
}

/** What Settings shows about the model. */
sealed interface WakeModelStatus {
    data object Checking : WakeModelStatus
    data class Missing(val problem: String? = null) : WakeModelStatus
    data class Downloading(val percent: Int?, val paused: Boolean = false) : WakeModelStatus
    data object Verifying : WakeModelStatus
    data class Ready(val verified: Boolean) : WakeModelStatus

    fun describe(spec: WakeModelSpec): String = when (this) {
        Checking -> "Checking for the wake-word model…"
        is Missing -> "Wake-word model: not on this phone." + (problem?.let { " $it" } ?: "")
        is Downloading -> "Downloading the wake-word model" + (percent?.let { " ($it%)" } ?: "") + "…" +
            (if (paused) " The phone has paused it, for example while offline." else "")
        Verifying -> "Checking the download against the official checksums…"
        is Ready ->
            if (verified) "Wake-word model ready: ${spec.name}, checksum verified."
            else "Wake-word model ready: ${spec.name}, placed manually, so TraceVeil has not verified its checksum."
    }
}

/** File work for the model (pure JVM; no Android types), so verification and unpacking are unit-tested. */
object WakeModelFiles {
    const val VERIFIED_MARKER = ".traceveil-verified-sha256"

    fun digests(file: File): FileDigests {
        val sha = MessageDigest.getInstance("SHA-256")
        val md5 = MessageDigest.getInstance("MD5")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                sha.update(buf, 0, n)
                md5.update(buf, 0, n)
            }
        }
        return FileDigests(sha.digest().hex(), md5.digest().hex())
    }

    fun checkArchive(zip: File, spec: WakeModelSpec): ArchiveCheck {
        if (!zip.isFile) return ArchiveCheck.MISSING
        if (zip.length() != spec.zipBytes) return ArchiveCheck.WRONG_SIZE
        val d = digests(zip)
        return if (d.sha256.equals(spec.sha256, ignoreCase = true) && d.md5.equals(spec.md5, ignoreCase = true)) ArchiveCheck.OK
        else ArchiveCheck.WRONG_CHECKSUM
    }

    fun missingFiles(modelDir: File, spec: WakeModelSpec): List<String> =
        spec.requiredFiles.filterNot { File(modelDir, it).let { f -> f.isFile && f.length() > 0 } }

    /** True only for a folder this app unpacked from an archive that matched the pinned checksums. */
    fun isVerified(modelDir: File, spec: WakeModelSpec): Boolean =
        runCatching { File(modelDir, VERIFIED_MARKER).readText().trim().equals(spec.sha256, ignoreCase = true) }.getOrDefault(false)

    /**
     * Verifies [zip], unpacks it through a staging folder into `wakeDir/<name>` and deletes the archive. A rejected
     * archive is deleted too, and nothing partial is left in `wakeDir/<name>`.
     */
    fun install(zip: File, wakeDir: File, spec: WakeModelSpec): InstallResult {
        val size = zip.length()
        when (checkArchive(zip, spec)) {
            ArchiveCheck.OK -> Unit
            ArchiveCheck.MISSING -> return InstallResult.Rejected("The model archive is missing.")
            ArchiveCheck.WRONG_SIZE -> {
                zip.delete()
                return InstallResult.Rejected("The download is $size bytes, not the official ${spec.zipBytes}; it was deleted.")
            }
            ArchiveCheck.WRONG_CHECKSUM -> {
                zip.delete()
                return InstallResult.Rejected("The download does not match the official model checksum; it was deleted.")
            }
        }
        val staging = File(wakeDir, ".unpack-${spec.name}")
        val target = File(wakeDir, spec.name)
        try {
            staging.deleteRecursively()
            extract(zip, staging, spec)
            val unpacked = File(staging, spec.name)
            val missing = missingFiles(unpacked, spec)
            if (missing.isNotEmpty()) {
                zip.delete()
                return InstallResult.Rejected("The archive is missing ${missing.first()}; it was deleted.")
            }
            File(unpacked, VERIFIED_MARKER).writeText(spec.sha256)
            target.deleteRecursively()
            if (!unpacked.renameTo(target)) return InstallResult.Rejected("The model could not be moved into place.")
            zip.delete()
            return InstallResult.Installed
        } catch (e: IOException) {
            zip.delete()
            return InstallResult.Rejected("The archive could not be unpacked safely (${e.message}); it was deleted.")
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Unpacks only entries under `<name>/`, refusing absolute paths, `..`, escapes, too many entries or too many bytes. */
    private fun extract(zip: File, staging: File, spec: WakeModelSpec) {
        staging.mkdirs()
        val root = staging.canonicalFile
        var total = 0L
        var entries = 0
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                if (++entries > spec.maxEntries) throw IOException("too many entries")
                val name = entry.name.replace('\\', '/')
                if (name.startsWith("/") || name.split('/').any { it == ".." } || !name.startsWith("${spec.name}/")) {
                    throw IOException("unexpected entry")
                }
                val out = File(root, name).canonicalFile
                if (!out.path.startsWith(root.path + File.separator)) throw IOException("entry escapes the model folder")
                if (entry.isDirectory) {
                    out.mkdirs()
                    continue
                }
                out.parentFile?.mkdirs()
                out.outputStream().buffered().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = zin.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > spec.maxUnzippedBytes) throw IOException("archive unpacks beyond ${spec.maxUnzippedBytes} bytes")
                        o.write(buf, 0, n)
                    }
                }
            }
        }
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
}
