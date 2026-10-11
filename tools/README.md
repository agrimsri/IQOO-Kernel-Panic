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

## Quick start with pip (no repo checkout)

This is what `final.bash` does, as plain commands. You need Python 3.11+ and `adb` (Android platform-tools) on PATH, and the **Traceveil debug APK** already installed on the phone (it is built from the Traceveil Android app repo; it is not part of the pip package).

```bash
# 1. Install the CLI in a clean virtual environment
python3 -m venv .venv
source .venv/bin/activate            # Windows: .venv\Scripts\activate
pip install --upgrade traceveil==0.4.1   # or: pipx install traceveil
traceveil --version

# 2. Check the phone (USB debugging on, Traceveil debug APK installed)
traceveil doctor

# 3. (Optional) install the app you want to audit
adb install -r path/to/your-app-debug.apk

# 4. Audit it and save the JSON report
traceveil audit --package com.example.yourapp --out report.json
echo $?                              # 0 pass, 1 findings, 2 inconclusive, 3 device/trigger, 6 timeout
```

Useful variations: `--plans silent` (quick, often inconclusive), `--auto-drive` (Traceveil taps Accept/Reject), `--serial <id>` with several phones, `--adb <path>` to choose the adb binary, `traceveil init` to create a `traceveil.toml`. Under WSL2 use the Windows adb (see the WSL2 note in section 1).

From a repo checkout instead (section 1 below also builds the APKs): `pip install -e .` (then `traceveil ...` or `python3 -m traceveil ...`). The examples below use `python3 -m traceveil`; the installed `traceveil` command is identical.

## 1. One-time setup (repo checkout: build the APKs)

