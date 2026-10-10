"""Permission-name normalisation for the APK permission-risk model.

The phone reads `PackageInfo.requestedPermissions`: full manifest names such as
`android.permission.CAMERA` or `com.android.vending.BILLING`. Every model feature
must therefore be such a full name. This module

* reads the platform permission names and protection levels from AOSP
  `frameworks/base/core/res/AndroidManifest.xml` at several Android versions,
* turns NATICUSdroid columns (already full names) into canonical names, and
* maps TUANDROMD's short names (e.g. `CAMERA`) to full names, dropping any name
  that is not a platform permission and has no single unambiguous owner.
"""
from __future__ import annotations

import re
import xml.etree.ElementTree as ET
from pathlib import Path

RAW = Path(__file__).resolve().parent / "data" / "raw"
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
AOSP_TAGS = ["android-2.3.3_r1", "android-4.4_r1", "android-6.0.0_r1", "android-9.0.0_r1", "android-14.0.0_r1"]
DANGEROUS_REFERENCE_TAG = "android-14.0.0_r1"

# Short names in TUANDROMD that are not platform permissions but have one well-known owner.
# Each is the exact string an app writes in its manifest.
NON_PLATFORM_ALIASES = {
    "BILLING": "com.android.vending.BILLING",
    "CHECK_LICENSE": "com.android.vending.CHECK_LICENSE",
    "READ_GSERVICES": "com.google.android.providers.gsf.permission.READ_GSERVICES",
    "INSTALL_SHORTCUT": "com.android.launcher.permission.INSTALL_SHORTCUT",
    "UNINSTALL_SHORTCUT": "com.android.launcher.permission.UNINSTALL_SHORTCUT",
    "BIND_GET_INSTALL_REFERRER_SERVICE": "com.google.android.finsky.permission.BIND_GET_INSTALL_REFERRER_SERVICE",
    "SET_ALARM": "com.android.alarm.permission.SET_ALARM",
    # Defined by AOSP apps/providers rather than frameworks/base.
    "ADD_VOICEMAIL": "com.android.voicemail.permission.ADD_VOICEMAIL",
    "READ_VOICEMAIL": "com.android.voicemail.permission.READ_VOICEMAIL",
    "WRITE_VOICEMAIL": "com.android.voicemail.permission.WRITE_VOICEMAIL",
    "READ_HISTORY_BOOKMARKS": "com.android.browser.permission.READ_HISTORY_BOOKMARKS",
    "WRITE_HISTORY_BOOKMARKS": "com.android.browser.permission.WRITE_HISTORY_BOOKMARKS",
    "ACCESS_ALL_DOWNLOADS": "android.permission.ACCESS_ALL_DOWNLOADS",
    "DOWNLOAD_WITHOUT_NOTIFICATION": "android.permission.DOWNLOAD_WITHOUT_NOTIFICATION",
    # Not defined by the platform, but the exact string many apps declare (also a NATICUSdroid column).
    "RECEIVE_USER_PRESENT": "android.permission.RECEIVE_USER_PRESENT",
}

# Short names that exist under more than one owner, so the short name alone cannot be
# mapped to the manifest string an app declared. They are dropped, not guessed.
AMBIGUOUS_SHORT_NAMES = {
    "RECEIVE",               # com.google.android.c2dm..., com.amazon.device.messaging..., ...
    "READ_SETTINGS",         # launcher-specific: com.android.launcher / htc / huawei / oppo ...
    "ACTIVITY_RECOGNITION",  # android.permission (API 29+) vs com.google.android.gms.permission
    "C2D_MESSAGE",           # <app package>.permission.C2D_MESSAGE (app-specific)
    "MAPS_RECEIVE",          # <app package>.permission.MAPS_RECEIVE (app-specific)
}


def _manifest(tag: str) -> Path:
    return RAW / "aosp" / f"AndroidManifest_{tag}.xml"


def platform_permissions() -> dict[str, dict[str, str]]:
    """{full name: {tag: base protection level}} across the AOSP versions we read."""
    out: dict[str, dict[str, str]] = {}
    for tag in AOSP_TAGS:
        root = ET.parse(_manifest(tag)).getroot()
        for el in root.iter("permission"):
            name = el.get(ANDROID_NS + "name")
            if not name:
                continue
            level = (el.get(ANDROID_NS + "protectionLevel") or "normal").split("|")[0]
            out.setdefault(name, {})[tag] = level
    return out


def dangerous_permissions(platform: dict[str, dict[str, str]] | None = None) -> set[str]:
    """Runtime ('dangerous') permissions as defined by the reference Android version."""
    platform = platform or platform_permissions()
    return {n for n, levels in platform.items() if levels.get(DANGEROUS_REFERENCE_TAG) == "dangerous"}


_DOUBLE_PREFIX = re.compile(r"^(android\.permission\.)+")


def canonical(full_name: str) -> str:
    """Canonical form of a full manifest permission name as written by an app."""
    name = full_name.strip()
    # NATICUSdroid has a column 'android.permission.android.permission.READ_PHONE_STATE'
    # (a manifest typo some apps ship); it means the same platform permission.
    name = _DOUBLE_PREFIX.sub("android.permission.", name)
    return name


def map_tuandromd_short(short: str, platform: set[str]) -> str | None:
    """Full manifest name for a TUANDROMD short column name, or None when it cannot be mapped."""
    # The published CSV replaced every 'NO' inside column names with 'goodware'
    # (e.g. BIND_goodwareTIFICATION_LISTENER_SERVICE); undo that first.
    name = short.strip().replace("goodware", "NO")
    if name in AMBIGUOUS_SHORT_NAMES:
        return None
    if name in NON_PLATFORM_ALIASES:
        return NON_PLATFORM_ALIASES[name]
    full = "android.permission." + name
    return full if full in platform else None
