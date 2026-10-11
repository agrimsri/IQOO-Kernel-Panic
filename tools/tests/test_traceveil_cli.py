"""Unit tests for the Traceveil CLI. No device needed: adb is faked and dumps come from tools/tests/fixtures.

The *_synthetic fixtures are edited copies of one real iQOO dump, not recordings of a real VPN (see plan Phase 0).
"""

import io
import json
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

from traceveil import (EXIT_DEVICE, EXIT_PASS, EXIT_POLICY, EXIT_USAGE, adb, cli, config, egress, netenv, report, shizuku)

FX = Path(__file__).resolve().parent / "fixtures"
OWN_UID = 10349


def fx(name):
    return (FX / name).read_text(encoding="utf-8")


class FakeDevice:
    def __init__(self, connectivity="connectivity_wifi.txt", props=None, settings=None, ip="2: wlan0 inet 192.0.2.10/21 x", serial="SER123"):
        self.serial, self.adb = serial, "adb"
        self.connectivity, self.ip = fx(connectivity), ip
        self.props = {"gsm.operator.iso-country": "in,", "persist.sys.timezone": "Asia/Calcutta",
                      "ro.product.model": "I2501", "ro.build.version.release": "16", **(props or {})}
        self.settings = settings or {}
        self.curl_body, self.curl_commands = '{"ip": "203.0.113.77", "country": "IN"}', []

    def shell(self, command):
        if command == "dumpsys connectivity":
            return self.connectivity
        if command.startswith("pm list packages -U"):
            return f"package:com.dpdpxray.app uid:{OWN_UID}\n"
        if command == "ip -o addr":
            return self.ip
        if command.startswith("curl "):
            self.curl_commands.append(command)
            if isinstance(self.curl_body, Exception):
                raise self.curl_body
            return self.curl_body
        raise AssertionError(command)

    def getprop(self, key):
        return self.props.get(key, "")

    def setting(self, ns, key):
        return self.settings.get((ns, key))


class AdbTests(unittest.TestCase):
    def test_find_adb_precedence(self):
        self.assertEqual(adb.find_adb("x", env={"ADB": "y"}), "x")
        self.assertEqual(adb.find_adb(None, env={"ADB": "y"}), "y")
        self.assertEqual(adb.find_adb(None, env={}, which=lambda _: "/usr/bin/adb"), "/usr/bin/adb")

    def test_find_adb_wsl_windows_exe(self):
        win = "/mnt/c/Users/me/AppData/Local/Android/Sdk/platform-tools/adb.exe"
        self.assertEqual(adb.find_adb(None, env={}, which=lambda _: None, globber=lambda _: [win]), win)

    def test_find_adb_missing(self):
        with self.assertRaises(adb.AdbError):
            adb.find_adb(None, env={}, which=lambda _: None, globber=lambda _: [])

    def test_parse_devices_and_selection(self):
        entries = adb.parse_devices("List of devices attached\r\n10BF device product:a model:I2501 transport_id:1\r\n\r\n")
        self.assertEqual(entries, [adb.DeviceEntry("10BF", "device", "I2501")])
        self.assertEqual(adb.select_device(entries, None).serial, "10BF")

    def test_unauthorized_has_actionable_message(self):
        entries = adb.parse_devices("10BF unauthorized transport_id:1")
        with self.assertRaisesRegex(adb.AdbError, "Allow"):
            adb.select_device(entries, None)

    def test_offline_none_and_multiple(self):
        with self.assertRaises(adb.AdbError):
            adb.select_device([adb.DeviceEntry("a", "offline")], None)
        with self.assertRaisesRegex(adb.AdbError, "no device"):
            adb.select_device([], None)
        two = [adb.DeviceEntry("a", "device"), adb.DeviceEntry("b", "device")]
        with self.assertRaisesRegex(adb.AdbError, "--serial"):
            adb.select_device(two, None)
        self.assertEqual(adb.select_device(two, "b").serial, "b")

    def test_connect_falls_back_to_windows_adb_when_path_adb_sees_nothing(self):
        def run(cmd, **k):
            out = "List of devices attached\n\n" if cmd[0] == "/usr/bin/adb" else "List of devices attached\n10BF device model:I2501\n"
            return SimpleNamespace(stdout=out)
        dev = adb.connect(None, None, runner=run, cands=[("/usr/bin/adb", False), ("/mnt/c/x/adb.exe", False)])
        self.assertEqual((dev.adb, dev.serial), ("/mnt/c/x/adb.exe", "10BF"))

    def test_connect_with_no_device_anywhere_explains_wsl(self):
        run = lambda cmd, **k: SimpleNamespace(stdout="List of devices attached\n\n")  # noqa: E731
        with self.assertRaisesRegex(adb.AdbError, "Windows adb.exe"):
            adb.connect(None, None, runner=run, cands=[("/usr/bin/adb", False)])

    def test_pinned_adb_is_never_replaced(self):
        self.assertEqual(adb.candidates("/p/adb", env={}, which=lambda _: "/usr/bin/adb"), [("/p/adb", True)])
        self.assertEqual(adb.candidates(None, env={"ADB": "/e/adb"}, which=lambda _: "/usr/bin/adb"), [("/e/adb", True)])

    def test_shell_strips_carriage_returns(self):
        run = lambda *a, **k: SimpleNamespace(stdout="a\r\nb\r\n")  # noqa: E731
        self.assertEqual(adb.Device("adb", "s", runner=run).shell("x"), "a\nb\n")

    def test_setting_null_is_none(self):
        run = lambda *a, **k: SimpleNamespace(stdout="null\r\n")  # noqa: E731
        self.assertIsNone(adb.Device("adb", "s", runner=run).setting("global", "http_proxy"))


