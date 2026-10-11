package com.dpdpxray.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The design tokens (UI_UX_REFACTOR_GUIDE.md section 6.1): soft surfaces, one accent, and three status colours. Status
 * colour appears as a dot or one word, never as a card fill. Two palettes follow the system light/dark setting.
 *
 * The scheme is built from five colours: Icy Aqua #BCF8EC, Light Blue #AED9E0, Lavender Grey #9FA0C3, Dusty Lavender
 * #8B687F and Vintage Berry #7B435B. Light uses Vintage Berry as the accent over surfaces tinted from Icy Aqua and Light
 * Blue; dark uses Icy Aqua as the accent over berry-charcoal surfaces. Where a text colour needs more contrast than the
 * raw colour gives, a deeper or lighter step of the same hue is used (checked in PaletteContrastTest).
 */
@Immutable
class Palette(
    val dark: Boolean,
    /** Screen background (flat). */
    val bg: Color,
    /** Grouped sections and cards. */
    val surface: Color,
    /** Sheets, dialogs and menus. */
    val surfaceRaised: Color,
    /** Hairlines between rows. */
    val separator: Color,
    /** A quiet fill for tracks, skeleton bars, code blocks and chips. */
    val fill: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    /** Captions only, never the only carrier of information. */
    val textTertiary: Color,
    /** Icons and switch thumbs (graphics need 3:1, not text contrast): Dusty Lavender in both themes. */
    val graphic: Color,
    /** Links, selection and the one primary action. */
    val accent: Color,
    val onAccent: Color,
    /** Critical and high findings, "At risk". */
    val risk: Color,
    /** Medium findings, "Needs work", caveats. */
    val caution: Color,
    /** "Ready", fixed, allowed. */
    val ok: Color,
    /** Low, info and inconclusive. */
    val neutral: Color,
)

object Palettes {
    val Dark = Palette(
        dark = true,
        bg = Color(0xFF151017), surface = Color(0xFF231B26), surfaceRaised = Color(0xFF2D252F),
        separator = Color(0xFF3A313D), fill = Color(0xFF2F2633),
        textPrimary = Color(0xFFF3EEF2), textSecondary = Color(0xFFB7ABB4), textTertiary = Color(0xFFA08E9B), graphic = Color(0xFF8B687F),
        accent = Color(0xFFBCF8EC), onAccent = Color(0xFF0F2420),
        risk = Color(0xFFF08AA8), caution = Color(0xFF9FA0C3), ok = Color(0xFF7FD9C0), neutral = Color(0xFFB7ABB4),
    )

    val Light = Palette(
        dark = false,
        bg = Color(0xFFE9F5F4), surface = Color(0xFFFFFFFF), surfaceRaised = Color(0xFFFFFFFF),
        separator = Color(0xFFD1E3E6), fill = Color(0xFFD5EAED),
        textPrimary = Color(0xFF2A1E26), textSecondary = Color(0xFF5E4F5A), textTertiary = Color(0xFF735668), graphic = Color(0xFF8B687F),
        accent = Color(0xFF7B435B), onAccent = Color(0xFFFFFFFF),
        risk = Color(0xFFA62F4E), caution = Color(0xFF565991), ok = Color(0xFF1B6B60), neutral = Color(0xFF5E4F5A),
    )
}

/**
 * The palette in use. [XrayTheme] sets it from the system light/dark setting. It is snapshot state, so anything that
 * reads a colour while composing or drawing refreshes when the theme changes, and plain (non-Compose) reads such as unit
 * tests get the dark palette.
 */
object ActivePalette {
    var current: Palette by mutableStateOf(Palettes.Dark)
}

/** Spacing on a 4 dp grid. */
object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    /** Screen gutter. */
    val gutter = 20.dp
    /** Gap between sections. */
    val section = 32.dp
}
