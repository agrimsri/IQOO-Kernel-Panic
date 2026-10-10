package com.dpdpxray.app.data

import com.dpdpxray.core.model.AppManifestFacts
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.report.AuditJson
import com.dpdpxray.core.update.AppVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class CheckedAppsTest {
    private fun meta(id: String, at: Long, name: String? = null, code: Long? = null, sample: Boolean = false, pkg: String = "com.leakyshop.demo") =
        AuditMeta(id, pkg, "LeakyShop", at, 21, ScoreBand.AT_RISK, 5, listOf("Silent", "Reject", "Accept"), sample,
            timingProfile = "normal", targetVersionName = name, targetVersionCode = code)

    private fun session(name: String?, code: Long?) = AuditSession(
        id = "s", targetPackage = "com.leakyshop.demo", targetLabel = "LeakyShop", plan = AuditPlan.SILENT,
        manifestFacts = AppManifestFacts(versionName = name, versionCode = code),
    )

    @Test
    fun `an audit saved before the version fields existed reads them as not recorded`() {
        val old = """{"id":"audit-1","targetPackage":"com.leakyshop.demo","targetLabel":"LeakyShop","createdAtMs":1,"score":21,
            |"band":"AT_RISK","findingCount":5,"plans":["Silent"],"sample":false,"timingProfile":"normal"}""".trimMargin()
        val meta = AuditJson.json.decodeFromString(AuditMeta.serializer(), old)
        assertNull(meta.targetVersionName)
        assertNull(meta.targetVersionCode)
        assertNull(CheckedApps.version(meta))
    }

    @Test
    fun `the version fields round trip`() {
        val meta = meta("a", 1, "1.1", 2)
        val back = AuditJson.json.decodeFromString(AuditMeta.serializer(), AuditJson.json.encodeToString(AuditMeta.serializer(), meta))
        assertEquals(meta, back)
        assertEquals(AppVersion("1.1", 2), CheckedApps.version(back))
    }

    @Test
    fun `version keys are written only when recorded and the core bundle view reads them`() {
        val without = AuditJson.json.encodeToJsonElement(AuditMeta.serializer(), meta("a", 1)).jsonObject
        assertTrue("targetVersionName" !in without && "targetVersionCode" !in without)
        val with = AuditJson.json.encodeToJsonElement(AuditMeta.serializer(), meta("a", 1, "1.1", 2)).jsonObject
        val core = AuditJson.json.decodeFromJsonElement(com.dpdpxray.core.export.BundleMeta.serializer(), with)
        assertEquals("1.1", core.targetVersionName)
        assertEquals(2L, core.targetVersionCode)
        val coreKeys = AuditJson.json.encodeToJsonElement(com.dpdpxray.core.export.BundleMeta.serializer(), core).jsonObject.keys
        assertEquals(with.keys, coreKeys)
    }

    @Test
    fun `the saved meta version is used without reading sessions`() {
        val loaded = mutableListOf<String>()
        val apps = CheckedApps.latest(listOf(meta("a", 1, "1.0", 1))) { loaded += it; null }
        assertEquals(AppVersion("1.0", 1), apps.single().version)
        assertTrue(loaded.isEmpty())
    }

    @Test
    fun `an older audit falls back to the version its sessions recorded`() {
        val apps = CheckedApps.latest(listOf(meta("a", 1))) { listOf(session("1.0", 1)) }
        assertEquals(AppVersion("1.0", 1), apps.single().version)
    }

    @Test
    fun `no recorded version anywhere stays null`() {
        assertNull(CheckedApps.latest(listOf(meta("a", 1))) { listOf(session(null, null)) }.single().version)
        assertNull(CheckedApps.latest(listOf(meta("a", 1))) { null }.single().version)
    }

    @Test
    fun `only the latest real audit of each package is read and samples are skipped`() {
        val loaded = mutableListOf<String>()
        val apps = CheckedApps.latest(
            listOf(meta("old", 1), meta("new", 3), meta("sample", 4, sample = true), meta("other", 2, pkg = "other.app")),
        ) { loaded += it; listOf(session("1.0", 1)) }
        assertEquals(setOf("new", "other"), apps.map { it.auditId }.toSet())
        assertEquals(setOf("new", "other"), loaded.toSet())
    }

    @Test
    fun `the last score and band are carried`() {
        val app = CheckedApps.latest(listOf(meta("a", 1, "1.0", 1))) { null }.single()
        assertEquals(21, app.score)
        assertEquals(ScoreBand.AT_RISK, app.band)
        assertEquals("LeakyShop", app.label)
    }
}