class ParsingTests(unittest.TestCase):
    def test_real_dump_ignores_request_lines_mentioning_vpn(self):
        nets = netenv.parse_networks(fx("connectivity_wifi.txt"))
        self.assertEqual(len(nets), 1)
        self.assertEqual((nets[0].kind, nets[0].transports, nets[0].interface, nets[0].is_default), ("WIFI", ["WIFI"], "wlan0", True))

    def test_fixture_is_scrubbed(self):
        text = fx("connectivity_wifi.txt")
        for leak in ("WeWork", "60:d0", "7e:cb", "10.156"):
            self.assertNotIn(leak, text)

    def test_parse_interfaces(self):
        self.assertEqual(netenv.parse_interfaces("1: lo inet 1\n23: wlan0 inet x\n23: wlan0 inet6 y\n2: tun0 inet z"), ["lo", "wlan0", "tun0"])


def snap_for(name, **kw):
    s = netenv.collect(FakeDevice(name, **kw))
    return s, netenv.derive_signals(s)


class SignalTests(unittest.TestCase):
    def test_plain_wifi_has_no_vpn_or_proxy(self):
        s, sig = snap_for("connectivity_wifi.txt", settings={("global", "private_dns_mode"): "off"})
        self.assertEqual(sig, [])
        c = netenv.check_vpn_proxy(s, sig)
        self.assertEqual((c.status, c.confidence), ("none", "medium"))
        self.assertTrue(any("does not rule out" in r for r in c.reasons))

    def test_third_party_vpn_is_likely(self):
        s, sig = snap_for("connectivity_vpn_synthetic.txt", ip="2: wlan0 x\n9: tun1 inet y")
        self.assertIn("vpn_transport", [x.name for x in sig])
        c = netenv.check_vpn_proxy(s, sig)
        self.assertEqual(c.status, "likely")

    def test_own_capture_vpn_is_excluded(self):
        s, sig = snap_for("connectivity_own_vpn_synthetic.txt", ip="2: wlan0 x\n9: tun1 inet y")
        self.assertEqual([x.name for x in sig if x.name != "private_dns"], [])
        self.assertEqual(netenv.check_vpn_proxy(s, sig).status, "none")

    def test_own_vpn_can_be_included_for_debugging(self):
        s = netenv.collect(FakeDevice("connectivity_own_vpn_synthetic.txt"))
        self.assertIn("vpn_transport", [x.name for x in netenv.derive_signals(s, exclude_own_vpn=False)])

    def test_link_proxy_and_global_proxy(self):
        s, sig = snap_for("connectivity_proxy_synthetic.txt", settings={("global", "http_proxy"): "10.0.0.5:8080"})
        names = [x.name for x in sig]
        self.assertIn("link_proxy", names)
        self.assertIn("global_proxy", names)
        self.assertEqual(netenv.check_vpn_proxy(s, sig).status, "likely")

    def test_always_on_vpn_setting(self):
        _, sig = snap_for("connectivity_wifi.txt", settings={("secure", "always_on_vpn_app"): "com.some.vpn"})
        self.assertIn("always_on_vpn", [x.name for x in sig])

    def test_tunnel_interface_alone_is_only_possible(self):
        s, sig = snap_for("connectivity_wifi.txt", ip="2: wlan0 x\n9: wg0 inet y")
        c = netenv.check_vpn_proxy(s, sig)
        self.assertEqual((c.status, c.confidence), ("possible", "low"))

    def test_private_dns_unset_is_weak_and_not_a_vpn_signal(self):
        s, sig = snap_for("connectivity_wifi.txt")
        self.assertEqual([x.strength for x in sig], ["weak"])
        self.assertEqual(netenv.check_vpn_proxy(s, sig).status, "none")

    def test_missing_data_is_unknown_never_none(self):
        dev = FakeDevice()
        dev.shell = mock.Mock(side_effect=RuntimeError("boom"))
        s = netenv.collect(dev)
        self.assertIsNone(s.networks)
        self.assertEqual(netenv.check_vpn_proxy(s, netenv.derive_signals(s)).status, "unknown")

    def test_country_parsing(self):
        self.assertEqual(netenv._country("in,"), "IN")
        self.assertIsNone(netenv._country(""))
        self.assertIsNone(netenv._country("???"))


