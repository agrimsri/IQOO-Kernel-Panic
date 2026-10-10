"""Unit tests for the jurisdiction layer (`traceveil laws`, `audit --jurisdictions`) and summary colours. No device needed."""

import io
import json
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest import mock

from traceveil import EXIT_FINDINGS, EXIT_PASS, EXIT_USAGE, audit, cli, colors, laws

SEVERITIES = set(laws.WEIGHTS) | {"as-reported", None}

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


def without_score(rep):
    return {k: v for k, v in rep.items() if k not in ("score", "band")}


class ProfileTests(unittest.TestCase):
    def test_profiles_are_well_formed(self):
        profiles = laws.load_profiles()
        self.assertEqual([p["id"] for p in profiles], ["in", "eu", "uk", "us-ca", "br"])
        for p in profiles:
            self.assertTrue(p["sources"] and all(s.startswith("https://") for s in p["sources"]), p["id"])
            for check, rule in p["checks"].items():
                self.assertIn(rule["severity"], SEVERITIES, f"{p['id']} {check}")
                self.assertTrue(rule["refs"], f"{p['id']} {check} has no provision")
            if p["id"] == "in":
                self.assertTrue(all(r["severity"] == "as-reported" for r in p["checks"].values()))
            else:
                self.assertNotIn("as-reported", [r["severity"] for r in p["checks"].values()])

    def test_india_formula_reproduces_the_app_score(self):
        a = laws.assess(without_score(LEAKY), laws.select("in")[0])
        self.assertEqual(a["score"], 8)  # 100 - (30 + 15*3 + 8/2*4 + 3/2) = 7.5 -> 8, rounded half up like Kotlin
        self.assertEqual(a["band"], "AT_RISK")
        self.assertEqual(laws.assess(LEAKY, laws.select("in")[0])["score"], 8)

    def test_other_laws_reweight_the_same_evidence(self):
        by = {a["id"]: a for a in laws.assess_all(LEAKY, "all")["assessments"]}
        self.assertEqual({r["check"] for r in by["eu"]["findings"]}, {f["check"] for f in LEAKY["findings"]})
        eu_c8 = next(r for r in by["eu"]["findings"] if r["check"] == "C8")
        self.assertFalse(eu_c8["scored"])  # GDPR has no language list
        ca_c1 = next(r for r in by["us-ca"]["findings"] if r["check"] == "C1")
        self.assertEqual((ca_c1["severity"], ca_c1["appSeverity"]), ("MEDIUM", "HIGH"))  # opt-out model
        self.assertGreater(by["us-ca"]["score"], by["eu"]["score"])
        self.assertIn("Civ. Code 1798.120", next(r for r in by["us-ca"]["findings"] if r["check"] == "C2")["refs"])

    def test_inconclusive_evidence_is_inconclusive_everywhere(self):
        for extra in ({"check": "V0", "severity": "INFO"}, {"check": "U1", "severity": "INFO", "entities": ["AppsFlyer"]}):
            rep = {**without_score(LEAKY), "findings": LEAKY["findings"] + [extra]}
            self.assertTrue(all(a["score"] is None and a["band"] == "INCONCLUSIVE" for a in laws.assess_all(rep, "all")["assessments"]))
        u1_empty = {**without_score(LEAKY), "findings": [{"check": "U1", "severity": "INFO", "entities": []}]}
        self.assertEqual(laws.assess(u1_empty, laws.select("eu")[0])["score"], 100)

    def test_unmapped_check_keeps_the_app_severity(self):
        rep = {"findings": [{"check": "C99", "severity": "LOW", "evidenceType": "OBSERVED", "title": "new"}]}
        row = laws.assess(rep, laws.select("eu")[0])["findings"][0]
        self.assertEqual((row["severity"], row["mapped"]), ("LOW", False))

    def test_selection_policy_and_view(self):
        self.assertEqual([p["id"] for p in laws.select("eu, us-ca")], ["eu", "us-ca"])
        with self.assertRaises(ValueError):
            laws.select("eu,mars")
        block = laws.assess_all(LEAKY, "eu", policy="us-ca")
        self.assertEqual([a["id"] for a in block["assessments"]], ["us-ca", "eu"])  # policy is always assessed
        view = laws.policy_view(block)
        self.assertEqual(view["score"], block["assessments"][0]["score"])
        self.assertNotIn(None, [f["severity"] for f in view["findings"]])

    def test_policy_view_for_india_gives_the_same_exit_as_the_raw_report(self):
        for fail_on in ("critical", "high", "medium", "low"):
            self.assertEqual(audit.exit_for_report(LEAKY, fail_on, 0),
                             audit.exit_for_report(laws.policy_view(laws.assess_all(LEAKY, "all")), fail_on, 0))


