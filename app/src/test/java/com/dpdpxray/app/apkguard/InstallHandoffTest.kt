package com.dpdpxray.app.apkguard

import com.dpdpxray.core.apk.InstallBlock
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/** The "Install anyway" hand-off to Android's installer, against a fake installer. */
class InstallHandoffTest {
    private class FakeGateway(var allowed: Boolean = true, val failWrite: Boolean = false, val failCreate: Boolean = false) : InstallerGateway {
        val created = mutableListOf<Pair<String, Long>>()
        val written = mutableMapOf<Int, ByteArray>()
        val committed = mutableListOf<Pair<Int, String>>()
        val abandoned = mutableListOf<Int>()
        var permissionChecks = 0

        override fun canRequestInstalls(): Boolean { permissionChecks++; return allowed }
        override fun createSession(packageName: String, sizeBytes: Long): Int {
            if (failCreate) throw IOException("no space")
            created += packageName to sizeBytes
            return 70 + created.size
        }
        override fun write(sessionId: Int, apk: File) {
            if (failWrite) throw IOException("broken pipe")
            written[sessionId] = apk.readBytes()
        }
        override fun commit(sessionId: Int, checkKey: String) { committed += sessionId to checkKey }
        override fun abandon(sessionId: Int) { abandoned += sessionId }
    }

    private fun held(bytes: ByteArray = byteArrayOf(1, 2, 3, 4, 5)): HeldCopy {
        val f = File.createTempFile("held-", ".apk").apply { writeBytes(bytes); deleteOnExit() }
        return HeldCopy(f, InstallHandoff.sha256(f)!!, "com.example.notes")
    }

    @Test fun `the exact checked bytes go into one session that is committed for this check`() {
        val gw = FakeGateway()
        val copy = held()
        val result = InstallHandoff(gw).start("key-1", copy, block = null)
        assertEquals(HandoffStart.Started(71), result)
        assertEquals(listOf("com.example.notes" to 5L), gw.created)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), gw.written[71])
        assertEquals(listOf(71 to "key-1"), gw.committed)
        assertTrue(gw.abandoned.isEmpty())
    }

    @Test fun `without the Install unknown apps permission nothing is created and the person is sent to the setting`() {
        val gw = FakeGateway(allowed = false)
        assertEquals(HandoffStart.NeedsPermission, InstallHandoff(gw).start("k", held(), block = null))
        assertTrue(gw.created.isEmpty())
        assertTrue(gw.committed.isEmpty())
    }

    @Test fun `a file Android would refuse is never handed over`() {
        val gw = FakeGateway()
        assertEquals(HandoffStart.Blocked(InstallBlock.DIFFERENT_SIGNER), InstallHandoff(gw).start("k", held(), InstallBlock.DIFFERENT_SIGNER))
        assertEquals(0, gw.permissionChecks)
        assertTrue(gw.created.isEmpty())
    }

    @Test fun `a file that changed after the check is never installed`() {
        val gw = FakeGateway()
        val copy = held()
        copy.file.writeBytes(byteArrayOf(9, 9, 9))
        val result = InstallHandoff(gw).start("k", copy, block = null)
        assertTrue(result is HandoffStart.Unavailable)
        assertTrue((result as HandoffStart.Unavailable).reason.contains("changed"))
        assertTrue(gw.created.isEmpty())
    }

    @Test fun `a missing checked copy is reported, not guessed`() {
        val gw = FakeGateway()
        assertTrue(InstallHandoff(gw).start("k", null, block = null) is HandoffStart.Unavailable)
        val gone = held().also { it.file.delete() }
        assertTrue(InstallHandoff(gw).start("k", gone, block = null) is HandoffStart.Unavailable)
        assertTrue(gw.created.isEmpty())
    }

    @Test fun `a failed write abandons the session and commits nothing`() {
        val gw = FakeGateway(failWrite = true)
        val result = InstallHandoff(gw).start("k", held(), block = null)
        assertTrue(result is HandoffStart.Failed)
        assertEquals(listOf(71), gw.abandoned)
        assertTrue(gw.committed.isEmpty())
    }

    @Test fun `a session Android refuses to create is a failure with a reason`() {
        val result = InstallHandoff(FakeGateway(failCreate = true)).start("k", held(), block = null)
        assertTrue(result is HandoffStart.Failed)
        assertTrue((result as HandoffStart.Failed).reason.contains("IOException"))
    }

    // --- what Android reports back ------------------------------------------------------------------------------------

    @Test fun `Android's session statuses map to plain outcomes`() {
        // Values of PackageInstaller.STATUS_*.
        assertEquals(InstallState.WaitingForYou, InstallOutcome.from(-1, null, null))
        assertEquals(InstallState.Installed("com.example.notes"), InstallOutcome.from(0, "com.example.notes", null))
        assertEquals(InstallState.Cancelled, InstallOutcome.from(3, null, null))
        listOf(1, 2, 4, 5, 6, 7, 8, 42).forEach { status ->
            val state = InstallOutcome.from(status, null, "INSTALL_FAILED_X: detail")
            assertTrue("$status", state is InstallState.Failed)
            state as InstallState.Failed
            assertTrue(state.reason.en.startsWith("Not installed"))
            assertTrue(state.reason.hi.isNotBlank())
            assertEquals("INSTALL_FAILED_X: detail", state.detail)
        }
        assertTrue((InstallOutcome.from(5, null, null) as InstallState.Failed).reason.en.contains("different key"))
        assertTrue((InstallOutcome.from(6, null, null) as InstallState.Failed).reason.en.contains("storage"))
        assertNull((InstallOutcome.from(4, null, " ") as InstallState.Failed).detail)
    }

    @Test fun `only the final outcomes end a hand-off`() {
        assertFalse(InstallOutcome.isFinal(InstallState.Preparing))
        assertFalse(InstallOutcome.isFinal(InstallState.WaitingForYou))
        assertTrue(InstallOutcome.isFinal(InstallState.Installed(null)))
        assertTrue(InstallOutcome.isFinal(InstallState.Cancelled))
        assertTrue(InstallOutcome.isFinal(InstallOutcome.from(1, null, null)))
    }

    // --- the new-install alert is not repeated for an install the person just made through TraceVeil ---------------

    @Test fun `an install handed over by TraceVeil is expected for thirty minutes`() {
        val expected = ExpectedInstalls()
        expected.expect("com.example.notes", now = 1_000L)
        assertTrue(expected.isExpected("com.example.notes", now = 1_000L + 29 * 60_000L))
        assertFalse(expected.isExpected("com.example.notes", now = 1_000L + 31 * 60_000L))
        assertFalse(expected.isExpected("com.other.app", now = 1_000L))
        assertFalse(expected.isExpected(null, now = 1_000L))
    }
}
