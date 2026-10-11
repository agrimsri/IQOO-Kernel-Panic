"""Unit tests for `traceveil sdks`. APKs are built in memory from synthetic DEX files; no device or Android SDK needed."""

import io
import json
import struct
import tempfile
import unittest
import zipfile
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest import mock

from traceveil import EXIT_DEVICE, EXIT_FINDINGS, EXIT_INCONCLUSIVE, EXIT_PASS, EXIT_USAGE, cli, sdks


def uleb(n: int) -> bytes:
    out = bytearray()
    while True:
        b, n = n & 0x7F, n >> 7
        out.append(b | (0x80 if n else 0))
        if not n:
            return bytes(out)


def make_dex(class_names: list[str]) -> bytes:
    """Minimal DEX with one string, type and class_def per class: enough for the header/table walk in sdks.dex_classes."""
    descs = [("L" + c.replace(".", "/") + ";").encode() for c in class_names]
    n = len(descs)
    string_ids_off = 0x70
    type_ids_off = string_ids_off + 4 * n
    class_defs_off = type_ids_off + 4 * n
    data_off = class_defs_off + 32 * n
    data, offsets = bytearray(), []
    for d in descs:
        offsets.append(data_off + len(data))
        data += uleb(len(d)) + d + b"\0"
    header = bytearray(0x70)
    header[:8] = b"dex\n035\0"
    struct.pack_into("<IIII", header, 0x38, n, string_ids_off, n, type_ids_off)
    struct.pack_into("<II", header, 0x60, n, class_defs_off)
    string_ids = b"".join(struct.pack("<I", o) for o in offsets)
    type_ids = b"".join(struct.pack("<I", i) for i in range(n))
    class_defs = b"".join(struct.pack("<I", i) + bytes(28) for i in range(n))
    return bytes(header) + string_ids + type_ids + class_defs + bytes(data)


APP = ["com.example.shop.MainActivity", "com.example.shop.Cart"]
APPSFLYER = ["com.appsflyer.AppsFlyerLib", "com.appsflyer.internal.AFa1"]
SENTRY = ["io.sentry.Sentry"]
ADMOB = ["com.google.android.gms.ads.MobileAds"]

REPORT = {
    "schemaVersion": 1, "requestId": "cli-1", "auditId": "audit-1", "target": {"package": "com.example.shop", "label": "Shop"},
    "score": 21, "band": "AT_RISK", "findings": [{"check": "C1", "severity": "HIGH", "entities": ["AppsFlyer"]}], "sessions": [],
    "observedTrackers": [
        {"entity": "AppsFlyer", "category": "ATTRIBUTION", "lookups": 4, "phases": ["AFTER_REJECT", "BEFORE_CONSENT"]},
        {"entity": "Mixpanel", "category": "ANALYTICS", "lookups": 2, "phases": ["AFTER_ACCEPT"]},
    ],
}

DUMPSYS = """Packages:
  Package [com.example.shop] (abc):
    requested permissions:
      android.permission.INTERNET
      android.permission.ACCESS_FINE_LOCATION
    install permissions:
      android.permission.INTERNET: granted=true
"""


class SdkTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def apk(self, *dexes: list[str], name="app.apk") -> Path:
        p = self.dir / name
        with zipfile.ZipFile(p, "w") as z:
            z.writestr("AndroidManifest.xml", b"\x03\x00")
            for i, classes in enumerate(dexes):
                z.writestr("classes.dex" if i == 0 else f"classes{i + 1}.dex", make_dex(classes))
        return p

    def run_cli(self, *argv):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = cli.main(list(argv))
        return code, out.getvalue(), err.getvalue()

    def test_dex_reader_returns_defined_classes(self):
        self.assertEqual(sdks.dex_classes(make_dex(APP + SENTRY)), APP + SENTRY)

    def test_malformed_dex_is_a_clean_error(self):
        with self.assertRaises(sdks.ApkError):
            sdks.dex_classes(b"not a dex")
        with self.assertRaises(sdks.ApkError):
            sdks.dex_classes(make_dex(APP)[:0x80])

    def test_signatures_match_package_prefix_not_substring(self):
        sigs = sdks.load_signatures()
        found = sdks.match_sdks(APP + APPSFLYER + ["com.appsflyerclone.Fake", "org.com.appsflyer.X"], sigs)
        self.assertEqual(list(found), ["AppsFlyer"])
        self.assertEqual(found["AppsFlyer"]["classes"], 2)

    def test_multidex_is_scanned(self):
        inv = sdks.build(self.apk(APP, SENTRY + APPSFLYER), sdks.load_signatures())
        self.assertEqual(inv["apk"]["dexFiles"], 2)
        self.assertEqual(sorted(r["entity"] for r in inv["sdks"]), ["AppsFlyer", "Sentry"])
        self.assertTrue(all(r["status"] == "in-apk-only" and r["observed"] is None for r in inv["sdks"]))
        self.assertIsNone(inv["audit"])

    def test_static_and_live_are_joined_into_three_groups(self):
        inv = sdks.build(self.apk(APP + APPSFLYER + SENTRY), sdks.load_signatures(), REPORT)
        by = {r["entity"]: r for r in inv["sdks"]}
        self.assertEqual(by["AppsFlyer"]["status"], "in-apk-and-live")
        self.assertEqual(by["AppsFlyer"]["observed"], {"lookups": 4, "phases": ["AFTER_REJECT", "BEFORE_CONSENT"]})
        self.assertEqual(by["Sentry"]["status"], "in-apk-only")
        self.assertEqual(by["Mixpanel"]["status"], "live-only")
        self.assertTrue(by["Mixpanel"]["evidence"].startswith("Inferred"))
        self.assertEqual(inv["counts"], {"in-apk-and-live": 1, "in-apk-only": 1, "live-only": 1})
        self.assertEqual(inv["audit"]["liveSource"], "observedTrackers")
        acts = " ".join(by["AppsFlyer"]["actions"])
        self.assertIn("before any consent", acts)
        self.assertIn("after the user refused", acts)
        self.assertEqual(sdks.needs_attention(inv), ["AppsFlyer"])

    def test_older_report_falls_back_to_finding_entities(self):
        old = {k: v for k, v in REPORT.items() if k != "observedTrackers"}
        inv = sdks.build(self.apk(APP + APPSFLYER), sdks.load_signatures(), old)
        self.assertEqual(inv["audit"]["liveSource"], "findings")
        row = inv["sdks"][0]
        self.assertEqual((row["entity"], row["status"], row["category"]), ("AppsFlyer", "in-apk-and-live", "ATTRIBUTION"))
        live_only = sdks.build(self.apk(APP), sdks.load_signatures(), old)["sdks"][0]
        self.assertEqual((live_only["status"], live_only["category"]), ("live-only", "ATTRIBUTION"))
        self.assertEqual(sdks.needs_attention(inv), [])  # phases unknown, so no consent claim
        self.assertIn("older report", sdks.summary(inv))

    def test_location_hint_only_for_ad_sdks_when_location_is_requested(self):
        perms = sdks.parse_requested_permissions(DUMPSYS)
        self.assertEqual(perms, ["android.permission.ACCESS_FINE_LOCATION", "android.permission.INTERNET"])
        apk = self.apk(APP + ADMOB + SENTRY)
        with_loc = {r["entity"]: r for r in sdks.build(apk, sdks.load_signatures(), None, perms)["sdks"]}
        self.assertTrue(any("location" in a for a in with_loc["Google Ads / AdMob"]["actions"]))
        self.assertFalse(any("location" in a for a in with_loc["Sentry"]["actions"]))
        no_loc = {r["entity"]: r for r in sdks.build(apk, sdks.load_signatures(), None, ["android.permission.INTERNET"])["sdks"]}
        self.assertFalse(any("location" in a for a in no_loc["Google Ads / AdMob"]["actions"]))

    def test_obfuscation_share(self):
        self.assertEqual(sdks.obfuscated_share(["a.b.C", "a.Bc", "com.example.X", "Top"]), 0.5)

    def test_every_signature_entity_names_a_catalogue_tracker(self):
        catalogue = Path(__file__).resolve().parents[2] / "core/src/main/resources/trackers/trackers.json"
        if not catalogue.exists():
            self.skipTest("tracker catalogue not present (installed outside the repo)")
        names = {e["entity"] for e in json.loads(catalogue.read_text(encoding="utf-8"))}
        self.assertEqual({s["entity"] for s in sdks.load_signatures()} - names, set())

    def test_cli_static_only_needs_no_device(self):
        with mock.patch("traceveil.adb.connect", side_effect=AssertionError("no device should be used")):
            code, out, _ = self.run_cli("sdks", "--apk", str(self.apk(APP + SENTRY)))
        self.assertEqual(code, EXIT_PASS)
        self.assertIn("in APK only", out)
        self.assertIn("Sentry", out)

    def test_cli_report_by_request_id_json_out_and_consent_gate(self):
        reports = self.dir / "reports"
        reports.mkdir()
        (reports / "cli-1.json").write_text(json.dumps(REPORT), encoding="utf-8")
        cfg = self.dir / "traceveil.toml"
        cfg.write_text(f'[output]\ndir = "{reports.as_posix()}"\n', encoding="utf-8")
        out_file = self.dir / "inv.json"
        apk = str(self.apk(APP + APPSFLYER))
        code, out, _ = self.run_cli("--config", str(cfg), "sdks", "--apk", apk, "--report", "cli-1", "--json", "--out", str(out_file))
        self.assertEqual(code, EXIT_PASS)
        self.assertEqual(json.loads(out), json.loads(out_file.read_text(encoding="utf-8")))
        code, _, err = self.run_cli("--config", str(cfg), "sdks", "--apk", apk, "--report", "cli-1", "--fail-on-consent")
        self.assertEqual(code, EXIT_FINDINGS)
        self.assertIn("AppsFlyer", err)

    def test_cli_errors(self):
        self.assertEqual(self.run_cli("sdks", "--apk", str(self.apk(APP)), "--report", "missing-id")[0], EXIT_USAGE)
        bad = self.dir / "bad.apk"
        bad.write_bytes(b"not a zip")
        self.assertEqual(self.run_cli("sdks", "--apk", str(bad))[0], EXIT_INCONCLUSIVE)
        nodex = self.dir / "nodex.apk"
        with zipfile.ZipFile(nodex, "w") as z:
            z.writestr("AndroidManifest.xml", b"")
        self.assertEqual(self.run_cli("sdks", "--apk", str(nodex))[0], EXIT_INCONCLUSIVE)

    def test_cli_package_reads_permissions_from_device(self):
        dev = mock.Mock()
        dev.shell.return_value = DUMPSYS
        with mock.patch("traceveil.adb.connect", return_value=dev):
            code, out, _ = self.run_cli("sdks", "--apk", str(self.apk(APP + ADMOB)), "--package", "com.example.shop", "--json")
        self.assertEqual(code, EXIT_PASS)
        self.assertIn("android.permission.ACCESS_FINE_LOCATION", json.loads(out)["permissions"]["requested"])
        dev.shell.return_value = "Unable to find package: com.nope.app"
        with mock.patch("traceveil.adb.connect", return_value=dev):
            self.assertEqual(self.run_cli("sdks", "--apk", str(self.apk(APP)), "--package", "com.nope.app")[0], EXIT_DEVICE)


if __name__ == "__main__":
    unittest.main()
