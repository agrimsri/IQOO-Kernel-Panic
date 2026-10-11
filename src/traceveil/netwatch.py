"""Network beyond DNS: what the audited app actually connected to, sampled from the laptop over adb.

TraceVeil's capture sees plain DNS lookups. An SDK that resolves names over encrypted DNS (DNS-over-HTTPS or -TLS),
connects to a hardcoded IP, or sends DNS straight to an outside resolver produces no lookup the capture can read. It
still has to open a socket, and `adb shell` (group readproc) can read every socket on the phone with its owner UID from
/proc/net/{tcp,tcp6,udp,udp6}. Sampling those during the audit and matching each remote address against the addresses
the visible lookups returned (`dnsAnswers` in the report) shows:

- encrypted DNS: port 853, or a public DNS-over-HTTPS resolver on 443 (or a lookup of a DoH resolver's name);
- plain DNS sent straight to an outside resolver, bypassing TraceVeil's relay;
- connections to addresses no visible lookup returned (hidden DNS or a hardcoded IP);
- QUIC (UDP 443) connections;
- and, for matched connections, that a tracker was actually *connected to*, not only looked up.

Only addresses, ports and the owner UID are read: no payload, no hostnames beyond the app's own lookups. Sampling can
miss connections shorter than the interval, and a CDN address shared by many services is matched by address only.
"""

from __future__ import annotations

import ipaddress
import re
import time
from dataclasses import dataclass, field
from typing import Callable

from .colors import PLAIN, Style

PROC_FILES = ("tcp", "tcp6", "udp", "udp6")
SAMPLE_CMD = "for f in tcp tcp6 udp udp6; do echo \"## $f\"; cat /proc/net/$f 2>/dev/null; done"
TCP_STATES = {"01": "ESTABLISHED", "02": "SYN_SENT", "03": "SYN_RECV", "04": "FIN_WAIT1", "05": "FIN_WAIT2", "06": "TIME_WAIT",
              "07": "CLOSE", "08": "CLOSE_WAIT", "09": "LAST_ACK", "0A": "LISTEN", "0B": "CLOSING"}

# Public resolvers that answer DNS-over-HTTPS on 443 (and DoT on 853). A 443 connection to one is "likely DoH".
DOH_RESOLVERS = {
    "8.8.8.8": "Google Public DNS", "8.8.4.4": "Google Public DNS",
    "2001:4860:4860::8888": "Google Public DNS", "2001:4860:4860::8844": "Google Public DNS",
    "1.1.1.1": "Cloudflare", "1.0.0.1": "Cloudflare", "1.1.1.2": "Cloudflare", "1.0.0.2": "Cloudflare",
    "1.1.1.3": "Cloudflare", "1.0.0.3": "Cloudflare", "2606:4700:4700::1111": "Cloudflare", "2606:4700:4700::1001": "Cloudflare",
    "9.9.9.9": "Quad9", "149.112.112.112": "Quad9", "9.9.9.10": "Quad9", "9.9.9.11": "Quad9", "2620:fe::fe": "Quad9", "2620:fe::9": "Quad9",
    "208.67.222.222": "OpenDNS", "208.67.220.220": "OpenDNS", "2620:119:35::35": "OpenDNS", "2620:119:53::53": "OpenDNS",
    "94.140.14.14": "AdGuard DNS", "94.140.15.15": "AdGuard DNS",
    "185.228.168.9": "CleanBrowsing", "185.228.169.9": "CleanBrowsing",
    "223.5.5.5": "AliDNS", "223.6.6.6": "AliDNS", "194.242.2.2": "Mullvad DNS",
}
DOH_HOSTNAMES = ("dns.google", "cloudflare-dns.com", "one.one.one.one", "dns.quad9.net", "doh.opendns.com", "dns.adguard-dns.com",
                 "dns.adguard.com", "dns.nextdns.io", "doh.cleanbrowsing.org", "dns.alidns.com", "doh.mullvad.net")

HIDDEN_KINDS = ("encrypted-dns", "direct-dns", "no-lookup", "quic")
LIMITATIONS = [
    "Sampled once per interval: a connection opened and closed between samples is missed.",
    "Addresses, ports and the owner app only; no payload is read. A matched address can be a CDN shared by many services.",
    "An address with no visible lookup can also come from a lookup made before the capture started (DNS cache).",
    "Android shows closed connections (TIME_WAIT) without their app, so only connections still open at a sample count.",
]


