package com.dpdpxray.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dpdpxray.app.R
import com.dpdpxray.core.model.Category
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.model.Severity

/*
 * Colour names kept from the earlier "film" look so every screen keeps compiling, now backed by the neutral tokens in
 * [Palette]. They are read through [ActivePalette], so they follow light/dark. Prefer the token names in new code:
 * base = bg, raised = surface, line = separator, bone = textPrimary, boneDim = textSecondary, scan = accent,
 * leak/high = risk, warn = caution, clear = ok.
 */
object Film {
    val base: Color get() = ActivePalette.current.bg
    val raised: Color get() = ActivePalette.current.surface
    val line: Color get() = ActivePalette.current.separator
    val bone: Color get() = ActivePalette.current.textPrimary
    val boneDim: Color get() = ActivePalette.current.textSecondary
    val scan: Color get() = ActivePalette.current.accent
    val leak: Color get() = ActivePalette.current.risk
    val high: Color get() = ActivePalette.current.caution
    val warn: Color get() = ActivePalette.current.caution
    val clear: Color get() = ActivePalette.current.ok

    /** A quiet fill for tracks, skeleton bars, code blocks and chips. */
    val fill: Color get() = ActivePalette.current.fill
    /** Captions only. */
    val tertiary: Color get() = ActivePalette.current.textTertiary
    /** Icons and switch thumbs. */
    val graphic: Color get() = ActivePalette.current.graphic
    /** Text and icons on a filled accent. */
    val onAccent: Color get() = ActivePalette.current.onAccent
    /** Low, info, inconclusive. */
    val neutral: Color get() = ActivePalette.current.neutral
}

/**
 * The accent. Earlier versions used a peach-to-rose gradient in many places; there is now one solid accent, used for the
 * main action, links and selection. The gradient-shaped members remain so old call sites compile; they are all solid.
 */
object Brand {
    val peach: Color get() = ActivePalette.current.accent
    val coral: Color get() = ActivePalette.current.accent
    val rose: Color get() = ActivePalette.current.accent
    val stops: List<Color> get() = listOf(peach, coral, rose)
    val horizontal: Brush get() = SolidColor(ActivePalette.current.accent)
    val vertical: Brush get() = SolidColor(ActivePalette.current.accent)
    val underline: Brush get() = SolidColor(ActivePalette.current.accent)
    val heroWash: Brush get() = SolidColor(Color.Transparent)
}

/** Surfaces that older code reached for by name. All are flat now: no gradients, no rim light. */
object FilmExtra {
    val raisedHigh: Color get() = ActivePalette.current.fill
    val scanWash: Color get() = ActivePalette.current.accent.copy(alpha = 0.10f)
    val hairline: Color get() = ActivePalette.current.separator
    val bar: Color get() = ActivePalette.current.surface
    val warnWash: Color get() = ActivePalette.current.caution.copy(alpha = 0.10f)
    val backdrop: Brush get() = SolidColor(ActivePalette.current.bg)
    val card: Brush get() = SolidColor(ActivePalette.current.surface)
    val cardEdge: Brush get() = SolidColor(Color.Transparent)
}

/** Score band as a two-stop list for the score ring; both stops are the band colour (no gradient). */
fun bandStops(b: ScoreBand): List<Color> = bandColor(b).let { listOf(it, it) }

/** Corner radii used across the app, so cards, buttons and chips share one rhythm. */
object Radii {
    val card = 20.dp
    val tile = 16.dp
    val button = 14.dp
    val row = 14.dp
    val small = 12.dp
}

/** Critical and high share the risk colour, the word tells them apart; medium is caution; low and info are neutral. */
fun severityColor(s: Severity) = when (s) {
    Severity.CRITICAL -> Film.leak
    Severity.HIGH -> Film.leak
    Severity.MEDIUM -> Film.warn
    Severity.LOW -> Film.neutral
    Severity.INFO -> Film.neutral
}

fun bandColor(b: ScoreBand) = when (b) {
    ScoreBand.READY -> Film.clear
    ScoreBand.NEEDS_WORK -> Film.warn
    ScoreBand.AT_RISK -> Film.leak
    ScoreBand.INCONCLUSIVE -> Film.neutral
}

fun categoryColor(c: Category) = when (c) {
    Category.ADVERTISING, Category.ATTRIBUTION -> Film.leak
    Category.ANALYTICS, Category.SOCIAL, Category.ENGAGEMENT, Category.CRASH_REPORTING -> Film.warn
    Category.FIRST_PARTY, Category.FIRST_PARTY_SAME_COMPANY -> Film.clear
    Category.UNKNOWN, Category.CONTENT_DELIVERY, Category.COMPANY_SERVICE -> Film.neutral
}

@OptIn(ExperimentalTextApi::class)
private fun archivo(weight: Int, width: Float) = Font(
    R.font.archivo,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.width(width)),
)

