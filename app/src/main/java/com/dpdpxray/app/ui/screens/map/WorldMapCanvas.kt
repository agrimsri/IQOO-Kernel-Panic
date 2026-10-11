package com.dpdpxray.app.ui.screens.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.ui.EndpointMapIntegration
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.core.geo.CountryNames
import com.dpdpxray.core.geo.WorldMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The bundled world outline (Natural Earth 1:110m, public domain), parsed to Compose paths once per process. */
private object WorldPaths {
    @Volatile private var cache: Pair<WorldMap, Map<String, Path>>? = null

    suspend fun get(): Pair<WorldMap, Map<String, Path>> = cache ?: withContext(Dispatchers.Default) {
        val map = WorldMap.loadDefault()
        val parser = PathParser()
        map.paths.mapNotNull { (iso, d) -> runCatching { iso to parser.parsePathString(d).toPath() }.getOrNull() }.toMap()
            .let { map to it }.also { cache = it }
    }
}

/**
 * Draws the world with [highlighted] countries filled. [uncertain] ones use a second fill. Countries too small for an
 * outline (Singapore, for one) cannot be drawn: a line under the map names them, and the list below stays the complete
 * record.
 */
@Composable
fun WorldMapCanvas(highlighted: Set<String>, uncertain: Set<String>, highlightColor: Color, modifier: Modifier = Modifier) {
    val world by produceState<Pair<WorldMap, Map<String, Path>>?>(null) { value = WorldPaths.get() }
    val w = world ?: return
    val (map, paths) = w
    val description = remember(highlighted) {
        if (highlighted.isEmpty()) "World map, no countries highlighted"
        else "World map highlighting ${highlighted.sorted().joinToString(", ") { CountryNames.name(it) }}"
    }
    val undrawn = remember(map, highlighted, uncertain) { EndpointMapIntegration.undrawnNote(map.undrawn(highlighted + uncertain)) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.fillMaxWidth().aspectRatio((map.width / map.height).toFloat()).semantics { contentDescription = description }) {
            val k = size.width / map.width.toFloat()
            val land = Color(MapColors.LAND)
            val outline = Stroke(width = 0.6f / k)
            scale(k, k, pivot = Offset.Zero) {
                translate(map.offsetX.toFloat(), map.offsetY.toFloat()) {
                    paths.forEach { (iso, path) ->
                        val fill = when (iso) {
                            in uncertain -> Color(MapColors.UNCERTAIN)
                            in highlighted -> highlightColor
                            else -> land
                        }
                        drawPath(path, fill)
                        drawPath(path, Film.base, style = outline)
                    }
                }
            }
        }
        undrawn?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Film.boneDim) }
    }
}
