"""Opt-in egress-country lookup. Never runs unless the caller passes an explicit https endpoint; results are cached.

Expected response: a JSON object with a 2-letter ISO country in `country`, `country_code` or `countryCode` (first
valid one wins; full country names are ignored) and optionally the caller's address in `ip` or `query`. A body with a
truthy `error` is a provider failure. The public IP is redacted before anything is cached or returned.

Vantage matters: `phone` runs curl on the device under audit (what the audit actually sees); `host` runs on this
machine and only describes the laptop's network.
"""

from __future__ import annotations

import hashlib
import ipaddress
import json
import shlex
import time
import urllib.request
from pathlib import Path

COUNTRY_KEYS = ("country", "country_code", "countryCode")
IP_KEYS = ("ip", "query")


def redact_ip(ip: str | None) -> str | None:
    """IPv4 -> /24, IPv6 -> /48. Returns None for anything unparsable."""
    if not ip:
        return None
    try:
        addr = ipaddress.ip_address(ip.strip())
    except ValueError:
        return None
    prefix = 24 if addr.version == 4 else 48
    return str(ipaddress.ip_network(f"{addr}/{prefix}", strict=False))


def _load(path: Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def host_fetch(endpoint: str, timeout: float, opener=urllib.request.urlopen) -> str:
    with opener(endpoint, timeout=timeout) as resp:
        return resp.read().decode("utf-8", "replace")


def phone_fetch(device, endpoint: str, timeout: float) -> str:
    """Runs the device's own curl. The endpoint is shell-quoted; only https URLs reach this point."""
    return device.shell(f"curl -sS --max-time {int(timeout)} {shlex.quote(endpoint)}")


def parse_response(body: str) -> tuple[str | None, str | None]:
    """Returns (country, ip). Raises ValueError for malformed or error responses."""
    data = json.loads(body)
    if not isinstance(data, dict):
        raise ValueError("response is not a JSON object")
    if data.get("error"):
        raise ValueError("provider reported an error")
    country = next((v.strip().upper() for k in COUNTRY_KEYS
                    if isinstance(v := data.get(k), str) and len(v.strip()) == 2 and v.strip().isalpha()), None)
    ip = next((v for k in IP_KEYS if isinstance(v := data.get(k), str)), None)
    return country, ip


def lookup(endpoint: str, cache_path: Path, ttl_hours: float, redact: bool = True, now=time.time,
           opener=urllib.request.urlopen, timeout: float = 8.0, fetch=None, vantage: str = "host") -> dict:
    """Returns {country, network, cached, error, vantage}. `network` is the redacted IP prefix (or None)."""
    base = {"vantage": vantage}
    if not endpoint.lower().startswith("https://"):
        return {**base, "country": None, "network": None, "cached": False, "error": "EndpointMustBeHttps"}
    key = hashlib.sha256(f"{vantage}|{endpoint}".encode()).hexdigest()[:16]
    cache = _load(cache_path)
    hit = cache.get(key)
    if hit and now() - hit.get("at", 0) < ttl_hours * 3600:
        return {**base, **hit["value"], "cached": True, "error": None}
    try:
        body = fetch(endpoint, timeout) if fetch else host_fetch(endpoint, timeout, opener)
        country, ip = parse_response(body)
    except Exception as e:  # network, TLS, adb and JSON errors all mean "unknown", never a guess
        return {**base, "country": None, "network": None, "cached": False, "error": type(e).__name__}
    value = {"country": country, "network": redact_ip(ip) if redact else (ip if isinstance(ip, str) else None)}
    if country is None:  # a response without a usable country is not cached as a success
        return {**base, **value, "cached": False, "error": "NoCountryInResponse"}
    cache[key] = {"at": now(), "value": value}
    try:
        cache_path.parent.mkdir(parents=True, exist_ok=True)
        cache_path.write_text(json.dumps(cache), encoding="utf-8")
    except OSError:
        pass
    return {**base, **value, "cached": False, "error": None}
