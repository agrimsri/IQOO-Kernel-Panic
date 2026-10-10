"""Tests for the Natural Earth to world.svg converter, on tiny synthetic GeoJSON (no download needed)."""

import importlib.util
import math
from pathlib import Path
import re
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "naturalearth_world_svg.py"
spec = importlib.util.spec_from_file_location("naturalearth_world_svg", SCRIPT)
ne = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ne)


def square(lon, lat, size=2.0):
    """A closed counter-clockwise square ring (lon, lat), GeoJSON style."""
    return [[lon, lat], [lon + size, lat], [lon + size, lat + size], [lon, lat + size], [lon, lat]]


def feature(iso_eh, a3, *polygons, iso=None, name="X"):
    geometry = (
        {"type": "Polygon", "coordinates": polygons[0]}
        if len(polygons) == 1
        else {"type": "MultiPolygon", "coordinates": list(polygons)}
    )
    return {
        "type": "Feature",
        "properties": {"ISO_A2": iso if iso is not None else iso_eh, "ISO_A2_EH": iso_eh, "ADM0_A3": a3, "ADMIN": name},
        "geometry": geometry,
    }


def collection(*features):
    return {"type": "FeatureCollection", "features": list(features)}


def absolute_points(d):
    """Replays the converter's own path grammar (M x,y l dx,dy ... z) back to absolute points per ring."""
    rings = []
    for ring in re.findall(r"M([^z]*)z", d):
        head, _, rest = ring.partition("l")
        x, y = (float(v) for v in head.split(","))
        points = [(x, y)]
        for pair in rest.split():
            dx, dy = (float(v) for v in pair.split(","))
            x, y = x + dx, y + dy
            points.append((x, y))
        rings.append(points)
    return rings


def signed_area(points):
    return sum(x1 * y2 - x2 * y1 for (x1, y1), (x2, y2) in zip(points, points[1:] + points[:1])) / 2


class IsoCodeTest(unittest.TestCase):
    def test_extended_code_wins_over_a_missing_iso_code(self):
        self.assertEqual("FR", ne.iso_code({"ISO_A2": "-99", "ISO_A2_EH": "FR", "ADM0_A3": "FRA"}))
        self.assertEqual("TW", ne.iso_code({"ISO_A2": "CN-TW", "ISO_A2_EH": "TW", "ADM0_A3": "TWN"}))

    def test_areas_without_an_iso_code_join_the_iso_3166_country_that_includes_them(self):
        self.assertEqual("SO", ne.iso_code({"ISO_A2": "-99", "ISO_A2_EH": "-99", "ADM0_A3": "SOL"}))
        self.assertEqual("CY", ne.iso_code({"ISO_A2": "-99", "ISO_A2_EH": "-99", "ADM0_A3": "CYN"}))

    def test_an_unknown_area_without_a_code_is_rejected_not_guessed(self):
        with self.assertRaises(ValueError):
            ne.iso_code({"ISO_A2": "-99", "ISO_A2_EH": "-99", "ADM0_A3": "ZZZ"})


class CountriesTest(unittest.TestCase):
    def test_antarctica_is_dropped_and_merged_areas_join_their_country(self):
        data = collection(
            feature("AQ", "ATA", [square(0, -80)]),
            feature("SO", "SOM", [square(45, 2)]),
            feature("-99", "SOL", [square(45, 9)]),
        )
        countries = ne.countries(data)
        self.assertEqual(["SO"], sorted(countries))
        self.assertEqual(2, len(countries["SO"]))

    def test_french_guiana_and_svalbard_get_their_own_iso_codes(self):
        data = collection(
            feature("FR", "FRA", [square(-53, 3)], [square(2, 46)], iso="-99"),
            feature("NO", "NOR", [square(10, 60)], [square(15, 77)], iso="-99"),
        )
        countries = ne.countries(data)
        self.assertEqual(["FR", "GF", "NO", "SJ"], sorted(countries))
        self.assertEqual(-53, countries["GF"][0][0][0][0])
        self.assertEqual(77, countries["SJ"][0][0][0][1])
        self.assertEqual(1, len(countries["FR"]))
        self.assertEqual(1, len(countries["NO"]))

    def test_crimea_follows_iso_3166_2_and_is_drawn_with_ukraine(self):
        data = collection(
            feature("RU", "RUS", [square(40, 55)], [square(33, 44.5)]),
            feature("UA", "UKR", [square(30, 48)]),
        )
        countries = ne.countries(data)
        self.assertEqual(1, len(countries["RU"]))
        self.assertEqual(2, len(countries["UA"]))

    def test_islands_lying_wholly_west_of_the_map_edge_are_drawn_east_of_180(self):
        data = collection(feature("FJ", "FJI", [square(178, -17, 1.5)], [square(-180, -16.5, 0.2)]))
        lons = [p[0] for poly in ne.countries(data)["FJ"] for p in poly[0]]
        self.assertTrue(all(lon > 170 for lon in lons), lons)
        self.assertAlmostEqual(180.2, max(lons))


