"""Unit tests for the fix plan (`traceveil fix`, `fixPlan` in audit reports). No device needed.

The fix templates and tracker catalogue are the app's own (core/src/main/resources); these tests also guard the copies.
"""

import io
import json
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest import mock

from traceveil import EXIT_FINDINGS, EXIT_USAGE, audit, cli, fixes, laws

ROOT = Path(__file__).resolve().parents[2]
CORE = ROOT / "core/src/main/resources"
PACKAGED = ROOT / "src/traceveil/data"

LEAKY = {
    "schemaVersion": 1, "requestId": "cli-leaky", "auditId": "audit-1", "target": {"package": "com.leakyshop.demo", "label": "LeakyShop"},
    "score": 8, "band": "AT_RISK", "sessions": [],
    "findings": [
        {"check": "C1", "severity": "HIGH", "evidenceType": "OBSERVED", "title": "before consent",
         "entities": ["AppsFlyer", "Microsoft Clarity"], "plans": ["SILENT"]},
        {"check": "C2", "severity": "CRITICAL", "evidenceType": "OBSERVED", "title": "after refusal", "entities": ["AppsFlyer", "Mixpanel"]},
        {"check": "C4", "severity": "HIGH", "evidenceType": "OBSERVED", "title": "no refuse", "entities": []},
        {"check": "C8", "severity": "LOW", "evidenceType": "INFERRED", "title": "english only", "entities": []},
        {"check": "V1", "severity": "INFO", "evidenceType": "OBSERVED", "title": "coverage note", "entities": []},
    ],
}


def plan(rep=LEAKY):
    return fixes.build(rep, laws.assess_all(rep, "all"))


class DataTests(unittest.TestCase):
    def test_packaged_fix_and_tracker_data_is_the_apps_data(self):
        if not CORE.exists():
            self.skipTest("not running from a repo checkout")
        for name in ("fixes/fix_templates.json", "trackers/trackers.json"):
            self.assertEqual((PACKAGED / name).read_bytes(), (CORE / name).read_bytes(),
                             f"{name} differs from core; run: python3 tools/sync_law_data.py")

    def test_every_sdk_key_has_a_template_or_none(self):
        ids = {t["id"] for t in fixes.templates()}
        for t in fixes.trackers().values():
            if t.get("sdkKey"):
                self.assertIn(t["sdkKey"], ids, t["entity"])


class PlanTests(unittest.TestCase):
    def test_issues_are_ordered_by_severity_and_info_is_a_note(self):
        p = plan()
        self.assertEqual([i["check"] for i in p["issues"]], ["C2", "C1", "C4", "C8"])
        self.assertEqual(p["notes"], [{"check": "V1", "title": "coverage note"}])
        self.assertEqual(p["schema"], fixes.SCHEMA)

    def test_fixes_are_picked_like_the_apps_fix_catalog(self):
        p = plan()
        by = {i["check"]: i for i in p["issues"]}
        self.assertEqual(by["C2"]["fixIds"], ["appsflyer", "mixpanel"])
        self.assertEqual(by["C4"]["fixIds"], ["ui-reject"])
        self.assertEqual([u["entity"] for u in by["C1"]["untemplatedTrackers"]], ["Microsoft Clarity"])
        fx = {f["id"]: f for f in p["fixes"]}
        self.assertEqual(fx["appsflyer"]["resolves"], ["C2", "C1"])
        self.assertIn("com.appsflyer", fx["appsflyer"]["locate"]["codePrefixes"])
        self.assertEqual(fx["appsflyer"]["trackers"], ["AppsFlyer"])

    def test_country_view_comes_from_the_lenses(self):
        by = {i["check"]: i for i in plan()["issues"]}
        self.assertIn("INDIA", by["C8"]["requiredIn"])
        self.assertNotIn("EU", by["C8"]["requiredIn"])
        eu = next(j for j in by["C2"]["jurisdictions"] if j["lens"] == "EU")
        self.assertEqual(eu["relation"], "REQUIRED")
        self.assertIn("DE", eu["countries"])
        self.assertTrue(all(c["url"].startswith("https://") for c in eu["citations"]))
        tracker = next(v for v in by["C2"]["trackers"] if v["entity"] == "AppsFlyer")
        self.assertEqual(tracker["companyCountry"], "IL")

    def test_plan_does_not_change_the_lens_block(self):
        block = laws.assess_all(LEAKY, "all")
        before = json.dumps(block, sort_keys=True)
        fixes.build(LEAKY, block)
        self.assertEqual(json.dumps(block, sort_keys=True), before)

    def test_markdown_is_a_task_list_with_code(self):
        md = fixes.markdown(plan())
        self.assertIn("## Instructions for the agent", md)
        self.assertIn("### 1. [ ] ", md)
        self.assertIn("```kotlin", md)
        self.assertIn("Microsoft Clarity", md)
        self.assertIn("| EU / EEA | 30 countries | REQUIRED |", md)
        self.assertIn("Done when:", md)

    def test_audit_summary_shows_countries_and_fixes(self):
        rep = {**LEAKY, "lenses": laws.assess_all(LEAKY, "in"), "fixPlan": plan()}
        text = audit.summary(rep, [], Path("x.json"))
        self.assertIn("required in: IN, EU, GB", text)
        self.assertIn("[ui-reject]", text)
        self.assertIn("fixPlan", text)


class FixCliTests(unittest.TestCase):
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

    def test_text_json_md_and_out(self):
        code, out, _ = self.run_cli("fix")
        self.assertEqual(code, EXIT_FINDINGS)
        self.assertIn("By country", out)
        _, out, _ = self.run_cli("fix", "--json")
        full = json.loads(out)
        self.assertEqual(full["findings"], LEAKY["findings"])
        self.assertEqual(full["fixPlan"]["summary"]["issues"], 4)
        js = self.dir / "report.json"
        self.run_cli("fix", "--out", str(js))
        self.assertIn("fixPlan", json.loads(js.read_text(encoding="utf-8")))
        md = self.dir / "FIXES.md"
        _, out, _ = self.run_cli("fixes", "--report", "cli-leaky", "--format", "md", "--out", str(md))
        self.assertTrue(out.startswith("# Traceveil fix plan"))
        self.assertEqual(md.read_text(encoding="utf-8").strip(), out.strip())
        self.assertEqual(self.run_cli("fix", "--report", "nope")[0], EXIT_USAGE)


if __name__ == "__main__":
    unittest.main()
