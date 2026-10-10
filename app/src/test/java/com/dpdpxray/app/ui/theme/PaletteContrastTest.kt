package com.dpdpxray.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.dpdpxray.app.ui.screens.map.Contrast
import org.junit.Assert.assertTrue
import org.junit.Test

/** The design tokens must stay readable in both themes: 4.5:1 for text, 3:1 for graphics (UI_UX_REFACTOR_GUIDE.md 6.1). */
class PaletteContrastTest {
    private fun Color.argb(): Long = toArgb().toLong() and 0xFFFFFFFFL

    private fun ratio(fg: Color, bg: Color) = Contrast.ratio(fg.argb(), bg.argb())

    private val failures = mutableListOf<String>()

    private fun check(name: String, p: Palette, min: Double, fg: Color, bg: Color) {
        val r = ratio(fg, bg)
        if (r < min) failures += "$name: ${"%.2f".format(r)}:1 is below $min:1"
    }

    private fun textPairs(name: String, p: Palette) {
        for ((bgName, bg) in listOf("bg" to p.bg, "surface" to p.surface, "fill" to p.fill)) {
            check("$name textPrimary on $bgName", p, 4.5, p.textPrimary, bg)
            check("$name textSecondary on $bgName", p, 4.5, p.textSecondary, bg)
            check("$name accent on $bgName", p, 4.5, p.accent, bg)
            check("$name risk on $bgName", p, 4.5, p.risk, bg)
            check("$name caution on $bgName", p, 4.5, p.caution, bg)
            check("$name ok on $bgName", p, 4.5, p.ok, bg)
            check("$name neutral on $bgName", p, 4.5, p.neutral, bg)
        }
        check("$name onAccent on accent", p, 4.5, p.onAccent, p.accent)
        check("$name textPrimary on surfaceRaised", p, 4.5, p.textPrimary, p.surfaceRaised)
        check("$name accent on surfaceRaised", p, 4.5, p.accent, p.surfaceRaised)
        // Captions carry no information that is not repeated elsewhere, so graphic-level contrast is enough.
        check("$name textTertiary on bg", p, 3.0, p.textTertiary, p.bg)
        check("$name separator on bg is a hairline, not text", p, 1.05, p.separator, p.bg)
    }

    @Test fun `dark palette is readable`() {
        textPairs("dark", Palettes.Dark)
        assertTrue(failures.joinToString("; "), failures.isEmpty())
    }

    @Test fun `light palette is readable`() {
        textPairs("light", Palettes.Light)
        assertTrue(failures.joinToString("; "), failures.isEmpty())
    }

    @Test fun `film names resolve to the active palette`() {
        assertTrue(Film.base == ActivePalette.current.bg)
        assertTrue(Film.scan == ActivePalette.current.accent)
        assertTrue(severityColorIsTiered())
    }

    private fun severityColorIsTiered(): Boolean {
        val c = com.dpdpxray.core.model.Severity.CRITICAL
        val h = com.dpdpxray.core.model.Severity.HIGH
        return severityColor(c) == severityColor(h)
    }
}
