"""App files given by path: a plain .apk, or a split bundle (.xapk, .apks, .apkm) unpacked into its APKs. Reads the package
name from the APK itself, installs the whole bundle over adb (plus any OBB expansion files) and skips the install when the
same version is already on the phone."""

from __future__ import annotations

import json
import re
import shlex
import shutil
import struct
import subprocess
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

from . import adb

BUNDLE_SUFFIXES = (".xapk", ".apks", ".apkm")
INSTALL_TIMEOUT_S = 600.0


class ApkFileError(ValueError):
    """The file is missing, not an APK or bundle, or its package name cannot be read."""


@dataclass
class AppFile:
    source: Path                      # what the user passed
    package: str
    base: Path                        # the APK with the app's code (what `sdks` scans)
    apks: list[Path]                  # everything `adb install-multiple` needs, base first
    version_code: int | None = None
    version_name: str | None = None
    label: str | None = None
    obbs: list[tuple[Path, str]] = field(default_factory=list)  # (local file, path under /sdcard)

    def describe(self) -> dict:
        return {"file": self.source.name, "kind": "bundle" if len(self.apks) > 1 or self.source.suffix.lower() in BUNDLE_SUFFIXES else "apk",
                "package": self.package, "versionCode": self.version_code, "versionName": self.version_name,
                "label": self.label, "apks": [p.name for p in self.apks], "obbs": [dst for _, dst in self.obbs]}


# --- binary AndroidManifest.xml ---------------------------------------------------------------------------------------

def _strings(data: bytes, off: int) -> list[str]:
    _, header_size, _, count, _, flags, strings_start = struct.unpack_from("<HHIIIII", data, off)
    utf8 = bool(flags & 0x100)
    offsets = struct.unpack_from(f"<{count}I", data, off + header_size)
    out = []
    for o in offsets:
        p = off + strings_start + o
        if utf8:
            p += 2 if data[p] & 0x80 else 1           # length in UTF-16 units (unused)
            n = data[p]
            if n & 0x80:
                n, p = ((n & 0x7F) << 8) | data[p + 1], p + 2
            else:
                p += 1
            out.append(data[p:p + n].decode("utf-8", "replace"))
        else:
            n = struct.unpack_from("<H", data, p)[0]
            if n & 0x8000:
                n, p = ((n & 0x7FFF) << 16) | struct.unpack_from("<H", data, p + 2)[0], p + 4
            else:
                p += 2
            out.append(data[p:p + 2 * n].decode("utf-16-le", "replace"))
    return out


def manifest_attrs(axml: bytes) -> dict[str, str | int]:
    """Attributes of the root <manifest> element of a compiled AndroidManifest.xml (package, versionCode, split, ...)."""
    try:
        if struct.unpack_from("<H", axml, 0)[0] != 0x0003:
            raise ApkFileError("AndroidManifest.xml is not compiled binary XML")
        pos, strings = struct.unpack_from("<H", axml, 2)[0], []
        while pos + 8 <= len(axml):
            ctype, _, size = struct.unpack_from("<HHI", axml, pos)
            if size < 8:
                break
            if ctype == 0x0001:
                strings = _strings(axml, pos)
            elif ctype == 0x0102:                     # first start-element is <manifest>
                ext = pos + 16
                attr_start, attr_size, attr_count = struct.unpack_from("<HHH", axml, ext + 8)
                attrs: dict[str, str | int] = {}
                for i in range(attr_count):
                    a = ext + attr_start + i * attr_size
                    _, name, raw, _, _, dtype, value = struct.unpack_from("<IIIHBBI", axml, a)
                    key = strings[name] if name < len(strings) else ""
                    if raw != 0xFFFFFFFF and raw < len(strings):
                        attrs[key] = strings[raw]
                    elif dtype == 0x03 and value < len(strings):
                        attrs[key] = strings[value]
                    elif 0x10 <= dtype <= 0x1F:
                        attrs[key] = value
                return attrs
            pos += size
    except (struct.error, IndexError) as e:
        raise ApkFileError(f"malformed AndroidManifest.xml ({e})") from e
    raise ApkFileError("AndroidManifest.xml has no <manifest> element")


