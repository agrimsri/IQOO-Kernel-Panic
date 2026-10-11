"""`traceveil audit`: trigger a debug-build audit on the phone over adb, wait, fetch and validate its JSON report."""

from __future__ import annotations

import json
import re
import secrets
import shlex
import sys
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable

from . import EXIT_DEVICE, EXIT_FINDINGS, EXIT_INCONCLUSIVE, EXIT_PASS, EXIT_TIMEOUT, EXIT_USAGE, adb, fixes, laws, netwatch
from .colors import PLAIN, Style
from .netenv import OWN_PACKAGE

ACTION = "com.dpdpxray.app.debug.RUN_AUDIT"
RECEIVER = f"{OWN_PACKAGE}/.cli.DebugAuditReceiver"
REMOTE_REPORTS = f"/sdcard/Android/data/{OWN_PACKAGE}/files/reports"
SCHEMA_VERSION = 1
SEVERITY_ORDER = ["info", "low", "medium", "high", "critical"]
NET_SAMPLE_S = 1.0  # socket sampling interval while an audit runs
REQUIRED_KEYS = ("schemaVersion", "requestId", "auditId", "target", "score", "band", "findings", "sessions")


@dataclass
class Io:
    sleep: Callable[[float], None] = time.sleep
    clock: Callable[[], float] = time.monotonic
    out: object = None
    err: object = None
    interactive: bool = False
    color: bool = False
    prompt: Callable[[str], str] = input
    poll_s: float = 2.0
    color_out: bool = False   # colours in the summary on stdout (`color` covers stderr messages)

    def __post_init__(self):
        self.out = self.out or sys.stdout
        self.err = self.err or sys.stderr


def paint(text: str, code: str, enabled: bool) -> str:
    return f"\x1b[{code}m{text}\x1b[0m" if enabled else text


def new_request_id() -> str:
    return f"cli-{datetime.now(timezone.utc):%Y%m%dT%H%M%SZ}-{secrets.token_hex(4)}"


def _remote(request_id: str, name: str) -> str:
    return f"{REMOTE_REPORTS}/{request_id}/{name}"


def _fail(io: Io, msg: str, code: int) -> int:
    print(paint(f"error: {msg}", "1;31", io.color), file=io.err)
    return code


def _json_or_none(text: str):
    try:
        return json.loads(text)
    except ValueError:
        return None


def trigger(dev, request_id: str, package: str, plans: str | None, auto_drive: bool, reset: bool) -> tuple[bool, str]:
    """Sends the debug broadcast. Returns (accepted, reason)."""
    extras = f"--es request_id {shlex.quote(request_id)} --es package {shlex.quote(package)}"
    if plans:
        extras += f" --es plans {shlex.quote(plans)}"
    if auto_drive:
        extras += " --es auto_drive true"
    if reset:
        extras += " --es reset true"
    out = dev.shell_checked(f"am broadcast -a {ACTION} -n {RECEIVER} {extras}")
    m = re.search(r"result=(\d+)", out)
    code = int(m.group(1)) if m else 0
    if code == 1:
        return True, "accepted"
    if code == 2:
        d = re.search(r'data="([^"]*)"', out)
        return False, d.group(1) if d else "the app rejected the request"
    return False, ("no debug trigger answered: install a debug build of Traceveil and open it once (a never-opened or "
                   "force-stopped app does not receive broadcasts); release builds have no trigger by design")


def validate_report(text: str, request_id: str) -> tuple[dict | None, str]:
    rep = _json_or_none(text)
    if not isinstance(rep, dict):
        return None, "report.json is not valid JSON"
    missing = [k for k in REQUIRED_KEYS if k not in rep]
    if missing:
        return None, f"report.json is missing {', '.join(missing)}"
    if rep["schemaVersion"] != SCHEMA_VERSION:
        return None, f"report.json schemaVersion {rep['schemaVersion']!r} is not supported (expected {SCHEMA_VERSION})"
    if rep["requestId"] != request_id:
        return None, "report.json belongs to a different request"
    if not isinstance(rep["findings"], list) or not isinstance(rep["sessions"], list):
        return None, "report.json findings/sessions are not lists"
    return rep, ""


