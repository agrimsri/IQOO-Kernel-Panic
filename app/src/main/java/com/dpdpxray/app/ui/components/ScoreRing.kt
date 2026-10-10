package com.dpdpxray.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.LocalReduceMotion
import com.dpdpxray.app.ui.theme.LocalScale
import com.dpdpxray.app.ui.theme.Motion
import com.dpdpxray.app.ui.theme.bandStops
import com.dpdpxray.core.model.ScoreBand
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The score as a ring: a band-coloured gradient arc fills from 0 to the score while the number counts up, over a soft
 * glow of the same colour. An inconclusive result shows "?" inside a dashed ring that breathes gently; it never shows
 * zero. With reduced motion the ring and number appear at their final values. TalkBack hears one sentence.
 */
@Composable
fun ScoreRing(score: Int?, band: ScoreBand, modifier: Modifier = Modifier, ringSize: Dp = 164.dp, key: Any? = null) {
    val inconclusive = band == ScoreBand.INCONCLUSIVE || score == null
    val reduce = LocalReduceMotion.current
    val target = if (inconclusive) 0f else (score ?: 0).coerceIn(0, 100) / 100f
    val progress = remember(key) { Animatable(if (reduce) target else 0f) }
    LaunchedEffect(key, target, reduce) {
        if (reduce) progress.snapTo(target) else progress.animateTo(target, tween(1100, delayMillis = 120, easing = Motion.enter))
    }
    val shown by remember(progress) { derivedStateOf { (progress.value * 100f).roundToInt() } }
    val breath: State<Float> = if (inconclusive && !reduce) {
        rememberInfiniteTransition(label = "ring-breath").animateFloat(
            0.45f, 1f, infiniteRepeatable(tween(1400, easing = Motion.emphasized), RepeatMode.Reverse), label = "ring-alpha",
        )
    } else remember { mutableStateOf(1f) }
    val stops = bandStops(band)
    val label = if (inconclusive) "Consent readiness: no score, the result is inconclusive" else "Consent readiness $score out of 100, ${band.label}"

    Box(
        modifier.size(ringSize).clearAndSetSemantics { contentDescription = label }.drawWithCache {
            val stroke = 12.dp.toPx()
            val inset = stroke / 2f + 6.dp.toPx()
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            val radius = arcSize.width / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            val glow = Brush.radialGradient(listOf(stops.last().copy(alpha = 0.22f), Color.Transparent), center = c, radius = size.minDimension / 2f)
            val sweep = Brush.sweepGradient(listOf(stops.first(), stops.last()), center = c)
            val track = Stroke(stroke)
            val dashed = Stroke(stroke * 0.55f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 9.dp.toPx())))
            val butt = Stroke(stroke, cap = StrokeCap.Butt)
            onDrawBehind {
                if (inconclusive) {
                    drawCircle(Film.boneDim.copy(alpha = 0.10f * breath.value), radius = radius + stroke)
                    drawCircle(Film.boneDim.copy(alpha = breath.value), radius = radius, center = c, style = dashed)
                    return@onDrawBehind
                }
                drawCircle(glow, radius = size.minDimension / 2f)
                drawCircle(Film.line.copy(alpha = 0.55f), radius = radius, center = c, style = track)
                val p = progress.value
                if (p > 0f) {
                    // The sweep gradient starts at 3 o'clock; turning the canvas puts its start at 12 o'clock.
                    rotate(-90f, c) { drawArc(sweep, 0f, 360f * p, false, topLeft, arcSize, style = butt) }
                    // Round ends drawn as dots, so the gradient has no seam at the start.
                    drawCircle(stops.first(), stroke / 2f, Offset(c.x, c.y - radius))
                    val a = Math.toRadians((360.0 * p) - 90.0)
                    drawCircle(lerp(stops.first(), stops.last(), p), stroke / 2f, Offset(c.x + radius * cos(a).toFloat(), c.y + radius * sin(a).toFloat()))
                }
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        val scale = LocalScale.current
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (inconclusive) "?" else "$shown",
                style = MaterialTheme.typography.displayLarge.copy(fontSize = scale.sp(54), lineHeight = scale.sp(56)),
                color = if (inconclusive) Film.boneDim else stops.last(),
            )
            Text(if (inconclusive) "no score" else "of 100", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, color = Film.boneDim)
        }
    }
}
