"""A background watch (the app's "Watch in the background") states its length and that no consent screen was driven."""

import unittest
from pathlib import Path

from traceveil import audit, cli

WATCH_SESSION = {"id": "w", "targetPackage": "com.example.rollcall", "targetLabel": "RollCall", "plan": "SILENT",
                 "watch": {"plannedMs": 600000, "watchedMs": 600000}}
C12 = {"checkId": "C12", "severity": "INFO", "evidenceType": "OBSERVED", "title": "Tracker hosts looked up while the app was in the background",
       "entities": ["Google Analytics for Firebase"]}


def bundle(sessions):
    return {"schema": "traceveil.audit.v1", "app": {"package": "com.example.rollcall", "label": "RollCall"}, "meta": {"id": "audit-w"},
            "result": {"findings": [C12], "score": 100, "band": "READY"}, "sessions": sessions}


class BackgroundWatchTest(unittest.TestCase):
    def test_app_export_of_a_watch_carries_its_length(self):
        rep = cli.from_app_export(bundle([WATCH_SESSION]))
        w = rep["backgroundWatch"]
        self.assertEqual(w["plannedMinutes"], 10)
        self.assertFalse(w["stoppedEarly"])
        self.assertFalse(w["consentScreenDriven"])
        self.assertTrue(w["text"].startswith("background watch of 10 minutes: what the app sends while it runs in the background"))

    def test_a_watch_stopped_early_says_so(self):
        early = {**WATCH_SESSION, "watch": {"plannedMs": 600000, "watchedMs": 252000, "stoppedEarly": True}}
        w = audit.background_watch([early])
        self.assertIn("4 min 12 s of a planned 10 minutes (stopped early)", w["text"])

    def test_summary_prints_the_watch_and_an_ordinary_export_has_none(self):
        text = audit.summary(cli.from_app_export(bundle([WATCH_SESSION])), [], Path("saved.json"))
        self.assertIn("watch: background watch of 10 minutes", text)
        self.assertIn("its consent screen was not tested", text)
        plain = cli.from_app_export(bundle([{"id": "q", "plan": "REJECT"}]))
        self.assertNotIn("backgroundWatch", plain)
        self.assertNotIn("watch:", audit.summary(plain, [], Path("saved.json")))


if __name__ == "__main__":
    unittest.main()
