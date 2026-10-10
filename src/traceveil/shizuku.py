"""Shizuku server detection and adb start. The manager app process is NOT the server: the server runs as
`shell` (or root) with the process name `shizuku_server`, which is what we look for."""

from __future__ import annotations

import re
import time

PACKAGE = "moe.shizuku.privileged.api"
SERVER_NAME = "shizuku_server"
ABI_DIR = {"arm64-v8a": "arm64", "armeabi-v7a": "arm", "armeabi": "arm", "x86_64": "x86_64", "x86": "x86"}


def is_running(device) -> bool:
    out = device.shell("ps -A -o USER,NAME")
    return any(len(p) >= 2 and p[0] in ("shell", "root") and p[-1] == SERVER_NAME
               for p in (line.split() for line in out.splitlines()))


def starter_path(device) -> str | None:
    """Path of Shizuku's own starter binary inside its install dir, or None if Shizuku isn't installed."""
    m = re.search(r"package:(/\S+)/base\.apk", device.shell(f"pm path {PACKAGE}"))
    if not m:
        return None
    abi = ABI_DIR.get(device.getprop("ro.product.cpu.abi"), "arm64")
    return f"{m.group(1)}/lib/{abi}/libshizuku.so"


def start(device, wait: float = 6.0, sleep=time.sleep) -> tuple[bool, str]:
    """Starts the server over adb if needed. Returns (running, message). Never raises for 'not installed'."""
    if is_running(device):
        return True, "already running"
    path = starter_path(device)
    if path is None:
        return False, f"{PACKAGE} is not installed"
    device.shell(path)
    deadline = max(1, int(wait / 0.5))
    for _ in range(deadline):
        sleep(0.5)
        if is_running(device):
            return True, "started over adb"
    return False, f"ran {path} but no {SERVER_NAME} process appeared; open Shizuku and use its adb start, or check USB debugging (Security settings)"
