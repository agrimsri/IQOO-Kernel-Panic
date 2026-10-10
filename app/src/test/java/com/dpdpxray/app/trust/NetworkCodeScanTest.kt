package com.dpdpxray.app.trust

import com.dpdpxray.app.capture.DnsForwarder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps "TraceVeil's own network use" true: the trust screen lists every source file that uses a network API, and
 * says nothing else does. If a change adds a network call anywhere, this fails until the screen describes it.
 */
class NetworkCodeScanTest {
    private fun dir(vararg candidates: String) = candidates.map(::File).first { it.isDirectory }
    private val appRoot = dir("src/main/java/com/dpdpxray/app", "app/src/main/java/com/dpdpxray/app")
    private val coreRoot = dir("../core/src/main/kotlin", "core/src/main/kotlin")

    /** Java/Android APIs that open connections, resolve names or fetch URLs. */
    private val networkApi = Regex(
        "HttpURLConnection|HttpsURLConnection|openConnection\\(|(?<![A-Za-z_.])URL\\(|DatagramSocket|(?<![A-Za-z_])Socket\\(|" +
            "SocketChannel|DatagramChannel|ServerSocket|DownloadManager|okhttp3|WebView|InetAddress\\.getBy|getAllByName|openStream\\(|" +
            "HttpClient|ktor|retrofit2",
    )

    private fun scan(root: File): Set<File> =
        root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") && networkApi.containsMatchIn(it.readText()) }.toSet()

    @Test
    fun `only the files the trust screen lists use the network`() {
        val found = scan(appRoot).map { it.relativeTo(appRoot).invariantSeparatorsPath }.toSet()
        val listed = TrustText.networkPaths(onlineVoiceOn = false, fallbackResolvers = DnsForwarder.FALLBACKS).map { it.sourceFile }.toSet()
        assertEquals("Network code changed: update TrustText.networkPaths and the trust screen", listed, found)
    }

    @Test
    fun `the core module never touches the network`() {
        assertEquals(emptySet<File>(), scan(coreRoot))
    }

    @Test
    fun `no analytics, advertising or crash-reporting library is a dependency`() {
        val catalogue = listOf("../gradle/libs.versions.toml", "gradle/libs.versions.toml").map(::File).first { it.isFile }.readText()
        val build = listOf("build.gradle.kts", "app/build.gradle.kts").map(::File).first { it.isFile && it.readText().contains("com.dpdpxray.app") }.readText()
        val banned = Regex("firebase|crashlytics|sentry|bugsnag|mixpanel|amplitude|appsflyer|adjust|segment|braze|clevertap|onesignal|" +
            "facebook|play-services-ads|gms:play-services-measurement|branch|instabug|newrelic|datadog|appcenter", RegexOption.IGNORE_CASE)
        listOf(catalogue, build).forEach { text -> assertTrue(banned.find(text)?.value ?: "", banned.find(text) == null) }
        assertTrue(TrustText.NETWORK_NOTHING_ELSE.contains("no analytics, advertising or crash-reporting library"))
    }
}
