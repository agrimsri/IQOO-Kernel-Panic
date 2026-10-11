package com.dpdpxray.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.dpdpxray.app.ui.theme.ArchivoDisplay
import com.dpdpxray.app.ui.theme.Brand
import com.dpdpxray.app.ui.theme.Film

/**
 * The TraceVeil mark in a 100 x 100 box: a lens under a gradient veil whose lower-right part is lifted back along a
 * diagonal fold (light lining, soft shadow). Under the lifted veil the lens sends out two cyan scan arcs, and one red
 * lookup breaks the outer arc. Same geometry as docs/brand/traceveil-mark.svg and the launcher icon foreground.
 */
internal object BrandMarkGeometry {
    const val LENS = "M6,50 A44,44 0 1,1 94,50 A44,44 0 1,1 6,50 Z"
    const val ARC_INNER = "M67.68,49.29 A13,13 0 0,1 49.29,67.68"
    const val ARC_OUTER = "M76.87,40.1 A26,26 0 0,1 77.5,76.22 M62.55,84.17 A26,26 0 0,1 40.1,76.87"
    const val LOOKUP = "M65.29,81.44 A5.4,5.4 0 1,1 76.09,81.44 A5.4,5.4 0 1,1 65.29,81.44 Z"
    const val VEIL = "M28.55,88.42 A44,44 0 1,1 88.42,28.55 Z"
    const val SHADE = "M28.55,88.42 A54,54 0 0,1 88.42,28.55 Z"
    const val LINING = "M28.55,88.42 A64,64 0 0,1 88.42,28.55 Z"
    const val ARC_WIDTH = 6f
    const val OUTER_ARC_ALPHA = 0.55f
    const val SHADE_ALPHA = 0.3f

    /** Midpoint of the fold line; both gradients end here. */
    val foldMid = Offset(58.49f, 58.49f)
    val all = listOf(LENS, ARC_INNER, ARC_OUTER, LOOKUP, VEIL, SHADE, LINING)
}

/** Parsed once per process; drawing only reads them. */
private object MarkPaths {
    private fun parse(d: String): Path = PathParser().parsePathString(d).toPath()
    val lens = parse(BrandMarkGeometry.LENS)
    val arcInner = parse(BrandMarkGeometry.ARC_INNER)
    val arcOuter = parse(BrandMarkGeometry.ARC_OUTER)
    val lookup = parse(BrandMarkGeometry.LOOKUP)
    val veil = parse(BrandMarkGeometry.VEIL)
    val shade = parse(BrandMarkGeometry.SHADE)
    val lining = parse(BrandMarkGeometry.LINING)
}

private val VeilLining = Color(0xFFFFF1E8)

/** The mark keeps its original colours in every theme: it is the logo, not part of the UI palette. */
private object MarkColors {
    val lens = Color(0xFF10202E)
    val scan = Color(0xFF7FD3E6)
    val lookup = Color(0xFFFF5A4E)
    val peach = Color(0xFFFAA275)
    val coral = Color(0xFFFF8C61)
    val rose = Color(0xFFCE6A85)
    val stops = listOf(peach, coral, rose)
}
private val VeilShade = Color(0xFF8E3F5C)

/**
 * The TraceVeil brand mark, drawn with vector paths and gradients (no bitmap), so it stays sharp at any [size].
 * [lens] fills the disc behind the scan arcs; the default matches the app background, a darker tone makes it a visible
 * well. Pass `contentDescription = null` when a visible "TraceVeil" label sits next to it.
 */
@Composable
fun BrandMark(
    size: Dp,
    modifier: Modifier = Modifier,
    lens: Color = MarkColors.lens,
    contentDescription: String? = "TraceVeil",
) {
    val described = contentDescription?.let { label -> modifier.semantics { this.contentDescription = label } } ?: modifier
    Canvas(described.size(size)) {
        val k = this.size.minDimension / 100f
        scale(k, k, pivot = Offset.Zero) { drawMark(lens) }
    }
}

private fun DrawScope.drawMark(lens: Color) {
    val g = BrandMarkGeometry
    drawPath(MarkPaths.lens, lens)
    drawPath(MarkPaths.arcInner, MarkColors.scan, style = Stroke(width = g.ARC_WIDTH))
    drawPath(MarkPaths.arcOuter, MarkColors.scan, alpha = g.OUTER_ARC_ALPHA, style = Stroke(width = g.ARC_WIDTH))
    drawPath(MarkPaths.lookup, MarkColors.lookup)
    drawPath(MarkPaths.veil, Brush.linearGradient(MarkColors.stops, start = Offset(19f, 19f), end = g.foldMid))
    drawPath(MarkPaths.shade, VeilShade, alpha = g.SHADE_ALPHA)
    drawPath(
        MarkPaths.lining,
        Brush.linearGradient(listOf(VeilLining, MarkColors.peach), start = g.foldMid - Offset(30f, 30f), end = g.foldMid),
    )
}

/**
 * Mark plus the "TraceVeil" wordmark in the app's display face (Archivo 800, narrow): "Trace" in bone, "Veil" in the
 * brand gradient, as in docs/brand/traceveil-logo-horizontal.svg. Text is sized from [markSize] (cap height = 38% of
 * the mark box), so the lock-up keeps its proportions; it is read out once as "TraceVeil".
 */
@Composable
fun BrandWordmark(modifier: Modifier = Modifier, markSize: Dp = 32.dp) {
    val fontSize = with(LocalDensity.current) { (markSize * 0.554f).toSp() }
    // "Veil" starts at about 60% of the word's width; the span brush spans the whole line, so the gradient starts there.
    val veilBrush = Brush.horizontalGradient(0.6f to MarkColors.peach, 0.8f to MarkColors.coral, 1f to MarkColors.rose)
    Row(
        modifier.clearAndSetSemantics { contentDescription = "TraceVeil" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(markSize * 0.09f),
    ) {
        BrandMark(markSize, contentDescription = null)
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = Film.bone)) { append("Trace") }
                withStyle(SpanStyle(brush = veilBrush)) { append("Veil") }
            },
            style = TextStyle(fontFamily = ArchivoDisplay, fontWeight = FontWeight(800), fontSize = fontSize, letterSpacing = (-0.01).em),
            maxLines = 1,
            softWrap = false,
        )
    }
}