def S(**kw):
    return netenv.Snapshot(**kw)


class GeoTests(unittest.TestCase):
    vpn_likely = netenv.Check("vpn_proxy", "likely", "high")
    vpn_none = netenv.Check("vpn_proxy", "none", "medium")

    def test_no_egress_is_unknown(self):
        self.assertEqual(netenv.check_geo(S(sim_country="IN"), None, self.vpn_none).status, "unknown")

    def test_no_device_context_is_unknown(self):
        self.assertEqual(netenv.check_geo(S(), "US", self.vpn_none).status, "unknown")

    def test_agreement(self):
        self.assertEqual(netenv.check_geo(S(sim_country="IN", timezone="Asia/Kolkata"), "in", self.vpn_none).status, "none")

    def test_mismatch_alone_is_low_confidence(self):
        c = netenv.check_geo(S(sim_country="IN", timezone="Asia/Kolkata"), "US", self.vpn_none)
        self.assertEqual((c.status, c.confidence), ("possible", "low"))
        self.assertTrue(any("travel" in r for r in c.reasons))

    def test_vpn_plus_mismatch_raises_confidence_but_not_to_proof(self):
        c = netenv.check_geo(S(sim_country="IN", timezone="Asia/Kolkata"), "US", self.vpn_likely)
        self.assertEqual((c.status, c.confidence), ("likely", "medium"))

    def test_partial_disagreement_stays_possible(self):
        c = netenv.check_geo(S(sim_country="IN", timezone="America/New_York"), "US", self.vpn_likely)
        self.assertEqual(c.status, "possible")


class EgressTests(unittest.TestCase):
    def test_redaction(self):
        self.assertEqual(egress.redact_ip("203.0.113.77"), "203.0.113.0/24")
        self.assertEqual(egress.redact_ip("2001:db8:1:2::9"), "2001:db8:1::/48")
        self.assertIsNone(egress.redact_ip("not-an-ip"))

    def _opener(self, body, calls):
        def op(url, timeout=0):
            calls.append(url)
            return io.BytesIO(json.dumps(body).encode())
        return op

    def test_lookup_redacts_and_caches(self):
        calls = []
        with tempfile.TemporaryDirectory() as d:
            cache = Path(d) / "c.json"
            op = self._opener({"ip": "203.0.113.77", "country_code": "us"}, calls)
            first = egress.lookup("https://x.example/ip", cache, 24, now=lambda: 1000, opener=op)
            second = egress.lookup("https://x.example/ip", cache, 24, now=lambda: 2000, opener=op)
            self.assertEqual((first["country"], first["network"], first["cached"]), ("US", "203.0.113.0/24", False))
            self.assertTrue(second["cached"])
            self.assertEqual(len(calls), 1)
            self.assertNotIn("203.0.113.77", cache.read_text())

    def test_cache_expires(self):
        calls = []
        with tempfile.TemporaryDirectory() as d:
            cache = Path(d) / "c.json"
            op = self._opener({"country": "DE"}, calls)
            egress.lookup("https://x.example/ip", cache, 1, now=lambda: 0, opener=op)
            egress.lookup("https://x.example/ip", cache, 1, now=lambda: 7200, opener=op)
            self.assertEqual(len(calls), 2)

    def test_failure_is_unknown(self):
        def boom(*a, **k):
            raise OSError("no route")
        with tempfile.TemporaryDirectory() as d:
            r = egress.lookup("https://x.example/ip", Path(d) / "c.json", 1, opener=boom)
            self.assertIsNone(r["country"])
            self.assertEqual(r["error"], "OSError")