You need: Python 3.11+, JDK 17, Android platform-tools (adb), a phone with USB debugging on.

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
  "sessions": [ { "plan": "REJECT", "netEvents": 12, "consentChoice": "REJECT", "notes": [] } ],
  "observedTrackers": [ { "entity": "AppsFlyer", "category": "ATTRIBUTION", "lookups": 4, "phases": ["AFTER_REJECT", "BEFORE_CONSENT"] } ]
}
```

`score` is `null` (never 0) when the result is inconclusive. The report is a technical assessment, not legal advice.
`observedTrackers` lists every catalogue tracker the app looked up, by consent phase (debug APKs built from this version on; older reports do not have it).

## 3a. SDK inventory: "SDK found in APK" vs "tracker observed live"

Lists the tracking SDKs bundled in an APK (from the class names in its `classes*.dex`) and compares them with what an audit actually saw. No phone is needed unless you pass `--package`.

```bash
traceveil sdks --apk app/build/outputs/apk/debug/app-debug.apk                                   # static only
traceveil sdks --apk app-debug.apk --report .traceveil/reports/<request_id>.json                  # compare with an audit
traceveil sdks --apk app-debug.apk --report <request_id> --package com.example.app --fail-on-consent   # + permissions, CI gate
```

| Group | Meaning | Evidence |
|---|---|---|
| `in APK + live` | SDK code is bundled and its hosts were looked up | Observed |
| `in APK only` | SDK code is bundled, no lookup in this audit | Not established (may be dormant, consent-gated, or not triggered) |
| `live only` | Tracker hosts looked up, but no bundled SDK matched | Inferred (WebView, custom HTTP code, renamed/obfuscated SDK) |

Each row gets next steps: gate the SDK on consent if it was looked up before consent or after Reject; remove it if unused; and, for ad SDKs in an app that requests location (from `--package`), review the SDK's location defaults.
`--fail-on-consent` exits 1 when a bundled SDK was looked up before consent or after Reject. `--json` / `--out` give the JSON inventory.
The signature list (`src/traceveil/data/sdk_signatures.json`) is hand-written from public package names and covers the tracker catalogue's entities. Obfuscated or unlisted SDKs are not recognised; bundled code does not prove it ran; a lookup does not prove what data was sent.

## 3b. Legal lenses: several countries' privacy laws

The CLI uses the **same deterministic legal lenses as the app** (`LegalLens`, data in `core/src/main/resources/law/`, copied into the package by `tools/sync_law_data.py`). Findings and severities never change; each lens marks every check as **required** by that law, **context**, or **not applicable**, with the law's own passages and source links. The lens score is the app's score without the not-applicable findings, so the INDIA lens always equals the app score.

| Lens | Law |
|---|---|
| `INDIA` (`in`) | DPDP Act 2023 + DPDP Rules 2025 |
| `EU` (`eu`, `gdpr`) | GDPR + ePrivacy Directive Art 5(3) |
| `UK` | UK GDPR + PECR reg 6 |
| `US_CALIFORNIA` (`us-ca`, `ccpa`) | CCPA/CPRA |
| `US_CHILDREN` (`coppa`) | COPPA |
| `BRAZIL` (`br`, `lgpd`) | LGPD |
| `SINGAPORE` (`sg`, `pdpa`) | PDPA |
| `GLOBAL_BASELINE` (`global`) | consent-first good practice (not a law) |

```bash
traceveil audit --package com.example.app                      # summary ends with all 8 lenses
traceveil audit --package com.example.app --lens eu,us-ca --policy eu   # the EU lens decides the exit code
traceveil lens                                                 # re-read the newest saved report, no phone needed
traceveil lens --report <request_id> --lens eu --detail        # relation, the law's note and cited passages per finding
traceveil lens --list
```

Config: `audit.lenses` (default `"all"`) and `audit.policy` (default `"INDIA"`, so exit codes are unchanged). The saved report gains a `lenses` block. A lens is a reading of the evidence, not a legal verdict.

### How to fix, per country, for you or your coding agent

`traceveil fix` turns a saved audit into a **fix plan**: for each finding, the countries whose law requires it (from the lenses above), the trackers behind it with their company's country, and the app's own "How to fix" templates (`core/src/main/resources/fixes/fix_templates.json`, the same `FixCatalog` the phone shows; copied by `tools/sync_law_data.py`). Nothing is generated by a model, so the same report always gives the same plan, and no report data leaves the machine.

```bash
traceveil audit --package com.example.app --out report.json   # the JSON report carries the fixes in "fixPlan"
traceveil audit --package com.example.app --format md         # print the fix plan as Markdown instead of the summary
traceveil fix                                                 # newest report: per finding "required in: IN, EU, GB ..." + fixes, by-country table
traceveil fix --report <request_id> --json --out report.json  # re-read a saved (or older) report and write it back with fixPlan
traceveil fix --report <request_id> --format md               # same plan as Markdown (an --out path ending in .md also writes Markdown)
```

The fixes live in the JSON report itself, under `fixPlan` (schema `traceveil/fix-plan@1`); hand that file to a coding agent. The audit summary shows `required in:` and `fix:` under each finding. The plan is built for agents: `agentInstructions`; `fixes[]` with `manifest`, `code`, `steps`, `docUrl`, `verified` (false = check the API for your SDK version), `locate` (package prefixes to search for, files to open) and the `resolves` issues; `issues[]` with evidence, `trackers`, `requiredIn` / `contextIn` lens ids, `jurisdictions` (countries, the law's note, cited passages) and a `doneWhen` test; and `verify.commands` to re-audit. Trackers without a template are listed under `untemplatedTrackers` with a generic "gate it behind consent" hint.

Colours: severities (CRITICAL, HIGH, MEDIUM, LOW), scores, bands and tracker names are coloured on a terminal. `NO_COLOR=1` turns colours off; `FORCE_COLOR=1` keeps them in pipes.

## 3c. Network beyond DNS: traffic the DNS capture cannot see

TraceVeil captures plain DNS lookups. An SDK that uses **encrypted DNS** (DNS-over-HTTPS or -TLS), connects to a **hardcoded IP**, or sends DNS **straight to an outside resolver** makes no lookup the capture can read. It still has to open a socket, and `adb shell` can read every socket on the phone with its owner app from `/proc/net/{tcp,tcp6,udp,udp6}`. While an audit runs, the CLI samples the audited app's sockets once a second and matches each remote address against the addresses its visible lookups returned (`dnsAnswers` in the report):

| Label | Meaning |
|---|---|
| `ENCRYPTED DNS` | port 853, or a public DNS-over-HTTPS resolver (Google, Cloudflare, Quad9, ...) on 443; also a lookup of a DoH resolver's name |
| `DIRECT DNS` | plain DNS to an outside resolver, bypassing TraceVeil's relay |
| `NO LOOKUP` | an address no visible lookup returned: hidden DNS, a hardcoded IP, or a lookup cached from before the capture |
| `QUIC` | UDP 443 to an address no visible lookup returned |
| `matched` | connected to an address a visible lookup returned: the tracker was **contacted**, not only looked up |

An app that declares its own VPN service (`BIND_VPN_SERVICE`) is flagged too: Android runs one VPN at a time, so such a tunnel would also stop TraceVeil's capture.

```bash
traceveil audit --package com.example.app                    # "Network beyond DNS" section (on by default)
traceveil audit --package com.example.app --fail-on-hidden   # CI: exit 1 when hidden traffic is found
traceveil watch --package com.example.app --seconds 60 --report <request_id>   # sample while you use the app
```

Limits: sampling is once a second, so a connection opened and closed between samples is missed; Android shows closed (TIME_WAIT) connections without their app; only addresses and ports are read, never payload, and the hostnames inside encrypted DNS stay unknown. `--no-net-watch` or `audit.watch_network = false` turns sampling off. The LeakyShop leaky demo includes an `io.branch` stand-in that resolves over DNS-over-HTTPS to show this.

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
