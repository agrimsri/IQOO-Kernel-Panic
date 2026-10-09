package com.dpdpxray.app.ui.screens.map

import androidx.compose.ui.graphics.toArgb
import com.dpdpxray.app.ui.theme.Film
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandPaletteTest {
    private val raised = Film.raised.toArgb().toLong() and 0xFFFFFFFFL
    private val base = Film.base.toArgb().toLong() and 0xFFFFFFFFL
    private val bone = Film.bone.toArgb().toLong() and 0xFFFFFFFFL

    @Test
    fun `all ten brand colours are defined exactly as given`() {
        assertEquals(
            listOf(0xFFFAA275L, 0xFFFF8C61L, 0xFFCE6A85L, 0xFF985277L, 0xFF5C374CL, 0xFFBCF8ECL, 0xFFAED9E0L, 0xFF9FA0C3L, 0xFF8B687FL, 0xFF7B435BL),
            Brand.all.map { it.argb },
        )
        assertEquals(10, Brand.all.map { it.name }.toSet().size)
    }

    @Test
    fun `contrast ratio matches known WCAG values`() {
        assertEquals(21.0, Contrast.ratio(0xFFFFFFFFL, 0xFF000000L), 0.01)
        assertEquals(1.0, Contrast.ratio(0xFF123456L, 0xFF123456L), 0.001)
        assertEquals(4.48, Contrast.ratio(0xFF777777L, 0xFFFFFFFFL), 0.01) // #777 on white is the textbook 4.48:1
        assertEquals(Contrast.ratio(0xFF777777L, 0xFFFFFFFFL), Contrast.ratio(0xFFFFFFFFL, 0xFF777777L), 0.0001)
    }

    @Test
    fun `every text colour used by the card has at least 4_5 to 1 on its background`() {
        MapColors.textPairs(raised, base, bone).forEach { (name, fg, bg) ->
            assertTrue("$name: ${"%.2f".format(Contrast.ratio(fg, bg))} on ${bg.toString(16)}", Contrast.ratio(fg, bg) >= 4.5)
        }
    }

    @Test
    fun `every graphic colour used by the card has at least 3 to 1 on its background`() {
        MapColors.graphicPairs(raised).forEach { (name, fg, bg) ->
            assertTrue("$name: ${"%.2f".format(Contrast.ratio(fg, bg))}", Contrast.ratio(fg, bg) >= 3.0)
        }
    }

    @Test
    fun `each evidence class has a distinct look so colour is never the only cue`() {
        val looks = Evidence_ALL.map { MapColors.chip(it) }
        assertEquals(looks.size, looks.toSet().size)
        assertTrue(MapColors.chip(com.dpdpxray.core.geo.Evidence.UNKNOWN).outlined)
        assertTrue(listOf(com.dpdpxray.core.geo.Evidence.OBSERVED, com.dpdpxray.core.geo.Evidence.INFERRED, com.dpdpxray.core.geo.Evidence.SOURCED)
            .none { MapColors.chip(it).outlined })
    }

    @Test
    fun `the two modes have different accent colours`() {
        assertTrue(MapColors.accent(com.dpdpxray.core.geo.MapMode.SOURCE_DNS) != MapColors.accent(com.dpdpxray.core.geo.MapMode.OWNER_LEGAL))
    }

    private val Evidence_ALL = com.dpdpxray.core.geo.Evidence.entries
}
