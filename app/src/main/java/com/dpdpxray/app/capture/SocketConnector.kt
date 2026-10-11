package com.dpdpxray.app.capture

import com.dpdpxray.core.netcapture.UpstreamConnector
import com.dpdpxray.core.netcapture.UpstreamTcp
import com.dpdpxray.core.netcapture.UpstreamUdp
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Real sockets for the relay. Each is excluded from the tunnel with [VpnService.protect] before it connects, so the
 * relay's own traffic never loops back into the VPN. Addresses are literal IPs; no name lookup happens here.
 */
class SocketConnector(
    private val protectTcp: (Socket) -> Boolean,
    private val protectUdp: (DatagramSocket) -> Boolean,
) : UpstreamConnector {

    override fun tcp(dstIp: String, port: Int): UpstreamTcp? {
        val s = Socket()
        return try {
            if (!protectTcp(s)) { s.close(); return null }
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(InetAddress.getByName(dstIp), port), CONNECT_TIMEOUT_MS)
            object : UpstreamTcp {
                private val input = s.getInputStream()
                private val output = s.getOutputStream()
                override fun write(b: ByteArray, off: Int, len: Int) = try { output.write(b, off, len); output.flush(); true } catch (_: Exception) { false }
                override fun read(buf: ByteArray) = try { input.read(buf) } catch (_: Exception) { -1 }
                override fun shutdownOutput() { runCatching { s.shutdownOutput() } }
                override fun close() { runCatching { s.close() } }
            }
        } catch (_: Exception) {
            runCatching { s.close() }
            null
        }
    }

    override fun udp(dstIp: String, port: Int): UpstreamUdp? {
        val s = DatagramSocket()
        return try {
            if (!protectUdp(s)) { s.close(); return null }
            s.soTimeout = UDP_IDLE_MS
            s.connect(InetAddress.getByName(dstIp), port)
            object : UpstreamUdp {
                override fun send(b: ByteArray, len: Int) = try { s.send(DatagramPacket(b, len)); true } catch (_: Exception) { false }
                override fun receive(buf: ByteArray): Int = try {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    p.length
                } catch (_: SocketTimeoutException) { -1 } catch (_: Exception) { -1 }
                override fun close() { runCatching { s.close() } }
            }
        } catch (_: Exception) {
            runCatching { s.close() }
            null
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 8000
        const val UDP_IDLE_MS = 30_000
    }
}
