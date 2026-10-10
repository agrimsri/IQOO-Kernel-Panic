package com.dpdpxray.app.ui.modes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Line glyphs for the two front doors, drawn on the same 24-unit grid and stroke weight as the app's other glyphs
 * (ui/guide/VisualKit), so no icon files are needed.
 */
enum class ModeGlyph {
    /** A phone with a check: "I use apps" / everyday mode / "Check my apps". */
    PHONE,

    /** Code brackets: "I build apps" / developer mode / automation. */
    CODE,

    /** A speech bubble with a cross: saying No to a consent popup. */
    SAY_NO,
}

@Composable
fun ModeGlyphIcon(glyph: ModeGlyph, tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val stroke = Stroke(width = 1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun o(x: Float, y: Float) = Offset(x * u, y * u)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(tint, o(x1, y1), o(x2, y2), 1.8f * u, StrokeCap.Round)
        fun poly(vararg p: Float) = drawPath(Path().apply {
            moveTo(p[0] * u, p[1] * u)
            var i = 2
            while (i < p.size) { lineTo(p[i] * u, p[i + 1] * u); i += 2 }
        }, tint, style = stroke)
        fun box(l: Float, t: Float, r: Float, b: Float, radius: Float) =
            drawRoundRect(tint, o(l, t), Size((r - l) * u, (b - t) * u), CornerRadius(radius * u), style = stroke)

        when (glyph) {
            ModeGlyph.PHONE -> {
                box(6.5f, 2.5f, 17.5f, 21.5f, 2.8f)
                line(10.5f, 18.6f, 13.5f, 18.6f)
                poly(9.2f, 10.8f, 11.2f, 12.8f, 14.9f, 8.8f)
            }
            ModeGlyph.CODE -> {
                poly(8.5f, 7f, 3.5f, 12f, 8.5f, 17f)
                poly(15.5f, 7f, 20.5f, 12f, 15.5f, 17f)
                line(13.4f, 5f, 10.6f, 19f)
            }
            ModeGlyph.SAY_NO -> {
                box(3f, 4f, 21f, 16f, 4f)
                poly(8f, 16f, 7f, 20f, 12f, 16f)
                line(9.6f, 7.6f, 14.4f, 12.4f)
                line(14.4f, 7.6f, 9.6f, 12.4f)
            }
        }
    }
}