def read_manifest(apk: Path) -> dict[str, str | int]:
    try:
        with zipfile.ZipFile(apk) as z:
            return manifest_attrs(z.read("AndroidManifest.xml"))
    except KeyError as e:
        raise ApkFileError(f"{apk.name} has no AndroidManifest.xml (not an APK)") from e
    except (OSError, zipfile.BadZipFile) as e:
        raise ApkFileError(f"cannot read {apk}: {e}") from e


# --- opening a path ---------------------------------------------------------------------------------------------------

def _int(v) -> int | None:
    try:
        return int(v)
    except (TypeError, ValueError):
        return None


def open_app(path: str | Path, cache_dir: Path) -> AppFile:
    """A plain APK is used in place; a bundle is unpacked once into cache_dir (reused while the file is unchanged)."""
    src = Path(path).expanduser()
    if not src.is_file():
        raise ApkFileError(f"no such file: {src}")
    try:
        with zipfile.ZipFile(src) as z:
            names = z.namelist()
            if "AndroidManifest.xml" in names:
                m = manifest_attrs(z.read("AndroidManifest.xml"))
                if not m.get("package"):
                    raise ApkFileError(f"{src.name}: no package name in AndroidManifest.xml")
                return AppFile(src, str(m["package"]), src, [src], _int(m.get("versionCode")), _str(m.get("versionName")))
            inner = [n for n in names if n.lower().endswith(".apk") and not n.endswith("/")]
            if not inner:
                raise ApkFileError(f"{src.name} is neither an APK nor an .xapk/.apks/.apkm bundle (no APKs inside)")
            st = src.stat()
            out = cache_dir / f"{re.sub(r'[^A-Za-z0-9._-]+', '_', src.stem)}-{st.st_size}-{int(st.st_mtime)}"
            meta = _bundle_meta(z, names)
            obb_entries = [(e.get("file"), e.get("install_path")) for e in meta.get("expansions", []) if isinstance(e, dict)]
            obb_entries = [(f, d) for f, d in obb_entries if f in names and d]
            done = out / ".complete"
            if not done.exists():
                if out.exists():
                    shutil.rmtree(out)
                out.mkdir(parents=True)
                for n in inner + [f for f, _ in obb_entries]:
                    target = out / Path(n).name if n in inner else out / "obb" / Path(n).name
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with z.open(n) as r, open(target, "wb") as w:
                        shutil.copyfileobj(r, w, 1 << 20)
                done.write_text("ok")
    except (OSError, zipfile.BadZipFile) as e:
        raise ApkFileError(f"cannot read {src}: {e}") from e

    apks = sorted(out / Path(n).name for n in inner)
    base, info = None, {}
    for apk in apks:                                  # the base APK is the one without a split="..." attribute
        m = read_manifest(apk)
        if not m.get("split"):
            base, info = apk, m
            break
    if base is None:
        raise ApkFileError(f"{src.name}: every APK inside is a split; the base APK is missing")
    package = str(info.get("package") or meta.get("package_name") or meta.get("pname") or "")
    if not package:
        raise ApkFileError(f"{src.name}: no package name in the base APK")
    return AppFile(src, package, base, [base] + [a for a in apks if a != base],
                   _int(info.get("versionCode")) or _int(meta.get("version_code")) or _int(meta.get("versioncode")),
                   _str(info.get("versionName")) or _str(meta.get("version_name")) or _str(meta.get("release_version")),
                   _str(meta.get("name")) or _str(meta.get("app_name")),
                   [(out / "obb" / Path(f).name, d) for f, d in obb_entries])


