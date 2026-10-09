package com.dpdpxray.core.geo

/**
 * The country outlines of the bundled `geo/world.svg` (CC BY-NC 4.0, see docs/ATTRIBUTION.md): SVG path data per ISO
 * country code, in a [width] x [height] box shifted by ([offsetX], [offsetY]). Parsed once; drawing happens in the UI.
 */
class WorldMap(val width: Double, val height: Double, val offsetX: Double, val offsetY: Double, val paths: Map<String, String>) {
    companion object {
        private val path = Regex("<path\\b([^>]*)>", RegexOption.DOT_MATCHES_ALL)
        private val id = Regex("\\bid=\"([A-Za-z]{2})\"")
        private val data = Regex("\\bd=\"([^\"]*)\"")
        private val size = Regex("<svg\\b[^>]*?\\bwidth=\"([0-9.]+)\"[^>]*?\\bheight=\"([0-9.]+)\"|<svg\\b[^>]*?\\bheight=\"([0-9.]+)\"[^>]*?\\bwidth=\"([0-9.]+)\"", RegexOption.DOT_MATCHES_ALL)
        private val shift = Regex("<g\\b[^>]*transform=\"translate\\(([-0-9.]+)[ ,]+([-0-9.]+)\\)\"")

        fun parse(svg: String): WorldMap {
            val s = size.find(svg)
            val w = (s?.groupValues?.get(1)?.takeIf { it.isNotEmpty() } ?: s?.groupValues?.get(4))?.toDoubleOrNull() ?: 0.0
            val h = (s?.groupValues?.get(2)?.takeIf { it.isNotEmpty() } ?: s?.groupValues?.get(3))?.toDoubleOrNull() ?: 0.0
            val g = shift.find(svg)
            val paths = LinkedHashMap<String, String>()
            for (m in path.findAll(svg)) {
                val attrs = m.groupValues[1]
                val code = id.find(attrs)?.groupValues?.get(1)?.uppercase() ?: continue
                val d = data.find(attrs)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                paths[code] = d
            }
            return WorldMap(w, h, g?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0, g?.groupValues?.get(2)?.toDoubleOrNull() ?: 0.0, paths)
        }

        fun loadDefault(): WorldMap =
            WorldMap::class.java.getResourceAsStream("/geo/world.svg")?.bufferedReader(Charsets.UTF_8)?.use { parse(it.readText()) }
                ?: WorldMap(0.0, 0.0, 0.0, 0.0, emptyMap())
    }
}
