"""A kept-data audit whose consent history is unknown is a bounded range, not "inconclusive" (core HistoryBounds)."""

import unittest
from pathlib import Path

from traceveil import EXIT_FINDINGS, EXIT_PASS, audit, cli, laws

BOUNDS = {"lowScore": 65, "lowBand": "NEEDS_WORK", "highScore": 99, "highBand": "READY"}
U1 = {"check": "U1", "severity": "INFO", "evidenceType": "OBSERVED", "title": "Consent history unknown — app kept its data",
      "entities": ["AppLovin", "Vungle / Liftoff"]}
C13 = {"check": "C13", "severity": "LOW", "evidenceType": "INFERRED", "title": "SDK data collection on by default in the app's manifest",
       "entities": ["Google Analytics for Firebase"]}
RANGE = {"target": {"package": "com.block.juggle", "label": "Block Blast!"}, "score": None, "band": "INCONCLUSIVE",
         "findings": [U1, C13], "historyBounds": BOUNDS}


class HistoryBoundsTest(unittest.TestCase):
    def test_verdict_wording_matches_the_app(self):
        self.assertEqual(audit.history_verdict(BOUNDS), "Needs work either way · 65–99")
        split = {"lowScore": 41, "lowBand": "AT_RISK", "highScore": 77, "highBand": "NEEDS_WORK"}
        self.assertEqual(audit.history_verdict(split), "At best Needs work · 41–77")
        self.assertEqual(audit.history_verdict({**split, "answer": "DECLARED_NEVER"}), "41 · At risk · based on your answer")

    def test_a_range_is_not_inconclusive_and_exits_by_its_never_agreed_end(self):
        code, reasons = audit.exit_for_report(RANGE, "critical", 60)
        self.assertEqual((code, reasons), (EXIT_PASS, []))
        code, reasons = audit.exit_for_report(RANGE, "critical", 85)
        self.assertEqual(code, EXIT_FINDINGS)
        self.assertIn("min_score 85 is met only if the person agreed earlier", reasons[0])
        code, reasons = audit.exit_for_report(RANGE, "high", 0)
        self.assertEqual(code, EXIT_FINDINGS)
        self.assertIn("if the person never agreed", reasons[0])

    def test_summary_prints_the_range_and_an_answer(self):
        text = audit.summary(RANGE, [], Path("saved.json"))
        self.assertIn("consent history unknown: Needs work either way · 65–99", text)
        self.assertNotIn("INCONCLUSIVE", text)
        answered = {**RANGE, "score": 65, "band": "NEEDS_WORK", "historyBounds": {**BOUNDS, "answer": "DECLARED_NEVER"},
                    "findings": [{**U1, "declared": True}, {"check": "C1", "severity": "HIGH", "evidenceType": "OBSERVED",
                                                              "title": "Tracker hosts looked up although you say you never agreed", "declared": True}]}
        text = audit.summary(answered, [], Path("saved.json"))
        self.assertIn("Consent history: declared by the person: never agreed", text)
        self.assertIn("based on your answer", text)

    def test_app_export_carries_the_bounds_and_the_lens_keeps_them(self):
        bundle = {"schema": "traceveil.audit.v1", "app": {"package": "com.block.juggle", "label": "Block Blast!"}, "meta": {"id": "audit-1"},
                  "result": {"findings": [U1, C13], "score": None, "band": "INCONCLUSIVE", "historyBounds": BOUNDS}, "sessions": []}
        rep = cli.from_app_export(bundle)
        self.assertEqual(rep["historyBounds"], BOUNDS)
        block = laws.assess_all(rep, ["INDIA"])
        self.assertEqual(laws.policy_view(block)["historyBounds"], BOUNDS)

    def test_an_answered_history_finding_is_not_inconclusive(self):
        self.assertIsNone(laws.score([U1]))
        self.assertEqual(laws.score([{**U1, "declared": True}, C13]), 99)


if __name__ == "__main__":
    unittest.main()
