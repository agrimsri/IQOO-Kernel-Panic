package com.dpdpxray.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The design tokens (UI_UX_REFACTOR_GUIDE.md section 6.1): neutral surfaces, one accent, and three status colours.
 * Status colour appears as a dot or one word, never as a card fill. Two palettes follow the system light/dark setting.
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
        bg = Color(0xFF0E0E10), surface = Color(0xFF1A1A1D), surfaceRaised = Color(0xFF232327),
        separator = Color(0xFF2C2C30), fill = Color(0xFF2A2A2E),
        textPrimary = Color(0xFFF5F5F7), textSecondary = Color(0xFFA1A1A6), textTertiary = Color(0xFF6E6E73),
        accent = Color(0xFFFF9E80), onAccent = Color(0xFF1A0F0B),
        risk = Color(0xFFFF6B62), caution = Color(0xFFF2B35A), ok = Color(0xFF6FD39A), neutral = Color(0xFFA1A1A6),
    )

    val Light = Palette(
        dark = false,
        bg = Color(0xFFF5F5F7), surface = Color(0xFFFFFFFF), surfaceRaised = Color(0xFFFFFFFF),
        separator = Color(0xFFE5E5EA), fill = Color(0xFFEDEDF0),
        textPrimary = Color(0xFF1D1D1F), textSecondary = Color(0xFF636366), textTertiary = Color(0xFF8A8A8E),
        accent = Color(0xFFB2472D), onAccent = Color(0xFFFFFFFF),
        risk = Color(0xFFBD2F26), caution = Color(0xFF8F5806), ok = Color(0xFF25704A), neutral = Color(0xFF636366),
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
