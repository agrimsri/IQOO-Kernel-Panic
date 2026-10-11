"""Unit tests for the CLI's legal lenses (`traceveil lens`, `audit --lens`) and summary colours. No device needed.

The lens data is the app's own (core/src/main/resources/law); these tests also guard that the packaged copy matches it.
"""

import io
import json
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest import mock

from traceveil import EXIT_FINDINGS, EXIT_PASS, EXIT_USAGE, audit, cli, colors, laws

ROOT = Path(__file__).resolve().parents[2]
CORE_LAW = ROOT / "core/src/main/resources/law"
PACKAGED_LAW = ROOT / "src/traceveil/data/law"

LEAKY = {
    "schemaVersion": 1, "requestId": "cli-leaky", "auditId": "audit-1", "target": {"package": "com.leakyshop.demo", "label": "LeakyShop"},
    "score": 8, "band": "AT_RISK", "sessions": [],
    "findings": [
        {"check": "C2", "severity": "CRITICAL", "evidenceType": "OBSERVED", "title": "after refusal", "entities": ["AppsFlyer", "Mixpanel"]},
        {"check": "C1", "severity": "HIGH", "evidenceType": "OBSERVED", "title": "before consent", "entities": ["AppsFlyer"]},
        {"check": "C3", "severity": "HIGH", "evidenceType": "OBSERVED", "title": "pre-ticked", "entities": []},
        {"check": "C4", "severity": "HIGH", "evidenceType": "OBSERVED", "title": "no refuse", "entities": []},
        {"check": "C5", "severity": "MEDIUM", "evidenceType": "INFERRED", "title": "bundled", "entities": []},
        {"check": "C6", "severity": "MEDIUM", "evidenceType": "INFERRED", "title": "notice", "entities": []},
        {"check": "C7", "severity": "MEDIUM", "evidenceType": "INFERRED", "title": "withdraw", "entities": []},
        {"check": "C10", "severity": "MEDIUM", "evidenceType": "INFERRED", "title": "ad ids", "entities": ["AppsFlyer"]},
        {"check": "C8", "severity": "LOW", "evidenceType": "INFERRED", "title": "english only", "entities": []},
    ],
}


def lens(name):
    return laws.select(name)[0]


class DataTests(unittest.TestCase):
    def test_packaged_lens_data_is_the_apps_data(self):
        if not CORE_LAW.exists():
            self.skipTest("not running from a repo checkout")
        for name in ("profiles.json", "passages.json"):
            self.assertEqual((PACKAGED_LAW / name).read_bytes(), (CORE_LAW / name).read_bytes(),
                             f"{name} differs from core; run: python3 tools/sync_law_data.py")

    def test_lenses_match_the_app_enum_and_every_ref_has_a_passage(self):
        self.assertEqual(laws.ids(), ["INDIA", "EU", "UK", "US_CALIFORNIA", "US_CHILDREN", "BRAZIL", "SINGAPORE", "GLOBAL_BASELINE"])
        for p in laws.load_profiles():
            for check, m in p["checks"].items():
                self.assertIn(m["relation"], ("REQUIRED", "CONTEXT", "NOT_APPLICABLE"))
                for ref in m.get("refs", []):
                    if p.get("passageSource") != "DPDP_CORPUS":
                        self.assertIn(ref, laws.passages(), f"{p['id']} {check} cites unknown passage {ref}")

    def test_parse_accepts_app_names_and_short_aliases(self):
        for text, want in (("eu", "EU"), ("US-California", "US_CALIFORNIA"), ("us-ca", "US_CALIFORNIA"), ("in", "INDIA"),
                           ("coppa", "US_CHILDREN"), ("global_baseline", "GLOBAL_BASELINE"), ("mars", None)):
            self.assertEqual(laws.parse(text), want, text)


class LensTests(unittest.TestCase):
    def test_india_lens_equals_the_app_score(self):
        v = laws.view(LEAKY, lens("INDIA"))
        self.assertEqual((v["score"], v["band"]), (8, "AT_RISK"))  # 100 - (30 + 15*3 + 8/2*4 + 3/2) = 7.5 -> 8, half up like Kotlin

    def test_lens_keeps_severities_and_only_skips_not_applicable(self):
        v = laws.view(LEAKY, lens("UK"))
        self.assertEqual(v["notApplicable"], ["C8"])
        self.assertEqual([r["severity"] for r in v["findings"]], [f["severity"] for f in LEAKY["findings"]])
        self.assertEqual(v["score"], 9)  # India's 7.5 + 1.5 for the skipped inferred LOW C8 -> 9
        ca = laws.view(LEAKY, lens("US_CALIFORNIA"))
        self.assertEqual(ca["required"], ["C2"])
        self.assertEqual(ca["score"], 8)  # CONTEXT findings still count

    def test_unmapped_check_uses_the_lens_fallback(self):
        rep = {"findings": [{"check": "C99", "severity": "LOW", "evidenceType": "OBSERVED", "title": "new"}]}
        row = laws.view(rep, lens("EU"))["findings"][0]
        self.assertFalse(row["mapped"])
        self.assertEqual(row["relation"], lens("EU")["fallback"]["relation"])

    def test_inconclusive_evidence_is_inconclusive_under_every_lens(self):
        for extra in ({"check": "V0", "severity": "INFO"}, {"check": "U1", "severity": "INFO", "entities": ["AppsFlyer"]}):
            rep = {**LEAKY, "score": None, "band": "INCONCLUSIVE", "findings": LEAKY["findings"] + [extra]}  # what the app reports
            self.assertTrue(all(v["score"] is None for v in laws.assess_all(rep, "all")["lenses"]))
            rep = {k: v for k, v in rep.items() if k not in ("score", "band")}  # and the formula agrees on its own
            self.assertTrue(all(v["score"] is None and v["band"] == "INCONCLUSIVE" for v in laws.assess_all(rep, "all")["lenses"]))
        self.assertEqual(laws.score([{"check": "U1", "severity": "INFO", "entities": []}]), 100)

    def test_policy_lens_is_always_assessed_and_drives_the_view(self):
        block = laws.assess_all(LEAKY, "eu", policy="us-ca")
        self.assertEqual(block["policy"], "US_CALIFORNIA")
        self.assertEqual([v["id"] for v in block["lenses"]], ["US_CALIFORNIA", "EU"])
        with self.assertRaises(ValueError):
            laws.assess_all(LEAKY, "eu", policy="mars")
        with self.assertRaises(ValueError):
            laws.select("eu,mars")

    def test_india_policy_gives_the_same_exit_as_the_raw_report(self):
        for fail_on in ("critical", "high", "medium", "low"):
            self.assertEqual(audit.exit_for_report(LEAKY, fail_on, 0),
                             audit.exit_for_report(laws.policy_view(laws.assess_all(LEAKY, "all")), fail_on, 0))

    def test_saved_device_reports_match_the_app_score(self):
        reports = sorted((ROOT / ".traceveil/reports").glob("*.json"))
        if not reports:
            self.skipTest("no saved device reports in this checkout")
        for p in reports:
            rep = json.loads(p.read_text(encoding="utf-8"))
            if "findings" in rep and "score" in rep:
                self.assertEqual(laws.view(rep, lens("INDIA"))["score"], rep["score"], p.name)


