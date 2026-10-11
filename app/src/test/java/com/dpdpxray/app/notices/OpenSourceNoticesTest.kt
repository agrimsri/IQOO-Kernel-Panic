package com.dpdpxray.app.notices

import com.dpdpxray.app.ui.EndpointMapIntegration
import com.dpdpxray.core.geo.BundledGeoTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Keeps the in-app "Open-source notices" screen in step with the licence inventory (docs/LICENSES_FOR_SALE.md,
 * section 2 "Bundled in the APK") and with the texts shipped under app/src/main/assets/licenses/.
 */
class OpenSourceNoticesTest {
    private fun file(vararg candidates: String) = candidates.map(::File).first { it.exists() }
    private val assets = file("src/main/assets", "app/src/main/assets")
    private val inventory = file("../docs/LICENSES_FOR_SALE.md", "docs/LICENSES_FOR_SALE.md").readText()
    private val catalogue = file("../gradle/libs.versions.toml", "gradle/libs.versions.toml").readText()
    private val notices = OpenSourceNotices.all(appVersion = "9.9.9")

    /** A table cell without Markdown code marks and bold marks (a single star, as in a file pattern, stays). */
    private fun clean(cell: String) = cell.replace("`", "").replace("**", "").trim()

    /** First cells of every table row in section 2 ("Bundled in the APK"), header and divider rows excluded. */
    private val bundledRows: List<String> = run {
        val section = inventory.substringAfter("## 2. Bundled in the APK").substringBefore("\n## 3.")
        section.lines().map { it.trim() }
            .filter { it.startsWith("|") && !it.startsWith("| ---") && !it.startsWith("|---") }
            .map { clean(it.removePrefix("|").substringBefore("|")) }
            .filter { it != "Component" && it != "File in the APK" }
    }

    @Test
    fun `the inventory's bundled section is read`() {
        // 13 library rows and 11 data rows on 11 October 2026; the exact number may grow, never vanish.
        assertTrue("$bundledRows", bundledRows.size >= 20)
        assertTrue(bundledRows.contains("res/font/archivo.ttf"))
        assertTrue(bundledRows.contains("Qualcomm QNN runtime (com.qualcomm.qti:qnn-runtime)"))
    }

    @Test
    fun `every component the inventory lists as shipped in the APK has an entry`() {
        val covered = notices.flatMap { it.inventory }.map(::clean).toSet()
        val missing = bundledRows.filter { it !in covered }
        assertEquals("Inventory rows with no notices entry: add them to OpenSourceNotices", emptyList<String>(), missing)
    }

    @Test
    fun `no entry names an inventory row that no longer exists`() {
        val stale = notices.flatMap { it.inventory }.map(::clean).filter { it !in bundledRows }
        assertEquals(emptyList<String>(), stale)
    }

    @Test
    fun `every entry has at least one bundled text, and every text file exists in the assets`() {
        notices.forEach { c ->
            assertTrue("${c.id} has no text", c.texts.isNotEmpty())
            c.texts.forEach { t ->
                assertTrue("${t.asset} is not under licenses/", t.asset.startsWith("${OpenSourceNotices.ASSET_DIR}/"))
                val f = File(assets, t.asset)
                assertTrue("${c.id}: missing asset ${t.asset}", f.isFile && f.length() > 0)
                assertTrue("${t.asset} has no source", t.source.isNotBlank())
            }
        }
    }

    @Test
    fun `every file in the licences folder is shown by some entry`() {
        val dir = File(assets, OpenSourceNotices.ASSET_DIR)
        val shipped = dir.walkTopDown().filter { it.isFile }.map { "${OpenSourceNotices.ASSET_DIR}/${it.relativeTo(dir).invariantSeparatorsPath}" }.toSet()
        val shown = notices.flatMap { it.texts }.map { it.asset }.toSet()
        assertEquals(emptySet<String>(), shipped - shown)
    }

