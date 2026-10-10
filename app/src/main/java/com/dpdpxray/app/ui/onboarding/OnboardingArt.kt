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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
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
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.ui.components.KeyCaveat
import com.dpdpxray.app.ui.components.cardSurface
import com.dpdpxray.app.ui.guide.GuideLang
import com.dpdpxray.app.ui.theme.Brand
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.FilmExtra
import com.dpdpxray.app.ui.theme.LocalReduceMotion

/**
 * Page 1: a phone with a consent popup, an X-ray scan band passing over it, and lookups leaving it towards hosts.
 * The one continuous motion in onboarding; everything else is still or reacts to the person. With the system
 * "Remove animations" setting on, the band rests mid-phone and the hosts stay lit.
 *
 * Paths and brushes are built once per size (drawWithCache); each frame only moves them.
 */
@Composable
fun PromiseArt(modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    val scan: State<Float>
    val ping: State<Float>
    if (!reduce) {
        val t = rememberInfiniteTransition(label = "promise")
        scan = t.animateFloat(0f, 1f, infiniteRepeatable(tween(2800, easing = LinearEasing), RepeatMode.Restart), label = "scan")
        ping = t.animateFloat(0f, 3f, infiniteRepeatable(tween(3000, easing = LinearEasing), RepeatMode.Restart), label = "ping")
    } else {
        scan = remember { mutableStateOf(0.45f) }
        ping = remember { mutableStateOf(-1f) }
    }
    Spacer(
        modifier.fillMaxWidth().height(230.dp)
            .semantics { contentDescription = "A phone showing a consent popup while TraceVeil watches the addresses it contacts" }
            .drawWithCache {
                val pw = 118.dp.toPx()
                val ph = 214.dp.toPx()
                val left = (size.width - pw) / 2
                val top = (size.height - ph) / 2
                val r = 22.dp.toPx()

                // A soft warm glow behind the phone, so the art sits in light rather than on a flat background.
                val glowCenter = Offset(size.width / 2, size.height / 2)
                val glow = Brush.radialGradient(
                    listOf(Brand.coral.copy(alpha = 0.16f), Brand.rose.copy(alpha = 0.06f), Color.Transparent),
                    center = glowCenter, radius = size.height * 0.62f,
                )

                // Lookups: thin lines from the phone's edges to hosts; one host is a tracker (warm), the others neutral.
                val hosts = listOf(
                    Offset(left - 64.dp.toPx(), top + ph * 0.22f) to Film.boneDim,
                    Offset(left + pw + 70.dp.toPx(), top + ph * 0.30f) to Film.high,
                    Offset(left + pw + 58.dp.toPx(), top + ph * 0.72f) to Film.boneDim,
                    Offset(left - 74.dp.toPx(), top + ph * 0.66f) to Film.leak,
                )
                val starts = hosts.map { (p, _) -> Offset(if (p.x < left) left else left + pw, p.y + (top + ph / 2 - p.y) * 0.35f) }

                val phone = Path().apply { addRoundRect(RoundRect(left, top, left + pw, top + ph, CornerRadius(r))) }
                val band = 70.dp.toPx()
                val bandBrush = Brush.verticalGradient(listOf(Color.Transparent, Film.scan.copy(alpha = 0.28f), Color.Transparent), startY = 0f, endY = band)
                val bodyFill = Brush.verticalGradient(listOf(FilmExtra.raisedHigh, Film.raised), startY = top, endY = top + ph)

                val cl = left + 12.dp.toPx()
                val cw = pw - 24.dp.toPx()
                val ct = top + ph * 0.36f
                val chh = 92.dp.toPx()
                val by = ct + chh - 32.dp.toPx()
                val bw = (cw - 30.dp.toPx()) / 2

                onDrawBehind {
                    drawCircle(glow, radius = size.height * 0.62f, center = glowCenter)

                    val pingNow = ping.value
                    for (i in hosts.indices) {
                        val (p, c) = hosts[i]
                        drawLine(c.copy(alpha = 0.45f), starts[i], p, 1.5.dp.toPx(), StrokeCap.Round)
                        val g = if (pingNow < 0f) 0.6f else {
                            val phase = ((pingNow - i * 0.75f) % 3f + 3f) % 3f
                            if (phase < 1f) 1f - phase else 0f
                        }
                        drawCircle(c.copy(alpha = 0.18f * g), (7 + 10 * (1 - g)).dp.toPx(), p)
                        drawCircle(c, 5.dp.toPx(), p)
                    }

                    // The phone body.
                    drawRoundRect(bodyFill, Offset(left, top), Size(pw, ph), CornerRadius(r))
                    drawRoundRect(Film.line, Offset(left, top), Size(pw, ph), CornerRadius(r), style = Stroke(2.dp.toPx()))
                    drawLine(Film.line, Offset(left + pw * 0.38f, top + 10.dp.toPx()), Offset(left + pw * 0.62f, top + 10.dp.toPx()), 3.dp.toPx(), StrokeCap.Round)

                    // The consent popup: two text lines and two buttons (Reject outlined, Accept filled).
                    drawRoundRect(FilmExtra.raisedHigh, Offset(cl, ct), Size(cw, chh), CornerRadius(12.dp.toPx()))
                    drawLine(Film.boneDim, Offset(cl + 12.dp.toPx(), ct + 18.dp.toPx()), Offset(cl + cw * 0.75f, ct + 18.dp.toPx()), 4.dp.toPx(), StrokeCap.Round)
                    drawLine(Film.boneDim.copy(alpha = 0.6f), Offset(cl + 12.dp.toPx(), ct + 32.dp.toPx()), Offset(cl + cw * 0.55f, ct + 32.dp.toPx()), 4.dp.toPx(), StrokeCap.Round)
                    drawRoundRect(Film.warn, Offset(cl + 10.dp.toPx(), by), Size(bw, 20.dp.toPx()), CornerRadius(10.dp.toPx()), style = Stroke(1.5.dp.toPx()))
                    drawRoundRect(Film.scan, Offset(cl + 20.dp.toPx() + bw, by), Size(bw, 20.dp.toPx()), CornerRadius(10.dp.toPx()))

                    // The X-ray: a soft scan band sweeping down inside the phone only.
                    clipPath(phone) {
                        val y = top - band + (ph + band) * scan.value
                        translate(top = y) { drawRect(bandBrush, Offset(left, 0f), Size(pw, band)) }
                        drawLine(Film.scan.copy(alpha = 0.7f), Offset(left, y + band / 2), Offset(left + pw, y + band / 2), 1.dp.toPx())
                    }
                }
            },
    )
}