def exit_for_report(rep: dict, fail_on: str, min_score: int) -> tuple[int, list[str]]:
    if rep.get("score") is None or rep.get("band") == "INCONCLUSIVE":
        return EXIT_INCONCLUSIVE, ["audit result is inconclusive (no score)"]
    reasons = []
    if rep["score"] < min_score:
        reasons.append(f"score {rep['score']} is below min_score {min_score}")
    if fail_on in SEVERITY_ORDER:
        bar = SEVERITY_ORDER.index(fail_on)
        hits = [f for f in rep["findings"] if isinstance(f, dict) and str(f.get("severity", "")).lower() in SEVERITY_ORDER
                and SEVERITY_ORDER.index(str(f["severity"]).lower()) >= bar]
        if hits:
            reasons.append(f"{len(hits)} finding(s) at or above {fail_on}")
    return (EXIT_FINDINGS if reasons else EXIT_PASS), reasons


MAX_NAMES = 4


def _names(entities: list, style: Style) -> str:
    shown = ", ".join(style.tracker(e) for e in entities[:MAX_NAMES])
    return shown + (style.dim(f" (+{len(entities) - MAX_NAMES} more)") if len(entities) > MAX_NAMES else "")


def summary(rep: dict, reasons: list[str], saved: Path, style: Style = PLAIN) -> str:
    t = rep["target"]
    lines = [f"Traceveil audit  {style.bold(t.get('label', '?'))} ({t.get('package', '?')})  "
             f"score {style.score(rep['score'], rep['band'])} / {style.band(rep['band'])}  findings {len(rep['findings'])}"]
    plan = {i["check"]: i for i in rep.get("fixPlan", {}).get("issues", [])}
    for f in rep["findings"]:
        sev = str(f.get("severity", "?"))
        tag = style.dim(" inferred") if str(f.get("evidenceType", "")).upper() == "INFERRED" else ""
        lines.append(f"  {f.get('check', '?'):<4} {style.severity(sev, 8)} {f.get('title', '')}{tag}")
        if f.get("entities"):
            lines.append(f"       {style.dim('trackers:')} {_names(f['entities'], style)}")
        if f.get("check") in plan:
            lines += fixes.summary_lines({**rep["fixPlan"], "issues": [plan[f["check"]]]}, style, compact=True)
    if rep.get("lenses"):
        lines += laws.table(rep["lenses"], style)
    if rep.get("network"):
        lines += netwatch.summary_lines(rep["network"], style)
    for r in reasons:
        lines.append(f"  {style.warn('policy:')} {r}")
    lines.append(f"  report saved: {saved}")
    if rep.get("fixPlan", {}).get("fixes"):
        lines.append(style.dim("  fixes are in the report's fixPlan (give the JSON to a coding agent); --format md prints them as Markdown"))
    return "\n".join(lines)


def _wait_for_device(dev, io: Io, deadline: list[float], request_id: str) -> bool:
    """Called after adb dropped. Tells the user to reconnect; True once adb answers again, False to give up."""
    print(paint(f"Device disconnected. Reconnect the cable, unlock the phone and accept USB debugging if asked. The audit keeps "
                f"running on the phone; resume later with: traceveil audit --resume {request_id}", "1;33", io.color), file=io.err)
    while True:
        if io.interactive:
            started = io.clock()
            answer = io.prompt(paint("Press Enter to retry, or type q to quit: ", "1;36", io.color)).strip().lower()
            deadline[0] += io.clock() - started  # time the user spent at the prompt does not count against --wait
            if answer == "q":
                return False
        else:
            if io.clock() >= deadline[0]:
                return False
            io.sleep(io.poll_s)
        try:
            dev.shell_checked("echo ok")
            print(paint("Device reconnected, continuing.", "1;32", io.color), file=io.err)
            return True
        except adb.AdbDisconnected:
            if not io.interactive and io.clock() >= deadline[0]:
                return False


