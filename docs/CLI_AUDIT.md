# `traceveil audit` (debug builds only)

Runs the normal on-device audit from a laptop over adb and brings back a small JSON report. Nothing is uploaded; the only channel is adb.

## Command

```
python3 -m traceveil audit --package <app id> [--plans silent,reject,accept] [--auto-drive] [--reset] [--wait 900] [--json] [--out FILE]
python3 -m traceveil audit --resume <request_id>        # fetch a result after a timeout or disconnect
```

Add `--adb <path-to-adb.exe>` under WSL2 (the Linux adb cannot see USB phones) and `--serial <serial>` when several devices are attached. Defaults come from `traceveil.toml` (`[audit] fail_on`, `min_score`; `[output] dir`).

Defaults are conservative: you tap Accept/Reject yourself, and app data is **not** cleared. `--auto-drive` lets TraceVeil tap; `--reset` clears the target's data and needs Shizuku, so use it only on developer-owned test apps.

## How it works

1. The CLI sends `am broadcast -a com.dpdpxray.app.debug.RUN_AUDIT -n com.dpdpxray.app/.cli.DebugAuditReceiver --es request_id ... --es package ...`.
2. `DebugAuditReceiver` (in `app/src/debug/` only, declared in the debug manifest, `android:permission="android.permission.DUMP"` so only the adb shell can call it) validates the request and starts the existing `AuditOrchestrator`. Release builds contain neither the class nor the manifest entry (checked in the merged release manifest and by `CliAuditTest`).
3. Progress goes to `<external files>/reports/<request_id>/status.json` (`accepted|running|done|failed`); on success `report.json` is written there atomically. Its last key, `observedTrackers`, lists every catalogue entity the app looked up (lookups and consent phases); `traceveil sdks` compares it with the SDKs bundled in the APK. On a phone: `/sdcard/Android/data/com.dpdpxray.app/files/reports/<request_id>/`.
4. The CLI polls `status.json`, reads `report.json`, validates it (`schemaVersion` 1, matching `requestId`, required keys), saves it to `.traceveil/reports/<request_id>.json` and prints a summary.

Pure, unit-tested code is in `app/src/main/.../cli/` (`CliAuditRequest`, `CliAuditReport`, `CliReportStore`); CLI logic is `src/traceveil/audit.py`.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Audit finished, policy passed |
| 1 | Policy failed: `score < min_score` or a finding at/above `fail_on` |
| 2 | Inconclusive: no score, app-reported failure, or missing/malformed report |
| 3 | Device or trigger problem: no device, app not installed, trigger rejected (VPN/accessibility off, busy, bad plan) |
| 4 | Usage error |
| 6 | Timed out. The audit is **not** cancelled; use `--resume` |

If adb drops mid-audit the CLI prints a yellow message asking you to reconnect (Enter to retry, `q` to quit; non-interactive runs wait automatically until `--wait` expires). The audit keeps running on the phone.

## Requirements and limits

- Debug APK installed and opened once (a never-opened or force-stopped app does not receive broadcasts). VPN permission granted and the TraceVeil accessibility service on, as for a UI audit.
- Do not audit personal apps. Without `--auto-drive` the Accept/Reject plans wait for your tap on the phone.
- A single `silent` plan has little traffic; the rules engine can return `V0` "low network visibility" and an INCONCLUSIVE score. Use all three plans for a score.

## Verification (10 Oct 2026)

- Python: 85 tests (`python3 -m unittest tools.tests.test_traceveil_audit tools.tests.test_traceveil_cli`), all pass.
- JVM: `:core:test :app:testDebugUnitTest` 492 tests, 0 failures. Release merged manifest contains no `DebugAuditReceiver`.
- Real iQOO 15 (vivo I2501, Android 16), debug APK installed with `adb install -r`, target `com.leakyshop.demo`, plan `silent`, no reset: trigger accepted, audit completed, `report.json` retrieved and validated (7 findings, INCONCLUSIVE/V0, exit 2). `--resume` of that request returned the same report. `--wait 3` returned exit 6 with a resume hint (that audit kept running on the phone). A missing package returned exit 3 with the app's reason; an invalid plan returned exit 3.
- Not tested on a device: an actual cable disconnect (covered by unit tests with a scripted fake), a full three-plan audit with scoring, `--auto-drive`, `--reset`.
