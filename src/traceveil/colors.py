"""Terminal colours for summaries. Off unless the stream is a TTY; NO_COLOR turns them off, FORCE_COLOR on."""

from __future__ import annotations

import os

_SEVERITY = {"CRITICAL": "1;97;41", "HIGH": "1;31", "MEDIUM": "1;33", "LOW": "36", "INFO": "2"}
_BAND = {"READY": "1;32", "NEEDS_WORK": "1;33", "AT_RISK": "1;31", "INCONCLUSIVE": "1;35"}


def enabled_for(stream) -> bool:
    if os.environ.get("NO_COLOR"):
        return False
    if os.environ.get("FORCE_COLOR", "") not in ("", "0"):
        return True
    return bool(getattr(stream, "isatty", lambda: False)())


class Style:
    """Pads first, then colours, so column alignment survives the escape codes."""

    def __init__(self, enabled: bool):
        self.enabled = enabled

    def _paint(self, text: str, code: str | None) -> str:
        return f"\x1b[{code}m{text}\x1b[0m" if self.enabled and code else text

    def severity(self, sev: str, width: int = 0) -> str:
        return self._paint(f"{sev:<{width}}", _SEVERITY.get(str(sev).upper()))

    def band(self, band: str, width: int = 0) -> str:
        return self._paint(f"{band:<{width}}", _BAND.get(str(band).upper()))

    def score(self, score, band: str, width: int = 0) -> str:
        return self._paint(f"{'-' if score is None else score:>{width}}", _BAND.get(str(band).upper()))

    def tracker(self, name: str) -> str:
        return self._paint(name, "1;36")

    def bold(self, text: str) -> str:
        return self._paint(text, "1")

    def dim(self, text: str) -> str:
        return self._paint(text, "2")

    def ok(self, text: str) -> str:
        return self._paint(text, "1;32")

    def warn(self, text: str) -> str:
        return self._paint(text, "1;33")

    def bad(self, text: str) -> str:
        return self._paint(text, "1;31")


PLAIN = Style(False)