def _ip(hexaddr: str) -> str:
    raw = bytes.fromhex(hexaddr)
    if len(raw) == 4:
        return str(ipaddress.IPv4Address(raw[::-1]))
    words = b"".join(raw[i:i + 4][::-1] for i in range(0, 16, 4))
    ip6 = ipaddress.IPv6Address(words)
    return str(ip6.ipv4_mapped) if ip6.ipv4_mapped else str(ip6)


@dataclass(frozen=True)
class Socket:
    proto: str      # tcp | udp
    ip: str
    port: int
    state: str
    uid: int


def parse_proc_net(text: str) -> list[Socket]:
    """The output of SAMPLE_CMD: every socket with a remote endpoint (listeners and unconnected UDP are skipped)."""
    out, proto = [], None
    for line in text.splitlines():
        line = line.strip()
        if line.startswith("## "):
            proto = "udp" if line[3:].startswith("udp") else "tcp"
            continue
        parts = line.split()
        if proto is None or len(parts) < 8 or not re.match(r"\d+:$", parts[0]):
            continue
        try:
            rip, rport = parts[2].split(":")
            port, uid = int(rport, 16), int(parts[7])
            ip = _ip(rip)
        except (ValueError, IndexError):
            continue
        if port == 0 or ipaddress.ip_address(ip).is_unspecified:
            continue
        state = TCP_STATES.get(parts[3].upper(), parts[3]) if proto == "tcp" else "CONNECTED"
        if state == "LISTEN":
            continue
        out.append(Socket(proto, ip, port, state, uid))
    return out


@dataclass
class Seen:
    first: float
    last: float
    samples: int = 0
    states: set = field(default_factory=set)


class Sampler:
    """Samples the sockets owned by one UID. `sample()` is cheap enough to call once a second during an audit."""

    def __init__(self, dev, uid: int, clock: Callable[[], float] = time.monotonic):
        self.dev, self.uid, self.clock = dev, uid, clock
        self.start = clock()
        self.samples = self.errors = 0
        self.seen: dict[tuple[str, str, int], Seen] = {}

    def sample(self) -> None:
        try:
            text = self.dev.shell_checked(SAMPLE_CMD)
        except Exception:  # a dropped device is handled by the audit loop; a missed sample is only counted
            self.errors += 1
            return
        now = self.clock() - self.start
        self.samples += 1
        for s in parse_proc_net(text):
            if s.uid != self.uid:
                continue
            key = (s.proto, s.ip, s.port)
            seen = self.seen.setdefault(key, Seen(first=now, last=now))
            seen.last, seen.samples = now, seen.samples + 1
            seen.states.add(s.state)

    def as_dict(self, interval: float) -> dict:
        return {"uid": self.uid, "intervalSeconds": interval, "samples": self.samples, "sampleErrors": self.errors,
                "durationSeconds": round(self.clock() - self.start, 1)}


def uid_of(dev, package: str) -> int | None:
    for line in dev.shell_checked(f"pm list packages -U {package}").splitlines():
        m = re.match(rf"package:{re.escape(package)}\s+uid:(\d+)", line.strip())
        if m:
            return int(m.group(1))
    return None


def declares_vpn_service(dumpsys: str) -> bool:
    """`dumpsys package <pkg>`: a service guarded by BIND_VPN_SERVICE means the app can run its own VPN tunnel."""
    return "android.permission.BIND_VPN_SERVICE" in dumpsys


def _public(ip: str) -> bool:
    a = ipaddress.ip_address(ip)
    return not (a.is_private or a.is_loopback or a.is_link_local or a.is_multicast or a.is_reserved)


