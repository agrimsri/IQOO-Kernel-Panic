package com.dpdpxray.app.ui.screens.map

import androidx.compose.ui.graphics.Color
import com.dpdpxray.core.geo.Evidence
import com.dpdpxray.core.geo.MapMode
import kotlin.math.pow

/** The ten brand colours, exactly as supplied. */
enum class Brand(val argb: Long) {
    TangerineDream(0xFFFAA275),
    CoralGlow(0xFFFF8C61),
    BlushRose(0xFFCE6A85),
    BerryBlush(0xFF985277),
    MauveShadow(0xFF5C374C),
    IcyAqua(0xFFBCF8EC),
    LightBlue(0xFFAED9E0),
    LavenderGrey(0xFF9FA0C3),
    DustyLavender(0xFF8B687F),
    VintageBerry(0xFF7B435B);

    val color: Color get() = Color(argb)

    companion object { val all: List<Brand> = entries }
}

/** WCAG 2.x contrast ratio between two opaque ARGB colours. */
object Contrast {
    fun ratio(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun luminance(argb: Long): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}

/**
 * How the palette is used on the existing dark "film" surfaces. Brand colours carry the two modes and the four evidence
 * classes; consent-phase and severity colours elsewhere in the app keep their meaning. Every foreground/background pair
 * below is checked for contrast in BrandPaletteTest.
 */
object MapColors {
    /** A chip's look. Unknown is hollow, so the four classes differ by shape as well as colour. */
    data class Chip(val text: Long, val fill: Long?, val outlined: Boolean)

    fun accent(mode: MapMode): Long = when (mode) {
        MapMode.SOURCE_DNS -> Brand.IcyAqua.argb
        MapMode.OWNER_LEGAL -> Brand.TangerineDream.argb
    }

    fun chip(e: Evidence): Chip = when (e) {
        Evidence.OBSERVED -> Chip(Brand.IcyAqua.argb, Brand.MauveShadow.argb, false)
        Evidence.INFERRED -> Chip(Brand.TangerineDream.argb, Brand.MauveShadow.argb, false)
        Evidence.SOURCED -> Chip(Brand.LightBlue.argb, Brand.MauveShadow.argb, false)
        Evidence.UNKNOWN -> Chip(Brand.LavenderGrey.argb, null, true)
    }

    const val LAND = 0xFF2A4558L           // Film.line
    val LOCATED = Brand.LightBlue.argb      // a country where lookups were answered (inferred)
    val UNCERTAIN = Brand.LavenderGrey.argb // answered there, but CDN or several countries
    val COMPANY_LOCATION = Brand.TangerineDream.argb

    /** (name, text, background) pairs that must reach 4.5:1. */
    fun textPairs(raised: Long, base: Long, bone: Long): List<Triple<String, Long, Long>> = buildList {
        Evidence.entries.forEach { e -> chip(e).let { add(Triple("chip $e", it.text, it.fill ?: raised)) } }
        MapMode.entries.forEach { m -> add(Triple("selected $m", base, accent(m))) }
        add(Triple("mode label on card", bone, raised))
        add(Triple("unselected mode", bone, Brand.MauveShadow.argb))
        add(Triple("uncertain label", Brand.CoralGlow.argb, raised))
        MapMode.entries.forEach { m -> add(Triple("accent heading $m", accent(m), raised)) }
    }

    /** (name, colour, background) pairs that must reach 3:1. */
    fun graphicPairs(raised: Long): List<Triple<String, Long, Long>> = listOf(
        Triple("located vs land", LOCATED, LAND),
        Triple("uncertain vs land", UNCERTAIN, LAND),
        Triple("company location vs land", COMPANY_LOCATION, LAND),
        Triple("row rule: source mode", accent(MapMode.SOURCE_DNS), raised),
        Triple("row rule: owner mode", Brand.BlushRose.argb, raised),
    )
}
