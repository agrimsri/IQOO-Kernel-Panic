package com.dpdpxray.app.ui.guide

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.ui.components.IconBadge
import com.dpdpxray.app.ui.components.cardSurface
import com.dpdpxray.app.ui.components.gradientSweep
import com.dpdpxray.app.ui.components.pressClickable
import com.dpdpxray.app.ui.theme.Brand
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.FilmExtra

/** Line glyphs drawn on a 24-unit grid, so the app needs no icon files and every icon shares one stroke weight. */
enum class Glyph {
    APK, ASK, SAMPLE, CAMERA, VPN, WATCHER, RESET, PRIVATE_DNS, AI, SPEAKER_ON, SPEAKER_OFF, CHECK, LOCK, PLAY, BOOK,
    HOME, SEARCH, REPORTS, MORE, CHEVRON_RIGHT, CHEVRON_DOWN, INFO, WARN, SETTINGS, BELL, CLOCK,
}

@Composable
fun GlyphIcon(glyph: Glyph, tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Canvas(modifier.size(size)) { drawGlyph(glyph, tint) }
}

fun DrawScope.drawGlyph(g: Glyph, tint: Color) {
    val u = size.minDimension / 24f
    val stroke = Stroke(width = 1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun o(x: Float, y: Float) = Offset(x * u, y * u)
    fun shape(block: Path.() -> Unit) = drawPath(Path().apply(block), tint, style = stroke)
    fun Path.m(x: Float, y: Float) = moveTo(x * u, y * u)
    fun Path.l(x: Float, y: Float) = lineTo(x * u, y * u)
    fun Path.c(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) = cubicTo(x1 * u, y1 * u, x2 * u, y2 * u, x3 * u, y3 * u)
    fun box(l: Float, t: Float, r: Float, b: Float, radius: Float) =
        drawRoundRect(tint, o(l, t), Size((r - l) * u, (b - t) * u), CornerRadius(radius * u), style = stroke)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(tint, o(x1, y1), o(x2, y2), 1.8f * u, StrokeCap.Round)
    fun dot(x: Float, y: Float, r: Float = 1.2f) = drawCircle(tint, r * u, o(x, y))
    fun ring(x: Float, y: Float, r: Float) = drawCircle(tint, r * u, o(x, y), style = stroke)
    fun arc(cx: Float, cy: Float, r: Float, start: Float, sweep: Float) =
        drawArc(tint, start, sweep, false, o(cx - r, cy - r), Size(2 * r * u, 2 * r * u), style = stroke)
    fun speaker() = shape { m(4f, 9.5f); l(7.5f, 9.5f); l(12f, 5.5f); l(12f, 18.5f); l(7.5f, 14.5f); l(4f, 14.5f); close() }

    when (g) {
        Glyph.APK -> {
            shape { m(4f, 8f); l(12f, 4f); l(20f, 8f); l(20f, 16.5f); l(12f, 20.5f); l(4f, 16.5f); close() }
            shape { m(4f, 8f); l(12f, 12f); l(20f, 8f); m(12f, 12f); l(12f, 20.5f) }
        }
        Glyph.ASK -> {
            box(3f, 4f, 21f, 16f, 4f)
            shape { m(8f, 16f); l(7f, 20f); l(12f, 16f) }
            dot(8.5f, 10f); dot(12f, 10f); dot(15.5f, 10f)
        }
        Glyph.SAMPLE -> {
            shape { m(6f, 3f); l(14f, 3f); l(18f, 7f); l(18f, 21f); l(6f, 21f); close() }
            shape { m(14f, 3f); l(14f, 7f); l(18f, 7f) }
            line(9f, 12f, 15f, 12f); line(9f, 16f, 13f, 16f)
        }
        Glyph.CAMERA -> {
            box(3f, 7f, 21f, 19f, 2.5f)
            shape { m(8f, 7f); l(9.5f, 4.5f); l(14.5f, 4.5f); l(16f, 7f) }
            ring(12f, 13f, 3.5f)
        }
        Glyph.VPN -> {
            shape { m(12f, 3f); l(19f, 6f); l(19f, 11f); c(19f, 15.5f, 16f, 19f, 12f, 21f); c(8f, 19f, 5f, 15.5f, 5f, 11f); l(5f, 6f); close() }
            dot(12f, 11f, 1.6f)
        }
        Glyph.WATCHER -> {
            shape {
                m(2.5f, 12f); c(5f, 7.5f, 8.5f, 5.5f, 12f, 5.5f); c(15.5f, 5.5f, 19f, 7.5f, 21.5f, 12f)
                c(19f, 16.5f, 15.5f, 18.5f, 12f, 18.5f); c(8.5f, 18.5f, 5f, 16.5f, 2.5f, 12f); close()
            }
            ring(12f, 12f, 3f)
        }
        Glyph.RESET -> {
            arc(12f, 12f, 7f, -60f, 290f)
            shape { m(16.4f, 2.6f); l(15.5f, 5.9f); l(18.9f, 6.9f) }
        }
        Glyph.PRIVATE_DNS -> {
            ring(12f, 12f, 8.5f)
            drawOval(tint, o(8.5f, 3.5f), Size(7f * u, 17f * u), style = stroke)
            line(3.5f, 12f, 20.5f, 12f)
        }
        Glyph.AI -> {
            box(7f, 7f, 17f, 17f, 2f)
            listOf(10f, 14f).forEach { p -> line(p, 3.5f, p, 7f); line(p, 17f, p, 20.5f); line(3.5f, p, 7f, p); line(17f, p, 20.5f, p) }
            dot(12f, 12f, 1.5f)
        }
        Glyph.SPEAKER_ON -> {
            speaker()
            arc(13f, 12f, 3.5f, -50f, 100f)
            arc(13f, 12f, 7f, -50f, 100f)
        }
        Glyph.SPEAKER_OFF -> {
            speaker()
            line(15.5f, 9.5f, 20.5f, 14.5f); line(20.5f, 9.5f, 15.5f, 14.5f)
        }
        Glyph.CHECK -> shape { m(5f, 12.5f); l(10f, 17f); l(19f, 7f) }
        Glyph.LOCK -> {
            box(5f, 11f, 19f, 21f, 2.5f)
            shape { m(8f, 11f); l(8f, 8f); c(8f, 3.5f, 16f, 3.5f, 16f, 8f); l(16f, 11f) }
        }
        Glyph.PLAY -> shape { m(8f, 5.5f); l(18.5f, 12f); l(8f, 18.5f); close() }
        Glyph.HOME -> {
            shape { m(3.5f, 11.5f); l(12f, 4f); l(20.5f, 11.5f) }
            shape { m(6f, 9.8f); l(6f, 20f); l(18f, 20f); l(18f, 9.8f) }
            shape { m(10f, 20f); l(10f, 14.5f); l(14f, 14.5f); l(14f, 20f) }
        }
        Glyph.SEARCH -> {
            ring(10.5f, 10.5f, 6.5f)
            line(15.3f, 15.3f, 20.5f, 20.5f)
        }
        Glyph.REPORTS -> {
            box(5f, 4.5f, 19f, 21f, 2.5f)
            box(9f, 3f, 15f, 6.5f, 1.2f)
            line(8.5f, 11f, 15.5f, 11f); line(8.5f, 14.5f, 15.5f, 14.5f); line(8.5f, 18f, 12.5f, 18f)
        }
        Glyph.MORE -> {
            box(4f, 4f, 10.5f, 10.5f, 2f); box(13.5f, 4f, 20f, 10.5f, 2f)
            box(4f, 13.5f, 10.5f, 20f, 2f); box(13.5f, 13.5f, 20f, 20f, 2f)
        }
        Glyph.CHEVRON_RIGHT -> shape { m(9f, 5.5f); l(15.5f, 12f); l(9f, 18.5f) }
        Glyph.CHEVRON_DOWN -> shape { m(5.5f, 9f); l(12f, 15.5f); l(18.5f, 9f) }
        Glyph.INFO -> {
            ring(12f, 12f, 9f)
            line(12f, 11f, 12f, 16.5f)
            dot(12f, 7.6f, 1.3f)
        }
        Glyph.WARN -> {
            shape { m(12f, 3.5f); l(21f, 19.5f); l(3f, 19.5f); close() }
            line(12f, 9.5f, 12f, 14f)
            dot(12f, 16.6f, 1.2f)
        }
        Glyph.SETTINGS -> {
            ring(12f, 12f, 3.2f)
            ring(12f, 12f, 6.8f)
            (0 until 8).forEach { i ->
                val a = Math.toRadians(i * 45.0)
                val cx = kotlin.math.cos(a).toFloat()
                val cy = kotlin.math.sin(a).toFloat()
                line(12f + 6.8f * cx, 12f + 6.8f * cy, 12f + 9.3f * cx, 12f + 9.3f * cy)
            }
        }
        Glyph.BELL -> {
            shape { m(6f, 16.5f); l(6f, 11f); c(6f, 7.5f, 8.5f, 5f, 12f, 5f); c(15.5f, 5f, 18f, 7.5f, 18f, 11f); l(18f, 16.5f); l(19.5f, 18f); l(4.5f, 18f); close() }
            shape { m(10f, 20.5f); c(10.8f, 21.6f, 13.2f, 21.6f, 14f, 20.5f) }
        }
        Glyph.CLOCK -> {
            ring(12f, 12f, 8.5f)
            shape { m(12f, 7.5f); l(12f, 12f); l(15f, 14f) }
        }
        Glyph.BOOK -> {
            shape {
                m(12f, 6.5f); c(10f, 5f, 6.5f, 4.5f, 3.5f, 5f); l(3.5f, 18.5f); c(6.5f, 18f, 10f, 18.5f, 12f, 20f)
                c(14f, 18.5f, 17.5f, 18f, 20.5f, 18.5f); l(20.5f, 5f); c(17.5f, 4.5f, 14f, 5f, 12f, 6.5f); close()
            }
            line(12f, 6.5f, 12f, 20f)
        }
    }
}

/**
 * The one action that matters most on a screen. Brand gradient, dark text, 58dp tall, a soft brand glow beneath it,
 * and (when [sweep] is on) a very slow, faint light band drifting across. Shrinks slightly while pressed.
 */
@Composable
fun GradientAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, glyph: Glyph? = null, sweep: Boolean = false) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier.fillMaxWidth().heightIn(min = 58.dp)
            .then(if (enabled) Modifier.shadow(10.dp, shape, ambientColor = Brand.coral.copy(alpha = 0.4f), spotColor = Brand.coral.copy(alpha = 0.6f)) else Modifier)
            .pressClickable(enabled = enabled, shape = shape, onClick = onClick)
            .background(if (enabled) Brand.horizontal else SolidColor(Film.line), shape)
            .then(if (enabled && sweep) Modifier.gradientSweep(alpha = 0.22f) else Modifier)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (glyph != null) {
            GlyphIcon(glyph, if (enabled) Film.base else Film.boneDim, size = 20.dp)
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = if (enabled) Film.base else Film.boneDim)
    }
}