class EgressParsingTests(unittest.TestCase):
    def lookup(self, body, **kw):
        with tempfile.TemporaryDirectory() as d:
            cache = Path(d) / "c.json"
            res = egress.lookup("https://geo.example/json/", cache, 24, fetch=lambda url, t: body, vantage="phone", **kw)
            return res, (cache.read_text() if cache.exists() else "")

    def test_success_records_vantage_and_redacts_everywhere(self):
        res, cached = self.lookup('{"ip": "203.0.113.77", "country": "in"}')
        self.assertEqual((res["country"], res["network"], res["vantage"], res["error"]), ("IN", "203.0.113.0/24", "phone", None))
        self.assertNotIn("203.0.113.77", json.dumps(res) + cached)

    def test_keep_ip_is_the_only_way_to_store_the_full_address(self):
        res, _ = self.lookup('{"ip": "203.0.113.77", "country": "IN"}', redact=False)
        self.assertEqual(res["network"], "203.0.113.77")

    def test_full_country_name_falls_back_to_country_code(self):
        res, _ = self.lookup('{"country": "India", "country_code": "IN"}')
        self.assertEqual(res["country"], "IN")

    def test_malformed_and_error_responses_are_unknown_not_guesses(self):
        for body in ("not json", "[1, 2]", "{}", '{"country": "India"}', '{"country": 12}', '{"error": true, "reason": "RateLimited"}', ""):
            res, cached = self.lookup(body)
            self.assertIsNone(res["country"], body)
            self.assertTrue(res["error"], body)
            self.assertEqual(cached, "", f"failure must not be cached: {body!r}")

    def test_timeouts_and_transport_errors_are_unknown(self):
        for exc in (TimeoutError("t"), adb.AdbError("adb timed out"), OSError("no route"), ConnectionResetError()):
            def boom(url, t, exc=exc):
                raise exc
            with tempfile.TemporaryDirectory() as d:
                res = egress.lookup("https://geo.example/json/", Path(d) / "c.json", 1, fetch=boom)
            self.assertIsNone(res["country"])
            self.assertEqual(res["error"], type(exc).__name__)

    def test_non_https_endpoint_is_refused_without_any_request(self):
        called = []
        with tempfile.TemporaryDirectory() as d:
            res = egress.lookup("http://geo.example/json", Path(d) / "c.json", 1, fetch=lambda u, t: called.append(u) or "{}")
        self.assertEqual(res["error"], "EndpointMustBeHttps")
        self.assertEqual(called, [])

    def test_cache_is_per_vantage(self):
        calls = []
        fetch = lambda u, t: calls.append(u) or '{"country": "IN"}'  # noqa: E731
        with tempfile.TemporaryDirectory() as d:
            c = Path(d) / "c.json"
            egress.lookup("https://g.example/j", c, 24, fetch=fetch, vantage="phone")
            egress.lookup("https://g.example/j", c, 24, fetch=fetch, vantage="host")
            self.assertEqual(len(calls), 2)

    def test_phone_fetch_quotes_the_endpoint(self):
        dev = FakeDevice()
        egress.phone_fetch(dev, "https://geo.example/json/?a=1&b=$(id)", 8)
        self.assertIn("'https://geo.example/json/?a=1&b=$(id)'", dev.curl_commands[0])

    def test_host_geo_check_is_capped_at_low_confidence(self):
        vpn = netenv.Check("vpn_proxy", "likely", "high")
        c = netenv.check_geo(S(sim_country="IN", timezone="Asia/Kolkata"), "US", vpn, vantage="host")
        self.assertEqual((c.status, c.confidence), ("possible", "low"))
        self.assertTrue(any("host machine" in r for r in c.reasons))


