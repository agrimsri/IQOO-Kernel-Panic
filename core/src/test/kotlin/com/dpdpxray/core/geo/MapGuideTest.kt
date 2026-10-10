package com.dpdpxray.core.geo

import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.CaptureHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapGuideTest {
    private val fallbacks = listOf("8.8.8.8", "1.1.1.1")
    private fun session(plan: AuditPlan, privateDns: Boolean?, hidden: Long? = null, health: Boolean = true) = AuditSession(
        id = plan.name, targetPackage = "com.leakyshop.demo", targetLabel = "LeakyShop", plan = plan, privateDnsActive = privateDns,
        captureHealth = if (health) CaptureHealth(hiddenDnsPackets = hidden) else null,
    )

    private fun allText(): String = (MapGuide.points(fallbacks).flatMap { listOf(it.title, it.line, it.detail) } +
        MapGuide.whyItMatters.flatMap { listOf(it.region, it.rule, it.line, it.citation) } +
        listOf(MapGuide.TITLE, MapGuide.WHY_HEADING, MapGuide.WHY_CLOSING)).joinToString("\n")

    // ---- the fold's statements ---------------------------------------------------------------------------------------

    @Test
    fun `the fold says the map shows servers, never the person, and that no location permission is held`() {
        val first = MapGuide.points(fallbacks).first()
        assertTrue(first.line, first.line.contains("never shows where you are"))
        assertTrue(first.line, first.line.contains("no location permission"))
        assertTrue(first.detail, first.detail.contains("not proof that data was sent"))
    }

    @Test
    fun `it covers approximation, cited owners, VPNs, Private DNS and hidden lookups`() {
        val titles = MapGuide.points(fallbacks).map { it.title }
        assertEquals(
            listOf("It places servers, never you", "A country from an IP address is approximate", "The company comes from cited documents",
                "Using your own VPN?", "Private DNS and DNS servers abroad", "Hidden lookups are not a pass"),
            titles,
        )
        val text = allText()
        listOf("CDNs, anycast", "not where your data is stored", "Not established", "one VPN at a time", "Turn it off before a check",
            "Inconclusive", "never works it out").forEach { assertTrue(it, text.contains(it)) }
    }

    @Test
    fun `the resolver detail names the relay's real fallback servers`() {
        val p = MapGuide.points(listOf("192.0.2.53", "198.51.100.53")).first { it.title.startsWith("Private DNS") }
        assertTrue(p.detail, p.detail.contains("then to 192.0.2.53 or 198.51.100.53 if those fail"))
        assertTrue(p.detail, p.detail.contains("does not record which one answered"))
    }

    @Test
    fun `why it matters covers several jurisdictions, each with a citation`() {
        val rules = MapGuide.whyItMatters
        assertEquals(listOf("European Union and United Kingdom", "India", "China", "Data-localisation rules"), rules.map { it.region })
        assertTrue(rules.first().rule.contains("Chapter V"))
        assertTrue(rules[1].rule.contains("section 16"))
        assertTrue(rules[1].line, rules[1].line.contains("13 May 2027"))        // readiness language, not "in force"
        assertTrue(rules[2].rule.contains("Personal Information Protection Law"))
        assertTrue(rules[3].line.contains("RBI"))
        rules.forEach { assertTrue(it.region, it.citation.isNotBlank()) }
        assertTrue(MapGuide.WHY_CLOSING.contains("not proof of where your data is stored"))
    }

    @Test
    fun `nothing in the fold gives a verdict about an app`() {
        val banned = Regex("\\bviolat|illegal|unlawful|breach|\\bsafe\\b|compliant|\\bproves?\\b|guarantee|₹|crore", RegexOption.IGNORE_CASE)
        val text = allText()
        assertNull(banned.find(text)?.value, banned.find(text))
    }

    // ---- network context ----------------------------------------------------------------------------------------------

    private fun context(vararg s: AuditSession) = MapGuide.networkContext(s.toList(), fallbacks).associateBy { it.label }

    @Test
    fun `private DNS off in every plan is observed, on in some is flagged with what to do`() {
        val off = context(session(AuditPlan.REJECT, false), session(AuditPlan.ACCEPT, false)).getValue("Private DNS during this check")
        assertEquals("Off", off.value)
        assertEquals(Evidence.OBSERVED, off.evidence)
        val on = context(session(AuditPlan.REJECT, true), session(AuditPlan.ACCEPT, false)).getValue("Private DNS during this check")
        assertEquals("On in 1 of 2 plans", on.value)
        assertTrue(on.note!!, on.note!!.contains("Turn it off"))
    }

    @Test
    fun `an unrecorded setting stays unknown, never off`() {
        val none = context(session(AuditPlan.REJECT, null)).getValue("Private DNS during this check")
        assertEquals("Not recorded", none.value)
        assertEquals(Evidence.UNKNOWN, none.evidence)
        val partly = context(session(AuditPlan.REJECT, false), session(AuditPlan.ACCEPT, null)).getValue("Private DNS during this check")
        assertEquals("Off (not recorded for 1 of 2 plans)", partly.value)
    }

    @Test
    fun `hidden DNS is summed when recorded and unknown when not`() {
        val counted = context(session(AuditPlan.REJECT, false, hidden = 3), session(AuditPlan.ACCEPT, false, hidden = 2))
            .getValue("Encrypted or TCP DNS the check could not read")
        assertEquals("5 packets", counted.value)
        assertEquals(Evidence.OBSERVED, counted.evidence)
        assertTrue(counted.note!!, counted.note!!.contains("DNS over HTTPS"))
        val zero = context(session(AuditPlan.REJECT, false, hidden = 0)).getValue("Encrypted or TCP DNS the check could not read")
        assertEquals("0 packets", zero.value)
        val old = context(session(AuditPlan.REJECT, false, health = false)).getValue("Encrypted or TCP DNS the check could not read")
        assertEquals("Not recorded", old.value)
        assertEquals(Evidence.UNKNOWN, old.evidence)
        val mixed = context(session(AuditPlan.REJECT, false, hidden = 1), session(AuditPlan.ACCEPT, false, hidden = null))
            .getValue("Encrypted or TCP DNS the check could not read")
        assertEquals("1 packet (not recorded for 1 of 2 plans)", mixed.value)
    }

    @Test
    fun `the answering resolver is honestly not recorded`() {
        val r = context(session(AuditPlan.REJECT, false)).getValue("DNS server that answered")
        assertEquals("Not recorded", r.value)
        assertEquals(Evidence.UNKNOWN, r.evidence)
        assertTrue(r.note!!, r.note!!.contains("8.8.8.8 or 1.1.1.1"))
    }

    @Test
    fun `no sessions means no context rows rather than invented ones`() {
        assertTrue(MapGuide.networkContext(emptyList(), fallbacks).isEmpty())
    }
}
