"""Network-environment signals: VPN, proxy and geography hints. Signals only, never proof.

Parsing rule learned from a real iQOO dump: `dumpsys connectivity` contains hundreds of NetworkRequest lines that
mention VPN (`NOT_VPN`, `Transports: ...|VPN`). Only NetworkAgentInfo lines under "Current Networks:" describe real
networks, so everything else is ignored. Raw lines are never stored because they carry SSID/BSSID/MAC.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field

LIMITATIONS = (
    "IP geolocation is approximate and not proof of physical location. "
    "VPN/proxy detection can miss split-tunnel, per-app and in-app proxies, and can false-positive on carrier or enterprise networks."
)
TUNNEL_PREFIXES = ("tun", "wg", "ppp", "ipsec", "tap")
OWN_PACKAGE = "com.dpdpxray.app"


@dataclass
class Network:
    network_id: str
    kind: str
    transports: list[str]
    interface: str
    owner_uid: int | None
    proxy: str | None
    is_default: bool


def parse_networks(dump: str) -> list[Network]:
    default = re.search(r"Active default network:\s*(\d+)", dump)
    default_id = default.group(1) if default else None
    out, inside = [], False
    for line in dump.replace("\r", "").splitlines():
        if line.startswith("Current Networks:"):
            inside = True
            continue
        if inside and line and not line.startswith(" "):
            break
        if not (inside and "NetworkAgentInfo{" in line):
            continue
        nid = re.search(r"network\{(\d+)\}", line)
        kind = re.search(r"ni\{(\w+)", line)
        transports = re.search(r"Transports:\s*([A-Z_|]+)", line)
        iface = re.search(r"InterfaceName:\s*(\S+)", line)
        owner = re.search(r"OwnerUid:\s*(-?\d+)", line)
        proxy = re.search(r"HttpProxy:\s*(\[[^\]]*\]\s*\d+|\S+://\S+)", line)
        out.append(Network(
            network_id=nid.group(1) if nid else "?",
            kind=kind.group(1) if kind else "?",
            transports=transports.group(1).split("|") if transports else [],
            interface=iface.group(1) if iface else "",
            owner_uid=int(owner.group(1)) if owner else None,
            proxy=proxy.group(1) if proxy else None,
            is_default=bool(nid) and nid.group(1) == default_id,
        ))
    return out


@dataclass
class Signal:
    name: str
    strength: str  # strong | medium | weak
    detail: str


@dataclass
class Snapshot:
    """What the device reported. A None field means unknown, which is never treated as 'absent'."""
    networks: list[Network] | None = None
    own_uid: int | None = None
    global_proxy: str | None = None
    always_on_vpn: str | None = None
    always_on_lockdown: str | None = None
    private_dns_mode: str | None = None
    interfaces: list[str] | None = None
    sim_country: str | None = None
    network_country: str | None = None
    timezone: str | None = None
    locale: str | None = None


def _country(value: str | None) -> str | None:
    if not value:
        return None
    first = value.split(",")[0].strip().upper()
    return first if len(first) == 2 and first.isalpha() else None


def parse_interfaces(ip_addr: str) -> list[str]:
    names = []
    for line in ip_addr.replace("\r", "").splitlines():
        m = re.match(r"\s*\d+:\s+([^\s:@]+)", line)
        if m and m.group(1) not in names:
            names.append(m.group(1))
    return names


def collect(device) -> Snapshot:
    """Gather a snapshot through adb; every field degrades to None on failure."""
    def safe(fn):
        try:
            return fn()
        except Exception:
            return None

    snap = Snapshot()
    dump = safe(lambda: device.shell("dumpsys connectivity"))
    if dump and "Current Networks:" in dump:
        snap.networks = parse_networks(dump)
    uid_line = safe(lambda: device.shell(f"pm list packages -U {OWN_PACKAGE}"))
    m = re.search(r"uid:(\d+)", uid_line or "")
    snap.own_uid = int(m.group(1)) if m else None
    snap.global_proxy = safe(lambda: device.setting("global", "http_proxy"))
    snap.always_on_vpn = safe(lambda: device.setting("secure", "always_on_vpn_app"))
    snap.always_on_lockdown = safe(lambda: device.setting("secure", "always_on_vpn_lockdown"))
    snap.private_dns_mode = safe(lambda: device.setting("global", "private_dns_mode"))
    ip = safe(lambda: device.shell("ip -o addr"))
    snap.interfaces = parse_interfaces(ip) if ip else None
    snap.sim_country = _country(safe(lambda: device.getprop("gsm.sim.operator.iso-country")))
    snap.network_country = _country(safe(lambda: device.getprop("gsm.operator.iso-country")))
    snap.timezone = safe(lambda: device.getprop("persist.sys.timezone")) or None
    snap.locale = safe(lambda: device.getprop("persist.sys.locale")) or None
    return snap


def derive_signals(snap: Snapshot, exclude_own_vpn: bool = True) -> list[Signal]:
    signals: list[Signal] = []
    for net in snap.networks or []:
        is_vpn = "VPN" in net.transports or net.kind == "VPN"
        own = exclude_own_vpn and snap.own_uid is not None and net.owner_uid == snap.own_uid
        if is_vpn and not own:
            signals.append(Signal("vpn_transport", "strong", f"network {net.network_id} has VPN transport"
                                  + (" (active default)" if net.is_default else "")))
        if net.proxy:
            signals.append(Signal("link_proxy", "strong", f"network {net.network_id} carries an HTTP proxy"))
    if snap.global_proxy:
        signals.append(Signal("global_proxy", "strong", "global HTTP proxy is configured"))
    if snap.always_on_vpn:
        signals.append(Signal("always_on_vpn", "strong", "an always-on VPN app is configured"))
    tunnels = [i for i in snap.interfaces or [] if i.startswith(TUNNEL_PREFIXES)]
    if tunnels and not _tunnels_explained_by_own_vpn(snap, tunnels):
        signals.append(Signal("tunnel_interface", "medium", f"tunnel-like interface present: {', '.join(tunnels)}"))
    if snap.private_dns_mode != "off":
        mode = snap.private_dns_mode or "unset (Automatic)"
        signals.append(Signal("private_dns", "weak", f"Private DNS mode is {mode}: encrypted DNS can limit capture (not a VPN)"))
    return signals


def _tunnels_explained_by_own_vpn(snap: Snapshot, tunnels: list[str]) -> bool:
    """Our own capture VPN shows up as tun0. If an own-uid VPN network exists and is the only VPN, don't flag it."""
    nets = snap.networks or []
    own_vpn = [n for n in nets if ("VPN" in n.transports) and n.owner_uid == snap.own_uid and snap.own_uid is not None]
    other_vpn = [n for n in nets if ("VPN" in n.transports) and n not in own_vpn]
    return bool(own_vpn) and not other_vpn and len(tunnels) <= len(own_vpn)


