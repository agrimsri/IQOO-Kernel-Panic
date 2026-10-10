"""Tests for `traceveil audit`. The phone is a scripted fake: no adb, no device, no sleeping."""

import io
import json
import tempfile
import unittest
from pathlib import Path

from traceveil import EXIT_DEVICE, EXIT_FINDINGS, EXIT_INCONCLUSIVE, EXIT_PASS, EXIT_TIMEOUT, EXIT_USAGE, adb, audit, config

REPORT = {
    "schemaVersion": 1, "requestId": "REQ", "auditId": "audit-1", "completedAtMs": 1, "target": {"package": "com.x.y", "label": "X"},
    "device": {"model": "I2501", "androidVersion": "16"}, "score": 21, "band": "AT_RISK", "findingCount": 1, "plans": ["Plan C"],
    "findings": [{"check": "C1", "severity": "HIGH", "evidenceType": "OBSERVED", "title": "Trackers before consent", "confidence": 1.0, "plans": ["SILENT"], "entities": []}],
    "sessions": [],
}


def status(state, **kw):
    return json.dumps({"schemaVersion": 1, "requestId": "REQ", "state": state, **kw})


class Clock:
    def __init__(self):
        self.t = 0.0

    def __call__(self):
        return self.t

    def sleep(self, s):
        self.t += s


class FakePhone:
    """files: path-suffix -> list of contents returned on successive reads (last one repeats). events: scripted failures."""

    def __init__(self, broadcast="Broadcast completed: result=1, data=\"accepted\"", files=None, installed=True, gone_at=()):
        self.serial, self.adb = "SERX", "adb"
        self.broadcast, self.files, self.installed = broadcast, files or {}, installed
        self.gone_at, self.calls, self.reads = set(gone_at), [], 0

    def shell_checked(self, command):
        self.calls.append(command)
        if command.startswith("pm list packages"):
            return "package:com.dpdpxray.app\n" if self.installed else ""
        if command.startswith("am broadcast"):
            return self.broadcast
        if command == "echo ok" or command.startswith("cat "):
            self.reads += 1
            if self.reads in self.gone_at:
                raise adb.AdbDisconnected("device 'SERX' not found")
            if command == "echo ok":
                return "ok\n"
            for suffix, seq in self.files.items():
                if command.endswith(suffix):
                    return seq.pop(0) if len(seq) > 1 else seq[0]
            return "cat: No such file or directory"
        raise AssertionError(command)


def make_io(clock, tty=False, answers=()):
    answers = list(answers)
    return audit.Io(sleep=clock.sleep, clock=clock, out=io.StringIO(), err=io.StringIO(), interactive=tty, color=False,
                    prompt=lambda msg: answers.pop(0) if answers else "q", poll_s=2.0)


class AuditCommandTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.cfg = config.load(None, Path(self.tmp.name))
        self.cfg["output"]["dir"] = str(Path(self.tmp.name) / "reports")

    def run_audit(self, phone, clock=None, wait=100.0, tty=False, answers=(), **kw):
        clock = clock or Clock()
        i = make_io(clock, tty, answers)
        opts = dict(package="com.x.y", plans=None, auto_drive=False, reset=False, wait_s=wait, resume=None, as_json=False, out_file=None,
                    request_id="REQ")
        opts.update(kw)
        code = audit.run_audit(phone, self.cfg, i, **opts)
        return code, i

    def good_files(self):
        return {"REQ/status.json": [status("running", step="x"), status("done", auditId="audit-1")], "REQ/report.json": [json.dumps(REPORT)]}

    def test_happy_path_saves_report_and_prints_summary(self):
        code, i = self.run_audit(FakePhone(files=self.good_files()), fail_on_override="off")
        self.assertEqual(EXIT_PASS, code)
        saved = json.loads((Path(self.cfg["output"]["dir"]) / "REQ.json").read_text())
        self.assertEqual(21, saved["score"])
        self.assertIn("score 21", i.out.getvalue())
        self.assertIn("C1", i.out.getvalue())

    def test_default_policy_fails_on_a_high_finding(self):
        code, _ = self.run_audit(FakePhone(files=self.good_files()))
        self.assertEqual(EXIT_FINDINGS, code)

    def test_min_score_policy(self):
        self.cfg["audit"].update(fail_on="off", min_score=50)
        self.assertEqual(EXIT_FINDINGS, self.run_audit(FakePhone(files=self.good_files()))[0])

    def test_json_flag_prints_the_report_only(self):
        self.cfg["audit"]["fail_on"] = "off"
        _, i = self.run_audit(FakePhone(files=self.good_files()), as_json=True)
        self.assertEqual(21, json.loads(i.out.getvalue())["score"])

    def test_trigger_sends_explicit_component_and_quotes_values(self):
        phone = FakePhone(files=self.good_files())
        self.run_audit(phone, plans="silent,reject", auto_drive=True)
        cmd = next(c for c in phone.calls if c.startswith("am broadcast"))
        self.assertIn("-n com.dpdpxray.app/.cli.DebugAuditReceiver", cmd)
        self.assertIn("--es request_id REQ", cmd)
        self.assertIn("--es plans silent,reject", cmd)
        self.assertIn("--es auto_drive true", cmd)
        self.assertNotIn("reset", cmd)

    def test_reset_is_sent_only_when_asked(self):
        phone = FakePhone(files=self.good_files())
        self.run_audit(phone, reset=True)
        self.assertIn("--es reset true", next(c for c in phone.calls if c.startswith("am broadcast")))

    def test_app_not_installed(self):
        code, i = self.run_audit(FakePhone(installed=False))
        self.assertEqual(EXIT_DEVICE, code)
        self.assertIn("not installed", i.err.getvalue())

    def test_missing_package_is_usage_error(self):
        code, i = self.run_audit(FakePhone(), package="")
        self.assertEqual(EXIT_USAGE, code)

    def test_release_build_or_stopped_app_means_no_receiver(self):
        code, i = self.run_audit(FakePhone(broadcast="Broadcast completed: result=0"))
        self.assertEqual(EXIT_DEVICE, code)
        self.assertIn("debug build", i.err.getvalue())

    def test_rejected_trigger_shows_the_apps_reason(self):
        code, i = self.run_audit(FakePhone(broadcast='Broadcast completed: result=2, data="VPN permission not granted"'))
        self.assertEqual(EXIT_DEVICE, code)
        self.assertIn("VPN permission not granted", i.err.getvalue())

    def test_app_reported_failure_is_inconclusive_with_the_reason(self):
        phone = FakePhone(files={"REQ/status.json": [status("failed", error="Cannot launch com.x.y")]})
        code, i = self.run_audit(phone)
        self.assertEqual(EXIT_INCONCLUSIVE, code)
        self.assertIn("Cannot launch", i.err.getvalue())

    def test_timeout_keeps_the_audit_and_prints_resume_hint(self):
        phone = FakePhone(files={"REQ/status.json": [status("running", step="Observing")]})
        code, i = self.run_audit(phone, wait=10)
        self.assertEqual(EXIT_TIMEOUT, code)
        self.assertIn("--resume REQ", i.err.getvalue())
        self.assertIn("Observing", i.err.getvalue())

    def test_done_without_report_is_inconclusive(self):
        phone = FakePhone(files={"REQ/status.json": [status("done")]})
        code, i = self.run_audit(phone)
        self.assertEqual(EXIT_INCONCLUSIVE, code)
        self.assertIn("report.json", i.err.getvalue())

    def test_malformed_report_is_rejected_and_not_saved(self):
        for body in ("{not json", "[]", json.dumps({**REPORT, "schemaVersion": 2}), json.dumps({**REPORT, "requestId": "OTHER"}),
                     json.dumps({k: v for k, v in REPORT.items() if k != "findings"})):
            phone = FakePhone(files={"REQ/status.json": [status("done")], "REQ/report.json": [body]})
            code, i = self.run_audit(phone)
            self.assertEqual(EXIT_INCONCLUSIVE, code, body[:40])
        self.assertFalse((Path(self.cfg["output"]["dir"]) / "REQ.json").exists())

    def test_inconclusive_band_is_not_a_pass(self):
        rep = {**REPORT, "score": None, "band": "INCONCLUSIVE", "findings": [], "findingCount": 0}
        phone = FakePhone(files={"REQ/status.json": [status("done")], "REQ/report.json": [json.dumps(rep)]})
        self.assertEqual(EXIT_INCONCLUSIVE, self.run_audit(phone)[0])

    def test_disconnect_non_interactive_waits_then_resumes_to_completion(self):
        files = self.good_files()
        phone = FakePhone(files=files, gone_at=(1, 2))
        code, i = self.run_audit(phone, fail_on_override="off")
        self.assertEqual(EXIT_PASS, code)
        self.assertIn("disconnected", i.err.getvalue().lower())

    def test_disconnect_interactive_prompts_and_user_can_quit(self):
        phone = FakePhone(files=self.good_files(), gone_at=(1,))
        code, i = self.run_audit(phone, tty=True, answers=["q"])
        self.assertEqual(EXIT_DEVICE, code)
        self.assertIn("--resume REQ", i.err.getvalue())

    def test_disconnect_interactive_retry_continues(self):
        phone = FakePhone(files=self.good_files(), gone_at=(1,))
        code, _ = self.run_audit(phone, tty=True, answers=[""], fail_on_override="off")
        self.assertEqual(EXIT_PASS, code)

    def test_disconnect_that_never_returns_times_out_as_device_error(self):
        phone = FakePhone(files=self.good_files(), gone_at=set(range(1, 500)))
        code, i = self.run_audit(phone, wait=30)
        self.assertEqual(EXIT_DEVICE, code)

    def test_resume_skips_the_trigger(self):
        phone = FakePhone(files=self.good_files())
        self.run_audit(phone, resume="REQ", request_id=None)
        self.assertFalse(any(c.startswith("am broadcast") for c in phone.calls))

    def test_color_only_when_enabled(self):
        self.assertEqual("\x1b[1;33mhi\x1b[0m", audit.paint("hi", "1;33", True))
        self.assertEqual("hi", audit.paint("hi", "1;33", False))

    def test_request_ids_are_unique_and_valid(self):
        a, b = audit.new_request_id(), audit.new_request_id()
        self.assertNotEqual(a, b)
        self.assertRegex(a, r"^cli-\d{8}T\d{6}Z-[0-9a-f]{8}$")

    def test_checked_shell_detects_disconnection_from_adb_output(self):
        class Proc:
            def __init__(s, rc, out, err): s.returncode, s.stdout, s.stderr = rc, out, err
        for err in ("error: device offline", "error: no devices/emulators found", "error: device 'X' not found", "error: closed"):
            d = adb.Device("adb", "X", runner=lambda *a, **k: Proc(1, "", err))
            with self.assertRaises(adb.AdbDisconnected):
                d.shell_checked("ls")
        d = adb.Device("adb", "X", runner=lambda *a, **k: Proc(1, "", "cat: x: No such file or directory"))
        self.assertIn("No such file", d.shell_checked("cat x"))


if __name__ == "__main__":
    unittest.main()
