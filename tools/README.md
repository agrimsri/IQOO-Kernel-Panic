# Traceveil CLI

Run a Traceveil privacy audit on your Android phone from your laptop, and get a JSON report back. Everything goes over USB (adb); nothing is uploaded.

```
laptop                                   phone (debug build of Traceveil)
  traceveil audit  --adb broadcast-->    DebugAuditReceiver -> runs the normal audit
        ^                                          |
        |  adb shell cat                           v
        +-------- report.json  <--- /sdcard/Android/data/com.dpdpxray.app/files/reports/<id>/
  saves .traceveil/reports/<id>.json
```

The trigger exists **only in debug builds**. Release builds have no way to start an audit from adb.

## 1. One-time setup

You need: Python 3.11+, JDK 17, Android platform-tools (adb), a phone with USB debugging on.

Install the CLI (from PyPI once published, or from a checkout):

```bash
pipx install traceveil          # or: pip install traceveil
pip install -e .                # from a checkout; `python3 -m traceveil` also works
```

The examples below use `python3 -m traceveil`; the installed `traceveil` command is identical.

```bash
# 1. Build and install the debug app (run from the repo root)
bash ./gradlew :app:assembleDebug :leakyshop:assembleLeakyDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r leakyshop/build/outputs/apk/leaky/debug/leakyshop-leaky-debug.apk   # demo app to audit

# 2. Check the phone is ready
python3 -m traceveil doctor
```

Under **WSL2** the Linux `adb` cannot see USB phones. Use the Windows one for every command:
`--adb /mnt/c/Users/<you>/AppData/Local/Android/Sdk/platform-tools/adb.exe` (or `export ADB=...`).
Install the APK with that adb too, from a Windows path.

On the phone, once:
1. **Open Traceveil** and allow the **VPN** permission (Android only delivers the trigger to an app that has been opened).
2. Turn on the **Traceveil accessibility service** (Settings > Accessibility).
3. Set **Private DNS = Off** (otherwise lookups are hidden from the audit).

## 2. Run an audit

```bash
python3 -m traceveil audit --package com.leakyshop.demo
```

With several phones attached add `--serial <serial>` (`adb devices` lists them).

What happens, step by step:

1. The CLI checks Traceveil is installed and sends the trigger. You see `audit started: cli-<time>-<id>`.
2. The phone runs the audit (about 1 to 3 minutes for three plans). By default **you tap Accept / Reject on the phone** when asked. Add `--auto-drive` to let Traceveil tap.
3. The CLI waits (default 15 min, change with `--wait <seconds>`), then reads `report.json` from the phone and checks it is valid.
4. It saves the report to `.traceveil/reports/<request_id>.json` and prints a summary:

```
Traceveil audit  LeakyShop (com.leakyshop.demo)  score 21 / AT_RISK  findings 5
  C1   HIGH     Trackers contacted before consent
  ...
  policy: 3 finding(s) at or above high
  report saved: .traceveil/reports/cli-20261010T071219Z-0f9239c5.json
```

Useful options:

| Option | What it does |
|---|---|
| `--plans silent,reject,accept` | Pick plans (default all three; one plan is quick but often gives an inconclusive score) |
| `--json` | Print the full JSON instead of the summary |
| `--out report.json` | Also write the JSON to a file you choose |
| `--auto-drive` | Traceveil taps Accept/Reject itself |
| `--reset` | Clear the app's data first. **Only for your own test apps**; needs Shizuku |
| `--resume <request_id>` | Fetch the result of an earlier run (after a timeout or unplug) |

Settings you use often can live in `traceveil.toml` (`python3 -m traceveil init` creates it): `target.package`, `audit.fail_on`, `audit.min_score`, `output.dir`.

## 3. The report

```json
{
  "schemaVersion": 1,
  "requestId": "cli-20261010T071219Z-0f9239c5",
  "auditId": "audit-1791616339835",
  "target": { "package": "com.leakyshop.demo", "label": "LeakyShop" },
  "device": { "model": "vivo I2501", "androidVersion": "16" },
  "score": 21,
  "band": "AT_RISK",
  "findingCount": 5,
  "findings": [
    { "check": "C1", "severity": "HIGH", "title": "Trackers contacted before consent", "confidence": 1.0 }
  ],
  "sessions": [ { "plan": "REJECT", "netEvents": 12, "consentChoice": "REJECT", "notes": [] } ]
}
```

`score` is `null` (never 0) when the result is inconclusive. The report is a technical assessment, not legal advice.

## 4. Exit codes (for scripts and CI)

| Code | Meaning |
|---|---|
| 0 | Finished, policy passed |
| 1 | Policy failed (score below `min_score`, or a finding at/above `fail_on`) |
| 2 | Inconclusive, app-reported failure, or missing/malformed report |
| 3 | Device or trigger problem (no phone, app missing, VPN/accessibility off, another audit running) |
| 4 | Bad arguments |
| 6 | Timed out (the audit keeps running; use `--resume`) |

## 5. If something goes wrong

- **Phone unplugged mid-audit**: the CLI prints a yellow message. Reconnect, unlock the phone, press Enter to retry (or `q` to quit). The audit keeps running on the phone; `--resume <request_id>` picks it up later.
- **"no debug trigger answered"**: install the *debug* APK and open Traceveil once. A force-stopped app does not receive the trigger.
- **"VPN permission not granted" / "accessibility service is off"**: do steps 1 and 2 of the phone setup.
- **Timeout**: raise `--wait`, or run again with `--resume <id>`.
- **Device "unauthorized"**: unlock the phone and tap Allow on the USB debugging prompt.

Safety: audit only your own test apps (like the LeakyShop demos). The CLI never force-stops anything and never uploads data.

## 6. Tests

```bash
pip install -e . && python3 -m unittest tools.tests.test_traceveil_audit tools.tests.test_traceveil_cli   # CLI, no phone needed
bash ./gradlew :core:test :app:testDebugUnitTest                                       # Android side
```

More detail (design, limits, device evidence): `docs/CLI_AUDIT.md`.
