"""Copy the app data the CLI reads into the Python package, so the CLI and the Android app use the same rules.

Sources of truth (core/src/main/resources):
    law/{profiles,passages}.json   LegalLens profiles and cited passages
    fixes/fix_templates.json       the app's "How to fix" templates (FixCatalog)
    trackers/trackers.json         the tracker catalogue (entity -> sdkKey, company, company country)
Run after those files change:
    python3 tools/sync_law_data.py
tools/tests/test_traceveil_laws.py and test_traceveil_fixes.py fail while the copies differ.
"""

import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "core/src/main/resources"
DST = ROOT / "src/traceveil/data"
FILES = ("law/profiles.json", "law/passages.json", "fixes/fix_templates.json", "trackers/trackers.json")


def main() -> int:
    for name in FILES:
        (DST / name).parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(SRC / name, DST / name)
        print(f"copied {SRC / name} -> {DST / name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
