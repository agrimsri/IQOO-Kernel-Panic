package com.dpdpxray.app.integration

import com.dpdpxray.app.ui.EndpointMapIntegration
import com.dpdpxray.core.geo.LocationBasis
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class EndpointMapIntegrationTest {
    @Test fun `map reads the merged A5 facts rather than a second owner list`() {
        val catalog = TrackerCatalog.loadDefault()
        val owners = EndpointMapIntegration.owners(catalog)
        catalog.entries().filter { it.location != null }.forEach { entry ->
            val country = owners.find(entry.entity)!!.location!!
            val source = entry.location!!
            assertEquals(source.country, country.iso)
            assertEquals(source.source, country.sourceUrl)
            assertEquals(source.quote, country.quote)
        }
        assertNotNull(owners.find("Amplitude"))
        assertNull(owners.find("CleverTap")!!.location)
    }

    @Test fun `stated address does not become a headquarters in the mounted map`() {
        assertEquals(LocationBasis.STATED_ADDRESS, EndpointMapIntegration.owners(TrackerCatalog.loadDefault()).find("AppsFlyer")!!.location!!.basis)
    }

    @Test fun `current legal context states future commencement and official sources`() {
        val legal = EndpointMapIntegration.legalContext(LocalDate.of(2026, 10, 10))
        assertTrue(legal.caveat.contains("13 May 2027"))
        assertTrue(legal.caveat.contains("not yet in force"))
        assertTrue(legal.caveat.contains("indiacode.nic.in"))
        assertFalse(legal.caveat.contains("applies to this app"))
    }

    @Test fun `later dates do not keep the obsolete not yet commenced message`() {
        val legal = EndpointMapIntegration.legalContext(LocalDate.of(2027, 5, 13))
        assertFalse(legal.caveat.contains("not yet in force"))
        assertTrue(legal.caveat.contains("Verify"))
    }
}
