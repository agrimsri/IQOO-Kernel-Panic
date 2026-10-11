package com.dpdpxray.core.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSitesTest {
    @Test
    fun `site names - two labels, three for a shared suffix, hosts on shared hosting are their own site`() {
        assertEquals("example.com", WebSites.siteOf("www.example.com"))
        assertEquals("example.com", WebSites.siteOf("a.b.example.com."))
        assertEquals("example.co.in", WebSites.siteOf("news.example.co.in"))
        assertEquals("mygov.gov.in", WebSites.siteOf("www.mygov.gov.in"))
        assertEquals("example.co.uk", WebSites.siteOf("shop.example.co.uk"))
        assertEquals("alice.github.io", WebSites.siteOf("alice.github.io"))
        assertEquals("d111.cloudfront.net", WebSites.siteOf("d111.cloudfront.net"))
        assertEquals("8.8.8.8", WebSites.siteOf("8.8.8.8"))
        assertEquals("localhost", WebSites.siteOf("localhost"))
    }

    @Test
    fun `third-party is a different site, not a different host`() {
        assertFalse(WebSites.isThirdParty("cdn.example.com", "www.example.com"))
        assertTrue(WebSites.isThirdParty("www.google-analytics.com", "www.example.com"))
        assertTrue(WebSites.isThirdParty("bob.github.io", "alice.github.io"))
        assertTrue(WebSites.isThirdParty("other.co.in", "example.co.in"))
    }
}