class ColorTests(unittest.TestCase):
    def test_plain_style_adds_no_escape_codes(self):
        block = laws.assess_all(LEAKY, "all")
        self.assertNotIn("\x1b[", "\n".join(laws.table(block)) + laws.detail(block))

    def test_colors_pad_before_painting(self):
        st = colors.Style(True)
        self.assertEqual(st.severity("HIGH", 8), "\x1b[1;31mHIGH    \x1b[0m")
        self.assertEqual(st.band("READY"), "\x1b[1;32mREADY\x1b[0m")
        self.assertEqual(st.tracker("AppsFlyer"), "\x1b[1;36mAppsFlyer\x1b[0m")
        self.assertEqual(st.score(None, "INCONCLUSIVE"), "\x1b[1;35m-\x1b[0m")

    def test_env_switches(self):
        tty, pipe = mock.Mock(isatty=lambda: True), mock.Mock(isatty=lambda: False)
        for env, stream, want in (({}, tty, True), ({}, pipe, False), ({"NO_COLOR": "1"}, tty, False),
                                  ({"FORCE_COLOR": "1"}, pipe, True), ({"FORCE_COLOR": "0"}, pipe, False)):
            with mock.patch.dict("os.environ", env, clear=True):
                self.assertEqual(colors.enabled_for(stream), want, env)

    def test_audit_summary_names_trackers_and_shows_the_lenses(self):
        rep = {**LEAKY, "lenses": laws.assess_all(LEAKY, "in,eu")}
        text = audit.summary(rep, [], Path("x.json"))
        self.assertIn("trackers: AppsFlyer, Mixpanel", text)
        self.assertIn("Legal lenses", text)
        self.assertIn("EU", text)
        self.assertIn("inferred", text)


class LensCliTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        reports = self.dir / "reports"
        reports.mkdir()
        (reports / "cli-leaky.json").write_text(json.dumps(LEAKY), encoding="utf-8")
        self.cfg = self.dir / "traceveil.toml"
        self.cfg.write_text(f'[output]\ndir = "{reports.as_posix()}"\n', encoding="utf-8")

    def tearDown(self):
        self.tmp.cleanup()

    def run_cli(self, *argv):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err), mock.patch("traceveil.adb.connect", side_effect=AssertionError("no device")):
            code = cli.main(["--config", str(self.cfg), *argv])
        return code, out.getvalue(), err.getvalue()

    def test_newest_report_is_used_by_default(self):
        code, out, _ = self.run_cli("lens")
        self.assertEqual(code, EXIT_FINDINGS)
        self.assertIn("cli-leaky", out)
        self.assertIn("US_CALIFORNIA", out)
        self.assertEqual(self.run_cli("laws")[0], EXIT_FINDINGS)  # alias

    def test_json_detail_list_and_errors(self):
        _, out, _ = self.run_cli("lens", "--report", "cli-leaky", "--lens", "eu", "--json")
        self.assertEqual([v["id"] for v in json.loads(out)["lenses"]], ["INDIA", "EU"])
        _, out, _ = self.run_cli("lens", "--report", "cli-leaky", "--profile", "BRAZIL", "--detail")
        self.assertIn("required", out)
        self.assertIn("https://", out)
        self.assertEqual(self.run_cli("lens", "--list")[0], EXIT_PASS)
        self.assertEqual(self.run_cli("lens", "--report", "cli-leaky", "--lens", "mars")[0], EXIT_USAGE)
        self.assertEqual(self.run_cli("lens", "--report", "nope")[0], EXIT_USAGE)

    def test_bad_config_policy_is_rejected(self):
        self.cfg.write_text('[audit]\npolicy = "mars"\n', encoding="utf-8")
        self.assertEqual(self.run_cli("lens", "--list")[0], EXIT_USAGE)


if __name__ == "__main__":
    unittest.main()
