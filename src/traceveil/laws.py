"""Legal lenses for the CLI: the same rules the app uses (core `LegalLens`), applied to an audit report.

Data: `data/law/profiles.json` and `passages.json`, exact copies of `core/src/main/resources/law/` (kept in sync by
`tools/sync_law_data.py`). A lens never changes the evidence or the severities. For every check it says how that law
relates to it: REQUIRED (the law sets a rule the check tests), CONTEXT (relevant, or only sometimes required) or
NOT_APPLICABLE. The lens score is the app's consent-first score over the findings that are not NOT_APPLICABLE, so the
INDIA lens always equals the app's own score. A reading of the evidence, not a legal verdict.
"""

from __future__ import annotations

import json
import math
from functools import lru_cache
from importlib import resources

from .colors import PLAIN, Style

WEIGHTS = {"CRITICAL": 30, "HIGH": 15, "MEDIUM": 8, "LOW": 3, "INFO": 0}
DEFAULT_POLICY = "INDIA"
DISCLAIMER = "Same findings and severities under every lens; a lens only marks which ones each law requires. A reading of the evidence, not a legal verdict."
ALIASES = {"IN": "INDIA", "US_CA": "US_CALIFORNIA", "CA": "US_CALIFORNIA", "CCPA": "US_CALIFORNIA", "COPPA": "US_CHILDREN",
           "BR": "BRAZIL", "LGPD": "BRAZIL", "SG": "SINGAPORE", "PDPA": "SINGAPORE", "GDPR": "EU", "GLOBAL": "GLOBAL_BASELINE"}
_REL_SHORT = {"REQUIRED": "required", "CONTEXT": "context", "NOT_APPLICABLE": "n/a"}


def _read(name: str):
    return json.loads(resources.files("traceveil").joinpath(f"data/law/{name}").read_text(encoding="utf-8"))


@lru_cache(maxsize=1)
def load_profiles() -> tuple:
    return tuple(_read("profiles.json")["profiles"])


@lru_cache(maxsize=1)
def passages() -> dict:
    return {p["id"]: p for p in _read("passages.json")}


def ids() -> list[str]:
    return [p["id"] for p in load_profiles()]


def parse(text: str) -> str | None:
    """Like `LegalProfile.parse`, plus short aliases: "eu", "us-ca", "in", "coppa"... -> profile id, or None."""
    key = str(text).strip().upper().replace("-", "_").replace(" ", "_")
    key = ALIASES.get(key, key)
    return key if key in ids() else None


def select(selection) -> list[dict]:
    """`all`, a comma string or a list of lens names, in the order given. Raises ValueError on an unknown name."""
    profiles = {p["id"]: p for p in load_profiles()}
    if selection in (None, "", "all") or selection == ["all"]:
        return list(profiles.values())
    names = [s for s in (selection.split(",") if isinstance(selection, str) else selection) if str(s).strip()]
    unknown = [n for n in names if parse(n) is None]
    if unknown:
        raise ValueError(f"unknown lens(es): {', '.join(unknown)} (known: {', '.join(profiles)})")
    return [profiles[i] for i in dict.fromkeys(parse(n) for n in names)]


def _inconclusive(findings: list[dict]) -> bool:
    """`Inconclusive.reason` in the app: V0, or U1 naming trackers."""
    return any(f.get("check") == "V0" or (f.get("check") == "U1" and f.get("entities")) for f in findings)


def band_for(score: int | None) -> str:
    if score is None:
        return "INCONCLUSIVE"
    return "READY" if score >= 85 else "NEEDS_WORK" if score >= 60 else "AT_RISK"


def score(findings: list[dict]) -> int | None:
    """`ScoreCalculator.compute`: 100 minus severity weights, inferred count half, rounded half up."""
    if _inconclusive(findings):
        return None
    penalty = sum(WEIGHTS.get(str(f.get("severity", "INFO")).upper(), 0) * (0.5 if str(f.get("evidenceType", "")).upper() == "INFERRED" else 1.0)
                  for f in findings)
    return max(0, int(math.floor(100 - penalty + 0.5)))


