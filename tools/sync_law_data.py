"""Copy the legal-lens data the Android app uses into the Python package, so both read the same rules.

Source of truth: core/src/main/resources/law/{profiles,passages}.json (LegalLens). Run after those files change:
    python3 tools/sync_law_data.py
tools/tests/test_traceveil_laws.py fails while the copies differ.
"""

import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "core/src/main/resources/law"
DST = ROOT / "src/traceveil/data/law"
FILES = ("profiles.json", "passages.json")


def main() -> int:
    DST.mkdir(parents=True, exist_ok=True)
    for name in FILES:
        shutil.copyfile(SRC / name, DST / name)
        print(f"copied {SRC / name} -> {DST / name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
