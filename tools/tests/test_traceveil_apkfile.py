"""Unit tests for app files given by path (`audit --apk`, `sdks --apk`): binary manifests, bundles and installs, all in memory."""

import io
import json
import struct
import tempfile
import unittest
import zipfile
from contextlib import redirect_stderr
from pathlib import Path
from unittest import mock

from traceveil import EXIT_FINDINGS, EXIT_PASS, EXIT_USAGE, adb, apkfile, audit, cli, config

try:  # run as tools/tests/... or from inside tools/tests
    from test_traceveil_audit import REPORT, Clock, FakePhone, make_io, status
    from test_traceveil_sdks import make_dex
except ImportError:  # pragma: no cover
    from tools.tests.test_traceveil_audit import REPORT, Clock, FakePhone, make_io, status
    from tools.tests.test_traceveil_sdks import make_dex


def axml(attrs: dict, utf8: bool = False) -> bytes:
    """Compiled AndroidManifest.xml with one <manifest> element; ints become typed values, strings raw strings."""
    strings = ["manifest"] + list(attrs) + [v for v in attrs.values() if isinstance(v, str)]
    idx = {}
    for s in strings:
        idx.setdefault(s, len(idx))
    pool = list(idx)
    data, offsets = bytearray(), []
    for s in pool:
        offsets.append(len(data))
        if utf8:
            b = s.encode()
            data += bytes([len(s), len(b)]) + b + b"\0"
        else:
            data += struct.pack("<H", len(s)) + s.encode("utf-16-le") + b"\0\0"
    while len(data) % 4:
        data += b"\0"
    header_size = 28
    strings_start = header_size + 4 * len(pool)
    sp = struct.pack("<HHIIIIII", 0x0001, header_size, strings_start + len(data), len(pool), 0, 0x100 if utf8 else 0,
                     strings_start, 0) + b"".join(struct.pack("<I", o) for o in offsets) + bytes(data)
    attr_bytes = b""
    for k, v in attrs.items():
        if isinstance(v, int):
            attr_bytes += struct.pack("<IIIHBBI", 0xFFFFFFFF, idx[k], 0xFFFFFFFF, 8, 0, 0x10, v)
        else:
            attr_bytes += struct.pack("<IIIHBBI", 0xFFFFFFFF, idx[k], idx[v], 8, 0, 0x03, idx[v])
    ext = struct.pack("<IIHHHHHH", 0xFFFFFFFF, idx["manifest"], 20, 20, len(attrs), 0, 0, 0)
    el = struct.pack("<HHIII", 0x0102, 16, 16 + len(ext) + len(attr_bytes), 1, 0xFFFFFFFF) + ext + attr_bytes
    body = sp + el
    return struct.pack("<HHI", 0x0003, 8, 8 + len(body)) + body


def write_apk(path: Path, attrs: dict, dex: bool = True) -> Path:
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("AndroidManifest.xml", axml(attrs))
        if dex:
            z.writestr("classes.dex", b"dex\n035\0")
    return path


class Proc:
    def __init__(self, rc=0, out="", err=""):
        self.returncode, self.stdout, self.stderr = rc, out, err


class FakeDev:
    def __init__(self, installed_version=None, install_out="Success\n"):
        self.adb, self.serial, self.cmds, self.shells = "adb", "SER", [], []
        self.installed_version, self.install_out = installed_version, install_out

    def shell(self, command):
        self.shells.append(command)
        if command.startswith("dumpsys package") and self.installed_version is not None:
            return f"Packages:\n  versionCode={self.installed_version} minSdk=24\n"
        return ""

    def _run(self, cmd, **kw):
        self.cmds.append(cmd)
        return Proc(0, self.install_out if cmd[3].startswith("install") else "1 file pushed\n")


class ApkFileTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.dir = Path(self.tmp.name)
        self.cache = self.dir / "cache"

    def bundle(self, name="game.xapk", meta=None, obb=False, base_first=False):
        base = write_apk(self.dir / "b.apk", {"versionCode": 7, "versionName": "0.7", "package": "com.gt.game"})
        split = write_apk(self.dir / "s.apk", {"package": "com.gt.game", "split": "config.arm64_v8a"}, dex=False)
        p = self.dir / name
        with zipfile.ZipFile(p, "w") as z:
            entries = [("com.gt.game.apk", base), ("config.arm64_v8a.apk", split)]
            for n, f in (entries if base_first else entries[::-1]):
                z.write(f, n)
            if meta is not None:
                z.writestr("manifest.json", json.dumps(meta))
            if obb:
                z.writestr("Android/obb/com.gt.game/main.7.com.gt.game.obb", b"obb")
        return p

    def test_manifest_attributes_from_utf16_and_utf8_pools(self):
        for utf8 in (False, True):
            m = apkfile.manifest_attrs(axml({"versionCode": 42, "package": "com.x.y", "versionName": "1.2"}, utf8=utf8))
            self.assertEqual((m["package"], m["versionCode"], m["versionName"]), ("com.x.y", 42, "1.2"))

    def test_plain_apk_is_used_in_place(self):
        p = write_apk(self.dir / "app.apk", {"package": "com.x.y", "versionCode": 3})
        app = apkfile.open_app(p, self.cache)
        self.assertEqual((app.package, app.base, app.apks, app.version_code), ("com.x.y", p, [p], 3))
        self.assertFalse(self.cache.exists())
        self.assertEqual(app.describe()["kind"], "apk")

    def test_xapk_is_unpacked_and_the_base_apk_found_by_its_manifest(self):
        meta = {"package_name": "com.gt.game", "name": "Game",
                "expansions": [{"file": "Android/obb/com.gt.game/main.7.com.gt.game.obb",
                                "install_path": "Android/obb/com.gt.game/main.7.com.gt.game.obb"}]}
        app = apkfile.open_app(self.bundle(meta=meta, obb=True), self.cache)
        self.assertEqual(app.package, "com.gt.game")
        self.assertEqual(app.base.name, "com.gt.game.apk")
        self.assertEqual([a.name for a in app.apks], ["com.gt.game.apk", "config.arm64_v8a.apk"])
        self.assertEqual((app.version_code, app.version_name, app.label), (7, "0.7", "Game"))
        self.assertEqual(app.obbs[0][1], "Android/obb/com.gt.game/main.7.com.gt.game.obb")
        self.assertTrue(app.obbs[0][0].is_file())
        self.assertEqual(apkfile.code_apk(app.source, self.cache), app.base)

    def test_apks_without_metadata_still_works_and_reuses_the_cache(self):
        p = self.bundle(name="game.apks")
        first = apkfile.open_app(p, self.cache)
        (first.base.parent / "marker").write_text("x")
        second = apkfile.open_app(p, self.cache)
        self.assertEqual(second.package, "com.gt.game")
        self.assertTrue((second.base.parent / "marker").exists())  # not unpacked again

    def test_not_an_app_file(self):
        p = self.dir / "x.zip"
        with zipfile.ZipFile(p, "w") as z:
            z.writestr("readme.txt", "hi")
        with self.assertRaises(apkfile.ApkFileError):
            apkfile.open_app(p, self.cache)
        with self.assertRaises(apkfile.ApkFileError):
            apkfile.open_app(self.dir / "missing.apk", self.cache)

    def test_install_multiple_for_bundles_and_obb_push(self):
        meta = {"expansions": [{"file": "Android/obb/com.gt.game/main.7.com.gt.game.obb",
                                "install_path": "Android/obb/com.gt.game/main.7.com.gt.game.obb"}]}
        app = apkfile.open_app(self.bundle(meta=meta, obb=True), self.cache)
        dev = FakeDev()
        self.assertEqual(apkfile.install(dev, app, log=lambda m: None), "installed")
        self.assertEqual(dev.cmds[0][3:5], ["install-multiple", "-r"])
        self.assertEqual(len(dev.cmds[0]) - 5, 2)
        self.assertEqual(dev.cmds[1][3], "push")
        self.assertTrue(dev.cmds[1][-1].startswith("/sdcard/Android/obb/com.gt.game/"))

    def test_install_skipped_when_same_version_is_installed(self):
        app = apkfile.open_app(write_apk(self.dir / "app.apk", {"package": "com.x.y", "versionCode": 3}), self.cache)
        dev = FakeDev(installed_version=3)
        self.assertEqual(apkfile.install(dev, app, log=lambda m: None), "already installed")
        self.assertEqual(dev.cmds, [])
        apkfile.install(dev, app, force=True, log=lambda m: None)
        self.assertEqual(dev.cmds[0][3:5], ["install", "-r"])

    def test_install_failure_explains_the_reason(self):
        app = apkfile.open_app(write_apk(self.dir / "app.apk", {"package": "com.x.y", "versionCode": 3}), self.cache)
        dev = FakeDev(install_out="Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match]\n")
        with self.assertRaisesRegex(adb.AdbError, "UPDATE_INCOMPATIBLE.*uninstall it first"):
            apkfile.install(dev, app, log=lambda m: None)

    def test_windows_adb_gets_windows_paths(self):
        runner = mock.Mock(return_value=Proc(0, "\\\\wsl.localhost\\Ubuntu\\x.apk\n"))
        with mock.patch("shutil.which", return_value="/usr/bin/wslpath"):
            self.assertEqual(apkfile.host_path("/mnt/c/adb.exe", Path("x.apk"), runner), "\\\\wsl.localhost\\Ubuntu\\x.apk")
        self.assertEqual(apkfile.host_path("/usr/bin/adb", Path("x.apk"), runner), str(Path("x.apk").resolve()))

    def test_audit_apk_installs_and_audits_the_package_read_from_the_file(self):
        p = self.bundle(meta={"name": "Game"})
        dev = FakeDev()
        with mock.patch.object(cli, "APK_CACHE", self.cache), mock.patch("traceveil.adb.connect", return_value=dev), \
                mock.patch("traceveil.audit.run_audit", return_value=EXIT_PASS) as run, redirect_stderr(io.StringIO()):
            code = cli.main(["audit", "--path", str(p), "--auto-drive"])
        self.assertEqual(code, EXIT_PASS)
        self.assertEqual(dev.cmds[0][3], "install-multiple")
        kw = run.call_args.kwargs
        self.assertEqual(kw["package"], "com.gt.game")
        self.assertEqual(kw["app_file"].base.name, "com.gt.game.apk")

    def test_audit_apk_rejects_a_mismatched_package(self):
        p = write_apk(self.dir / "app.apk", {"package": "com.x.y"})
        with mock.patch("traceveil.adb.connect", return_value=FakeDev()), redirect_stderr(io.StringIO()) as err:
            code = cli.main(["audit", "--apk", str(p), "--package", "com.other"])
        self.assertEqual(code, EXIT_USAGE)
        self.assertIn("does not match", err.getvalue())


    def test_audit_from_a_file_records_the_build_and_its_bundled_sdks(self):
        p = self.dir / "app.apk"
        with zipfile.ZipFile(p, "w") as z:
            z.writestr("AndroidManifest.xml", axml({"package": "com.x.y", "versionCode": 5}))
            z.writestr("classes.dex", make_dex(["com.x.y.Main", "com.appsflyer.AppsFlyerLib"]))
        app = apkfile.open_app(p, self.cache)
        cfg = config.load(None, self.dir)
        cfg["output"]["dir"] = str(self.dir / "reports")
        phone = FakePhone(files={"REQ/status.json": [status("done", auditId="audit-1")], "REQ/report.json": [json.dumps(REPORT)]})
        i = make_io(Clock())
        code = audit.run_audit(phone, cfg, i, package="com.x.y", plans=None, auto_drive=False, reset=False, wait_s=100.0, resume=None,
                               as_json=False, out_file=None, request_id="REQ", app_file=app, watch_network=False)
        self.assertIn(code, (EXIT_PASS, EXIT_FINDINGS))
        saved = json.loads((self.dir / "reports" / "REQ.json").read_text())
        self.assertEqual(saved["apk"]["package"], "com.x.y")
        self.assertEqual(saved["apk"]["versionCode"], 5)
        self.assertIn("AppsFlyer", [r["entity"] for r in saved["sdkInventory"]["sdks"]])
        self.assertIn("SDKs", i.out.getvalue())


if __name__ == "__main__":
    unittest.main()