class ProjectionTest(unittest.TestCase):
    def test_mercator_puts_the_bounds_on_the_edges(self):
        p = ne.Projection(-170.0, 190.0, -56.0, 84.0, width=1000.0)
        self.assertEqual((0.0, 0.0), p.project(-170.0, 84.0))
        x, y = p.project(190.0, -56.0)
        self.assertAlmostEqual(1000.0, x)
        self.assertAlmostEqual(p.height, y)

    def test_mercator_stretches_high_latitudes(self):
        p = ne.Projection(-180.0, 180.0, -60.0, 60.0, width=360.0)
        _, equator = p.project(0.0, 0.0)
        _, north30 = p.project(0.0, 30.0)
        _, north60 = p.project(0.0, 60.0)
        self.assertAlmostEqual(p.height / 2, equator)
        self.assertGreater(north30 - north60, equator - north30)
        # One degree of longitude is one unit wide everywhere at this width; at the equator a degree of latitude is too.
        self.assertAlmostEqual(math.degrees(math.log(math.tan(math.pi / 4 + math.radians(60) / 2))) * 2, p.height)


class SvgTest(unittest.TestCase):
    def test_svg_has_the_size_offset_and_one_path_per_code_that_the_app_parser_reads(self):
        data = collection(feature("IN", "IND", [square(70, 10, 20)]), feature("US", "USA", [square(-120, 30, 30)]))
        svg = ne.build_svg(ne.countries(data), width=500.0, pad=0.5, decimals=2)
        self.assertRegex(svg, r'<svg\b[^>]*\bwidth="501(\.0+)?"[^>]*\bheight="[0-9.]+"')
        self.assertIn('<g transform="translate(0.5,0.5)">', svg)
        self.assertEqual(["IN", "US"], re.findall(r'<path id="([A-Z]{2})"', svg))
        self.assertNotIn("amcharts", svg.lower())

    def test_path_replays_to_the_projected_points_within_rounding(self):
        data = collection(feature("IN", "IND", [square(70, 10, 20)]), feature("US", "USA", [square(-120, 30, 30)]))
        countries = ne.countries(data)
        proj = ne.Projection.fit(countries, width=500.0)
        d = ne.path_data(countries["IN"], proj, decimals=2)
        rings = absolute_points(d)
        self.assertEqual(1, len(rings))
        expected = [proj.project(lon, lat) for lon, lat in countries["IN"][0][0][:-1]]
        self.assertEqual(len(expected), len(rings[0]))
        for got, want in zip(sorted(rings[0]), sorted(expected)):
            self.assertAlmostEqual(want[0], got[0], delta=0.006)
            self.assertAlmostEqual(want[1], got[1], delta=0.006)

    def test_holes_wind_opposite_to_their_outer_ring_so_nonzero_fill_leaves_them_empty(self):
        outer = square(10, -30, 10)
        hole = square(13, -27, 2)  # wound the same way as the outer ring: the converter must reverse it
        data = collection(feature("ZA", "ZAF", [outer, hole]))
        countries = ne.countries(data)
        proj = ne.Projection.fit(countries, width=100.0)
        rings = absolute_points(ne.path_data(countries["ZA"], proj, decimals=2))
        self.assertEqual(2, len(rings))
        self.assertLess(signed_area(rings[0]) * signed_area(rings[1]), 0)

    def test_pieces_of_one_country_that_share_a_border_are_drawn_as_one_outline_with_no_seam(self):
        # Somaliland joining Somalia, Crimea joining Ukraine, Chukotka meeting the rest of Russia at 180 degrees.
        data = collection(feature("SO", "SOM", [square(44, 2, 2)]), feature("-99", "SOL", [square(46, 2, 2)]))
        countries = ne.countries(data)
        proj = ne.Projection.fit(countries, width=400.0)
        rings = absolute_points(ne.path_data(countries["SO"], proj, decimals=2))
        self.assertEqual(1, len(rings))
        self.assertEqual(6, len(rings[0]))  # the two shared corners stay as points on the outline
        seam_x = proj.project(46, 2)[0]
        xs = [round(x, 2) for x, _ in rings[0]]
        self.assertEqual(2, xs.count(round(seam_x, 2)))  # only the two corners, no edge running along the seam

    def test_points_that_round_onto_each_other_are_dropped_and_specks_vanish(self):
        speck = [[0.0, 0.0], [0.00001, 0.0], [0.00001, 0.00001], [0.0, 0.0]]
        data = collection(feature("IN", "IND", [square(70, 10, 20)], [speck]))
        countries = ne.countries(data)
        proj = ne.Projection.fit(countries, width=100.0)
        self.assertEqual(1, len(absolute_points(ne.path_data(countries["IN"], proj, decimals=2))))


if __name__ == "__main__":
    unittest.main()
