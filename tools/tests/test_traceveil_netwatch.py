"""Unit tests for `netwatch`: /proc/net parsing, classification of connections the DNS capture cannot see, and the
audit/watch integration. /proc/net text is generated here in the kernel's format (addresses in little-endian hex words).
"""

import io
import ipaddress
import json
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest import mock

from traceveil import EXIT_FINDINGS, EXIT_PASS, cli, netwatch

UID = 10349


def hexaddr(ip: str, v6: bool) -> str:
    a = ipaddress.ip_address(ip)
    if v6 and a.version == 4:
        a = ipaddress.IPv6Address("::ffff:" + ip)
    raw = a.packed
    return "".join(raw[i:i + 4][::-1].hex().upper() for i in range(0, len(raw), 4))


def line(n, rip, rport, uid=UID, state="01", v6=False, lport=40000):
    local = hexaddr("::1" if v6 else "10.0.0.2", v6)
    return (f"  {n}: {local}:{lport:04X} {hexaddr(rip, v6)}:{rport:04X} {state} 00000000:00000000 00:00000000 00000000 "
            f"{uid:>5}        0 12345 1 0000000000000000 20 4 30 10 -1")


def proc_net(tcp=(), tcp6=(), udp=(), udp6=()) -> str:
    head = "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode"
    out = []
    for name, rows in (("tcp", tcp), ("tcp6", tcp6), ("udp", udp), ("udp6", udp6)):
        out += [f"## {name}", head, *rows]
    return "\n".join(out) + "\n"


REPORT = {
    "requestId": "cli-1", "findings": [],
    "dnsAnswers": [
        {"host": "launches.appsflyersdk.com", "entity": "AppsFlyer", "ips": ["108.159.46.34"]},
        {"host": "api.leakyshop.example", "entity": None, "ips": ["54.230.1.7"]},
        {"host": "dns.google", "entity": None, "ips": ["8.8.4.4"]},
    ],
}


class FakeDev:
    def __init__(self, texts, uid_line=f"package:com.leakyshop.demo uid:{UID}\n", dumpsys="Packages:\n"):
        self.texts, self.uid_line, self.dumpsys = list(texts), uid_line, dumpsys

    def shell_checked(self, cmd):
        if cmd.startswith("pm list packages -U"):
            return self.uid_line
        if cmd == netwatch.SAMPLE_CMD:
            return self.texts.pop(0) if len(self.texts) > 1 else self.texts[0]
        raise AssertionError(cmd)

    def shell(self, cmd):
        return self.dumpsys if cmd.startswith("dumpsys package") else self.shell_checked(cmd)


class ParseTests(unittest.TestCase):
    def test_ipv4_ipv6_and_mapped_addresses_states_and_filters(self):
        text = proc_net(
            tcp=[line(0, "108.159.46.34", 443), line(1, "0.0.0.0", 0, state="0A"), line(2, "1.1.1.1", 443, state="06", uid=0)],
            tcp6=[line(0, "8.8.4.4", 443, v6=True), line(1, "2606:4700:4700::1111", 443, v6=True)],
            udp=[line(0, "142.250.1.1", 443, state="07"), line(1, "0.0.0.0", 0, state="07")],
        )
        socks = netwatch.parse_proc_net(text)
        self.assertEqual([(s.proto, s.ip, s.port) for s in socks], [
            ("tcp", "108.159.46.34", 443), ("tcp", "1.1.1.1", 443), ("tcp", "8.8.4.4", 443),
            ("tcp", "2606:4700:4700::1111", 443), ("udp", "142.250.1.1", 443)])
        self.assertEqual(socks[0].state, "ESTABLISHED")
        self.assertEqual(socks[1].state, "TIME_WAIT")
        self.assertEqual(socks[2].uid, UID)

    def test_real_phone_header_lines_and_garbage_are_ignored(self):
        self.assertEqual(netwatch.parse_proc_net("## tcp\n  sl  local_address\nnot a row\n## udp\n"), [])

    def test_uid_and_vpn_service(self):
        dev = FakeDev([proc_net()], uid_line=f"package:com.leakyshop.demo.fixed uid:1\npackage:com.leakyshop.demo uid:{UID}\n")
        self.assertEqual(netwatch.uid_of(dev, "com.leakyshop.demo"), UID)
        self.assertIsNone(netwatch.uid_of(FakeDev([proc_net()], uid_line=""), "com.x.y"))
        self.assertTrue(netwatch.declares_vpn_service("  Service ... permission=android.permission.BIND_VPN_SERVICE"))