def run_audit(dev, cfg: dict, io: Io, *, package: str, plans: str | None, auto_drive: bool, reset: bool, wait_s: float,
              resume: str | None, as_json: bool, out_file: str | None, request_id: str | None = None, as_md: bool = False,
              fail_on_override: str | None = None, lenses=None, policy: str | None = None,
              watch_network: bool | None = None, fail_on_hidden: bool = False) -> int:
    request_id = resume or request_id or new_request_id()
    package = package or cfg["target"]["package"]
    if not resume and not package:
        return _fail(io, "no target app: pass --package or set target.package in traceveil.toml", EXIT_USAGE)
    try:
        if not resume:
            if f"package:{OWN_PACKAGE}" not in dev.shell_checked(f"pm list packages {OWN_PACKAGE}").split():
                return _fail(io, f"{OWN_PACKAGE} is not installed on the device (install the debug APK)", EXIT_DEVICE)
            ok, reason = trigger(dev, request_id, package, plans, auto_drive, reset)
            if not ok:
                return _fail(io, f"audit was not started: {reason}", EXIT_DEVICE)
            print(f"audit started: {request_id}", file=io.err)
    except adb.AdbDisconnected as e:
        return _fail(io, f"device disconnected before the audit started ({e}); reconnect and run again", EXIT_DEVICE)

    sampler = None
    if not resume and (cfg["audit"].get("watch_network", True) if watch_network is None else watch_network):
        try:
            uid = netwatch.uid_of(dev, package)
            sampler = netwatch.Sampler(dev, uid, io.clock) if uid is not None else None
        except adb.AdbError:
            sampler = None
    poll_s = min(io.poll_s, NET_SAMPLE_S) if sampler else io.poll_s

    deadline = [io.clock() + wait_s]
    last_step = ""
    while True:
        if sampler:
            sampler.sample()  # first, so even an audit that is already done gets one sample
        try:
            raw = dev.shell_checked(f"cat {shlex.quote(_remote(request_id, 'status.json'))}")
            state = _json_or_none(raw)
            if isinstance(state, dict):
                last_step = state.get("step") or last_step
                if state.get("state") == "failed":
                    return _fail(io, f"audit failed on the device: {state.get('error') or 'no reason given'}", EXIT_INCONCLUSIVE)
                if state.get("state") == "done":
                    break
        except adb.AdbDisconnected:
            if not _wait_for_device(dev, io, deadline, request_id):
                return _fail(io, f"device not reachable; audit may still be running. Resume with: traceveil audit --resume {request_id}", EXIT_DEVICE)
            continue
        if io.clock() >= deadline[0]:
            return _fail(io, f"timed out after {wait_s:.0f}s (last step: {last_step or 'unknown'}). The audit was not cancelled; "
                             f"fetch it later with: traceveil audit --resume {request_id}", EXIT_TIMEOUT)
        io.sleep(poll_s)

    try:
        text = dev.shell_checked(f"cat {shlex.quote(_remote(request_id, 'report.json'))}")
    except adb.AdbDisconnected as e:
        return _fail(io, f"device disconnected while fetching the report ({e}); resume with: traceveil audit --resume {request_id}", EXIT_DEVICE)
    if "No such file" in text:
        return _fail(io, "audit finished but report.json is missing on the device", EXIT_INCONCLUSIVE)
    rep, problem = validate_report(text, request_id)
    if rep is None:
        return _fail(io, problem, EXIT_INCONCLUSIVE)

    try:
        policy = policy or cfg["audit"]["policy"]
        rep["lenses"] = laws.assess_all(rep, lenses or cfg["audit"]["lenses"], policy)
        rep["fixPlan"] = fixes.build(rep, laws.assess_all(rep, "all", policy))  # every country, whatever lenses are shown
    except ValueError as e:
        return _fail(io, str(e), EXIT_USAGE)
    if sampler:
        try:
            vpn = netwatch.declares_vpn_service(dev.shell_checked(f"dumpsys package {shlex.quote(package)}"))
        except adb.AdbError:
            vpn = None
        rep["network"] = netwatch.analyze(sampler.seen, rep, sampler.as_dict(poll_s), vpn)
    out_dir = Path(cfg["output"]["dir"])
    out_dir.mkdir(parents=True, exist_ok=True)
    saved = out_dir / f"{request_id}.json"
    saved.write_text(json.dumps(rep, indent=2), encoding="utf-8")
    if out_file:  # a .md path gets the fix plan as Markdown; anything else the JSON report, fixPlan included
        Path(out_file).parent.mkdir(parents=True, exist_ok=True)
        Path(out_file).write_text(fixes.markdown(rep["fixPlan"]) if out_file.endswith(".md") else json.dumps(rep, indent=2),
                                  encoding="utf-8")
    code, reasons = exit_for_report(laws.policy_view(rep["lenses"]), fail_on_override or cfg["audit"]["fail_on"],
                                    int(cfg["audit"]["min_score"]))
    if fail_on_hidden and rep.get("network") and netwatch.hidden(rep["network"]):
        reasons.append("the app made connections the DNS capture could not see (--fail-on-hidden)")
        code = code if code != EXIT_PASS else EXIT_FINDINGS
    text = (json.dumps(rep, indent=2) if as_json else fixes.markdown(rep["fixPlan"]) if as_md
            else summary(rep, reasons, saved, Style(io.color_out)))
    print(text, file=io.out)
    return code