@OptIn(ExperimentalTextApi::class)
private fun inter(weight: Int) = Font(
    R.font.inter,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Archivo is kept only for the logo wordmark and a few legacy headings; everything else uses [Sans]. */
val ArchivoText = FontFamily(archivo(400, 100f), archivo(500, 100f), archivo(600, 100f))
val ArchivoDisplay = FontFamily(archivo(700, 82f), archivo(800, 75f))

/** Inter (SIL OFL): the app's one typeface. */
val Sans = FontFamily(inter(400), inter(500), inter(600), inter(700))

class Scale(val k: Float) {
    fun sp(v: Int): TextUnit = (v * k).sp
}

val LocalScale = staticCompositionLocalOf { Scale(1f) }

/**
 * Type scale (guide section 6.2): large title 34, title 22, headline 17, body 16/15, footnote 13, caption 12. Labels
 * never go below 12 sp. Every M3 slot is set so dialogs, sheets and the navigation bar use the same face.
 */
private fun typography(k: Float) = Typography(
    displayLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight(600), fontSize = (64 * k).sp, lineHeight = (64 * k).sp, letterSpacing = (-1).sp),
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight(700), fontSize = (34 * k).sp, lineHeight = (41 * k).sp, letterSpacing = (-0.4).sp),
    headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight(600), fontSize = (26 * k).sp, lineHeight = (32 * k).sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight(600), fontSize = (22 * k).sp, lineHeight = (28 * k).sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight(600), fontSize = (17 * k).sp, lineHeight = (22 * k).sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight(600), fontSize = (15 * k).sp, lineHeight = (20 * k).sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight(400), fontSize = (16 * k).sp, lineHeight = (24 * k).sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight(400), fontSize = (15 * k).sp, lineHeight = (22 * k).sp),
    bodySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight(400), fontSize = (13 * k).sp, lineHeight = (18 * k).sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight(600), fontSize = (15 * k).sp, lineHeight = (20 * k).sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight(500), fontSize = (13 * k).sp, lineHeight = (18 * k).sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight(500), fontSize = (12 * k).sp, lineHeight = (16 * k).sp),
)

@Composable
fun XrayTheme(stageMode: Boolean, themeMode: String = "system", content: @Composable () -> Unit) {
    val k = if (stageMode) 1.18f else 1f
    val reduceMotion = rememberSystemReduceMotion()
    val systemDark = isSystemInDarkTheme()
    val palette = when (themeMode) { "dark" -> Palettes.Dark; "light" -> Palettes.Light; else -> if (systemDark) Palettes.Dark else Palettes.Light }
    // Keep the status and navigation bar icons readable on the chosen theme (the system only knows its own setting).
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect {
        val window = (view.context as? android.app.Activity)?.window
        if (window != null) {
            val controls = androidx.core.view.WindowCompat.getInsetsController(window, view)
            controls.isAppearanceLightStatusBars = !palette.dark
            controls.isAppearanceLightNavigationBars = !palette.dark
        }
    }
    // Set before any child reads a colour, so the first frame already uses the right palette.
    remember(palette) { ActivePalette.current = palette }
    // Text without an explicit colour must read as the primary text colour (Compose defaults it to black).
    androidx.compose.runtime.CompositionLocalProvider(
        LocalScale provides Scale(k),
        androidx.compose.material3.LocalContentColor provides palette.textPrimary,
        LocalReduceMotion provides reduceMotion,
    ) {
        val scheme = if (palette.dark) {
            darkColorScheme(
                primary = palette.accent, onPrimary = palette.onAccent, background = palette.bg, onBackground = palette.textPrimary,
                surface = palette.bg, onSurface = palette.textPrimary, surfaceVariant = palette.surface, onSurfaceVariant = palette.textSecondary,
                surfaceContainer = palette.surface, surfaceContainerHigh = palette.surfaceRaised, surfaceContainerHighest = palette.fill,
                surfaceContainerLow = palette.surface, secondaryContainer = palette.accent.copy(alpha = 0.18f), onSecondaryContainer = palette.textPrimary,
                outline = palette.separator, outlineVariant = palette.separator, error = palette.risk, surfaceTint = Color.Transparent,
            )
        } else {
            lightColorScheme(
                primary = palette.accent, onPrimary = palette.onAccent, background = palette.bg, onBackground = palette.textPrimary,
                surface = palette.bg, onSurface = palette.textPrimary, surfaceVariant = palette.surface, onSurfaceVariant = palette.textSecondary,
                surfaceContainer = palette.surface, surfaceContainerHigh = palette.surfaceRaised, surfaceContainerHighest = palette.fill,
                surfaceContainerLow = palette.surface, secondaryContainer = palette.accent.copy(alpha = 0.14f), onSecondaryContainer = palette.textPrimary,
                outline = palette.separator, outlineVariant = palette.separator, error = palette.risk, surfaceTint = Color.Transparent,
            )
        }
        MaterialTheme(colorScheme = scheme, typography = typography(k), content = content)
    }
}

val Mono = FontFamily.Monospace