    @Test
    fun `ids are unique and an entry can be found by its id`() {
        assertEquals(notices.size, notices.map { it.id }.toSet().size)
        notices.forEach { assertEquals(it, OpenSourceNotices.byId(it.id, "9.9.9")) }
        assertEquals(null, OpenSourceNotices.byId("nope", "9.9.9"))
        notices.forEach { assertTrue(it.id, it.id.matches(Regex("[a-z0-9-]+"))) }
    }

    @Test
    fun `versions match the version catalogue`() {
        fun pinned(key: String) = Regex("""(?m)^$key\s*=\s*"([^"]+)"""").find(catalogue)?.groupValues?.get(1) ?: error("no $key in catalogue")
        mapOf(
            "litert" to "litert", "litertlm" to "litertlm", "qnn-delegate" to "qnn", "qnn-runtime" to "qnn",
            "vosk" to "vosk", "shizuku" to "shizuku", "kotlin" to "kotlin", "coroutines" to "coroutines",
            "serialization" to "serialization", "androidx" to "composeBom",
        ).forEach { (id, key) ->
            val c = notices.first { it.id == id }
            assertTrue("${c.id}: '${c.version}' should name ${pinned(key)}", c.version.contains(pinned(key)))
        }
        assertEquals("9.9.9", notices.first { it.id == "traceveil" }.version)
    }

    // ---- the texts themselves --------------------------------------------------------------------------------------

    private fun text(asset: String) = File(assets, asset).readText()
    private fun sha256(asset: String) = MessageDigest.getInstance("SHA-256").digest(File(assets, asset).readBytes()).joinToString("") { "%02x".format(it) }
    private fun textsOf(id: String) = notices.first { it.id == id }.texts.map { it.asset }

    @Test
    fun `Apache 2_0 is the full text published by apache_org`() {
        val asset = "licenses/apache-2.0.txt"
        assertEquals("cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30", sha256(asset))
        assertTrue(text(asset).contains("END OF TERMS AND CONDITIONS"))
        listOf("androidx", "kotlin", "coroutines", "serialization", "gson", "annotations", "vosk", "jna", "litertlm").forEach {
            assertTrue(it, asset in textsOf(it))
        }
    }

    @Test
    fun `Shizuku's MIT licence carries the copyright line from its repository`() {
        assertTrue(text("licenses/shizuku-api-mit.txt").contains("Copyright (c) 2021 RikkaW"))
        assertTrue(textsOf("shizuku").contains("licenses/shizuku-api-mit.txt"))
    }

    @Test
    fun `the Archivo font ships with the full OFL and the copyright line in its own metadata`() {
        val ofl = text("licenses/archivo-ofl-1.1.txt")
        assertTrue(ofl.startsWith("Copyright 2020 The Archivo Project Authors (https://github.com/Omnibus-Type/Archivo)"))
        assertTrue(ofl.contains("SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007"))
        assertTrue(ofl.contains("5) The Font Software, modified or unmodified, in part or in whole,"))
        assertTrue(textsOf("archivo").contains("licenses/archivo-ofl-1.1.txt"))
    }

    @Test
    fun `Creative Commons texts are the full legal codes`() {
        assertTrue(text("licenses/cc-by-4.0.txt").contains("Attribution 4.0 International"))
        assertTrue(text("licenses/cc-by-sa-3.0.txt").contains("Attribution-ShareAlike 3.0 Unported"))
        assertTrue(text("licenses/cc-by-3.0.txt").contains("Attribution 3.0 Unported"))
        assertEquals(listOf("licenses/tracker-hostname-model.txt", "licenses/cc-by-sa-3.0.txt", "licenses/cc-by-3.0.txt"), textsOf("tracker-model"))
        assertTrue(textsOf("permission-model").contains("licenses/cc-by-4.0.txt"))
        assertTrue(textsOf("dbip").contains("licenses/cc-by-4.0.txt"))
    }

    @Test
    fun `map credits use the exact wording the map prints`() {
        assertTrue(text("licenses/dbip-country-lite.txt").contains(BundledGeoTable.ATTRIBUTION))
        assertTrue(text("licenses/natural-earth.txt").contains(EndpointMapIntegration.OUTLINE_CREDIT))
    }

    @Test
    fun `model data notices credit their sources`() {
        val tracker = text("licenses/tracker-hostname-model.txt")
        assertTrue(tracker.contains("The EasyList authors (https://easylist.to/)") && tracker.contains("Majestic (https://majestic.com/)"))
        val perms = text("licenses/apk-permission-model.txt")
        assertTrue(perms.contains("10.24432/C5FS64") && perms.contains("10.24432/C5560H"))
        val lens = text("licenses/consentlens-training-data.txt")
        assertTrue(lens.contains("Copyright (c) 2019-2020, KIProtect GmbH") && lens.contains("Copyright (c) 2014 AmauriC"))
    }

    @Test
    fun `Qualcomm's own files are shipped byte for byte as they come inside the 2_50_0 AARs`() {
        // SHA-256 of NOTICE.txt and LICENSE.pdf as extracted from qnn-runtime-2.50.0.aar and qnn-litert-delegate-2.50.0.aar.
        assertEquals("0c5e8aad3506d0ab881cabaf0dae8de64e1784dc1ee6c873caf24a78fbf01924", sha256("licenses/qualcomm/qnn-runtime-2.50.0-NOTICE.txt"))
        assertEquals("ec1dccfdcba5c6e64126e84199b8362bf4999107bfa567ebe831dbb4c461692b", sha256("licenses/qualcomm/qnn-runtime-2.50.0-LICENSE.pdf"))
        assertEquals("2d20d88391972aa355c2d2ac0d03e7d4805b9a1c2398649d861c7dfd10dd5f83", sha256("licenses/qualcomm/qnn-litert-delegate-2.50.0-LICENSE.pdf"))
        assertEquals(listOf("licenses/qualcomm/qnn-runtime-2.50.0-NOTICE.txt", "licenses/qualcomm/qnn-runtime-2.50.0-LICENSE.pdf"), textsOf("qnn-runtime"))
        assertEquals(listOf("licenses/qualcomm/qnn-litert-delegate-2.50.0-LICENSE.pdf"), textsOf("qnn-delegate"))
    }

    @Test
    fun `LiteRT's own licence and LiteRT-LM's third-party notices are shipped as they come inside the AARs`() {
        assertEquals("71c6915d04265772a0339bed47276942c678b45cc01534210ebe6984fd1aec65", sha256("licenses/google/litert-1.4.2-LICENSE.txt"))
        assertEquals("67d807a83a6e4f9457365ca61ff3fa1db95b135a17feeefadeff8e9f02123243", sha256("licenses/google/litertlm-android-0.17.1-THIRD_PARTY_NOTICE.txt"))
    }

    // ---- wording ---------------------------------------------------------------------------------------------------

    @Test
    fun `the screen reproduces licences and credits and makes no legal claim`() {
        val words = (notices.flatMap { listOf(it.name, it.version, it.licence, it.usedFor) + it.texts.map { t -> t.title } } +
            listOf(OpenSourceNotices.TITLE, OpenSourceNotices.INTRO, OpenSourceNotices.NOT_ADVICE, OpenSourceNotices.ROW_DETAIL)).joinToString(" ").lowercase()
        listOf("compliant", "compliance", "cleared", "certified", "approved", "fully licensed", "legally").forEach {
            assertFalse(it, words.contains(it))
        }
        assertEquals("Open-source notices", OpenSourceNotices.TITLE)
    }

    @Test
    fun `entries are grouped and every group has entries`() {
        NoticeGroup.entries.forEach { g -> assertTrue(g.name, notices.any { it.group == g }) }
        assertNotNull(notices.firstOrNull { it.id == "traceveil" && it.group == NoticeGroup.TRACEVEIL })
    }

    @Test
    fun `routes are stable`() {
        assertEquals("notices", NoticesRoutes.LIST)
        assertEquals("notices/litert", NoticesRoutes.component("litert"))
    }
}
