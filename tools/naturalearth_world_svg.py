#!/usr/bin/env python3
"""Builds core/src/main/resources/geo/world.svg from Natural Earth's 1:110m Admin 0 - Countries (public domain).

Input: ne_110m_admin_0_countries.geojson from the official repository, pinned to release v5.1.2:
  https://raw.githubusercontent.com/nvkelso/natural-earth-vector/f1890d9f152c896d250a77557a5751a93d494776/geojson/ne_110m_admin_0_countries.geojson
  (838,726 bytes, SHA-256 6866c877d39cba9c357620878839b336d569f8c662d3cfab4cb1dbe2d39c977f)
Terms: https://www.naturalearthdata.com/about/terms-of-use/ ("All versions of Natural Earth raster + vector map data
found on this website are in the public domain." "No permission is needed to use Natural Earth.")

Usage: python -I tools/naturalearth_world_svg.py <ne_110m_admin_0_countries.geojson> core/src/main/resources/geo/world.svg

Output is what com.dpdpxray.core.geo.WorldMap parses: <svg width height>, one <g transform="translate(pad,pad)">, and
one <path id="ISO alpha-2" d="..."/> per country. Projection: Mercator, longitudes and latitudes fitted to the land
(Antarctica left out, as before). Path data is absolute M per ring, then relative l steps, rounded to `decimals`.

Keys are ISO 3166-1 alpha-2 codes (Kosovo uses the user-assigned XK, like IP tables do):
- ISO_A2_EH is used, since ISO_A2 is "-99" for France, Norway and Kosovo and "CN-TW" for Taiwan.
- Two areas Natural Earth draws without an ISO code join the ISO 3166 country that includes them: Somaliland (SOL)
  joins Somalia (SO), Northern Cyprus (CYN) joins Cyprus (CY).
- Natural Earth draws French Guiana inside France and Svalbard inside Norway; ISO 3166 and IP tables give them their own
  codes (GF, SJ), so those polygons are split out.
- Crimea is drawn as part of Ukraine (ISO 3166-2:UA lists it as UA-43); Natural Earth's default file draws it with Russia.
- Antarctica (AQ) is dropped: Mercator cannot show the pole and the previous map left it out too.
"""

import json
import math
import sys
from pathlib import Path

#: ADM0_A3 of Natural Earth areas with no ISO code -> the ISO 3166 country that includes them.
MERGE = {"SOL": "SO", "CYN": "CY"}
#: ISO codes left off the map.
DROP = {"AQ"}
#: Polygons lying wholly west of this longitude are moved east by 360 degrees (Chukotka, Fiji), so the map is one piece.
WRAP_WEST_OF = -169.0


def _bbox(polygon):
    lons = [p[0] for p in polygon[0]]
    lats = [p[1] for p in polygon[0]]
    return min(lons), max(lons), min(lats), max(lats)


def _french_guiana(polygon):
    return _bbox(polygon)[1] < -40.0


def _svalbard(polygon):
    return _bbox(polygon)[2] > 74.0


def _crimea(polygon):
    lon_min, lon_max, lat_min, lat_max = _bbox(polygon)
    return lon_min >= 32.0 and lon_max <= 37.0 and lat_min >= 44.0 and lat_max <= 46.5


#: Country -> [(ISO code to give the polygon, test on the polygon)].
SPLIT = {"FR": [("GF", _french_guiana)], "NO": [("SJ", _svalbard)], "RU": [("UA", _crimea)]}


def iso_code(props):
    """ISO alpha-2 code for a Natural Earth feature, or ValueError if it has none and no rule covers it."""
    for key in ("ISO_A2_EH", "ISO_A2"):
        code = props.get(key) or ""
        if len(code) == 2 and code.isalpha() and code.isupper():
            return code
    merged = MERGE.get(props.get("ADM0_A3"))
    if merged:
        return merged
    raise ValueError(f"no ISO code for {props.get('ADMIN')!r} ({props.get('ADM0_A3')})")


def _polygons(geometry):
    if geometry["type"] == "Polygon":
        return [geometry["coordinates"]]
    if geometry["type"] == "MultiPolygon":
        return list(geometry["coordinates"])
    raise ValueError(f"unexpected geometry {geometry['type']}")


def _wrapped(polygon):
    if _bbox(polygon)[1] < WRAP_WEST_OF:
        return [[[lon + 360.0, lat] for lon, lat, *_ in ring] for ring in polygon]
    return [[[lon, lat] for lon, lat, *_ in ring] for ring in polygon]


def countries(geojson):
    """ISO code -> list of polygons (each a list of rings of [lon, lat]), in the order the features appear."""
    out = {}
    for feature in geojson["features"]:
        code = iso_code(feature["properties"])
        if code in DROP:
            continue
        for polygon in _polygons(feature["geometry"]):
            target = code
            for other, test in SPLIT.get(code, []):
                if test(polygon):
                    target = other
            out.setdefault(target, []).append(_wrapped(polygon))
    return out


def _mercator(lat):
    return math.log(math.tan(math.pi / 4 + math.radians(lat) / 2))


