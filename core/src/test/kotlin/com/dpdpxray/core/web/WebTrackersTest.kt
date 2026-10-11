package com.dpdpxray.core.web

import com.dpdpxray.core.model.Category
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebTrackersTest {
    private val catalog = TrackerCatalog.loadDefault()
    private val web = WebTrackers.default

    /** Entries added with the website check; each cites the vendor page that shows its domains in use. */
    private val added = listOf(
        "Google Tag Manager", "LinkedIn Insight Tag", "Pinterest Tag", "Snap Pixel", "Criteo", "Taboola", "Outbrain",
        "Adobe Experience Cloud", "Microsoft Invest (Xandr)", "The Trade Desk", "Comscore",
    )

    @Test
    fun `web tracker entries carry their vendor source and classify their hosts`() {
        for (name in added + "Hotjar") {
            val e = catalog.entryFor(name)
            assertNotNull(name, e)
            assertTrue("$name has no https source", e!!.domainSource?.startsWith("https://") == true)
            assertTrue("$name is not tracking", e.category.isTracking)
        }
        assertEquals("Google Tag Manager", catalog.classify("www.googletagmanager.com").entity)
        assertEquals("LinkedIn Insight Tag", catalog.classify("px.ads.linkedin.com").entity)
        assertEquals("Hotjar", catalog.classify("content.hotjar.io").entity)
        assertEquals("Adobe Experience Cloud", catalog.classify("dpm.demdex.net").entity)
        // Shared hosts stay unclassified: LinkedIn's image CDN and Pinterest's image host are not trackers by host.
        assertNull(catalog.classify("media.licdn.com").entity)
        assertNull(catalog.classify("i.pinimg.com").entity)
        // Unverified vendors are not guessed.
        assertNull(catalog.classify("bat.bing.com").entity)
        assertNull(catalog.classify("analytics.tiktok.com").entity)
    }

    @Test
    fun `every path and cookie rule cites an https source and names a known or self-described entity`() {
        for (r in web.data.pathRules) {
            assertTrue(r.source.startsWith("https://"))
            assertTrue(r.entity, catalog.entryFor(r.entity) != null || r.category != null)
        }
        for (r in web.data.cookies) {
            assertTrue(r.source.startsWith("https://"))
            assertTrue(r.entity, catalog.entryFor(r.entity) != null || r.category != null)
            assertTrue(r.patterns.isNotEmpty())
        }
    }

    @Test
    fun `cookie names match exactly or by documented pattern, case-sensitive`() {
        assertEquals("Google Analytics", web.cookieRule("_ga")?.entity)
        assertEquals("Google Analytics", web.cookieRule("_ga_ABC123")?.entity)
        assertEquals("Google Analytics", web.cookieRule("_gat_UA-1")?.entity)
        assertEquals("Meta (Facebook SDK)", web.cookieRule("_fbp")?.entity)
        assertEquals("Hotjar", web.cookieRule("_hjSessionUser_123")?.entity)
        assertEquals("Mixpanel", web.cookieRule("mp_0123abcd_mixpanel")?.entity)
        assertEquals("Amplitude", web.cookieRule("AMP_0123456789")?.entity)
        assertNull(web.cookieRule("amp_0123456789"))
        assertNull(web.cookieRule("_gax"))
        assertNull(web.cookieRule("session"))
        assertEquals(Category.ADVERTISING, web.categoryOf("TikTok Pixel", catalog))
        assertEquals(Category.ADVERTISING, web.categoryOf("Pinterest Tag", catalog))
    }
}