/**
 * Page 3: three runs as lanes with a moving playhead, on a raised card. Dots after a refusal are drawn in the leak
 * colour. The caption (each dot is an attempt to reach an address, not proof data was sent) always stays in view.
 * With animations off, every dot shows and the playhead rests out of sight.
 */
@Composable
fun HowItWorksArt(lang: GuideLang, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    val playhead: State<Float> = if (!reduce) {
        rememberInfiniteTransition(label = "how").animateFloat(
            0f, 1.15f, infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart), label = "playhead",
        )
    } else remember { mutableStateOf(1.15f) }
    data class Lane(val choiceAt: Float?, val choiceColor: Color, val dots: List<Float>, val trackerDots: Set<Float>)
    val lanes = remember {
        listOf(
            Lane(null, Film.boneDim, listOf(0.12f, 0.3f, 0.55f, 0.82f), setOf(0.3f)),
            Lane(0.4f, Film.warn, listOf(0.1f, 0.24f, 0.62f, 0.8f), setOf(0.62f, 0.8f)),
            Lane(0.4f, Film.scan, listOf(0.14f, 0.5f, 0.66f, 0.86f), setOf(0.66f)),
        )
    }
    Column(
        modifier.fillMaxWidth().padding(vertical = 8.dp).cardSurface().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        lanes.forEachIndexed { i, lane ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", style = MaterialTheme.typography.titleLarge, color = Brand.coral, modifier = Modifier.width(22.dp))
                Text(OnboardingCopy.lanes[i].pick(lang), style = MaterialTheme.typography.titleMedium, color = Film.bone, modifier = Modifier.width(96.dp))
                Canvas(Modifier.weight(1f).height(34.dp)) {
                    val p = playhead.value
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
        // Honesty caveat: a lookup is an attempt to reach an address, not proof that data was sent. Always visible.
        KeyCaveat(OnboardingCopy.exampleCaption.pick(lang), color = Film.boneDim)
    }
}