def view(report: dict, profile: dict) -> dict:
    """`LegalLens.view`: each finding with its relation under this lens, and the lens score."""
    findings = [f for f in report.get("findings", []) if isinstance(f, dict)]
    rows = []
    for f in findings:
        mapped = f.get("check") in profile["checks"]
        m = profile["checks"].get(f.get("check"), profile["fallback"])
        rows.append({"check": f.get("check"), "title": f.get("title"), "severity": f.get("severity"),
                     "evidenceType": f.get("evidenceType"), "entities": f.get("entities", []),
                     "relation": m["relation"], "note": m.get("note"), "refs": m.get("refs", []), "mapped": mapped})
    counted = [f for f, r in zip(findings, rows) if r["relation"] != "NOT_APPLICABLE"]
    if len(counted) == len(findings) and "score" in report:
        # Nothing skipped: LegalLens scores exactly what the app scored, so the app's figure is used as is.
        s, band = report["score"], report.get("band") or band_for(report["score"])
    else:
        s = score(counted)
        band = band_for(s)
    return {"id": profile["id"], "name": profile["name"], "laws": profile["laws"], "kind": profile.get("kind", "LAW"),
            "score": s, "band": band, "findings": rows,
            "required": [r["check"] for r in rows if r["relation"] == "REQUIRED"],
            "context": [r["check"] for r in rows if r["relation"] == "CONTEXT"],
            "notApplicable": [r["check"] for r in rows if r["relation"] == "NOT_APPLICABLE"],
            "limitations": profile.get("limitations", [])}


def assess_all(report: dict, selection=None, policy: str = DEFAULT_POLICY) -> dict:
    pid = parse(policy)
    if pid is None:
        raise ValueError(f"unknown policy lens: {policy} (known: {', '.join(ids())})")
    profiles = select(selection)
    if pid not in [p["id"] for p in profiles]:
        profiles = select([pid]) + profiles
    return {"policy": pid, "source": "core LegalLens (resources/law)", "disclaimer": DISCLAIMER,
            "lenses": [view(report, p) for p in profiles]}


def policy_view(block: dict) -> dict:
    """The policy lens in the shape `audit.exit_for_report` reads: its score, band and counted findings."""
    v = next(x for x in block["lenses"] if x["id"] == block["policy"])
    return {"score": v["score"], "band": v["band"],
            "findings": [{"severity": r["severity"]} for r in v["findings"] if r["relation"] != "NOT_APPLICABLE"]}


def table(block: dict, style: Style = PLAIN) -> list[str]:
    lines = [style.bold("  Legal lenses") + style.dim("  (same findings; each law marks what it requires)")]
    for v in block["lenses"]:
        req = ", ".join(v["required"]) or style.dim("none")
        na = style.dim(f"  n/a: {', '.join(v['notApplicable'])}") if v["notApplicable"] else ""
        marker = "*" if v["id"] == block["policy"] else " "
        kind = style.dim(" (good practice)") if v["kind"] != "LAW" else ""
        lines.append(f"  {marker} {v['id']:<15} {style.score(v['score'], v['band'], 3)}  {style.band(v['band'], 12)}  "
                     f"required: {req}{na}{kind}")
    lines.append(style.dim(f"    * decides the exit code. {block['disclaimer']}"))
    return lines


def detail(block: dict, style: Style = PLAIN) -> str:
    pas = passages()
    out = []
    for v in block["lenses"]:
        out.append(f"{style.bold(v['name'])} ({v['id']})  {style.score(v['score'], v['band'])} / {style.band(v['band'])}")
        out.append(style.dim(f"  {v['laws']}"))
        for r in v["findings"]:
            rel = _REL_SHORT[r["relation"]]
            rel = style.bad(f"{rel:<9}") if r["relation"] == "REQUIRED" else style.dim(f"{rel:<9}")
            out.append(f"  {r['check']:<4} {style.severity(str(r['severity']), 8)} {rel} {r['title'] or ''}")
            if r["note"]:
                out.append(f"       {r['note']}" + ("" if r["mapped"] else style.dim(" (not yet mapped; lens fallback)")))
            for ref in r["refs"]:
                p = pas.get(ref)
                if p:
                    wording = ("verbatim" if p.get("verbatim") else "summary") + (f", {p.get('kind', 'LAW').lower()}" if p.get("kind", "LAW") != "LAW" else "")
                    out.append(style.dim(f"       - {p['source']}: {p['title']} ({wording}) {p.get('url') or ''}".rstrip()))
        for lim in v["limitations"]:
            out.append(style.dim(f"  limitation: {lim}"))
        out.append("")
    out.append(style.dim(block["disclaimer"]))
    return "\n".join(out)
