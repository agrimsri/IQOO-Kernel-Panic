"""JSON envelope and terminal summary for the network-environment check."""

from __future__ import annotations

import hashlib
import json
from dataclasses import asdict

from . import __version__
from .netenv import LIMITATIONS, Check, Signal, Snapshot, device_countries

SCHEMA_VERSION = 1


def serial_hash(serial: str) -> str:
    return hashlib.sha256(serial.encode()).hexdigest()[:12]


def build_env_report(device_info: dict, snap: Snapshot, signals: list[Signal], checks: list[Check],
                     egress: dict | None, verdict: dict) -> dict:
    return {
        "schemaVersion": SCHEMA_VERSION,
        "tool": {"name": "traceveil", "version": __version__},
        "device": device_info,
        "networkEnvironment": {
            "checks": [asdict(c) for c in checks],
            "signals": [asdict(s) for s in signals],
            "deviceCountries": device_countries(snap),
            "egress": egress,
            "privateDnsMode": snap.private_dns_mode,
            "limitations": LIMITATIONS,
        },
        "verdict": verdict,
    }


def dumps(report: dict) -> str:
    return json.dumps(report, indent=2, sort_keys=False)


def summary(report: dict) -> str:
    env = report["networkEnvironment"]
    dev = report["device"]
    lines = [f"Traceveil network environment  ({dev.get('model', '?')}, Android {dev.get('androidVersion', '?')})"]
    for c in env["checks"]:
        lines.append(f"  {c['id']:<13} {c['status']:<9} confidence {c['confidence']}")
        for s in c["signals"]:
            lines.append(f"      - {s}")
        for r in c["reasons"]:
            lines.append(f"      · {r}")
    if env["deviceCountries"]:
        lines.append("  device context: " + ", ".join(f"{k}={v}" for k, v in env["deviceCountries"].items()))
    eg = env["egress"]
    if eg:
        lines.append(f"  egress ({eg.get('vantage', '?')}): {eg.get('country') or 'unknown'}"
                     + (f" ({eg['network']})" if eg.get("network") else "")
                     + (f" [lookup failed: {eg['error']}]" if eg.get("error") else ""))
    v = report["verdict"]
    lines.append(f"  verdict: {v['status']}" + (f" - {'; '.join(v['reasons'])}" if v["reasons"] else ""))
    lines.append(f"  note: {env['limitations']}")
    return "\n".join(lines)