class AnalyzeTests(unittest.TestCase):
    def sample(self, *texts, uid=UID):
        clock = iter(range(100))
        s = netwatch.Sampler(FakeDev(texts), uid, clock=lambda: next(clock))
        for _ in texts:
            s.sample()
        return s

    def test_every_kind_of_hidden_traffic_is_named(self):
        s = self.sample(proc_net(
            tcp=[line(0, "108.159.46.34", 443), line(1, "1.1.1.1", 443), line(2, "9.9.9.9", 853), line(3, "52.95.110.1", 443),
                 line(4, "192.168.1.1", 443), line(5, "5.5.5.5", 443, uid=999)],
            udp=[line(0, "8.8.8.8", 53, state="07"), line(1, "142.250.1.1", 443, state="07")],
        ))
        net = netwatch.analyze(s.seen, REPORT, s.as_dict(1.0))
        kinds = {(r["ip"], r["port"]): r["kind"] for r in net["connections"]}
        self.assertEqual(kinds, {("108.159.46.34", 443): "matched", ("1.1.1.1", 443): "encrypted-dns", ("9.9.9.9", 853): "encrypted-dns",
                                 ("52.95.110.1", 443): "no-lookup", ("8.8.8.8", 53): "direct-dns", ("142.250.1.1", 443): "quic"})
        self.assertEqual(net["trackersConnected"], ["AppsFlyer"])
        self.assertEqual(net["dohLookups"], ["dns.google"])
        self.assertTrue(netwatch.hidden(net))
        self.assertEqual(len(net["hiddenSignals"]), 5)

    def test_clean_app_has_no_hidden_signals(self):
        s = self.sample(proc_net(tcp=[line(0, "108.159.46.34", 443), line(1, "54.230.1.7", 443)]))
        net = netwatch.analyze(s.seen, {**REPORT, "dnsAnswers": REPORT["dnsAnswers"][:2]}, s.as_dict(1.0))
        self.assertFalse(netwatch.hidden(net))
        self.assertIn("every outside connection matched", "\n".join(netwatch.summary_lines(net)))

    def test_without_dns_answers_connections_are_unmatched_not_accused(self):
        s = self.sample(proc_net(tcp=[line(0, "52.95.110.1", 443), line(1, "1.1.1.1", 443)]))
        net = netwatch.analyze(s.seen, {"findings": []}, s.as_dict(1.0))
        self.assertEqual(sorted(r["kind"] for r in net["connections"]), ["encrypted-dns", "unmatched"])
        self.assertEqual(net["counts"]["no-lookup"], 0)

    def test_first_and_last_seen_across_samples(self):
        a = proc_net(tcp=[line(0, "108.159.46.34", 443)])
        s = self.sample(a, proc_net(), a)
        row = netwatch.analyze(s.seen, REPORT, s.as_dict(1.0))["connections"][0]
        self.assertEqual((row["firstSeen"], row["lastSeen"], row["samples"]), (1, 3, 2))

    def test_vpn_service_is_a_signal(self):
        net = netwatch.analyze({}, REPORT, {"uid": UID, "intervalSeconds": 1, "samples": 1, "sampleErrors": 0, "durationSeconds": 1}, True)
        self.assertTrue(any("VPN" in s for s in net["hiddenSignals"]))


class WatchCliTests(unittest.TestCase):
    def test_watch_matches_against_a_saved_report_and_gates(self):
        with tempfile.TemporaryDirectory() as d:
            rep = Path(d) / "r.json"
            rep.write_text(json.dumps(REPORT), encoding="utf-8")
            dev = FakeDev([proc_net(tcp=[line(0, "1.1.1.1", 443)])])
            out, err = io.StringIO(), io.StringIO()
            with redirect_stdout(out), redirect_stderr(err), mock.patch("traceveil.adb.connect", return_value=dev), \
                    mock.patch("time.sleep"), mock.patch("time.monotonic", side_effect=[0, 0, 0, 0.5, 2, 3, 4, 5, 6]):
                code = cli.main(["watch", "--package", "com.leakyshop.demo", "--seconds", "1", "--report", str(rep), "--json", "--fail-on-hidden"])
            self.assertEqual(code, EXIT_FINDINGS)
            self.assertEqual(json.loads(out.getvalue())["connections"][0]["kind"], "encrypted-dns")


if __name__ == "__main__":
    unittest.main()