def analyze(seen: dict, report: dict | None, sampling: dict, vpn_service: bool | None = None) -> dict:
    answers = [a for a in (report or {}).get("dnsAnswers", []) if isinstance(a, dict)]
    have_answers = report is not None and "dnsAnswers" in report
    by_ip: dict[str, dict] = {}
    for a in answers:
        for ip in a.get("ips", []):
            by_ip.setdefault(ip, a)
    doh_lookups = sorted({a["host"] for a in answers if any(a["host"] == h or a["host"].endswith("." + h) for h in DOH_HOSTNAMES)})

    rows = []
    for (proto, ip, port), s in sorted(seen.items(), key=lambda kv: kv[1].first):
        if not _public(ip):
            continue
        hit = by_ip.get(ip)
        if port == 853:
            kind, note = "encrypted-dns", "DNS over TLS/QUIC (port 853): lookups inside are hidden."
        elif ip in DOH_RESOLVERS and port == 443:
            kind, note = "encrypted-dns", f"Likely DNS over HTTPS to {DOH_RESOLVERS[ip]}: lookups inside are hidden."
        elif port == 53:
            kind, note = "direct-dns", "Plain DNS sent straight to an outside resolver, not through TraceVeil's relay."
        elif hit:
            kind = "matched"
            note = "Connected to an address a visible lookup returned" + (" (QUIC)." if proto == "udp" and port == 443 else ".")
        elif proto == "udp" and port == 443:
            kind, note = "quic", "QUIC (UDP 443) to an address no visible lookup returned."
        elif have_answers:
            kind, note = "no-lookup", "No visible lookup returned this address: hidden DNS, a hardcoded IP, or a cached lookup."
        else:
            kind, note = "unmatched", "The report has no DNS answers (older TraceVeil build), so this cannot be matched."
        rows.append({"proto": proto, "ip": ip, "port": port, "kind": kind, "host": hit and hit.get("host"),
                     "entity": hit and hit.get("entity"), "firstSeen": round(s.first, 1), "lastSeen": round(s.last, 1),
                     "samples": s.samples, "states": sorted(s.states), "note": note})

    counts = {k: sum(1 for r in rows if r["kind"] == k) for k in ("matched", *HIDDEN_KINDS, "unmatched")}
    signals = []
    if counts["encrypted-dns"]:
        signals.append(f"{counts['encrypted-dns']} connection(s) to encrypted DNS: some lookups were hidden from the capture.")
    if doh_lookups:
        signals.append(f"Looked up a DNS-over-HTTPS resolver: {', '.join(doh_lookups)}.")
    if counts["direct-dns"]:
        signals.append(f"{counts['direct-dns']} plain DNS connection(s) bypassed TraceVeil's relay.")
    if counts["no-lookup"]:
        signals.append(f"{counts['no-lookup']} connection(s) to addresses no visible lookup returned.")
    if counts["quic"]:
        signals.append(f"{counts['quic']} QUIC connection(s) to addresses no visible lookup returned.")
    if vpn_service:
        signals.append("The app declares its own VPN service (BIND_VPN_SERVICE): it could tunnel traffic past the capture "
                       "if the user grants it; Android allows one VPN at a time, so this would stop TraceVeil's capture.")
    return {"sampling": sampling, "connections": rows, "counts": counts,
            "trackersConnected": sorted({r["entity"] for r in rows if r["kind"] == "matched" and r["entity"]}),
            "dohLookups": doh_lookups, "declaresVpnService": vpn_service, "hiddenSignals": signals,
            "limitations": LIMITATIONS}


def hidden(net: dict) -> bool:
    return any(net["counts"][k] for k in HIDDEN_KINDS) or bool(net["dohLookups"])


_KIND_LABEL = {"matched": "matched", "encrypted-dns": "ENCRYPTED DNS", "direct-dns": "DIRECT DNS", "no-lookup": "NO LOOKUP",
               "quic": "QUIC", "unmatched": "unmatched"}


def summary_lines(net: dict, style: Style = PLAIN, max_rows: int = 12) -> list[str]:
    s, c = net["sampling"], net["counts"]
    lines = [style.bold("  Network beyond DNS") + style.dim(f"  (sockets of uid {s['uid']}, {s['samples']} samples every "
                                                              f"{s['intervalSeconds']}s over {s['durationSeconds']}s)")]
    if not net["connections"]:
        lines.append(style.dim("    no outside connections seen" + (" (sampling failed)" if not s["samples"] else "")))
    for r in net["connections"][:max_rows]:
        label = f"{_KIND_LABEL[r['kind']]:<13}"
        label = style.dim(label) if r["kind"] in ("matched", "unmatched") else style.bad(label)
        who = style.tracker(r["entity"]) if r["entity"] else (r["host"] or "")
        lines.append(f"    {label} {r['proto']} {r['ip']}:{r['port']:<6} {who}")
    if len(net["connections"]) > max_rows:
        lines.append(style.dim(f"    ... {len(net['connections']) - max_rows} more in the JSON report"))
    if net["trackersConnected"]:
        lines.append(f"    trackers connected to (not only looked up): {', '.join(style.tracker(t) for t in net['trackersConnected'])}")
    for sig in net["hiddenSignals"]:
        lines.append(f"    {style.warn('!!')} {sig}")
    if not net["hiddenSignals"] and net["connections"]:
        lines.append(f"    {style.ok('ok')} every outside connection matched a visible lookup")
    return lines