class ColorTests(unittest.TestCase):
    def test_plain_style_adds_no_escape_codes(self):
        block = laws.assess_all(LEAKY, "all")
        text = "\n".join(laws.table(block)) + laws.detail(block)
        self.assertNotIn("\x1b[", text)

    def test_colors_pad_before_painting(self):
        st = colors.Style(True)
        self.assertEqual(st.severity("HIGH", 8), "\x1b[1;31mHIGH    \x1b[0m")
        self.assertEqual(st.band("READY"), "\x1b[1;32mREADY\x1b[0m")
        self.assertEqual(st.tracker("AppsFlyer"), "\x1b[1;36mAppsFlyer\x1b[0m")
        self.assertEqual(st.score(None, "INCONCLUSIVE"), "\x1b[1;35m-\x1b[0m")

    def test_env_switches(self):
        tty = mock.Mock(isatty=lambda: True)
        pipe = mock.Mock(isatty=lambda: False)
        with mock.patch.dict("os.environ", {}, clear=True):
            self.assertTrue(colors.enabled_for(tty))
            self.assertFalse(colors.enabled_for(pipe))
        with mock.patch.dict("os.environ", {"NO_COLOR": "1"}, clear=True):
            self.assertFalse(colors.enabled_for(tty))
        with mock.patch.dict("os.environ", {"FORCE_COLOR": "1"}, clear=True):
            self.assertTrue(colors.enabled_for(pipe))

    def test_audit_summary_names_trackers_and_shows_the_table(self):
        rep = {**LEAKY, "jurisdictions": laws.assess_all(LEAKY, "in,eu")}
        text = audit.summary(rep, [], Path("x.json"))
        self.assertIn("trackers: AppsFlyer, Mixpanel", text)
        self.assertIn("By jurisdiction", text)
        self.assertIn("EU", text)
        self.assertIn("inferred", text)


class LawsCliTests(unittest.TestCase):
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

    def test_newest_report_is_used_by_default_and_policy_sets_the_exit_code(self):
        code, out, _ = self.run_cli("laws")
        self.assertEqual(code, EXIT_FINDINGS)
        self.assertIn("cli-leaky", out)
        self.assertIn("US-CA", out)

    def test_json_detail_list_and_errors(self):
        code, out, _ = self.run_cli("laws", "--report", "cli-leaky", "--jurisdictions", "eu", "--json")
        block = json.loads(out)
        self.assertEqual([a["id"] for a in block["assessments"]], ["in", "eu"])
        code, out, _ = self.run_cli("laws", "--report", "cli-leaky", "--jurisdictions", "br", "--detail")
        self.assertIn("LGPD Art 8 §4", out)
        self.assertEqual(self.run_cli("laws", "--list")[0], EXIT_PASS)
        self.assertEqual(self.run_cli("laws", "--report", "cli-leaky", "--jurisdictions", "mars")[0], EXIT_USAGE)
        self.assertEqual(self.run_cli("laws", "--report", "nope")[0], EXIT_USAGE)

    def test_bad_config_policy_is_rejected(self):
        self.cfg.write_text('[audit]\npolicy = "mars"\n', encoding="utf-8")
        self.assertEqual(self.run_cli("laws", "--list")[0], EXIT_USAGE)


if __name__ == "__main__":
    unittest.main()
