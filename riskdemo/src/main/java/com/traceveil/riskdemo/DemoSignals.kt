package com.traceveil.riskdemo

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The only network activity in this TEST app. It imitates the *signals* of a fake challan APK (a lookup of a
 * command-and-control style name and a connection to a hard-coded IP) without any chance of reaching a real server:
 *
 * - every hostname is under a reserved top-level domain (RFC 2606 / RFC 6761: `.invalid`, `.example`), so it has no
 *   real owner and resolvers answer "no such name";
 * - the IP address is in TEST-NET-3 (RFC 5737, 203.0.113.0/24), reserved for documentation and not routed.
 *
 * Nothing is ever written to the connection. If it unexpectedly opens, it is closed at once.
 * Plain Kotlin (no Android APIs) so the JVM tests can check every target.
 */
object DemoSignals {
    val HOSTS: List<String> = listOf("c2.traceveil-demo.invalid", "pay-challan-update.invalid", "otp-relay.example")

    /** 203.0.113.10, built from bytes so no name lookup ever happens for it. */
    val TEST_NET_ADDRESS: InetAddress = InetAddress.getByAddress(byteArrayOf(203.toByte(), 0, 113, 10))
    const val PORT = 443
    const val CONNECT_TIMEOUT_MS = 1_500

    enum class Kind { DNS_LOOKUP, TCP_CONNECT }

    /** One network attempt as shown on screen. [reached] is true only if a name resolved or a connection opened. */
    data class Attempt(val kind: Kind, val target: String, val reached: Boolean, val detail: String)

    /**
     * Makes each attempt once, in order, and never throws. [lookup] returns how many addresses a name resolved to;
     * [connect] opens and immediately closes a connection. Both are parameters so tests can run without a network.
     */
    fun run(
        lookup: (String) -> Int = ::systemLookup,
        connect: (InetAddress, Int, Int) -> Unit = ::connectAndClose,
    ): List<Attempt> {
        val lookups = HOSTS.map { host ->
            try {
                val count = lookup(host)
                Attempt(Kind.DNS_LOOKUP, host, true, "Resolved to $count address(es), which is unexpected for a reserved name. Nothing was sent.")
            } catch (e: Exception) {
                Attempt(Kind.DNS_LOOKUP, host, false, "No address, as expected for a reserved name (${e.javaClass.simpleName}).")
            }
        }
        val target = "${TEST_NET_ADDRESS.hostAddress}:$PORT"
        val connectAttempt = try {
            connect(TEST_NET_ADDRESS, PORT, CONNECT_TIMEOUT_MS)
            Attempt(Kind.TCP_CONNECT, target, true, "Connected, which is unexpected for a documentation address. Closed at once; 0 bytes sent.")
        } catch (e: Exception) {
            Attempt(Kind.TCP_CONNECT, target, false, "Not connected, as expected for a documentation address (${e.javaClass.simpleName}). 0 bytes sent.")
        }
        return lookups + connectAttempt
    }

    private fun systemLookup(host: String): Int = InetAddress.getAllByName(host).size

    /** Opens a TCP connection with a short timeout and closes it immediately without sending anything. */
    private fun connectAndClose(address: InetAddress, port: Int, timeoutMs: Int) {
        Socket().use { socket -> socket.connect(InetSocketAddress(address, port), timeoutMs) }
    }
}
