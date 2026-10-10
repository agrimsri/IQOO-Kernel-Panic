"""traceveil.toml loading. Precedence: flags > config file > defaults."""

from __future__ import annotations

import json
from pathlib import Path

try:
    import tomllib
except ModuleNotFoundError:  # Python < 3.11
    tomllib = None

DEFAULTS = {
    "device": {"serial": "", "adb": ""},
    "target": {"package": "", "apk_glob": "app/build/outputs/apk/debug/*.apk"},
    "audit": {"plans": ["silent", "reject", "accept"], "min_score": 0, "fail_on": "high",
              "lenses": "all", "policy": "INDIA", "watch_network": True},
    "network": {"check_vpn": "warn", "online_ip": False, "endpoint": "", "cache_ttl_hours": 24, "redact_ip": True},
    "output": {"dir": ".traceveil/reports", "formats": ["json", "summary"]},
}
CHECK_VPN_MODES = ("off", "warn", "fail")

TEMPLATE = """# Traceveil CLI configuration. Command-line flags override these values.
[device]
serial = ""   # leave empty when one phone is attached
adb = ""      # leave empty to auto-detect (WSL: Windows adb.exe)

[target]
package = ""
apk_glob = "app/build/outputs/apk/debug/*.apk"

[audit]
plans = ["silent", "reject", "accept"]
min_score = 0
fail_on = "high"
lenses = "all"           # legal lenses (same rules as the app): "all" or e.g. ["INDIA", "EU", "UK", "US_CALIFORNIA"]
policy = "INDIA"         # the lens whose score decides the exit code
watch_network = true     # sample the app's sockets during the audit to find traffic the DNS capture cannot see

[network]
check_vpn = "warn"       # off | warn | fail
online_ip = false        # true makes one cached lookup to `endpoint`; that service sees the public IP of the phone (or laptop with --egress-from host)
endpoint = ""            # https URL returning JSON with a 2-letter "country"/"country_code" (and optionally "ip"), e.g. https://ipapi.co/json/
cache_ttl_hours = 24
redact_ip = true

[output]
dir = ".traceveil/reports"
formats = ["json", "summary"]
"""


class ConfigError(Exception):
    pass


def _merge(base: dict, over: dict) -> dict:
    out = {k: dict(v) if isinstance(v, dict) else v for k, v in base.items()}
    for key, val in over.items():
        if isinstance(val, dict) and isinstance(out.get(key), dict):
            out[key].update(val)
        else:
            out[key] = val
    return out


def load(path: str | None = None, cwd: Path | None = None) -> dict:
    cwd = cwd or Path.cwd()
    candidates = [Path(path)] if path else [cwd / "traceveil.toml", cwd / "traceveil.json"]
    for p in candidates:
        if not p.is_file():
            if path:
                raise ConfigError(f"config file not found: {p}")
            continue
        try:
            if p.suffix == ".toml":
                if tomllib is None:
                    raise ConfigError("traceveil.toml needs Python 3.11+; use traceveil.json instead")
                data = tomllib.loads(p.read_text(encoding="utf-8"))
            else:
                data = json.loads(p.read_text(encoding="utf-8"))
        except (ValueError, OSError) as e:
            raise ConfigError(f"cannot read {p}: {e}") from e
        cfg = _merge(DEFAULTS, data)
        validate(cfg)
        return cfg
    return _merge(DEFAULTS, {})


def validate(cfg: dict) -> None:
    from . import laws
    if laws.parse(cfg["audit"].get("policy", "")) is None:
        raise ConfigError(f"audit.policy must be one of {laws.ids()}, got {cfg['audit'].get('policy')!r}")
    try:
        laws.select(cfg["audit"].get("lenses"))
    except ValueError as e:
        raise ConfigError(f"audit.lenses: {e}") from e
    mode = cfg["network"].get("check_vpn")
    if mode not in CHECK_VPN_MODES:
        raise ConfigError(f"network.check_vpn must be one of {CHECK_VPN_MODES}, got {mode!r}")
    if cfg["network"].get("online_ip") and not cfg["network"].get("endpoint"):
        raise ConfigError("network.online_ip is true but network.endpoint is empty (no default vendor is baked in)")
    endpoint = cfg["network"].get("endpoint")
    if endpoint and not str(endpoint).lower().startswith("https://"):
        raise ConfigError("network.endpoint must be an https:// URL")