def code_apk(path: str | Path, cache_dir: Path) -> Path:
    """The APK whose code `sdks` scans: the file itself unless it is a bundle of APKs, then the bundle's base APK."""
    src = Path(path).expanduser()
    try:
        with zipfile.ZipFile(src) as z:
            names = z.namelist()
    except (OSError, zipfile.BadZipFile):
        return src  # let the DEX reader report the problem
    if "AndroidManifest.xml" in names or not any(n.lower().endswith(".apk") for n in names):
        return src
    return open_app(src, cache_dir).base


def _str(v) -> str | None:
    return v if isinstance(v, str) and v else None


def _bundle_meta(z: zipfile.ZipFile, names: list[str]) -> dict:
    """manifest.json (XAPK) or info.json (APKM); empty for .apks, which carries none."""
    for n in ("manifest.json", "info.json"):
        if n in names:
            try:
                meta = json.loads(z.read(n).decode("utf-8", "replace"))
                return meta if isinstance(meta, dict) else {}
            except ValueError:
                return {}
    return {}


# --- installing -------------------------------------------------------------------------------------------------------

def host_path(adb_path: str, p: Path, runner=subprocess.run) -> str:
    """Windows adb.exe run from WSL cannot open /home/... paths; hand it the \\\\wsl$ form instead."""
    if adb_path.lower().endswith(".exe") and shutil.which("wslpath"):
        proc = runner(["wslpath", "-w", str(p.resolve())], capture_output=True, text=True)
        if proc.returncode == 0 and proc.stdout.strip():
            return proc.stdout.strip()
    return str(p.resolve())


def installed_version(dev, package: str) -> int | None:
    m = re.search(r"versionCode=(\d+)", dev.shell(f"dumpsys package {shlex.quote(package)}"))
    return int(m.group(1)) if m else None


def install(dev, app: AppFile, force: bool = False, log=print, runner=None) -> str:
    """Installs the bundle unless the same versionCode is already there. Returns 'installed' or 'already installed'."""
    if not force and app.version_code is not None and installed_version(dev, app.package) == app.version_code:
        return "already installed"
    run = runner or dev._run
    paths = [host_path(dev.adb, p) for p in app.apks]
    verb = ["install", "-r"] if len(paths) == 1 else ["install-multiple", "-r"]
    log(f"installing {app.package} ({len(paths)} APK{'s' if len(paths) > 1 else ''}) ...")
    try:
        proc = run([dev.adb, "-s", dev.serial, *verb, *paths], capture_output=True, text=True, timeout=INSTALL_TIMEOUT_S)
    except subprocess.TimeoutExpired as e:
        raise adb.AdbError(f"install of {app.source.name} timed out after {INSTALL_TIMEOUT_S:.0f}s") from e
    text = ((proc.stdout or "") + (proc.stderr or "")).replace("\r", "")
    if "Success" not in text:
        reason = (re.search(r"(INSTALL_[A-Z_]+[^\n]*)", text) or re.search(r"(.+)", text.strip() or "no output"))[1]
        hint = {"INSTALL_FAILED_UPDATE_INCOMPATIBLE": " (a copy signed with another key is installed: uninstall it first)",
                "INSTALL_FAILED_VERSION_DOWNGRADE": " (a newer version is installed: uninstall it first)",
                "INSTALL_FAILED_NO_MATCHING_ABIS": " (the bundle has no native code for this phone's CPU)",
                "INSTALL_FAILED_USER_RESTRICTED": " (allow 'Install via USB' in Developer options)"}
        raise adb.AdbError(f"could not install {app.source.name}: {reason}{next((h for k, h in hint.items() if k in reason), '')}")
    for local, dst in app.obbs:
        remote = "/sdcard/" + dst.lstrip("/")
        dev.shell(f"mkdir -p {shlex.quote(remote.rsplit('/', 1)[0])}")
        proc = run([dev.adb, "-s", dev.serial, "push", host_path(dev.adb, local), remote], capture_output=True, text=True,
                   timeout=INSTALL_TIMEOUT_S)
        if proc.returncode != 0:
            raise adb.AdbError(f"could not copy {local.name} to {remote}: {(proc.stderr or proc.stdout).strip()}")
    return "installed"
