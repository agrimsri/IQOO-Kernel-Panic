"""Tests for the data-access log (appops) lined up with dated tracker lookups. No phone: appops text is canned."""

import unittest

from traceveil import access, audit
from traceveil.colors import PLAIN

NOW = 1_000_000_000_000
APPOPS = """Uid mode: COARSE_LOCATION: allow
FINE_LOCATION: allow; time=+3s400ms ago; duration=+1s
READ_CLIPBOARD: allow; time=+9h22m5s ago
CAMERA: ignore; rejectTime=+2s ago
WIFI_SCAN: allow; time=+1s ago
"""
REP = {"auditId": f"audit-{NOW - 60_000}", "observedTrackers": [
    {"entity": "AppsFlyer", "lookupEvents": [{"atMs": NOW - 10_000, "phase": "BEFORE_CONSENT"}, {"atMs": NOW - 2_400, "phase": "BEFORE_CONSENT"}]},
    {"entity": "Meta", "lookupEvents": [{"atMs": NOW - 100, "phase": "AFTER_REJECT"}]},
]}


class FakeDev:
    def __init__(self, date="1000000000000000000\n", appops=APPOPS):
        self.date, self.appops = date, appops

    def shell_checked(self, cmd):
        return self.date if cmd.startswith("date") else self.appops


class AccessTests(unittest.TestCase):
    def test_parse_keeps_sensitive_ops_with_a_use_time(self):
        got = access.parse_appops(APPOPS, NOW)
        self.assertEqual({"FINE_LOCATION": NOW - 3400, "READ_CLIPBOARD": NOW - (9 * 3600 + 22 * 60 + 5) * 1000}, got)

    def test_correlate_finds_the_tracker_that_followed_and_drops_old_uses(self):
        rows = access.correlate(access.parse_appops(APPOPS, NOW), REP, NOW)
        self.assertEqual(["FINE_LOCATION"], [r["op"] for r in rows])   # the clipboard read predates the audit
        self.assertEqual([("AppsFlyer", 1000, "BEFORE_CONSENT"), ("Meta", 3300, "AFTER_REJECT")],
                         [(h["entity"], h["afterMs"], h["phase"]) for h in rows[0]["trackers"]])

    def test_lookups_before_the_access_or_too_late_do_not_count(self):
        rows = access.correlate({"CAMERA": NOW - 30_000}, REP, NOW)
        self.assertEqual([], rows[0]["trackers"])

    def test_report_without_dated_lookups_is_unknown_not_empty(self):
        rows = access.correlate({"CAMERA": NOW - 1000}, {"auditId": REP["auditId"], "observedTrackers": [{"entity": "X"}]}, NOW)
        self.assertIsNone(rows[0]["trackers"])
        self.assertIn("not in this report", "\n".join(access.lines(rows)))

    def test_collect_is_best_effort(self):
        self.assertEqual(["FINE_LOCATION"], [r["op"] for r in access.collect(FakeDev(), "p", REP)])
        self.assertIsNone(access.collect(FakeDev(date="garbage"), "p", REP))
        self.assertIsNone(access.collect(FakeDev(appops=""), "p", REP))

    def test_lines_and_summary(self):
        rows = access.correlate(access.parse_appops(APPOPS, NOW), REP, NOW)
        text = "\n".join(access.lines(rows, PLAIN))
        self.assertIn("precise location", text)
        self.assertIn("AppsFlyer +1.0s (BEFORE_CONSENT)", text)
        self.assertIn("no sensitive data use", "\n".join(access.lines([])))
        rep = {"target": {"label": "X", "package": "p"}, "score": 21, "band": "AT_RISK", "findings": [], "dataAccess": rows}
        self.assertIn("Data access", audit.summary(rep, [], __import__("pathlib").Path("r.json")))


if __name__ == "__main__":
    unittest.main()
