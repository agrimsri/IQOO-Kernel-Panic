"""Hostname parsing and the exact feature definition shared with Kotlin's HostnameModel.

Any change to `features()` must be mirrored in
core/src/main/kotlin/com/dpdpxray/core/ml/HostnameModel.kt; the golden test enforces it.
"""
from __future__ import annotations

import math
import re
from collections import Counter
from pathlib import Path

HASH_BITS = 16
MASK = (1 << HASH_BITS) - 1
NGRAM_MIN, NGRAM_MAX = 2, 5
DENSE = ["length", "labels", "digitRatio", "entropy"]

_HOST = re.compile(r"^(?=.{1,253}$)([a-z0-9_](?:[a-z0-9_-]{0,61}[a-z0-9_])?)(\.[a-z0-9_](?:[a-z0-9_-]{0,61}[a-z0-9_])?)+$")
_IPV4 = re.compile(r"^\d{1,3}(\.\d{1,3}){3}$")


def normalise(host: str) -> str:
    return host.strip().lower().rstrip(".")


def is_hostname(host: str) -> bool:
    return bool(_HOST.match(host)) and not _IPV4.match(host)


# ----------------------------------------------------------------------------- features (mirror in Kotlin)

def fnv1a32(data: bytes) -> int:
    h = 0x811C9DC5
    for b in data:
        h ^= b
        h = (h * 0x01000193) & 0xFFFFFFFF
    return h


def ngrams(host: str) -> list[str]:
    """Distinct character n-grams (2-5) of '^host$', in first-seen order."""
    s = "^" + host + "$"
    seen: dict[str, None] = {}
    for n in range(NGRAM_MIN, NGRAM_MAX + 1):
        for i in range(len(s) - n + 1):
            seen.setdefault(s[i:i + n], None)
    return list(seen)


def bucket(gram: str) -> int:
    return fnv1a32(gram.encode("utf-8")) & MASK


def dense(host: str) -> list[float]:
    n = len(host)
    if n == 0:
        return [0.0, 0.0, 0.0, 0.0]
    labels = host.count(".") + 1
    digits = sum(1 for c in host if "0" <= c <= "9")
    entropy = 0.0
    for count in Counter(host).values():  # insertion (first-seen) order, like Kotlin's LinkedHashMap
        p = count / n
        entropy -= p * math.log2(p)
    return [n / 64.0, labels / 8.0, digits / n, entropy / 5.0]


def features(host: str) -> tuple[list[int], list[float]]:
    """(sorted distinct active hash buckets, dense values) for an already-normalised host."""
    return sorted({bucket(g) for g in ngrams(host)}), dense(host)


# Second-level labels that sit under a two-letter country code (co.uk, com.au, co.in, ne.jp ...).
SECOND_LEVEL = {"co", "com", "net", "org", "gov", "ac", "edu", "ne", "or", "go", "gen", "firm", "ind", "nic", "res",
                "mil", "ltd", "plc", "nhs", "sch", "info", "biz"}


def site(host: str) -> str:
    """The 'site' part TrackerLens scores: a small, list-free approximation of the registrable domain.

    Last two labels, or last three when the second-to-last is a common second-level label under a
    two-letter country code. Mirrored exactly in Kotlin; its agreement with the full Public Suffix
    List is measured and reported by the training script.
    """
    labels = host.split(".")
    if len(labels) >= 3 and len(labels[-1]) == 2 and labels[-2] in SECOND_LEVEL:
        return ".".join(labels[-3:])
    return ".".join(labels[-2:])


# ----------------------------------------------------------------------------- Public Suffix List

class PublicSuffixList:
    def __init__(self, path: Path):
        self.rules: set[str] = set()
        self.wild: set[str] = set()
        self.exceptions: set[str] = set()
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("//"):
                continue
            rule = line.split()[0].lower()
            try:
                rule = ".".join(lbl if lbl in ("*", "!") or lbl.isascii() else lbl.encode("idna").decode("ascii")
                                for lbl in rule.split("."))
            except UnicodeError:
                continue
            if rule.startswith("!"):
                self.exceptions.add(rule[1:])
            elif rule.startswith("*."):
                self.wild.add(rule[2:])
            else:
                self.rules.add(rule)

    def registrable(self, host: str) -> str:
        """eTLD+1 (e.g. 'smetrics.example.co.uk' -> 'example.co.uk'); the host itself if it is a suffix."""
        labels = host.split(".")
        start = len(labels) - 1  # implicit '*' rule
        for i in range(len(labels)):
            cand = ".".join(labels[i:])
            if cand in self.exceptions:
                start = i + 1
                break
            if cand in self.rules or (i + 1 < len(labels) and ".".join(labels[i + 1:]) in self.wild):
                start = i
                break
        return host if start == 0 else ".".join(labels[start - 1:])


# ----------------------------------------------------------------------------- filter lists

_RULE = re.compile(r"^\|\|([a-z0-9_.-]+)\^(\$third-party)?$")
_EXCEPTION = re.compile(r"^@@\|\|([a-z0-9_.-]+)\^")


def blocked_hosts(path: Path) -> set[str]:
    """Hosts blocked outright by `||host^` (optionally `$third-party`) rules, minus `@@||host^` exceptions."""
    hosts, allowed = set(), set()
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        m = _RULE.match(line.strip())
        if m:
            h = normalise(m.group(1))
            if is_hostname(h):
                hosts.add(h)
            continue
        m = _EXCEPTION.match(line.strip())
        if m:
            allowed.add(normalise(m.group(1)))
    return hosts - allowed


def covered_by(host: str, blocked: set[str]) -> bool:
    """True when the host or any parent domain is in `blocked` (a `||d^` rule also blocks subdomains)."""
    labels = host.split(".")
    return any(".".join(labels[i:]) in blocked for i in range(len(labels) - 1))