class ShizukuDevice:
    """Fake phone whose shizuku_server appears only after the starter binary runs."""
    LIB = "/data/app/~~x==/moe.shizuku.privileged.api-y==/lib/arm64/libshizuku.so"

    def __init__(self, running=False, installed=True, starts=True, abi="arm64-v8a"):
        self.running, self.installed, self.starts, self.abi, self.ran = running, installed, starts, abi, []

    def shell(self, cmd):
        if cmd == "ps -A -o USER,NAME":
            rows = ["USER NAME", "u0_a351 moe.shizuku.privileged.api"]
            if self.running:
                rows.append("shell shizuku_server")
            return "\n".join(rows)
        if cmd.startswith("pm path"):
            return "package:/data/app/~~x==/moe.shizuku.privileged.api-y==/base.apk\n" if self.installed else ""
        self.ran.append(cmd)
        self.running = self.running or self.starts
        return ""

    def getprop(self, key):
        return self.abi


class ShizukuTests(unittest.TestCase):
    nosleep = staticmethod(lambda s: None)

    def test_manager_app_process_is_not_the_server(self):
        self.assertFalse(shizuku.is_running(ShizukuDevice(running=False)))
        self.assertTrue(shizuku.is_running(ShizukuDevice(running=True)))

    def test_already_running_is_left_alone(self):
        dev = ShizukuDevice(running=True)
        self.assertEqual(shizuku.start(dev, sleep=self.nosleep), (True, "already running"))
        self.assertEqual(dev.ran, [])

    def test_starts_with_the_installed_libshizuku(self):
        dev = ShizukuDevice()
        ok, msg = shizuku.start(dev, sleep=self.nosleep)
        self.assertTrue(ok)
        self.assertEqual(dev.ran, [ShizukuDevice.LIB])
        self.assertIn("started", msg)

    def test_not_installed_and_failed_start_are_reported(self):
        self.assertEqual(shizuku.start(ShizukuDevice(installed=False), sleep=self.nosleep)[0], False)
        ok, msg = shizuku.start(ShizukuDevice(starts=False), sleep=self.nosleep)
        self.assertFalse(ok)
        self.assertIn("no shizuku_server", msg)

    def test_abi_directory_mapping(self):
        self.assertTrue(shizuku.starter_path(ShizukuDevice(abi="armeabi-v7a")).endswith("/lib/arm/libshizuku.so"))

    def test_doctor_auto_starts_and_can_opt_out(self):
        class Doc(FakeDevice, ShizukuDevice):
            def __init__(self, **k):
                FakeDevice.__init__(self)
                ShizukuDevice.__init__(self, **k)
                self.adb = "adb"

            def shell(self, cmd):
                if cmd == "pm list packages":
                    return "package:com.dpdpxray.app\npackage:moe.shizuku.privileged.api\n"
                if cmd.startswith(("ps -A", "pm path")) or cmd.startswith("/data/app"):
                    return ShizukuDevice.shell(self, cmd)
                return FakeDevice.shell(self, cmd)

            def getprop(self, key):
                return self.abi if key == "ro.product.cpu.abi" else FakeDevice.getprop(self, key)

        for flag, expect_ran in ((False, 1), (True, 0)):
            dev = Doc()
            args = cli._parser().parse_args(["doctor"] + (["--no-start-shizuku"] if flag else []))
            buf = io.StringIO()
            with redirect_stdout(buf), mock.patch.object(shizuku.time, "sleep"):
                cli.cmd_doctor(args, config.load(cwd=Path("/nonexistent")), dev)
            self.assertEqual(len(dev.ran), expect_ran)
            self.assertIn("Shizuku server running", buf.getvalue())


