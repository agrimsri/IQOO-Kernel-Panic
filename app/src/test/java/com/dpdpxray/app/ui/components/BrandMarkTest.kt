package com.dpdpxray.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Guards the brand mark: the adaptive launcher icon keeps a themed (monochrome) layer and stays inside the 66 dp safe
 * zone, and the in-app [BrandMark] geometry stays inside its own lens. Paths are sampled, arcs included.
 */
class BrandMarkTest {
    private val res = File("src/main/res")

    @Test fun `adaptive launcher icons declare background, foreground and monochrome layers`() {
        for (name in listOf("ic_launcher", "ic_launcher_round")) {
            val xml = File(res, "mipmap-anydpi-v26/$name.xml").readText()
            assertTrue(name, xml.contains("<background android:drawable=\"@drawable/ic_launcher_background\""))
            assertTrue(name, xml.contains("<foreground android:drawable=\"@drawable/ic_launcher_foreground\""))
            assertTrue(name, xml.contains("<monochrome android:drawable=\"@drawable/ic_launcher_monochrome\""))
        }
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:icon=\"@mipmap/ic_launcher\""))
        assertTrue(manifest.contains("android:roundIcon=\"@mipmap/ic_launcher_round\""))
    }

    @Test fun `launcher foreground is the logo image inset into the 66 dp safe zone`() {
        val xml = File(res, "drawable/ic_launcher_foreground.xml").readText()
        assertTrue("foreground uses the logo image", xml.contains("@drawable/logo_mark"))
        val inset = Regex("android:inset=\"([0-9.]+)%\"").find(xml)?.groupValues?.get(1)?.toDouble() ?: 0.0
        // The mark fills about 88% of the image; a 17% inset on each side keeps it inside the 61% safe zone.
        assertTrue("inset is $inset%", inset >= 17.0)
        assertTrue("logo image is bundled", File(res, "drawable-nodpi/logo_mark.png").length() > 10_000)
    }

    @Test fun `launcher monochrome stays inside the 66 dp safe zone`() {
        for (name in listOf("ic_launcher_monochrome")) {
            val xml = File(res, "drawable/$name.xml").readText()
            val paths = Regex("android:pathData=\"([^\"]+)\"").findAll(xml).map { it.groupValues[1] }.toList()
            assertTrue("$name has the mark's shapes", paths.size >= 5)
            val halfStroke = Regex("android:strokeWidth=\"([0-9.]+)\"").findAll(xml)
                .maxOfOrNull { it.groupValues[1].toDouble() / 2 } ?: 0.0
            val reach = paths.flatMap(::samplePath).maxOf { (x, y) -> hypot(x - 54, y - 54) } + halfStroke
            assertTrue("$name reaches $reach dp from the centre", reach <= 33.0)
        }
    }

    @Test fun `in-app mark stays inside its lens and matches the launcher shape count`() {
        assertEquals(7, BrandMarkGeometry.all.size)
        val reach = BrandMarkGeometry.all.flatMap(::samplePath).maxOf { (x, y) -> hypot(x - 50, y - 50) }
        assertTrue("mark reaches $reach units from the centre", reach <= 44.05)
    }

    /** Points along an absolute M/L/A/Z path (the only commands the brand geometry uses). */
    private fun samplePath(d: String): List<Pair<Double, Double>> {
        val tokens = Regex("[MLAZ]|-?\\d*\\.?\\d+").findAll(d).map { it.value }.toList()
        val points = mutableListOf<Pair<Double, Double>>()
        var i = 0
        var cmd = 'M'
        var x = 0.0
        var y = 0.0
        fun num() = tokens[i++].toDouble()
        while (i < tokens.size) {
            if (tokens[i][0].isLetter()) {
                cmd = tokens[i++][0]
                if (cmd == 'Z') continue
            }
            when (cmd) {
                'M', 'L' -> { x = num(); y = num(); points += x to y }
                'A' -> {
                    val r = num(); num(); num()
                    val large = num() != 0.0
                    val sweep = num() != 0.0
                    val x2 = num()
                    val y2 = num()
                    points += arcPoints(x, y, r, large, sweep, x2, y2)
                    x = x2; y = y2
                }
                else -> error("unexpected path command $cmd in brand geometry")
            }
        }
        return points
    }

    /** Circular SVG arc (rx = ry, no rotation) from endpoint to centre parameterisation, sampled. */
    private fun arcPoints(x1: Double, y1: Double, r0: Double, large: Boolean, sweep: Boolean, x2: Double, y2: Double): List<Pair<Double, Double>> {
        val hx = (x1 - x2) / 2
        val hy = (y1 - y2) / 2
        val d2 = hx * hx + hy * hy
        val r = max(r0, sqrt(d2))
        val coef = (if (large == sweep) -1.0 else 1.0) * sqrt(max(0.0, (r * r - d2) / d2))
        val cx = coef * hy + (x1 + x2) / 2
        val cy = -coef * hx + (y1 + y2) / 2
        val a1 = atan2(y1 - cy, x1 - cx)
        var da = atan2(y2 - cy, x2 - cx) - a1
        if (sweep && da < 0) da += 2 * PI
        if (!sweep && da > 0) da -= 2 * PI
        return (0..64).map { val a = a1 + da * it / 64; (cx + r * cos(a)) to (cy + r * sin(a)) }
    }
}
