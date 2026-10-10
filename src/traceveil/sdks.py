"""`traceveil sdks`: which tracking SDKs are in an APK, and which of them were actually looked up during an audit.

Static side: reads the class definitions of every `classes*.dex` in the APK (standard library only) and matches their
Java packages against `data/sdk_signatures.json`. Live side: the JSON report of a `traceveil audit` run. The two are
joined on the tracker catalogue's entity name. Code being present never proves it ran; a lookup never proves what was sent.
"""

from __future__ import annotations

import hashlib
import json
import re
import struct
import zipfile
from importlib import resources
from pathlib import Path

from . import __version__

SCHEMA_VERSION = 1
PRE_CONSENT = {"BEFORE_CONSENT", "NO_CONSENT_SEEN"}
LOCATION_PERMISSIONS = {"android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION",
                        "android.permission.ACCESS_BACKGROUND_LOCATION"}
LIMITATIONS = [
    "SDK found in APK means code with that package name is bundled; it does not show the code runs or what it collects.",
    "Live results are DNS lookups seen during one audit; they do not show payload contents, and no lookup does not prove the SDK is inactive.",
    "Only SDKs in the bundled signature list are recognised; renamed (obfuscated) or unlisted SDKs are not.",
]
_DEX_NAME = re.compile(r"classes\d*\.dex")


class ApkError(ValueError):
    pass


def load_signatures() -> list[dict]:
    text = resources.files("traceveil").joinpath("data/sdk_signatures.json").read_text(encoding="utf-8")
    return json.loads(text)["signatures"]


def dex_classes(data: bytes) -> list[str]:
    """Defined class names in one DEX file, as dotted Java names (`com.example.Foo`)."""
    if len(data) < 0x70 or data[:4] != b"dex\n":
        raise ApkError("not a DEX file")
    try:
        string_ids_off = struct.unpack_from("<I", data, 0x3C)[0]
        type_ids_off = struct.unpack_from("<I", data, 0x44)[0]
        class_defs_size, class_defs_off = struct.unpack_from("<II", data, 0x60)
        names = []
        for i in range(class_defs_size):
            type_idx = struct.unpack_from("<I", data, class_defs_off + 32 * i)[0]
            string_idx = struct.unpack_from("<I", data, type_ids_off + 4 * type_idx)[0]
            p = struct.unpack_from("<I", data, string_ids_off + 4 * string_idx)[0]
            while data[p] & 0x80:  # skip the uleb128 length
                p += 1
            p += 1
            desc = data[p:data.index(b"\0", p)].decode("utf-8", "replace")
            if desc.startswith("L") and desc.endswith(";"):
                names.append(desc[1:-1].replace("/", "."))
        return names
    except (struct.error, IndexError, ValueError) as e:
        raise ApkError(f"malformed DEX file ({e})") from e


def apk_classes(path: Path) -> tuple[list[str], int, str]:
    """(class names, number of dex files, sha256 of the APK)."""
    try:
        raw = path.read_bytes()
        with zipfile.ZipFile(path) as z:
            dexes = sorted(n for n in z.namelist() if _DEX_NAME.fullmatch(n))
            if not dexes:
                raise ApkError(f"{path.name} has no classes.dex (not an APK, or a split APK without code)")
            classes = [c for n in dexes for c in dex_classes(z.read(n))]
    except (OSError, zipfile.BadZipFile) as e:
        raise ApkError(f"cannot read {path}: {e}") from e
    return classes, len(dexes), hashlib.sha256(raw).hexdigest()


def obfuscated_share(classes: list[str]) -> float:
    """Share of classes whose package path is made only of 1-2 letter segments (typical of R8/ProGuard renaming)."""
    if not classes:
        return 0.0
    short = sum(1 for c in classes if "." in c and all(len(s) <= 2 for s in c.split(".")[:-1]))
    return round(short / len(classes), 3)


def match_sdks(classes: list[str], signatures: list[dict]) -> dict[str, dict]:
    found: dict[str, dict] = {}
    for sig in signatures:
        hits = {p: sum(1 for c in classes if c.startswith(p + ".")) for p in sig["prefixes"]}
        hits = {p: n for p, n in hits.items() if n}
        if hits:
            found[sig["entity"]] = {"category": sig["category"], "classes": sum(hits.values()), "matchedPrefixes": sorted(hits)}
    return found


def observed_from_report(rep: dict) -> tuple[dict[str, dict], str]:
    """Entities looked up live. Prefers the report's `observedTrackers`; older reports only name trackers in findings."""
    if isinstance(rep.get("observedTrackers"), list):
        return ({t["entity"]: {"category": t.get("category"), "lookups": t.get("lookups"), "phases": t.get("phases", [])}
                 for t in rep["observedTrackers"] if isinstance(t, dict) and t.get("entity")}, "observedTrackers")
    seen: dict[str, dict] = {}
    for f in rep.get("findings", []):
        for e in f.get("entities", []) if isinstance(f, dict) else []:
            seen.setdefault(e, {"category": None, "lookups": None, "phases": []})
    return seen, "findings"


def parse_requested_permissions(dumpsys: str) -> list[str]:
    """`requested permissions:` block of `dumpsys package <pkg>`."""
    out, inside, indent = [], False, 0
    for line in dumpsys.splitlines():
        stripped = line.strip()
        if stripped == "requested permissions:":
            inside, indent = True, len(line) - len(line.lstrip())
            continue
        if inside:
            if not stripped or len(line) - len(line.lstrip()) <= indent:
                break
            out.append(stripped.split(":")[0].split(",")[0].strip())
    return sorted(set(out))


