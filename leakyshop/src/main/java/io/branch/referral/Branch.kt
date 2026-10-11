package io.branch.referral

import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.thread

/**
 * Demo stand-in, NOT the Branch SDK (see com.appsflyer.AppsFlyerLib). It shows traffic TraceVeil's DNS capture cannot
 * see: the tracker name is resolved over DNS-over-HTTPS (Cloudflare, addressed by IP, so the phone makes no plain
 * lookup), then a TCP connection to the answer is opened and held open briefly, as an SDK's keep-alive would. Only the
 * DNS question is sent; no app or user data. `traceveil audit` flags both connections under "Network beyond DNS".
 */
object Branch {
    private const val DOH = "https://1.1.1.1/dns-query?name=api2.branch.io&type=A"
    private val A_RECORD = Regex("\"data\":\"(\\d{1,3}(?:\\.\\d{1,3}){3})\"")

    fun init() = thread {
        runCatching {
            val c = URL(DOH).openConnection() as HttpsURLConnection
            c.setRequestProperty("accept", "application/dns-json")
            c.connectTimeout = 5_000
            c.readTimeout = 5_000
            val ip = A_RECORD.find(c.inputStream.bufferedReader().use { it.readText() })?.groupValues?.get(1) ?: return@runCatching
            Socket().use { s ->
                s.connect(InetSocketAddress(ip, 443), 5_000) // an IP literal: no DNS lookup
                Thread.sleep(HOLD_MS)
            }
        }
    }

    private const val HOLD_MS = 20_000L
}
