package com.dpdpxray.app.ui.onboarding

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.ui.guide.GuideLang
import com.dpdpxray.app.ui.theme.Brand
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.FilmExtra

/**
 * Page 1: a phone with a consent popup, an X-ray scan band passing over it, and lookups leaving it towards hosts.
 * The one continuous motion in onboarding; everything else is still or reacts to the person.
 */
@Composable
fun PromiseArt(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "promise")
    val scan by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2800, easing = LinearEasing), RepeatMode.Restart), label = "scan")
    val ping by t.animateFloat(0f, 3f, infiniteRepeatable(tween(3000, easing = LinearEasing), RepeatMode.Restart), label = "ping")
    Canvas(modifier.fillMaxWidth().height(230.dp).semantics { contentDescription = "A phone showing a consent popup while TraceVeil watches the addresses it contacts" }) {
        val pw = 118.dp.toPx()
        val ph = 214.dp.toPx()
        val left = (size.width - pw) / 2
        val top = (size.height - ph) / 2
        val r = 22.dp.toPx()

        // Lookups: thin lines from the phone's edges to hosts; one host is a tracker (warm), the others neutral.
        val hosts = listOf(
            Offset(left - 64.dp.toPx(), top + ph * 0.22f) to Film.boneDim,
            Offset(left + pw + 70.dp.toPx(), top + ph * 0.30f) to Film.high,
            Offset(left + pw + 58.dp.toPx(), top + ph * 0.72f) to Film.boneDim,
            Offset(left - 74.dp.toPx(), top + ph * 0.66f) to Film.leak,
        )
        hosts.forEachIndexed { i, (p, c) ->
            val from = Offset(if (p.x < left) left else left + pw, p.y + (top + ph / 2 - p.y) * 0.35f)
            drawLine(c.copy(alpha = 0.45f), from, p, 1.5.dp.toPx(), StrokeCap.Round)
            val phase = ((ping - i * 0.75f) % 3f + 3f) % 3f
            val glow = if (phase < 1f) 1f - phase else 0f
            drawCircle(c.copy(alpha = 0.18f * glow), (7 + 10 * (1 - glow)).dp.toPx(), p)
            drawCircle(c, 5.dp.toPx(), p)
        }

        // The phone body.
        drawRoundRect(Film.raised, Offset(left, top), Size(pw, ph), CornerRadius(r))
        drawRoundRect(Film.line, Offset(left, top), Size(pw, ph), CornerRadius(r), style = Stroke(2.dp.toPx()))
        drawLine(Film.line, Offset(left + pw * 0.38f, top + 10.dp.toPx()), Offset(left + pw * 0.62f, top + 10.dp.toPx()), 3.dp.toPx(), StrokeCap.Round)

        // The consent popup: two text lines and two buttons (Reject outlined, Accept filled).
        val cl = left + 12.dp.toPx()
        val cw = pw - 24.dp.toPx()
        val ct = top + ph * 0.36f
        val chh = 92.dp.toPx()
        drawRoundRect(FilmExtra.raisedHigh, Offset(cl, ct), Size(cw, chh), CornerRadius(12.dp.toPx()))
        drawLine(Film.boneDim, Offset(cl + 12.dp.toPx(), ct + 18.dp.toPx()), Offset(cl + cw * 0.75f, ct + 18.dp.toPx()), 4.dp.toPx(), StrokeCap.Round)
        drawLine(Film.boneDim.copy(alpha = 0.6f), Offset(cl + 12.dp.toPx(), ct + 32.dp.toPx()), Offset(cl + cw * 0.55f, ct + 32.dp.toPx()), 4.dp.toPx(), StrokeCap.Round)
        val by = ct + chh - 32.dp.toPx()
        val bw = (cw - 30.dp.toPx()) / 2
        drawRoundRect(Film.warn, Offset(cl + 10.dp.toPx(), by), Size(bw, 20.dp.toPx()), CornerRadius(10.dp.toPx()), style = Stroke(1.5.dp.toPx()))
        drawRoundRect(Film.scan, Offset(cl + 20.dp.toPx() + bw, by), Size(bw, 20.dp.toPx()), CornerRadius(10.dp.toPx()))

        // The X-ray: a soft scan band sweeping down inside the phone only.
        val phone = Path().apply { addRoundRect(RoundRect(left, top, left + pw, top + ph, CornerRadius(r))) }
        clipPath(phone) {
            val band = 70.dp.toPx()
            val y = top - band + (ph + band) * scan
            drawRect(
                Brush.verticalGradient(listOf(Color.Transparent, Film.scan.copy(alpha = 0.28f), Color.Transparent), startY = y, endY = y + band),
                Offset(left, y), Size(pw, band),
            )
            drawLine(Film.scan.copy(alpha = 0.7f), Offset(left, y + band / 2), Offset(left + pw, y + band / 2), 1.dp.toPx())
        }
    }
}

/** Page 2: three runs as lanes with a moving playhead. Dots after a refusal are drawn in the leak colour. */
@Composable
fun HowItWorksArt(lang: GuideLang, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "how")
    val p by t.animateFloat(0f, 1.15f, infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart), label = "playhead")
    data class Lane(val choiceAt: Float?, val choiceColor: Color, val dots: List<Float>, val trackerDots: Set<Float>)
    val lanes = listOf(
        Lane(null, Film.boneDim, listOf(0.12f, 0.3f, 0.55f, 0.82f), setOf(0.3f)),
        Lane(0.4f, Film.warn, listOf(0.1f, 0.24f, 0.62f, 0.8f), setOf(0.62f, 0.8f)),
        Lane(0.4f, Film.scan, listOf(0.14f, 0.5f, 0.66f, 0.86f), setOf(0.66f)),
    )
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        lanes.forEachIndexed { i, lane ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", style = MaterialTheme.typography.titleLarge, color = Brand.coral, modifier = Modifier.width(22.dp))
                Text(OnboardingCopy.lanes[i].pick(lang), style = MaterialTheme.typography.titleMedium, color = Film.bone, modifier = Modifier.width(96.dp))
                Canvas(Modifier.weight(1f).height(34.dp)) {
                    val mid = size.height / 2
                    drawLine(Film.line, Offset(0f, mid), Offset(size.width, mid), 2.dp.toPx(), StrokeCap.Round)
                    lane.choiceAt?.let { at ->
                        val x = size.width * at
                        drawLine(lane.choiceColor, Offset(x, 2.dp.toPx()), Offset(x, size.height - 2.dp.toPx()), 3.dp.toPx(), StrokeCap.Round)
                    }
                    lane.dots.forEach { d ->
                        val shown = p >= d
                        val afterRefusal = lane.choiceAt != null && lane.choiceColor == Film.warn && d > lane.choiceAt
                        val c = when {
                            afterRefusal && d in lane.trackerDots -> Film.leak
                            d in lane.trackerDots -> Film.high
                            else -> Film.boneDim
                        }
                        drawCircle(if (shown) c else c.copy(alpha = 0.15f), 5.dp.toPx(), Offset(size.width * d, mid))
                    }
                    val x = size.width * p.coerceAtMost(1f)
                    drawLine(Brand.coral.copy(alpha = if (p > 1f) 0f else 0.9f), Offset(x, 0f), Offset(x, size.height), 1.5.dp.toPx(), StrokeCap.Round)
                }
            }
        }
        Text(OnboardingCopy.exampleCaption.pick(lang), style = MaterialTheme.typography.labelMedium, color = Film.boneDim)
    }
}