class ConfigTests(unittest.TestCase):
    def test_defaults_when_no_file(self):
        with tempfile.TemporaryDirectory() as d:
            cfg = config.load(cwd=Path(d))
            self.assertEqual(cfg["network"]["check_vpn"], "warn")
            self.assertFalse(cfg["network"]["online_ip"])

    def test_toml_overrides_and_keeps_other_defaults(self):
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / "traceveil.toml").write_text('[network]\ncheck_vpn = "fail"\n')
            cfg = config.load(cwd=Path(d))
            self.assertEqual(cfg["network"]["check_vpn"], "fail")
            self.assertEqual(cfg["network"]["cache_ttl_hours"], 24)

    def test_invalid_mode_and_missing_endpoint(self):
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / "traceveil.json").write_text('{"network": {"check_vpn": "loud"}}')
            with self.assertRaises(config.ConfigError):
                config.load(cwd=Path(d))
            (Path(d) / "traceveil.json").write_text('{"network": {"online_ip": true}}')
            with self.assertRaisesRegex(config.ConfigError, "endpoint"):
                config.load(cwd=Path(d))

    def test_http_endpoint_is_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / "traceveil.json").write_text('{"network": {"endpoint": "http://geo.example/json"}}')
            with self.assertRaisesRegex(config.ConfigError, "https"):
                config.load(cwd=Path(d))

    def test_template_is_valid_toml_matching_defaults(self):
        import tomllib
        parsed = tomllib.loads(config.TEMPLATE)
        self.assertEqual(parsed["network"]["check_vpn"], config.DEFAULTS["network"]["check_vpn"])
        config.validate(config._merge(config.DEFAULTS, parsed))


