package com.dpdpxray.core.netcapture

/**
 * An [UpstreamConnector] that never reaches the network, for quarantine runs: the app must stay offline, but the first
 * thing it sends on each connection (a TLS ClientHello, an HTTP request head, a QUIC Initial) is worth reading.
 *
 * With [TunRelay], each TCP connection the app opens is answered on the phone: the relay completes the handshake and
 * hands the app's bytes to its [FlowTracker], and this connector discards them. [holdAfterDataMs] after the first bytes
 * arrive (time for the rest of a hello split over segments), or [idleMs] after the connection opened when the app sends
 * nothing (protocols where the server speaks first), the stream ends and the relay closes the connection with a FIN, so
 * the app sees a server that hung up. UDP datagrams are discarded and never answered. No socket is opened, nothing is
 * forwarded and nothing is ever sent back except the TCP control segments the relay itself writes.
 */
class SinkholeConnector(
    private val holdAfterDataMs: Long = 1_500,
    private val idleMs: Long = 8_000,
    private val udpIdleMs: Long = 20_000,
) : UpstreamConnector {

    override fun tcp(dstIp: String, port: Int): UpstreamTcp = SinkTcp()

    override fun udp(dstIp: String, port: Int): UpstreamUdp = SinkUdp()

    private fun nowMs() = System.nanoTime() / 1_000_000

    private inner class SinkTcp : UpstreamTcp {
        private val lock = Object()
        private val opened = nowMs()
        private var firstData = -1L
        private var closed = false

        override fun write(b: ByteArray, off: Int, len: Int): Boolean {
            synchronized(lock) {
                if (firstData < 0 && len > 0) {
                    firstData = nowMs()
                    lock.notifyAll()
                }
            }
            return true // discarded: nothing leaves the phone
        }

        override fun read(buf: ByteArray): Int {
            synchronized(lock) {
                while (!closed) {
                    val until = if (firstData >= 0) firstData + holdAfterDataMs else opened + idleMs
                    val left = until - nowMs()
                    if (left <= 0) break
                    lock.wait(left)
                }
            }
            return -1 // end of stream: the relay closes the app's connection
        }

        override fun shutdownOutput() = close()

        override fun close() {
            synchronized(lock) {
                closed = true
                lock.notifyAll()
            }
        }
    }

    private inner class SinkUdp : UpstreamUdp {
        private val lock = Object()
        private var lastSend = nowMs()
        private var closed = false

        override fun send(b: ByteArray, len: Int): Boolean {
            synchronized(lock) { lastSend = nowMs() }
            return true // discarded
        }

        /** Never yields a reply; returns -1 once the app has been quiet for [udpIdleMs], which ends the relay's flow. */
        override fun receive(buf: ByteArray): Int {
            synchronized(lock) {
                while (!closed) {
                    val left = lastSend + udpIdleMs - nowMs()
                    if (left <= 0) break
                    lock.wait(left)
                }
            }
            return -1
        }

        override fun close() {
            synchronized(lock) {
                closed = true
                lock.notifyAll()
            }
        }
    }
}
