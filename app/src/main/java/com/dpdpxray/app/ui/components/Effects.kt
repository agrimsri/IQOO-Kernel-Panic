package com.dpdpxray.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.FilmExtra
import com.dpdpxray.app.ui.theme.LocalReduceMotion
import com.dpdpxray.app.ui.theme.Motion

/*
 * Small, cheap motion helpers shared by every screen. Each one reads LocalReduceMotion and stands still when the
 * system "Remove animations" setting is on. Animated values are read inside draw or graphicsLayer blocks, so a frame
 * only redraws; nothing recomposes or allocates per frame.
 */

/** How much a card or button shrinks while pressed. */
const val PRESSED_SCALE = 0.98f

/**
 * [clickable] with a gentle press: the element scales to [PRESSED_SCALE] while a finger is down. Ripple and
 * semantics are the normal clickable ones, so TalkBack reads it exactly as before. Pass the element's [shape] so the
 * ripple follows its corners.
 */
fun Modifier.pressClickable(
    enabled: Boolean = true,
    role: Role? = Role.Button,
    onClickLabel: String? = null,
    shape: Shape? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val reduce = LocalReduceMotion.current
    val scale by animateFloatAsState(
        if (pressed && enabled && !reduce) PRESSED_SCALE else 1f, tween(if (pressed) 80 else Motion.SHORT, easing = Motion.emphasized),
        label = "press",
    )
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .let { if (shape != null) it.clip(shape) else it }
        .clickable(interactionSource = source, indication = LocalIndication.current, enabled = enabled, role = role, onClickLabel = onClickLabel, onClick = onClick)
}

/**
 * One short fade as a screen opens: no stagger, no rise. Plays once per screen visit; a list item scrolled back into
 * view does not replay. [index] is accepted so older call sites compile. Instant with reduced motion.
 */
@Suppress("UNUSED_PARAMETER")
fun Modifier.reveal(index: Int = 0): Modifier = composed {
    val reduce = LocalReduceMotion.current
    var played by rememberSaveable { mutableStateOf(reduce) }
    if (played) return@composed this
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(Motion.SHORT, easing = Motion.enter))
        played = true
    }
    this.graphicsLayer { alpha = progress.value }
}

/**
 * Tracks whether this element is on screen (its clipped bounds in the window are not empty), so looping effects can
 * pause when scrolled away.
 */
@Composable
fun rememberOnScreen(): Pair<State<Boolean>, Modifier> {
    val visible = remember { mutableStateOf(true) }
    val modifier = Modifier.onGloballyPositioned { c ->
        val b = c.boundsInWindow()
        val now = b.width > 0f && b.height > 0f
        if (visible.value != now) visible.value = now
    }
    return visible to modifier
}

/**
 * Retired: nothing sweeps across buttons or cards any more. Kept so older call sites compile; it changes nothing.
 */
@Suppress("UNUSED_PARAMETER")
fun Modifier.gradientSweep(color: Color = Color.White, alpha: Float = 0.10f, periodMs: Int = 5200): Modifier = this

/** Placeholders are flat and still; they do not shimmer. Kept so older call sites compile. */
fun Modifier.shimmer(): Modifier = this


/** One placeholder bar. */
@Composable
fun SkeletonBar(modifier: Modifier = Modifier, height: Dp = 14.dp) {
    Box(modifier.height(height).clip(RoundedCornerShape(height / 2)).background(FilmExtra.raisedHigh))
}

/**
 * A placeholder card shaped like a saved check (badge, two lines), shimmering while data loads. TalkBack hears
 * [label] once instead of empty boxes.
 */
@Composable
fun SkeletonCard(modifier: Modifier = Modifier, label: String = "Loading") {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier.fillMaxWidth().clip(shape).background(Film.raised).shimmer().padding(14.dp).semantics { contentDescription = label },
    ) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(FilmExtra.raisedHigh))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SkeletonBar(Modifier.fillMaxWidth(0.6f), 16.dp)
            SkeletonBar(Modifier.fillMaxWidth(0.85f))
            SkeletonBar(Modifier.fillMaxWidth(0.4f), 12.dp)
        }
    }
}

/** A short stack of shimmering text lines, for a report or explanation that is still being built. */
@Composable
fun SkeletonLines(lines: Int = 4, modifier: Modifier = Modifier, label: String = "Loading") {
    Column(modifier.fillMaxWidth().shimmer().semantics { contentDescription = label }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(lines) { i -> SkeletonBar(Modifier.fillMaxWidth(if (i == lines - 1) 0.55f else 0.92f - (i % 3) * 0.08f)) }
    }
}

/** A small "recording" dot that breathes while a live check runs. A steady dot when motion is reduced. */
@Composable
fun RecordingDot(color: Color = Film.leak, modifier: Modifier = Modifier, dot: Dp = 10.dp) {
    val reduce = LocalReduceMotion.current
    val pulse: State<Float> = if (!reduce) {
        rememberInfiniteTransition(label = "rec").animateFloat(
            0f, 1f, infiniteRepeatable(tween(1100, easing = Motion.emphasized), RepeatMode.Reverse), label = "rec-phase",
        )
    } else remember { mutableStateOf(0f) }
    Box(
        modifier.size(dot * 2.2f).drawWithCache {
            val r = dot.toPx() / 2f
            onDrawBehind {
                val p = pulse.value
                drawCircle(color.copy(alpha = 0.28f * (1f - p)), radius = r * (1f + 1.1f * p))
                drawCircle(color, radius = r)
            }
        },
    )
}

/** A small steady dot for "waiting" states. It no longer pulses; [BreathingDot] keeps its name for older call sites. */
@Composable
fun BreathingDot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}
