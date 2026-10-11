package com.dpdpxray.cli

import com.dpdpxray.cli.CliHarness.run
import com.dpdpxray.cli.CliHarness.write
import com.dpdpxray.cli.samples.SampleBundles
import com.dpdpxray.core.netcapture.FlowProtocol
import com.dpdpxray.core.netcapture.FlowRecord
import com.dpdpxray.core.netcapture.HttpExchange
import com.dpdpxray.core.netcapture.Redaction
import com.dpdpxray.core.netcapture.TlsStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** `traceveil-ci check` prints the connection capture's summary when a bundle has one, and C16 when it applies. */
class NetworkSummaryTest {
    @get:Rule val temp = TemporaryFolder()

    private val s = 1_000_000_000L

    private fun deepLeaky(withCleartext: Boolean): File {
        val r = Redaction.target("/v1/e?email=asha@example.in&ev=open")
        val flows = buildList {
            add(FlowRecord(1, FlowProtocol.TCP, "157.240.1.1", 443, 10 * s, sni = "graph.facebook.com", tls = TlsStatus.SNI_ONLY, dnsName = "graph.facebook.com"))
            add(FlowRecord(2, FlowProtocol.TCP, "8.8.8.8", 443, 2 * s, sni = "dns.google", tls = TlsStatus.SNI_ONLY, noLookup = true))
            if (withCleartext) add(
                FlowRecord(3, FlowProtocol.TCP, "203.0.113.30", 80, 12 * s, sni = "collect.example.in", tls = TlsStatus.CLEARTEXT,
                    http = listOf(HttpExchange(12 * s, "GET", "collect.example.in", r.text, sensitive = r.found)), noLookup = true),
            )
        }
        val bundle = SampleBundles.leaky().let { b -> b.copy(sessions = b.sessions.map { it.copy(flows = flows, captureDepth = "full-relay") }) }
        return write(temp.root, "deep.json", bundle)
    }

    @Test
    fun `a full-relay bundle gets a Network line with counts and names never looked up`() {
        deepLeaky(withCleartext = false)
        val r = run(temp.root, "check", "deep.json")
        val line = r.out.lines().single { it.startsWith("Network:") }
        assertEquals(
            "Network:  connection capture: 6 connections (6 named from TLS, QUIC or HTTP; 0 plain-HTTP requests, 0 with personal-data fields) · names without a lookup: dns.google",
            line,
        )
        assertFalse(r.out.contains("C16 "))
    }

    @Test
    fun `a cleartext request with an e-mail address raises C16 and the JSON carries counts but no value`() {
        deepLeaky(withCleartext = true)
        val r = run(temp.root, "check", "deep.json", "--format", "json")
        val bundle = Json.parseToJsonElement(r.out.substring(r.out.indexOf('{'))).jsonObject["bundles"]!!.jsonArray.single().jsonObject
        val network = bundle["network"]!!.jsonObject
        assertEquals("full-relay", network["depth"]!!.jsonPrimitive.content)
        assertEquals(3, network["cleartextRequests"]!!.jsonPrimitive.int)
        assertEquals(3, network["personalDataRequests"]!!.jsonPrimitive.int)
        val codes = bundle["findings"]!!.jsonArray.map { it.jsonObject["checkId"]!!.jsonPrimitive.content }
        assertTrue(codes.toString(), "C16" in codes)
        assertFalse(r.out.contains("asha"))
    }

    @Test
    fun `older bundles without a capture depth print no Network line and no network object`() {
        val dir = CliHarness.samples(temp.newFolder("s"))
        val r = run(dir, "check", SampleBundles.LEAKY)
        assertTrue(r.out.lines().none { it.startsWith("Network:") })
        val json = run(dir, "check", SampleBundles.LEAKY, "--format", "json").out
        val bundle = Json.parseToJsonElement(json.substring(json.indexOf('{'))).jsonObject["bundles"]!!.jsonArray.single().jsonObject
        assertNull(bundle["network"])
    }

    @Test
    fun `C16 is not established when no plan captured connections`() {
        val sessions = SampleBundles.leaky().sessions
        assertEquals(
            "connections were not captured (a DNS-only check or an older build), so plain-HTTP requests were not seen",
            Coverage.untested(com.dpdpxray.core.model.CheckId.C16, sessions),
        )
        assertNull(Coverage.untested(com.dpdpxray.core.model.CheckId.C16, sessions.map { it.copy(captureDepth = "full-relay") }))
    }
}
