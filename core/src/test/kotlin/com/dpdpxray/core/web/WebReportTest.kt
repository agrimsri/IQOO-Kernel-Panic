package com.dpdpxray.core.web

import com.dpdpxray.core.dpdp.CitationService
import com.dpdpxray.core.dpdp.DpdpCorpus
import com.dpdpxray.core.report.MarkdownReport
import com.dpdpxray.core.rules.RulesEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebReportTest {
    private val sessions = listOf(WebTestData.build(WebTestData.leakyReject()))
    private val md = MarkdownReport.build(RulesEngine(WebTestData.catalog).evaluate(sessions), sessions, emptyList(), CitationService(DpdpCorpus.loadDefault()))

    @Test
    fun `the report of a website check lists requests with full addresses, cookies without values, and the web method`() {
        assertTrue(md.startsWith("# TraceVeil website check — https://news.example"))
        assertTrue(md.contains("## Requests"))
        assertTrue(md.contains("| 0.5 | GET | script | https://www.googletagmanager.com/gtag/js?id=G-TEST | Google Tag Manager |"))
        assertTrue(md.contains("## Cookies and storage"))
        assertTrue(md.contains("| IDE | third | doubleclick.net | Google Ads / AdMob |"))
        assertTrue(md.contains(WebAudit.METHOD))
        assertTrue(md.contains(MarkdownReport.WEB_PRIVACY_LINE))
        assertFalse(md.contains("DNS lookups made by the audited app"))
        assertFalse(md.contains(MarkdownReport.PRIVACY_LINE))
        for (value in listOf("AHWqTUk-very-long-identifier", "GA1.1.111.222", "fb.1.333.444")) assertFalse(value, md.contains(value))
    }
}
