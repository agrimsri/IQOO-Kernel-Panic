package com.dpdpxray.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SocketTableTest {
    private val header = "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode"

    private fun row(local: String, remote: String, state: String, uid: Int) =
        "   0: $local $remote $state 00000000:00000000 00:00000000 00000000 $uid        0 12345 1 0000000000000000 20 4 30 10 -1"

    @Test fun `counts the app's established and connecting sockets to real remote addresses`() {
        val tcp = listOf(
            header,
            row("0A00020F:C350", "8EFA4D2E:01BB", "01", 10234), // 46.77.250.142:443 established
            row("0A00020F:C351", "8EFA4D2E:01BB", "02", 10234), // connecting
            row("0A00020F:C352", "8EFA4D2E:01BB", "01", 10999), // another app
            row("0100007F:1F90", "0100007F:C000", "01", 10234), // loopback
            row("00000000:1F90", "00000000:0000", "0A", 10234), // listening
            row("0A00020F:C353", "35006F0A:0035", "01", 10234), // lookup to TraceVeil's relay (port 53)
        ).joinToString("\n")
        val tcp6 = listOf(
            header,
            row("0000000000000000FFFF00000F02000A:C360", "0000000000000000FFFF00008EFA4D2E:01BB", "01", 10234), // mapped v4
            row("00000000000000000000000001000000:1F90", "00000000000000000000000001000000:C000", "01", 10234), // ::1
            row("0000000000000000FFFF00000F02000A:C361", "0000000000000000FFFF00000100007F:C000", "01", 10234), // mapped 127.0.0.1
        ).joinToString("\n")
        assertEquals(3, SocketTable.openConnections(tcp + "\n" + tcp6, 10234))
        assertEquals(1, SocketTable.openConnections(tcp, 10999))
    }

    @Test fun `a readable table with nothing of the app's is zero, an unreadable one is unknown`() {
        assertEquals(0, SocketTable.openConnections(header + "\n" + row("0A00020F:C350", "8EFA4D2E:01BB", "01", 10999), 10234))
        assertEquals(0, SocketTable.openConnections(header, 10234))
        assertNull(SocketTable.openConnections("cat: /proc/net/tcp: Permission denied", 10234))
        assertNull(SocketTable.openConnections("", 10234))
    }
}
