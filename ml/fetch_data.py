"""Download the public datasets used by the TraceVeil models into ml/data/raw/.

Raw data never goes into Git (ml/data/ is git-ignored). Each source is listed in
ml/DATA_SOURCES.md with its licence, citation and the SHA-256 we trained on.
A changed upstream file is reported, not silently accepted.

Usage (from the repository root):
    python -P ml/fetch_data.py            # everything
    python -P ml/fetch_data.py apk        # only the APK-permission sources
    python -P ml/fetch_data.py tracker    # only the hostname sources
"""
from __future__ import annotations

import base64
import hashlib
import sys
import urllib.request
import zipfile
from pathlib import Path

RAW = Path(__file__).resolve().parent / "data" / "raw"

AOSP = "https://android.googlesource.com/platform/frameworks/base/+/refs/tags/{tag}/core/res/AndroidManifest.xml?format=TEXT"
AOSP_TAGS = ["android-2.3.3_r1", "android-4.4_r1", "android-6.0.0_r1", "android-9.0.0_r1", "android-14.0.0_r1"]

# name -> (group, url, local file, expected sha256 or None, kind)
SOURCES = {
    "naticusdroid": ("apk", "https://archive.ics.uci.edu/static/public/722/naticusdroid+android+permissions+dataset.zip",
                     "naticusdroid/naticusdroid.zip",
                     "8d1a47a3a307bf68c3b0a51d58e146eddef941b4e403aeafce03a61fe96cbc82", "zip"),
    "tuandromd": ("apk", "https://archive.ics.uci.edu/static/public/855/tuandromd+(tezpur+university+android+malware+dataset).zip",
                  "tuandromd/tuandromd.zip",
                  "441d2005b97f3816e8b64e692c5e5a0b629685091638aad9e67fd81f40a5cf5f", "zip"),
}
for _tag in AOSP_TAGS:
    SOURCES[f"aosp-{_tag}"] = ("apk", AOSP.format(tag=_tag), f"aosp/AndroidManifest_{_tag}.xml", None, "b64")

TRACKER_SOURCES = {
    "easyprivacy": ("tracker", "https://easylist.to/easylist/easyprivacy.txt", "easyprivacy/easyprivacy.txt", None, "plain"),
    "majestic": ("tracker", "https://downloads.majestic.com/majestic_million.csv", "majestic/majestic_million.csv", None, "plain"),
    "psl": ("tracker", "https://publicsuffix.org/list/public_suffix_list.dat", "psl/public_suffix_list.dat", None, "plain"),
}
SOURCES.update(TRACKER_SOURCES)


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def safe_extract(zip_path: Path, dest: Path) -> None:
    """Extract only plain files that stay inside dest (no absolute paths, no '..')."""
    with zipfile.ZipFile(zip_path) as z:
        for info in z.infolist():
            target = (dest / info.filename).resolve()
            if dest.resolve() not in target.parents or info.is_dir():
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(z.read(info))


def fetch(name: str) -> None:
    _, url, rel, expected, kind = SOURCES[name]
    out = RAW / rel
    out.parent.mkdir(parents=True, exist_ok=True)
    if not out.exists():
        req = urllib.request.Request(url, headers={"User-Agent": "traceveil-ml-research/1.0"})
        with urllib.request.urlopen(req, timeout=120) as r:
            data = r.read()
        if kind == "b64":
            data = base64.b64decode(data)
        out.write_bytes(data)
    digest = sha256(out)
    status = "ok" if expected is None or digest == expected else "CHANGED UPSTREAM (metrics may differ)"
    print(f"{name:28s} {digest}  {status}")
    if kind == "zip":
        safe_extract(out, out.parent)


def main(argv: list[str]) -> None:
    group = argv[1] if len(argv) > 1 else None
    for name, spec in SOURCES.items():
        if group is None or spec[0] == group:
            fetch(name)


if __name__ == "__main__":
    main(sys.argv)
