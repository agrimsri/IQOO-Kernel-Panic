package com.dpdpxray.core.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldMapTest {
    @Test
    fun `parses ids, path data, size and the group offset from a small svg`() {
        val svg = """
            <svg width="100.5" height="50"><g transform="translate(0.25,0.5)">
              <path
                 d="m 1,1 2,2 z"
                 class="land" id="IN" />
              <path id="US" class="land"
                 d="M 5 5 L 9 9 Z"/>
              <path d="m 0,0 z" class="land"/>
            </g></svg>
        """.trimIndent()
        val m = WorldMap.parse(svg)
        assertEquals(100.5, m.width, 0.0)
        assertEquals(50.0, m.height, 0.0)
        assertEquals(0.25, m.offsetX, 0.0)
        assertEquals(0.5, m.offsetY, 0.0)
        assertEquals(setOf("IN", "US"), m.paths.keys)
        assertEquals("m 1,1 2,2 z", m.paths["IN"])
    }

    @Test
    fun `malformed or empty svg gives an empty map, not a crash`() {
        assertTrue(WorldMap.parse("").paths.isEmpty())
        assertTrue(WorldMap.parse("<svg><path d=\"\" id=\"IN\"/></svg>").paths.isEmpty())
        assertTrue(WorldMap.parse("<<<>>>").paths.isEmpty())
    }

    @Test
    fun `bundled map has the countries the list view can name`() {
        val m = WorldMap.loadDefault()
        assertEquals(176, m.paths.size)
        listOf("IN", "US", "IE", "DE", "NL", "IL", "GB").forEach { assertNotNull(it, m.paths[it]) }
        assertTrue(m.paths.keys.all { CountryNames.isIso(it) })
        assertEquals(1008.8549, m.width, 0.001)
        assertEquals(0.25, m.offsetX, 0.0)
    }

    @Test
    fun `small countries without an outline are still nameable for the list view`() {
        assertNull(WorldMap.loadDefault().paths["SG"])
        assertEquals("Singapore", CountryNames.name("SG"))
        assertEquals("Kosovo", CountryNames.name("XK"))
        assertTrue(WorldMap.loadDefault().paths.containsKey("XK"))
    }

    @Test
    fun `a country with no drawing is reported as missing, not invented`() = assertNull(WorldMap.loadDefault().paths["XX"])
}
