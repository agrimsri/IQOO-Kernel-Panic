"""Reads one audit's findings under several privacy laws (`data/jurisdictions.json`).

The evidence never changes between jurisdictions: the same checks fired on the same device. Each profile only swaps the
cited provisions and the risk weight, then scores with the app's formula (100 minus severity weights, inferred findings
count half, bands 85/60). India keeps the app's own severities, so its score always equals the app's score.
Severities are risk weighting for prioritising fixes, not a legal determination.
"""

from __future__ import annotations

import json
import math
from importlib import resources

from .colors import PLAIN, Style

WEIGHTS = {"CRITICAL": 30, "HIGH": 15, "MEDIUM": 8, "LOW": 3, "INFO": 0}
DEFAULT_POLICY = "in"
DISCLAIMER = "Same evidence read under each law; weights are Traceveil's risk ranking, not legal advice or a compliance verdict."


def load_profiles() -> list[dict]:
    text = resources.files("traceveil").joinpath("data/jurisdictions.json").read_text(encoding="utf-8")
    return json.loads(text)["jurisdictions"]


def ids() -> list[str]:
    return [p["id"] for p in load_profiles()]


def select(selection) -> list[dict]:
    """`all`, a comma string or a list of ids, in the order given. Raises ValueError on an unknown id."""
    profiles = {p["id"]: p for p in load_profiles()}
    if selection in (None, "", "all") or selection == ["all"]:
        return list(profiles.values())
    wanted = [s.strip().lower() for s in (selection.split(",") if isinstance(selection, str) else selection) if s.strip()]
    unknown = [w for w in wanted if w not in profiles]
    if unknown:
        raise ValueError(f"unknown jurisdiction(s): {', '.join(unknown)} (known: {', '.join(profiles)})")
    return [profiles[w] for w in dict.fromkeys(wanted)]


def _inconclusive(findings: list[dict]) -> bool:
    return any(f.get("check") == "V0" or (f.get("check") == "U1" and f.get("entities")) for f in findings)


def band_for(score: int | None) -> str:
    if score is None:
        return "INCONCLUSIVE"
    return "READY" if score >= 85 else "NEEDS_WORK" if score >= 60 else "AT_RISK"


def assess(report: dict, profile: dict) -> dict:
    findings = [f for f in report.get("findings", []) if isinstance(f, dict)]
    rows, penalty = [], 0.0
    for f in findings:
        reported = str(f.get("severity", "INFO")).upper()
        rule = profile["checks"].get(f.get("check"))
        if rule is None:
            sev, refs, note, mapped = reported, [], "Not mapped for this law; the app's severity is kept.", False
        else:
            sev = reported if rule["severity"] == "as-reported" else rule["severity"]
            refs, note, mapped = rule.get("refs", []), rule.get("note"), True
        inferred = str(f.get("evidenceType", "")).upper() == "INFERRED"
        if sev is not None:
            penalty += WEIGHTS.get(sev, 0) * (0.5 if inferred else 1.0)
        rows.append({"check": f.get("check"), "title": f.get("title"), "severity": sev, "appSeverity": reported,
                     "evidenceType": f.get("evidenceType"), "entities": f.get("entities", []), "refs": refs,
                     "note": note, "mapped": mapped, "scored": sev is not None})
    score = None if _inconclusive(findings) else max(0, int(math.floor(100 - penalty + 0.5)))
    if profile["id"] == "in" and "score" in report:   # the app's DPDP score is authoritative; the formula is the same
        score = report["score"]
    return {"id": profile["id"], "name": profile["name"], "law": profile["law"], "model": profile["model"],
            "score": score, "band": report.get("band", band_for(score)) if profile["id"] == "in" and "score" in report else band_for(score),
            "findings": rows, "sources": profile["sources"]}


def assess_all(report: dict, selection=None, policy: str = DEFAULT_POLICY) -> dict:
    profiles = select(selection)
    if policy not in [p["id"] for p in profiles]:
        profiles = select([policy]) + profiles
    return {"policy": policy, "disclaimer": DISCLAIMER, "assessments": [assess(report, p) for p in profiles]}


def policy_view(block: dict) -> dict:
    """The policy jurisdiction's result in the shape `audit.exit_for_report` reads (score, band, findings)."""
    a = next(x for x in block["assessments"] if x["id"] == block["policy"])
    return {"score": a["score"], "band": a["band"],
            "findings": [{"severity": r["severity"]} for r in a["findings"] if r["scored"]]}


def table(block: dict, style: Style = PLAIN) -> list[str]:
    lines = [style.bold("  By jurisdiction") + style.dim("  (same evidence, each law's weighting)")]
    for a in block["assessments"]:
        top = sorted((r for r in a["findings"] if r["scored"] and r["severity"] != "INFO"),
                     key=lambda r: -WEIGHTS[r["severity"]])[:3]
        worst = ", ".join(f"{r['check']} {style.severity(r['severity'])}" for r in top) or style.dim("no scored findings")
        marker = "*" if a["id"] == block["policy"] else " "
        lines.append(f"  {marker} {a['id'].upper():<6} {a['name']:<16} {style.score(a['score'], a['band'], 3)}  "
                     f"{style.band(a['band'], 12)}  {worst}")
    lines.append(style.dim(f"    * decides the exit code. {block['disclaimer']}"))
    return lines


def detail(block: dict, style: Style = PLAIN) -> str:
    out = []
    for a in block["assessments"]:
        out.append(f"{style.bold(a['name'])} ({a['id']})  {style.score(a['score'], a['band'])} / {style.band(a['band'])}")
        out.append(style.dim(f"  {a['law']} — {a['model']}"))
        for r in a["findings"]:
            sev = style.severity(r["severity"], 8) if r["scored"] else style.dim(f"{'n/a':<8}")
            changed = "" if r["severity"] == r["appSeverity"] else style.dim(f" (app: {r['appSeverity']})")
            out.append(f"  {r['check']:<4} {sev} {r['title'] or ''}{changed}")
            if r["refs"]:
                out.append(f"       {style.dim('refs:')} {'; '.join(r['refs'])}")
            if r["note"]:
                out.append(f"       {style.dim(r['note'])}")
        out.append(style.dim(f"  sources: {' '.join(a['sources'])}"))
        out.append("")
    out.append(style.dim(block["disclaimer"]))
    return "\n".join(out)
