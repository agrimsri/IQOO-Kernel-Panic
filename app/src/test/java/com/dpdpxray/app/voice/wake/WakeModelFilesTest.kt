package com.dpdpxray.app.voice.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WakeModelFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun zip(entries: Map<String, String>): File {
        val f = tmp.newFile("model-${System.nanoTime()}.zip")
        ZipOutputStream(f.outputStream()).use { out ->
            entries.forEach { (name, body) ->
                out.putNextEntry(ZipEntry(name))
                out.write(body.toByteArray())
                out.closeEntry()
            }
        }
        return f
    }

    private fun specFor(zip: File, required: List<String> = listOf("am/final.mdl", "conf/model.conf"), maxUnzipped: Long = 1_000_000): WakeModelSpec {
        val d = WakeModelFiles.digests(zip)
        return WakeModelSpec("m", "https://example.invalid/m.zip", zip.length(), d.sha256, d.md5, required, maxUnzipped, maxEntries = 16)
    }

    private val good = mapOf("m/" to "", "m/am/final.mdl" to "acoustic", "m/conf/model.conf" to "conf", "m/README" to "readme")

    @Test fun `digests are lowercase hex of the whole file`() {
        val f = tmp.newFile("abc").apply { writeText("abc") }
        val d = WakeModelFiles.digests(f)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", d.sha256)
        assertEquals("900150983cd24fb0d6963f7d28e17f72", d.md5)
    }

    @Test fun `the pinned production model is the official small English model`() {
        val s = WakeModelSpec.SMALL_EN_US
        assertEquals("vosk-model-small-en-us-0.15", s.name)
        assertEquals("https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip", s.url)
        assertEquals(41_205_931L, s.zipBytes)
        assertEquals("09ab50ccd62b674cbaa231b825f9c1cb", s.md5)
        assertEquals("30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498", s.sha256)
        assertTrue(s.requiredFiles.containsAll(listOf("am/final.mdl", "graph/HCLr.fst", "graph/Gr.fst", "conf/model.conf", "ivector/final.ie")))
    }

    @Test fun `an archive must match size, sha-256 and md5`() {
        val z = zip(good)
        val spec = specFor(z)
        assertEquals(ArchiveCheck.OK, WakeModelFiles.checkArchive(z, spec))
        assertEquals(ArchiveCheck.WRONG_SIZE, WakeModelFiles.checkArchive(z, spec.copy(zipBytes = spec.zipBytes + 1)))
        assertEquals(ArchiveCheck.WRONG_CHECKSUM, WakeModelFiles.checkArchive(z, spec.copy(sha256 = "0".repeat(64))))
        assertEquals(ArchiveCheck.WRONG_CHECKSUM, WakeModelFiles.checkArchive(z, spec.copy(md5 = "0".repeat(32))))
        assertEquals(ArchiveCheck.MISSING, WakeModelFiles.checkArchive(File(tmp.root, "absent.zip"), spec))
    }

    @Test fun `a verified archive is installed, marked verified and removed`() {
        val z = zip(good)
        val spec = specFor(z)
        val wake = tmp.newFolder("wake")
        val result = WakeModelFiles.install(z, wake, spec)
        assertEquals(InstallResult.Installed, result)
        val dir = File(wake, "m")
        assertEquals("acoustic", File(dir, "am/final.mdl").readText())
        assertTrue(WakeModelFiles.missingFiles(dir, spec).isEmpty())
        assertTrue(WakeModelFiles.isVerified(dir, spec))
        assertFalse(z.exists())
    }

    @Test fun `a checksum mismatch is rejected and the download is deleted`() {
        val z = zip(good)
        val spec = specFor(z).copy(sha256 = "f".repeat(64))
        val wake = tmp.newFolder("wake")
        assertTrue(WakeModelFiles.install(z, wake, spec) is InstallResult.Rejected)
        assertFalse(File(wake, "m").exists())
        assertFalse(z.exists())
    }

    @Test fun `entries that escape the model folder are rejected`() {
        val wake = tmp.newFolder("wake")
        listOf(
            mapOf("m/am/final.mdl" to "a", "m/conf/model.conf" to "c", "m/../../evil.txt" to "x"),
            mapOf("m/am/final.mdl" to "a", "m/conf/model.conf" to "c", "other/x" to "x"),
            mapOf("m/am/final.mdl" to "a", "m/conf/model.conf" to "c", "/abs" to "x"),
        ).forEach { entries ->
            val z = zip(entries)
            assertTrue(WakeModelFiles.install(z, wake, specFor(z)) is InstallResult.Rejected)
            assertFalse(File(wake, "m").exists())
            assertFalse(File(tmp.root, "evil.txt").exists())
        }
    }

    @Test fun `an archive missing a required file is rejected`() {
        val z = zip(mapOf("m/am/final.mdl" to "a"))
        val wake = tmp.newFolder("wake")
        assertTrue(WakeModelFiles.install(z, wake, specFor(z)) is InstallResult.Rejected)
        assertFalse(File(wake, "m").exists())
    }

    @Test fun `an archive that unpacks beyond the size limit is rejected`() {
        val z = zip(mapOf("m/am/final.mdl" to "a".repeat(5_000), "m/conf/model.conf" to "c"))
        val wake = tmp.newFolder("wake")
        assertTrue(WakeModelFiles.install(z, wake, specFor(z, maxUnzipped = 1_000)) is InstallResult.Rejected)
        assertFalse(File(wake, "m").exists())
    }

    @Test fun `model status wording separates verified, manual and failed states`() {
        val s = WakeModelSpec.SMALL_EN_US
        assertTrue(WakeModelStatus.Ready(verified = true).describe(s).contains("checksum verified"))
        assertTrue(WakeModelStatus.Ready(verified = false).describe(s).contains("not verified"))
        assertTrue(WakeModelStatus.Downloading(42).describe(s).contains("42%"))
        assertTrue(WakeModelStatus.Missing("It was deleted.").describe(s).endsWith("It was deleted."))
        assertFalse(WakeModelStatus.Missing().describe(s).contains("null"))
    }

    @Test fun `a manually placed folder is usable but not reported as verified`() {
        val dir = tmp.newFolder("wake", "m")
        File(dir, "am").mkdirs(); File(dir, "am/final.mdl").writeText("a")
        File(dir, "conf").mkdirs(); File(dir, "conf/model.conf").writeText("c")
        val spec = WakeModelSpec("m", "https://example.invalid/m.zip", 1, "0".repeat(64), "0".repeat(32), listOf("am/final.mdl", "conf/model.conf"), 1_000, 16)
        assertTrue(WakeModelFiles.missingFiles(dir, spec).isEmpty())
        assertFalse(WakeModelFiles.isVerified(dir, spec))
        assertEquals(listOf("conf/model.conf"), WakeModelFiles.missingFiles(dir, spec).let { File(dir, "conf/model.conf").delete(); WakeModelFiles.missingFiles(dir, spec) })
    }
}
