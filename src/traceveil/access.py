"""Which personal data the audited app touched, and when, from Android's own app-ops log (`appops get`).

No decryption and no payloads: it only records that the app last used, say, precise location at time T. Lined up with
the dated tracker lookups in the report, it says "location was read, then AppsFlyer was contacted 1.0 s later". Android
keeps only the *last* use of each op, so a later use hides an earlier one."""

from __future__ import annotations

import re
from datetime import datetime, timezone

from .colors import PLAIN, Style

SENSITIVE = {
    "FINE_LOCATION": "precise location", "COARSE_LOCATION": "approximate location", "READ_CONTACTS": "contacts",
    "READ_CLIPBOARD": "clipboard", "CAMERA": "camera", "RECORD_AUDIO": "microphone", "READ_CALL_LOG": "call log",
    "READ_SMS": "SMS", "READ_CALENDAR": "calendar", "READ_PHONE_STATE": "phone identity", "BODY_SENSORS": "body sensors",
}
WINDOW_MS = 5000   # a tracker lookup within this long after the access counts as "together"

_LINE = re.compile(r"^\s*([A-Z_0-9]+):[^;]*;.*?\btime=\+([0-9dhms ]+?) ago")
_UNIT_MS = {"d": 86_400_000, "h": 3_600_000, "m": 60_000, "s": 1000, "ms": 1}


def _age_ms(text: str) -> int:
    return sum(int(n) * _UNIT_MS[u] for n, u in re.findall(r"(\d+)(ms|d|h|m|s)", text))


def parse_appops(text: str, now_ms: int) -> dict[str, int]:
    """op -> wall-clock ms of its last use, for the sensitive ops only."""
    out = {}
    for raw in text.splitlines():
        m = _LINE.match(raw)
        if m and m.group(1) in SENSITIVE:
            out[m.group(1)] = now_ms - _age_ms(m.group(2))
    return out


def device_now_ms(dev) -> int | None:
    digits = re.sub(r"\D", "", dev.shell_checked("date +%s%N"))
    if len(digits) >= 18:
        return int(digits) // 1_000_000
    return int(digits) * 1000 if 9 <= len(digits) <= 11 else None


def correlate(accesses: dict[str, int], rep: dict, now_ms: int) -> list[dict]:
    """Sensitive ops used during this audit, each with the first tracker lookup that followed within WINDOW_MS.
    `trackers` is None when the report carries no dated lookups (an older debug APK), which is not the same as none."""
    try:
        start = int(str(rep.get("auditId", "")).rsplit("-", 1)[1])
    except (IndexError, ValueError):
        return []
    observed = rep.get("observedTrackers")
    dated = isinstance(observed, list) and all("lookupEvents" in t for t in observed)
    rows = []
    for op, at in sorted(accesses.items(), key=lambda kv: kv[1]):
        if not start <= at <= now_ms:
            continue
        hits = []
        if dated:
            for t in observed:
                after = [(e["atMs"] - at, e.get("phase", "")) for e in t["lookupEvents"] if 0 <= e["atMs"] - at <= WINDOW_MS]
                if after:
                    delta, phase = min(after)
                    hits.append({"entity": t["entity"], "afterMs": delta, "phase": phase})
        rows.append({"op": op, "label": SENSITIVE[op], "atMs": at, "trackers": sorted(hits, key=lambda h: h["afterMs"]) if dated else None})
    return rows


def collect(dev, package: str, rep: dict) -> list[dict] | None:
    """None when the log could not be read (best effort: it never fails an audit)."""
    try:
        now = device_now_ms(dev)
        text = dev.shell_checked(f"appops get {package}")
    except Exception:
        return None
    if now is None or not text.strip() or "Unknown" in text.splitlines()[0]:
        return None
    return correlate(parse_appops(text, now), rep, now)


def lines(rows: list[dict], style: Style = PLAIN) -> list[str]:
    out = [style.bold("  Data access (Android app-ops log; what was sent is not read)")]
    if not rows:
        return out + ["    no sensitive data use recorded during this audit"]
    for r in rows:
        when = datetime.fromtimestamp(r["atMs"] / 1000, timezone.utc).strftime("%H:%M:%S UTC")
        if r["trackers"] is None:
            tail = style.dim("tracker timing not in this report")
        elif not r["trackers"]:
            tail = style.dim("no tracker contacted within 5 s")
        else:
            tail = "; ".join(f"{style.tracker(h['entity'])} +{h['afterMs'] / 1000:.1f}s" + (f" ({h['phase']})" if h["phase"] else "")
                             for h in r["trackers"])
        out.append(f"    {when}  {r['label']:<20} {tail}")
    return out