def build(apk: Path, signatures: list[dict], rep: dict | None = None, permissions: list[str] | None = None) -> dict:
    classes, dex_count, sha = apk_classes(apk)
    static = match_sdks(classes, signatures)
    live, live_source = observed_from_report(rep) if rep else ({}, None)
    wants_location = bool(permissions) and bool(LOCATION_PERMISSIONS & set(permissions))
    known_category = {sig["entity"]: sig["category"] for sig in signatures}
    rows = []
    for entity in sorted(set(static) | set(live)):
        s, o = static.get(entity), live.get(entity)
        category = (s or {}).get("category") or (o or {}).get("category") or known_category.get(entity, "UNKNOWN")
        phases = set((o or {}).get("phases") or [])
        actions = []
        if s and o:
            status, evidence = "in-apk-and-live", "Observed: SDK code is bundled and its hosts were looked up during the audit."
        elif s:
            status = "in-apk-only"
            evidence = ("Not established: SDK code is bundled; its hosts were not looked up in this audit." if rep
                        else "SDK code is bundled. No audit report given, so live activity was not checked.")
            if rep:
                actions.append("Not seen in this audit: remove the dependency if unused, or confirm it stays off until consent.")
        else:
            status = "live-only"
            evidence = ("Inferred: hosts of this tracker were looked up, but no bundled SDK with a known package name matched "
                        "(WebView, custom HTTP code, a renamed SDK, or another library).")
            actions.append("Find what calls these hosts: check WebViews, HTTP clients and obfuscated libraries.")
        what = "this SDK" if s else "whatever calls these hosts"
        if phases & PRE_CONSENT:
            actions.append(f"Looked up before any consent choice: start {what} only after the user accepts.")
        if "AFTER_REJECT" in phases:
            actions.append(f"Looked up after the user refused: make sure Reject turns {what} off.")
        if category == "ADVERTISING" and s and wants_location:
            actions.append("The app requests location permission: review this ad SDK's location-collection defaults.")
        rows.append({"entity": entity, "category": category, "status": status,
                     "inApk": bool(s), "classes": (s or {}).get("classes", 0), "matchedPrefixes": (s or {}).get("matchedPrefixes", []),
                     "observed": None if o is None else {"lookups": o.get("lookups"), "phases": sorted(phases)},
                     "evidence": evidence, "actions": actions})
    counts = {k: sum(1 for r in rows if r["status"] == k) for k in ("in-apk-and-live", "in-apk-only", "live-only")}
    return {
        "schemaVersion": SCHEMA_VERSION,
        "tool": {"name": "traceveil", "version": __version__},
        "kind": "sdk-inventory",
        "apk": {"file": apk.name, "sha256": sha, "dexFiles": dex_count, "classes": len(classes), "obfuscatedShare": obfuscated_share(classes)},
        "audit": None if rep is None else {"requestId": rep.get("requestId"), "auditId": rep.get("auditId"),
                                           "package": (rep.get("target") or {}).get("package"), "liveSource": live_source},
        "permissions": None if permissions is None else {"source": "device", "requested": permissions},
        "sdks": rows,
        "counts": counts,
        "limitations": LIMITATIONS,
    }


def needs_attention(inv: dict) -> list[str]:
    """SDKs bundled in the APK that were looked up before consent or after Reject (used by --fail-on-consent)."""
    return [r["entity"] for r in inv["sdks"] if r["inApk"] and r["observed"]
            and (set(r["observed"]["phases"]) & (PRE_CONSENT | {"AFTER_REJECT"}))]


_LABEL = {"in-apk-and-live": "in APK + live", "in-apk-only": "in APK only", "live-only": "live only"}


def summary(inv: dict) -> str:
    a = inv["apk"]
    lines = [f"Traceveil SDK inventory  {a['file']}  ({a['dexFiles']} dex, {a['classes']} classes)"]
    if inv["audit"]:
        au = inv["audit"]
        lines.append(f"  compared with audit {au['requestId']} ({au['package']})"
                     + ("  [older report: only trackers named in findings]" if au["liveSource"] == "findings" else ""))
    else:
        lines.append("  no audit report given: static inventory only (add --report to compare with live lookups)")
    if not inv["sdks"]:
        lines.append("  no known tracking SDK found in the APK" + (" and none looked up live" if inv["audit"] else ""))
    for r in inv["sdks"]:
        live = "-"
        if r["observed"]:
            n = r["observed"]["lookups"]
            live = (f"{n} lookup{'' if n == 1 else 's'}" if n is not None else "named in findings") + (f": {', '.join(r['observed']['phases'])}" if r["observed"]["phases"] else "")
        elif inv["audit"]:
            live = "not looked up in this audit"
        lines.append(f"  {_LABEL[r['status']]:<14} {r['entity']:<30} {r['category']:<15} {live}")
        for act in r["actions"]:
            lines.append(f"      - {act}")
    if a["obfuscatedShare"] >= 0.3:
        lines.append(f"  note: {a['obfuscatedShare']:.0%} of classes look obfuscated; renamed SDKs are not recognised")
    c = inv["counts"]
    lines.append(f"  totals: {c['in-apk-and-live']} in APK + live, {c['in-apk-only']} in APK only, {c['live-only']} live only")
    lines.append("  note: bundled code does not prove it ran; a lookup does not prove what data was sent")
    return "\n".join(lines)
