package com.dpdpxray.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.dpdpxray.app.R
import com.dpdpxray.core.model.Category
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.model.Severity

/** Radiograph palette: blue-slate film, luminous bone text, colour only where it carries meaning. */
object Film {
    val base = Color(0xFF10202E)
    val raised = Color(0xFF172C3C)
    val line = Color(0xFF2A4558)
    val bone = Color(0xFFDCEBF2)
    val boneDim = Color(0xFF93AEBF)
    val scan = Color(0xFF7FD3E6)
    val leak = Color(0xFFFF5A4E)
    val high = Color(0xFFFF8A3D)
    val warn = Color(0xFFF2B544)
    val clear = Color(0xFF5FD49B)
}

/**
 * Brand accent: peach to coral to rose. Used sparingly, only where the person should act next (the main action,
 * the current guide step, the current onboarding page). Dark [Film.base] text on it keeps at least 4.5:1 contrast.
 */
object Brand {
    val peach = Color(0xFFFAA275)
    val coral = Color(0xFFFF8C61)
    val rose = Color(0xFFCE6A85)
    val stops = listOf(peach, coral, rose)
    val horizontal: Brush get() = Brush.horizontalGradient(stops)
    val vertical: Brush get() = Brush.verticalGradient(stops)
}

/** Extra film surfaces for layered panels: one step lighter than [Film.raised], plus a faint scan-coloured wash. */
object FilmExtra {
    val raisedHigh = Color(0xFF1D3649)
    val scanWash = Color(0x1A7FD3E6)
    val hairline = Color(0xFF223B4D)
}

fun severityColor(s: Severity) = when (s) {
    Severity.CRITICAL -> Film.leak
    Severity.HIGH -> Film.high
    Severity.MEDIUM -> Film.warn
    Severity.LOW -> Film.scan
    Severity.INFO -> Film.boneDim
}

fun bandColor(b: ScoreBand) = when (b) {
    ScoreBand.READY -> Film.clear
    ScoreBand.NEEDS_WORK -> Film.warn
    ScoreBand.AT_RISK -> Film.leak
    ScoreBand.INCONCLUSIVE -> Film.boneDim
}

fun categoryColor(c: Category) = when (c) {
    Category.ADVERTISING, Category.ATTRIBUTION -> Film.leak
    Category.ANALYTICS, Category.SOCIAL, Category.ENGAGEMENT -> Film.high
    Category.CRASH_REPORTING -> Film.warn
    Category.FIRST_PARTY, Category.FIRST_PARTY_SAME_COMPANY -> Film.clear
    Category.UNKNOWN, Category.CONTENT_DELIVERY -> Film.boneDim
}

@OptIn(ExperimentalTextApi::class)
private fun archivo(weight: Int, width: Float) = Font(
    R.font.archivo,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.width(width)),
)

/** Archivo carries the voice; narrow widths for headlines so app names and scores sit tight like film labels. */
val ArchivoText = FontFamily(archivo(400, 100f), archivo(500, 100f), archivo(600, 100f))
val ArchivoDisplay = FontFamily(archivo(700, 82f), archivo(800, 75f))

class Scale(val k: Float) {
    fun sp(v: Int): TextUnit = (v * k).sp
}

val LocalScale = staticCompositionLocalOf { Scale(1f) }

private fun typography(k: Float) = Typography(
    displayLarge = TextStyle(fontFamily = ArchivoDisplay, fontWeight = FontWeight(800), fontSize = (64 * k).sp, lineHeight = (64 * k).sp),
    headlineMedium = TextStyle(fontFamily = ArchivoDisplay, fontWeight = FontWeight(700), fontSize = (28 * k).sp, lineHeight = (32 * k).sp),
    titleLarge = TextStyle(fontFamily = ArchivoDisplay, fontWeight = FontWeight(700), fontSize = (21 * k).sp, lineHeight = (26 * k).sp),
    titleMedium = TextStyle(fontFamily = ArchivoText, fontWeight = FontWeight(600), fontSize = (16 * k).sp, lineHeight = (22 * k).sp),
    bodyLarge = TextStyle(fontFamily = ArchivoText, fontWeight = FontWeight(400), fontSize = (16 * k).sp, lineHeight = (24 * k).sp),
    bodyMedium = TextStyle(fontFamily = ArchivoText, fontWeight = FontWeight(400), fontSize = (14 * k).sp, lineHeight = (21 * k).sp),
    labelLarge = TextStyle(fontFamily = ArchivoText, fontWeight = FontWeight(600), fontSize = (15 * k).sp),
    labelMedium = TextStyle(fontFamily = ArchivoText, fontWeight = FontWeight(500), fontSize = (12.5f * k).sp),
)

@Composable
fun XrayTheme(stageMode: Boolean, content: @Composable () -> Unit) {
    val k = if (stageMode) 1.18f else 1f
    // Text without an explicit colour must read as "bone" on the film background (Compose defaults it to black).
    androidx.compose.runtime.CompositionLocalProvider(LocalScale provides Scale(k), androidx.compose.material3.LocalContentColor provides Film.bone) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Film.scan, onPrimary = Film.base, background = Film.base, onBackground = Film.bone,
                surface = Film.base, onSurface = Film.bone, surfaceVariant = Film.raised, onSurfaceVariant = Film.boneDim,
                outline = Film.line, error = Film.leak,
            ),
            typography = typography(k),
            content = content,
        )
    }
}

val Mono = FontFamily.Monospace
