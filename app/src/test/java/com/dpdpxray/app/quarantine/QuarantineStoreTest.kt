package com.dpdpxray.app.quarantine

import com.dpdpxray.core.quarantine.QuarantineObservations
import com.dpdpxray.core.quarantine.QuarantineReport
import com.dpdpxray.core.quarantine.QuarantineReports
import com.dpdpxray.core.quarantine.QuarantineStopReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class QuarantineStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun report(id: String, pkg: String = "com.traceveil.riskdemo"): QuarantineReport = QuarantineReports.build(
        id = id, targetPackage = pkg, targetLabel = "Risk Demo", startedAtEpochMs = 1, startedAtNanos = 0, endedAtNanos = 60_000_000_000,
        plannedDurationMs = 60_000, stopReason = QuarantineStopReason.TIMER, resolverAddresses = QuarantineNet.RESOLVERS,
        observations = QuarantineObservations(dnsQueries = 2),
    )

    @Test
    fun `saves as id dot json and loads the same report`() {
        val root = File(tmp.root, QuarantineStore.DIR)
        val store = QuarantineStore(root)
        val r = report("quarantine-1791600000000")
        assertTrue(store.save(r))
        assertTrue(File(root, "quarantine-1791600000000.json").isFile)
        assertEquals(r, store.load("quarantine-1791600000000"))
        assertFalse(File(root, "quarantine-1791600000000.json.tmp").exists())
    }

    @Test
    fun `unsafe ids are refused and never touch the file system`() {
        val store = QuarantineStore(tmp.root)
        assertFalse(store.save(report("../escape")))
        assertNull(store.load("../escape"))
        assertNull(store.load(""))
    }

    @Test
    fun `latestFor returns the newest readable report of that app and skips corrupt files`() {
        val store = QuarantineStore(tmp.root)
        store.save(report("quarantine-1"))
        File(tmp.root, "quarantine-1.json").setLastModified(1_000_000)
        store.save(report("quarantine-2", pkg = "com.other.app"))
        File(tmp.root, "quarantine-2.json").setLastModified(2_000_000)
        store.save(report("quarantine-3"))
        File(tmp.root, "quarantine-3.json").setLastModified(3_000_000)
        File(tmp.root, "quarantine-4.json").apply { writeText("{ not json"); setLastModified(4_000_000) }
        assertEquals("quarantine-3", store.latestFor("com.traceveil.riskdemo")!!.id)
        assertEquals("quarantine-2", store.latestFor("com.other.app")!!.id)
        assertNull(store.latestFor("com.missing"))
    }
}
