"""adb discovery and a thin device wrapper. WSL2 cannot see USB phones, so the Windows adb.exe is a first-class target."""

from __future__ import annotations

import glob
import os
import shutil
import subprocess
from dataclasses import dataclass


class AdbError(Exception):
    """Raised for problems the user can fix (no adb, no device, unauthorized)."""


class AdbDisconnected(AdbError):
    """The phone dropped off adb mid-command (cable, sleep, adb restart). Usually recoverable by reconnecting."""


_GONE_MARKERS = ("device offline", "no devices/emulators", "not found", "closed", "protocol fault", "unauthorized")


def candidates(explicit: str | None = None, env: dict | None = None, which=shutil.which, globber=glob.glob) -> list[tuple[str, bool]]:
    """(adb path, pinned) in preference order. Pinned entries (--adb / $ADB) are never replaced by a fallback."""
    env = os.environ if env is None else env
    for pinned in (explicit, env.get("ADB")):
        if pinned:
            return [(pinned, True)]
    found = []
    path_adb = which("adb")
    if path_adb:
        found.append((path_adb, False))
    found += [(w, False) for w in sorted(globber("/mnt/c/Users/*/AppData/Local/Android/Sdk/platform-tools/adb.exe"))]
    return found


def find_adb(explicit: str | None = None, env: dict | None = None, which=shutil.which, globber=glob.glob) -> str:
    env = os.environ if env is None else env
    for candidate in (explicit, env.get("ADB")):
        if candidate:
            return candidate
    found = which("adb")
    if found:
        return found
    wsl = sorted(globber("/mnt/c/Users/*/AppData/Local/Android/Sdk/platform-tools/adb.exe"))
    if wsl:
        return wsl[0]
    raise AdbError("adb not found: pass --adb, set $ADB, or install Android platform-tools")


@dataclass(frozen=True)
class DeviceEntry:
    serial: str
    state: str
    model: str = ""


def parse_devices(text: str) -> list[DeviceEntry]:
    out = []
    for raw in text.replace("\r", "").splitlines():
        line = raw.strip()
        if not line or line.startswith("List of devices") or line.startswith("*"):
            continue
        parts = line.split()
        if len(parts) < 2:
            continue
        model = next((p.split(":", 1)[1] for p in parts[2:] if p.startswith("model:")), "")
        out.append(DeviceEntry(parts[0], parts[1], model))
    return out


def select_device(entries: list[DeviceEntry], serial: str | None) -> DeviceEntry:
    if serial:
        match = [e for e in entries if e.serial == serial]
        if not match:
            raise AdbError(f"device {serial} not found; attached: {[e.serial for e in entries] or 'none'}")
        entry = match[0]
    else:
        ready = [e for e in entries if e.state == "device"]
        if len(entries) > 1 and len(ready) != 1:
            raise AdbError("several devices attached; choose one with --serial")
        if not entries:
            raise AdbError("no device attached: connect the phone and enable USB debugging")
        entry = ready[0] if ready else entries[0]
    if entry.state == "unauthorized":
        raise AdbError("device is unauthorized: unlock the phone and tap Allow on the USB debugging prompt")
    if entry.state != "device":
        raise AdbError(f"device {entry.serial} is {entry.state}; reconnect the cable or restart adb")
    return entry


class Device:
    def __init__(self, adb: str, serial: str, timeout: float = 20.0, runner=subprocess.run):
        self.adb, self.serial, self.timeout, self._run = adb, serial, timeout, runner

    def _cmd(self, *args: str) -> list[str]:
        return [self.adb, "-s", self.serial, *args]

    def shell(self, command: str) -> str:
        try:
            proc = self._run(self._cmd("shell", command), capture_output=True, text=True, timeout=self.timeout)
        except subprocess.TimeoutExpired as e:
            raise AdbError(f"adb timed out running: {command}") from e
        return proc.stdout.replace("\r", "")

    def shell_checked(self, command: str) -> str:
        """Like [shell] but raises [AdbDisconnected] when adb says the device is gone. Remote command failures
        (for example `cat` of a missing file) are returned as text, not raised."""
        try:
            proc = self._run(self._cmd("shell", command), capture_output=True, text=True, timeout=self.timeout)
        except subprocess.TimeoutExpired as e:
            raise AdbDisconnected(f"adb timed out running: {command}") from e
        err = (proc.stderr or "").lower()
        if proc.returncode != 0 and err.startswith("error:") and any(m in err for m in _GONE_MARKERS):
            raise AdbDisconnected((proc.stderr or "").strip())
        return (proc.stdout + ("" if proc.returncode == 0 else proc.stderr or "")).replace("\r", "")

    def getprop(self, key: str) -> str:
        return self.shell(f"getprop {key}").strip()

    def setting(self, namespace: str, key: str) -> str | None:
        value = self.shell(f"settings get {namespace} {key}").strip()
        return None if value in ("", "null") else value


def connect(explicit_adb: str | None, serial: str | None, timeout: float = 20.0, runner=subprocess.run,
            cands: list[tuple[str, bool]] | None = None) -> Device:
    """Tries each adb candidate until one lists a device. Under WSL2 the Linux adb on PATH sees no USB phones, so the
    Windows adb.exe is tried next."""
    cands = cands if cands is not None else candidates(explicit_adb)
    if not cands:
        raise AdbError("adb not found: pass --adb, set $ADB, or install Android platform-tools")
    last = None
    for adb, _pinned in cands:
        proc = runner([adb, "devices", "-l"], capture_output=True, text=True, timeout=timeout)
        entries = parse_devices(proc.stdout)
        if entries:
            entry = select_device(entries, serial or os.environ.get("SERIAL"))
            return Device(adb, entry.serial, timeout, runner)
        last = adb
    raise AdbError(f"no device attached (tried {', '.join(a for a, _ in cands)}): connect the phone and enable USB debugging; "
                   "under WSL2 use the Windows adb.exe via --adb")