class CliTests(unittest.TestCase):
    def run_env(self, dev, argv=(), cfg=None):
        args = cli._parser().parse_args(["env", *argv])
        buf = io.StringIO()
        with redirect_stdout(buf):
            code = cli.cmd_env(args, cfg or config.load(cwd=Path("/nonexistent")), dev)
        return code, buf.getvalue()

    def test_clean_device_passes_and_prints_limitations(self):
        code, out = self.run_env(FakeDevice())
        self.assertEqual(code, EXIT_PASS)
        self.assertIn("not proof of physical location", out)

    def test_fail_mode_exits_5_on_likely_vpn(self):
        code, out = self.run_env(FakeDevice("connectivity_vpn_synthetic.txt"), ["--check-vpn", "fail", "--json"])
        self.assertEqual(code, EXIT_POLICY)
        rep = json.loads(out)
        self.assertEqual(rep["verdict"]["exitCode"], EXIT_POLICY)
        self.assertEqual(rep["networkEnvironment"]["checks"][0]["status"], "likely")

    def test_warn_mode_does_not_fail(self):
        code, _ = self.run_env(FakeDevice("connectivity_vpn_synthetic.txt"))
        self.assertEqual(code, EXIT_PASS)

    def test_json_hashes_serial_and_has_no_raw_serial(self):
        _, out = self.run_env(FakeDevice(serial="10BFAU14E0000XR"), ["--json"])
        self.assertNotIn("10BFAU14E0000XR", out)
        self.assertEqual(json.loads(out)["device"]["serialHash"], report.serial_hash("10BFAU14E0000XR"))

    def test_online_ip_without_endpoint_is_usage_error_and_makes_no_request(self):
        with mock.patch.object(egress, "lookup") as lk:
            code, _ = self.run_env(FakeDevice(), ["--online-ip"])
        self.assertEqual(code, EXIT_USAGE)
        lk.assert_not_called()

    def online_cfg(self):
        cfg = config.load(cwd=Path("/nonexistent"))
        cfg["network"]["endpoint"] = "https://geo.example/json/"
        return cfg

    def run_online(self, dev, argv=()):
        import os
        with tempfile.TemporaryDirectory() as d:
            old = os.getcwd()
            os.chdir(d)
            try:
                code, out = self.run_env(dev, ["--online-ip", "--json", *argv], self.online_cfg())
                cache = Path(".traceveil/cache.json").read_text() if Path(".traceveil/cache.json").exists() else ""
            finally:
                os.chdir(old)
        return code, out, cache

    def test_online_ip_from_phone_detects_country_and_never_prints_full_ip(self):
        dev = FakeDevice()
        code, out, cache = self.run_online(dev)
        rep = json.loads(out)
        self.assertEqual(code, EXIT_PASS)
        self.assertEqual(rep["networkEnvironment"]["egress"]["country"], "IN")
        self.assertEqual(rep["networkEnvironment"]["egress"]["vantage"], "phone")
        self.assertEqual(rep["networkEnvironment"]["checks"][1]["status"], "none")
        self.assertEqual(len(dev.curl_commands), 1)
        self.assertNotIn("203.0.113.77", out + cache)

    def test_online_ip_mismatch_without_vpn_is_only_possible_and_low(self):
        dev = FakeDevice()
        dev.curl_body = '{"ip": "198.51.100.9", "country": "US"}'
        _, out, _ = self.run_online(dev)
        geo = json.loads(out)["networkEnvironment"]["checks"][1]
        self.assertEqual((geo["status"], geo["confidence"]), ("possible", "low"))

    def test_online_ip_failure_is_unknown_and_exit_zero(self):
        dev = FakeDevice()
        dev.curl_body = adb.AdbError("adb timed out")
        code, out, cache = self.run_online(dev)
        rep = json.loads(out)["networkEnvironment"]
        self.assertEqual(code, EXIT_PASS)
        self.assertEqual((rep["egress"]["country"], rep["egress"]["error"]), (None, "AdbError"))
        self.assertEqual(rep["checks"][1]["status"], "unknown")
        self.assertEqual(cache, "")

    def test_online_ip_does_not_weaken_check_vpn_fail(self):
        dev = FakeDevice("connectivity_vpn_synthetic.txt")
        code, _, _ = self.run_online(dev, ["--check-vpn", "fail"])
        self.assertEqual(code, EXIT_POLICY)

    def test_egress_from_host_uses_host_fetch_not_adb_curl(self):
        dev = FakeDevice()
        with mock.patch.object(egress, "host_fetch", return_value='{"country": "IN"}') as hf:
            _, out, _ = self.run_online(dev, ["--egress-from", "host"])
        hf.assert_called_once()
        self.assertEqual(dev.curl_commands, [])
        self.assertEqual(json.loads(out)["networkEnvironment"]["egress"]["vantage"], "host")

    def test_default_run_makes_no_network_call(self):
        with mock.patch.object(egress, "lookup") as lk:
            self.run_env(FakeDevice())
        lk.assert_not_called()

    def test_out_file_written(self):
        with tempfile.TemporaryDirectory() as d:
            self.run_env(FakeDevice(), ["--out", str(Path(d) / "sub" / "env.json")])
            self.assertEqual(json.loads((Path(d) / "sub" / "env.json").read_text())["schemaVersion"], 1)

    def test_init_writes_config_and_gitignore_once(self):
        import os
        with tempfile.TemporaryDirectory() as d:
            old = os.getcwd()
            os.chdir(d)
            try:
                self.assertEqual(cli.main(["init"]), EXIT_PASS)
                self.assertEqual(cli.main(["init"]), EXIT_USAGE)
                self.assertEqual(cli.main(["init", "--force"]), EXIT_PASS)
                self.assertEqual(Path(".gitignore").read_text().count(".traceveil/"), 1)
            finally:
                os.chdir(old)

    def test_main_maps_adb_error_to_device_exit_code(self):
        with mock.patch.object(cli.adb, "connect", side_effect=adb.AdbError("no device")):
            self.assertEqual(cli.main(["env"]), EXIT_DEVICE)


class GlobalOptionPlacementTests(unittest.TestCase):
    def test_device_options_work_before_or_after_the_subcommand(self):
        p = cli._parser()
        before = p.parse_args(["--adb", "X", "--serial", "S", "--timeout", "5", "audit", "--package", "a.b"])
        after = p.parse_args(["audit", "--package", "a.b", "--adb", "X", "--serial", "S", "--timeout", "5"])
        for a in (before, after):
            self.assertEqual((a.adb, a.serial, a.timeout), ("X", "S", 5.0))

    def test_unset_options_keep_their_defaults(self):
        a = cli._parser().parse_args(["doctor"])
        self.assertEqual((a.adb, a.serial, a.config, a.timeout), (None, None, None, 20.0))


if __name__ == "__main__":
    unittest.main()
