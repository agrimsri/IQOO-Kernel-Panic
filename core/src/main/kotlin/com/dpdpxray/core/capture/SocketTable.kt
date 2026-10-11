package com.dpdpxray.core.capture

/**
 * Counts one app's open network connections from the kernel's socket tables (`/proc/net/tcp`, `tcp6`, `udp`, `udp6`,
 * concatenated), read through Shizuku's shell. Used only to tell "the app used the network" from "it did not" when it
 * made almost no lookups. Only the count leaves this function: no address, port or host is kept or logged.
 *
 * Counted: sockets owned by [uid] that are connected or connecting (TCP states ESTABLISHED, SYN_SENT, SYN_RECV; UDP
 * "connected") to a remote address that is not zero, not loopback, and not port 53 (lookups to TraceVeil's own DNS relay).
 * Listening sockets and closed ones are not counted.
 */
object SocketTable {
    private val COUNTED_STATES = setOf("01", "02", "03")

    /** Open connections of [uid] in [text]; null when [text] holds no readable socket table at all. */
    fun openConnections(text: String, uid: Int): Int? {
        var tableSeen = false
        val seen = mutableSetOf<String>()
        for (line in text.lineSequence()) {
            val cols = line.trim().split(Regex("\\s+"))
            if (cols.size >= 2 && cols[0] == "sl" && cols[1] == "local_address") { tableSeen = true; continue }
            if (cols.size < 8 || !cols[0].endsWith(":")) continue
            tableSeen = true
            if (cols[7].toIntOrNull() != uid) continue
            if (cols[3].uppercase() !in COUNTED_STATES) continue
            val remote = cols[2].split(":")
            if (remote.size != 2) continue
            val (addr, portHex) = remote
            val port = portHex.toIntOrNull(16) ?: continue
            if (port == 0 || port == 53 || isZero(addr) || isLoopback(addr)) continue
            seen += "${cols[1]}>${cols[2]}"
        }
        return if (tableSeen) seen.size else null
    }

    private fun isZero(hex: String) = hex.all { it == '0' }

    /** 127.0.0.0/8 (IPv4, or IPv4-mapped in IPv6) and ::1. The kernel prints each 32-bit word little-endian. */
    private fun isLoopback(hex: String): Boolean = when (hex.length) {
        8 -> hex.takeLast(2).equals("7F", ignoreCase = true)
        32 -> hex.equals("00000000000000000000000001000000", ignoreCase = true) ||
            (hex.substring(0, 16).all { it == '0' } && hex.substring(16, 24).equals("FFFF0000", ignoreCase = true) &&
                hex.takeLast(2).equals("7F", ignoreCase = true))
        else -> false
    }
}