# Minimal timezone->country map for the zones that matter in practice; unknown zones stay unknown.
TZ_COUNTRY = {"Asia/Kolkata": "IN", "Asia/Calcutta": "IN", "America/New_York": "US", "America/Chicago": "US",
              "America/Denver": "US", "America/Los_Angeles": "US", "Europe/London": "GB", "Europe/Berlin": "DE",
              "Europe/Paris": "FR", "Asia/Singapore": "SG", "Asia/Dubai": "AE", "Asia/Tokyo": "JP",
              "Australia/Sydney": "AU", "Asia/Dhaka": "BD", "Asia/Karachi": "PK", "Asia/Colombo": "LK",
              "Asia/Kathmandu": "NP"}


@dataclass
class Check:
    id: str
    status: str       # none | possible | likely | unknown
    confidence: str   # low | medium | high
    signals: list[str] = field(default_factory=list)
    reasons: list[str] = field(default_factory=list)


def check_vpn_proxy(snap: Snapshot, signals: list[Signal]) -> Check:
    relevant = [s for s in signals if s.name != "private_dns"]
    strong = [s for s in relevant if s.strength == "strong"]
    if strong:
        return Check("vpn_proxy", "likely", "high" if len(strong) > 1 or snap.networks else "medium",
                     [s.detail for s in relevant], ["a strong signal was reported by Android itself"])
    if relevant:
        return Check("vpn_proxy", "possible", "low", [s.detail for s in relevant], ["only indirect signals"])
    if snap.networks is None and snap.interfaces is None:
        return Check("vpn_proxy", "unknown", "low", [], ["connectivity data could not be read"])
    return Check("vpn_proxy", "none", "medium" if snap.networks is not None else "low", [],
                 ["no VPN/proxy signal seen; this does not rule out split-tunnel or in-app proxies"])


def device_countries(snap: Snapshot) -> dict[str, str]:
    out = {}
    if snap.sim_country:
        out["sim"] = snap.sim_country
    if snap.network_country:
        out["network"] = snap.network_country
    tz = TZ_COUNTRY.get(snap.timezone or "")
    if tz:
        out["timezone"] = tz
    return out


def check_geo(snap: Snapshot, egress_country: str | None, vpn: Check, vantage: str = "phone") -> Check:
    check = _check_geo(snap, egress_country, vpn)
    if vantage == "host" and check.status in ("none", "possible", "likely"):
        # The laptop's egress says nothing certain about the phone (it may be on cellular or a VPN).
        check.status = "possible" if check.status != "none" else "none"
        check.confidence = "low"
        check.reasons.append("egress was measured from the host machine, not the phone")
    return check


def _check_geo(snap: Snapshot, egress_country: str | None, vpn: Check) -> Check:
    ctx = device_countries(snap)
    if egress_country is None:
        return Check("geo_mismatch", "unknown", "low", [],
                     ["no egress country (offline by default; use --online-ip to enable one lookup)"])
    if not ctx:
        return Check("geo_mismatch", "unknown", "low", [], ["no SIM/network/timezone country available to compare"])
    egress = egress_country.upper()
    disagree = {k: v for k, v in ctx.items() if v != egress}
    if not disagree:
        return Check("geo_mismatch", "none", "medium", [f"egress {egress} agrees with {', '.join(ctx)}"], [])
    sigs = [f"egress {egress} differs from {k} {v}" for k, v in disagree.items()]
    if len(disagree) == len(ctx) and len(ctx) >= 2 and vpn.status == "likely":
        return Check("geo_mismatch", "likely", "medium", sigs, ["VPN signal plus multiple device-context countries disagree with egress"])
    conf = "medium" if vpn.status in ("likely", "possible") else "low"
    return Check("geo_mismatch", "possible", conf, sigs,
                 ["travel, roaming, corporate networks and CDNs can also explain this"])