/** A secondary action under a [GradientAction]: same shape and height, outlined, light text, no brand colour. */
@Composable
fun OutlineAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, glyph: Glyph? = null) {
    val shape = RoundedCornerShape(16.dp)
    val tint = if (enabled) Film.bone else Film.boneDim
    Row(
        modifier.fillMaxWidth().heightIn(min = 52.dp)
            .pressClickable(enabled = enabled, shape = shape, onClick = onClick)
            .background(Film.bone.copy(alpha = 0.04f))
            .border(1.dp, Film.boneDim.copy(alpha = 0.55f), shape)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (glyph != null) {
            GlyphIcon(glyph, tint, size = 18.dp)
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium, color = tint, textAlign = TextAlign.Center)
    }
}

/** A secondary destination with a glyph, a name and one line saying what it is for. */
@Composable
fun ActionTile(title: String, detail: String, glyph: Glyph, onClick: () -> Unit, modifier: Modifier = Modifier, accent: Color = Film.scan) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.heightIn(min = 104.dp)
            .pressClickable(shape = shape, onClick = onClick)
            .cardSurface(16.dp)
            .padding(14.dp),
    ) {
        IconBadge(glyph, accent, size = 36.dp)
        Spacer(Modifier.size(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = Film.bone)
        Text(detail, style = MaterialTheme.typography.labelMedium, color = Film.boneDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Text link with a 48dp touch target. */
@Composable
fun LinkAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Film.scan, glyph: Glyph? = null) {
    TextButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp)) {
        if (glyph != null) {
            GlyphIcon(glyph, color, size = 18.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

/** Score in a tinted square; "?" when the result is inconclusive. Colour follows the score band. */
@Composable
fun ScoreChip(text: String, color: Color, modifier: Modifier = Modifier, size: Dp = 56.dp) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier.size(size).clip(shape)
            .background(Brush.linearGradient(listOf(color.copy(alpha = 0.26f), color.copy(alpha = 0.07f))))
            .border(1.5.dp, color.copy(alpha = 0.7f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge, color = color, maxLines = 1)
    }
}

/** A raised panel that groups one idea. */
@Composable
fun Panel(modifier: Modifier = Modifier, glow: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().cardSurface(glow = glow).padding(16.dp), content = content)
}

/** Empty states point to the next action: a film frame with a dashed consent line, a title, one line, a button. */
@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: Pair<String, () -> Unit>? = null) {
    Column(modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(width = 132.dp, height = 76.dp)) {
            val r = 10.dp.toPx()
            drawRoundRect(Brush.verticalGradient(listOf(Film.scan.copy(alpha = 0.10f), Color.Transparent)), cornerRadius = CornerRadius(r))
            drawRoundRect(Film.line, cornerRadius = CornerRadius(r), style = Stroke(1.5.dp.toPx()))
            val step = size.width / 7f
            (1..6).forEach { i -> drawCircle(Film.line, 2.dp.toPx(), Offset(step * i, 9.dp.toPx())); drawCircle(Film.line, 2.dp.toPx(), Offset(step * i, size.height - 9.dp.toPx())) }
            drawLine(
                Film.scan.copy(alpha = 0.8f), Offset(16.dp.toPx(), size.height / 2), Offset(size.width - 16.dp.toPx(), size.height / 2),
                2.dp.toPx(), StrokeCap.Round, PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())),
            )
        }
        Text(title, style = MaterialTheme.typography.titleMedium, color = Film.bone, modifier = Modifier.padding(top = 12.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Film.boneDim, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, start = 12.dp, end = 12.dp))
        if (action != null) LinkAction(action.first, action.second, Modifier.padding(top = 4.dp))
    }
}