class Projection:
    """Mercator scaled so [lon_min, lon_max] spans [0, width]; y grows downwards from lat_max."""

    def __init__(self, lon_min, lon_max, lat_min, lat_max, width):
        self.lon_min = lon_min
        self.lat_max = lat_max
        self.k = width / (lon_max - lon_min)  # units per degree of longitude
        self.width = width
        self.height = self._y(lat_min)

    def _y(self, lat):
        return (_mercator(self.lat_max) - _mercator(lat)) * math.degrees(1) * self.k

    def project(self, lon, lat):
        return (lon - self.lon_min) * self.k, self._y(lat)

    @classmethod
    def fit(cls, by_code, width):
        points = [p for polygons in by_code.values() for polygon in polygons for ring in polygon for p in ring]
        lons = [p[0] for p in points]
        lats = [p[1] for p in points]
        return cls(min(lons), max(lons), min(lats), max(lats), width)


def _ring_units(ring, proj, scale):
    """Projected ring as integer units of 10^-decimals, consecutive duplicates and the closing point removed."""
    out = []
    for lon, lat in ring:
        x, y = proj.project(lon, lat)
        p = (round(x * scale), round(y * scale))
        if not out or out[-1] != p:
            out.append(p)
    while len(out) > 1 and out[-1] == out[0]:
        out.pop()
    return out


def _area2(points):
    return sum(x1 * y2 - x2 * y1 for (x1, y1), (x2, y2) in zip(points, points[1:] + points[:1]))


def _fmt(units, decimals):
    if decimals == 0:
        return str(units)
    sign = "-" if units < 0 else ""
    whole, frac = divmod(abs(units), 10 ** decimals)
    frac_text = f"{frac:0{decimals}d}".rstrip("0")
    return f"{sign}{whole}.{frac_text}" if frac_text else f"{sign}{whole}"


def _oriented_rings(polygons, proj, scale):
    """Rounded rings; outer rings wind with positive area, holes with negative, so nonzero fill leaves holes empty."""
    rings = []
    for polygon in polygons:
        outer_kept = False
        for index, ring in enumerate(polygon):
            pts = _ring_units(ring, proj, scale)
            area = _area2(pts) if len(pts) >= 3 else 0
            if area == 0:
                continue
            if index == 0:
                outer_kept = True
                if area < 0:
                    pts.reverse()
            else:
                if not outer_kept:
                    continue  # a hole whose outer ring vanished
                if area > 0:
                    pts.reverse()
            rings.append(pts)
    return rings


def _merge_shared_borders(rings):
    """
    Removes borders shared by pieces of one country (Somaliland and Somalia, Crimea and Ukraine, Chukotka at 180
    degrees) so no seam is stroked inside it. An edge u->v cancels against v->u; the remaining directed edges are
    chained back into closed rings. Every vertex keeps in-degree equal to out-degree, so chaining always closes, and the
    winding number at every point (hence the nonzero fill) is unchanged.
    """
    outgoing = {}
    for pts in rings:
        for u, v in zip(pts, pts[1:] + pts[:1]):
            back = outgoing.get(v)
            if back and u in back:
                back.remove(u)
            else:
                outgoing.setdefault(u, []).append(v)
    merged = []
    for start in list(outgoing):
        while outgoing[start]:
            ring = [start]
            node = outgoing[start].pop(0)
            while node != start:
                ring.append(node)
                node = outgoing[node].pop(0)
            if len(ring) >= 3 and _area2(ring) != 0:
                merged.append(ring)
    return merged


def path_data(polygons, proj, decimals):
    """SVG path data: per ring "M x,y l dx,dy dx,dy ... z". Holes wind opposite to their outer ring."""
    scale = 10 ** decimals
    parts = []
    for pts in _merge_shared_borders(_oriented_rings(polygons, proj, scale)):
        x0, y0 = pts[0]
        steps = " ".join(
            f"{_fmt(x2 - x1, decimals)},{_fmt(y2 - y1, decimals)}" for (x1, y1), (x2, y2) in zip(pts, pts[1:])
        )
        parts.append(f"M{_fmt(x0, decimals)},{_fmt(y0, decimals)}l{steps}z")
    return "".join(parts)


def build_svg(by_code, width=1000.0, pad=0.5, decimals=2):
    proj = Projection.fit(by_code, width)
    w = _fmt(round((proj.width + 2 * pad) * 10 ** decimals), decimals)
    h = _fmt(round((proj.height + 2 * pad) * 10 ** decimals), decimals)
    lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        "<!-- Country outlines: Natural Earth 1:110m Admin 0 - Countries, v5.1.2 (public domain,",
        "     https://www.naturalearthdata.com/about/terms-of-use/). Generated by tools/naturalearth_world_svg.py;",
        "     Mercator projection, ISO 3166-1 alpha-2 ids. See docs/ATTRIBUTION.md. -->",
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}">',
        f'<g transform="translate({_fmt(round(pad * 10 ** decimals), decimals)},{_fmt(round(pad * 10 ** decimals), decimals)})">',
    ]
    for code in sorted(by_code):
        d = path_data(by_code[code], proj, decimals)
        if d:
            lines.append(f'<path id="{code}" d="{d}"/>')
    lines += ["</g>", "</svg>", ""]
    return "\n".join(lines)


def main(argv):
    if len(argv) != 3:
        print(__doc__)
        return 2
    source, target = Path(argv[1]), Path(argv[2])
    data = json.loads(source.read_text(encoding="utf-8"))
    by_code = countries(data)
    svg = build_svg(by_code)
    target.write_text(svg, encoding="utf-8", newline="\n")
    print(f"{len(by_code)} countries, {len(svg.encode('utf-8'))} bytes -> {target}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
