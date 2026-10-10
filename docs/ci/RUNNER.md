# TraceVeil Runner: a hands-free consent audit for every alpha/beta build

One command on a laptop or a CI runner. A test phone connected over USB runs TraceVeil's full consent audit of your build by itself and the command ends with **PASS (exit 0)**, **FAIL (exit 1)** or **ERROR (exit 2)**.

```sh
traceveil-ci run --package com.example.app --apk app-beta.apk --mode full --policy privacy/traceveil-policy.json
```

The Runner does not replace your alpha/beta testing. It adds one automatic check to it: what the app looks up on the network around the consent screen, for every build, the same way every time.

## How this fits alpha/beta testing

Alpha and beta testers check features: does checkout work, does the new screen look right. Nobody in that loop can see that an analytics SDK looked up its server 0.1 s after the tester tapped **Reject**. It happens below the screen, in a fraction of a second, and it looks exactly like a working app.

TraceVeil sees it, because it records the app's DNS lookups and the consent choices on the phone. The Runner makes that a gate in the pipeline you already have:

```text
 your pipeline today                         with the Runner
 ──────────────────                          ───────────────
 build the alpha/beta APK                    build the alpha/beta APK
 hand it to testers ──────────────┐          traceveil-ci run --apk app.apk ...   ← new step, no person needed
                                  │            installs it on the USB test phone,
                                  │            runs Silent, Reject and Accept plans,
                                  │            gates the evidence with your policy
                                  │          exit 1 → the build stops here, with the finding and its evidence
                                  └───────►  exit 0 → hand it to testers as before
```

- **Automatic:** the phone resets the test app, opens it, waits for the consent screen, taps Reject (and Accept in its own plan) and watches what follows. Nobody holds the phone.
- **Repeatable:** the same three plans and the same timing for every build, so a regression shows up as a new finding, not as a feeling.
- **In the tools you have:** an exit code, a text log, JSON for scripts and SARIF for GitHub code scanning. The evidence file is kept as a build artifact.

What it is not: a pass is not a compliance certificate, and a failure is not a legal verdict. A DNS lookup is an attempt to contact a host, not proof that personal data was sent. The evidence covers one phone, one run, one build.

## What happens in one run

1. **Preflight on the laptop:** adb runs; exactly one phone is connected and authorised (or the one named by `--device`); TraceVeil is installed; with `--apk`, `adb install -r` installs your build (app data is kept); the package under test is installed.
2. **One request to the phone:** a single explicit intent to TraceVeil's runner screen, with plain values only:
   `adb -s SERIAL shell am start -n com.dpdpxray.app/com.dpdpxray.app.automation.AutomationActivity --activity-multiple-task --es token '…' --es package 'com.example.app' --es mode 'full' --ez autoTap true --ez childFacing false --es runId 'run-…'`
3. **The phone decides.** TraceVeil checks that Developer automation is on, the token matches, the app is installed (and is not TraceVeil), a full run's app is on the reset allowlist with Shizuku ready, the VPN is allowed and the consent watcher is on, and no other check is running. If anything is missing it refuses, shows why on screen and writes the reason and the fix for the laptop.
4. **The normal audit runs.** An accepted request starts the same audit as **Start check** in the app (same plans, timing, agent taps, reset and saving) and shows the normal live screen.
5. **Progress:** the phone keeps `Android/data/com.dpdpxray.app/files/automation/<run id>/status.json` up to date; the laptop reads it every 2 seconds and prints each step.
6. **Result:** when the audit is saved, the phone writes `bundle.json` (the standard `traceveil.audit.v1` export, the same as **Export audit data (JSON)**), then marks the status `done` with a signature of the bundle (HMAC-SHA256 keyed with the run's pairing token). The laptop copies both with adb, checks the signature, re-checks the bundle with the current rules and applies the policy exactly like `traceveil-ci check`.

## One-time phone setup

Do this once on the test phone, yourself. The Runner never changes these settings.

1. **Install TraceVeil** (a build that includes Developer automation) and open it once.
2. **USB debugging:** in Developer options, switch on USB debugging, connect the laptop and tap **Allow** (tick *Always allow from this computer* for a CI runner).
3. **Developer automation:** TraceVeil → Settings → Developer automation → switch on **Allow TraceVeil Runner**.
4. **Copy the token:** tap **Copy** under *Pairing token* and give it to the laptop as the `TRACEVEIL_TOKEN` variable (a secret in CI). **Regenerate** revokes it.
5. **Allowlist the test app** (needed for `--mode full`): under *Test apps the runner may reset*, tap **Add a test app** and pick the app under test. A full run clears that app's local data before each of its three plans. Add only your own test or development builds, never a personal app.
6. **Allow the VPN once:** on TraceVeil's Home, tap **Allow the VPN** and accept Android's request.
7. **Switch on the consent watcher yourself:** Settings → Accessibility → **TraceVeil consent watcher** → On.
8. **Shizuku, for full runs only:** a full run uses TraceVeil's own reset, which clears the test app's data through Shizuku. Start Shizuku and allow TraceVeil in it (TraceVeil's Home then shows *Fresh start ready*). Shizuku has to be started again after the phone restarts. Quick runs do not need it.
9. **Recommended:** Private DNS off (encrypted DNS hides lookups), no other VPN active, and the screen kept on and unlocked during runs (Developer options → *Stay awake* while charging).

The Developer automation section in Settings shows which of the VPN, watcher and Shizuku items are still missing.

## Laptop or CI runner setup

- Java 17 and Android platform-tools (`adb`).
- Build the tool from this repository: `./gradlew :cli:installDist` (scripts in `cli/build/install/traceveil-ci/bin`), or `./gradlew :cli:fatJar` for one jar.
- adb is taken from `--adb`, else the `ADB` variable, else `adb` on `PATH`. The phone is `--device`, else `ANDROID_SERIAL`, else the only connected phone.

## The one-command demo

With the controlled LeakyShop demo app installed on the phone and allowlisted (step 5 above):

```sh
export TRACEVEIL_TOKEN='paste-the-token-here'
cli/build/install/traceveil-ci/bin/traceveil-ci run --package com.leakyshop.demo --mode full
```

Windows PowerShell:

```powershell
$env:TRACEVEIL_TOKEN = 'paste-the-token-here'
cli\build\install\traceveil-ci\bin\traceveil-ci.bat run --package com.leakyshop.demo --mode full
```

The log shows the preflight, the start command (with the token hidden), one line per audit step from the phone, the saved files and then the normal `check` report. LeakyShop's leaky build is made to look up tracker hosts after Reject, so a run that records the Reject choice is expected to end with C2 and **FAIL (exit 1)**; the fixed build (`--package com.leakyshop.demo.fixed`, also allowlisted) with `--policy docs/ci/traceveil-policy.json` is expected to pass when both choices are recorded. The actual result always comes from that run's evidence: a missed consent screen or a quiet run fails the default policy rather than passing.

## Command reference

```text
traceveil-ci run --package <pkg> [--device SERIAL] [--apk file.apk] [--mode full|quick] [--token T]
                 [--policy policy.json] [--timeout 300] [--out traceveil-out] [--adb path]
                 [--format text|json] [--sarif file] [--child-facing]
```

| Option | Meaning |
| --- | --- |
| `--package` | The app under test (required). Not TraceVeil itself. |
| `--device` | adb serial of the phone. Default: `ANDROID_SERIAL`, else the only connected phone. |
| `--apk` | Install this build first with `adb install -r` (keeps app data). If the package under test is unchanged afterwards (the APK was another app), the run stops with exit 2 instead of checking the older build. |
| `--mode` | `full` (default): Silent, Reject and Accept plans, each after a fresh reset; needs the allowlist and Shizuku. `quick`: one Accept plan; the app's data is cleared only if it is allowlisted and Shizuku is ready. Fast Preview is not offered: it is a first look and cannot gate a release. |
| `--token` | Pairing token. Default: `TRACEVEIL_TOKEN`. Never printed. |
| `--policy` | Policy file, same keys and defaults as `check` (see [CI mode](../CI_MODE.md#policy-reference)). A quick run cannot pass the default policy, which requires all three plans; the log says so. |
| `--timeout` | Seconds to wait for the phone after the start (default 300). |
| `--out` | Folder for the evidence (default `traceveil-out`); each run writes `<out>/<run id>/bundle.json` and `status.json`. |
| `--adb` | adb executable. Default: `ADB`, else `adb` on `PATH`. |
| `--format` | `text` (default) or `json`. With `json`, stdout holds one `traceveil.ci.run.v1` document for every run that gets past the option checks (option errors are printed to stderr with exit 2, as for `check`), and the progress log goes to stderr. |
| `--sarif` | Also write the findings as SARIF 2.1.0 for code scanning (only when a bundle was checked). |
| `--child-facing` | Declare the app child-facing, so C9 can be tested instead of being NOT ESTABLISHED. |

| Exit code | Meaning |
| --- | --- |
| 0 | The audit finished and its bundle passes the policy. |
| 1 | The audit finished and its bundle violates the policy (the report lists each violation). |
| 2 | Anything that stopped the run before a policy result: bad options or policy, adb missing, no phone, several phones without `--device`, an unauthorised or offline phone, TraceVeil or the app not installed, a failed `--apk` install or one that did not update the package, a refusal or failure on the phone (with its reason and fix), lost contact, `--timeout`, or a bundle that is not signed for this run or does not match it. |

A timeout does not stop the check on the phone; it finishes there and stays in TraceVeil's Reports.

### JSON output (`traceveil.ci.run.v1`)

`schema`, `tool`, `exitCode`, `passed`, `error` and `fix` (null when the run finished), `device`, `package`, `mode`, `runId`, `status` (the phone's last status, below), `bundle` (path of the saved bundle) and `check` (the `traceveil.ci.check.v1` report of `check --format json`, or null).

### The phone's status file (`traceveil.automation.status.v1`)

`automation/<run id>/status.json` on the phone, replaced whole on every change:

| Field | Meaning |
| --- | --- |
| `state` | `running`, `done` (the audit finished and was saved; not a pass) or `failed`. |
| `reason`, `reasonCode`, `fix` | Why it failed or was refused, a stable code for scripts, and what to change. |
| `package`, `mode`, `autoTap`, `childFacing`, `resetAppData` | What was run. Invalid values from a refused request are left out, not copied. |
| `step` | The live screen's current step, e.g. `Plan B · Reject (2 of 3): Waiting for a consent screen`. |
| `auditId`, `score`, `band`, `findingCount` | The saved audit. `score` is null when the result is inconclusive (unknown, never zero). |
| `bundleSignature` | With `done`: HMAC-SHA256 (hex) of `bundle.json`'s exact bytes, keyed with the run's pairing token. |
| `startedAt`, `updatedAt`, `finishedAt`, `appVersion` | ISO-8601 UTC times and the TraceVeil version. |

The pairing token is never written to it.

## Security model

- **Off by default.** Until *Allow TraceVeil Runner* is switched on in Settings, every request is refused.
- **adb only.** The runner screen has no intent filter; it opens only by its exact component name and only for callers holding `android.permission.DUMP`, which the adb shell and the system hold and ordinary apps on the phone cannot. TraceVeil itself does not request that permission. Exception: an app you authorise in Shizuku runs commands with the same shell rights as adb, so treat every Shizuku-authorised app on the test phone like the laptop itself, and authorise only apps you trust (TraceVeil needs Shizuku for full runs).
- **Pairing token.** 120 random bits, created on the phone when automation is first switched on, kept in TraceVeil's private storage (app backup is disabled) and compared in constant time. **Regenerate** revokes the old token at once. Without the right token, the phone reveals nothing about installed apps. The token travels on the adb command line, so it protects against other apps on the phone, not against whoever already controls the connected laptop. traceveil-ci never prints it; keep it in a CI secret.
- **Reset only what you allowed.** The reset allowlist starts empty and holds only apps you picked; TraceVeil cannot be added. A full run of any other app is refused. Quick runs keep the app's data unless it is allowlisted.
- **Nothing is granted for you.** The Runner never allows the VPN, never switches on the accessibility service, never grants a permission and never taps Android permission dialogs. If something is missing, the phone refuses and says what to do. The agent taps only Accept and Reject in the app under test's consent screen, as **Let the agent tap Accept and Reject** does.
- **Plain values, no paths or commands.** The request carries strings and booleans only. The run id must match `[A-Za-z0-9_-]{1,40}`; it is the only value that becomes part of a file path, and files are written only under TraceVeil's own `Android/data/com.dpdpxray.app/files/automation/`. An already used run id is refused rather than overwritten. The 20 most recent runs are kept.
- **Signed evidence.** The `done` status carries an HMAC-SHA256 of `bundle.json`'s exact bytes, keyed with the pairing token that started the run; traceveil-ci recomputes it and refuses (exit 2) to gate a bundle that does not match. A bundle written or changed by anything that does not know the token, such as another app with shell rights, is therefore never gated. It does not protect against someone who has the token, and on a debuggable TraceVeil build adb itself can read the token (`run-as`).
- **Nothing leaves the phone except over your own USB/adb connection.** TraceVeil uploads nothing and opens no network service for the Runner; the laptop reads `status.json` and copies `bundle.json` over adb. The bundle holds the evidence (looked-up hosts, consent-screen text, device model); treat CI artifacts that contain it accordingly.
- **Where the files sit.** `Android/data/com.dpdpxray.app/files/automation/` is TraceVeil's app-specific folder: from Android 11 on, other apps cannot read it, but adb can. On Android 10, apps holding the storage permission may be able to read and write it, so use a test phone without other untrusted apps.

## Limits

- One phone, one run, one build. A pass covers what that run observed. It is not proof that the app never contacts a tracker and not a compliance certificate.
- The phone must stay connected, on and unlocked for the whole run. A locked screen stops the app under test from opening; the run then fails with the app's general message that the app did not come to the screen.
- One check at a time per phone. A request while another check or a quarantine run is active is refused.
- Full runs need Shizuku running and allowed, which a phone restart undoes.
- The laptop reads TraceVeil's files under `Android/data` over adb. If an Android build does not let adb read them, the Runner stops with a *Permission denied* message; export the bundle from the app instead.
- Consent screens the watcher cannot read (some canvas-drawn games) give no recorded choice. The default policy then fails the run as not established rather than passing it.
- Timing is the app's normal timing: full LeakyShop audits started from the screen took about 63–70 s on our test phone. The default `--timeout` of 300 s covers that with room to spare, but with every wait at its limit (8 s launch, 20 s consent wait, 15–25 s observation and up to 90 s of on-device AI screen review per plan, plus model loading and one possible retry for the demo apps) a full run can take longer; raise `--timeout` if the phone's live screen is still moving. The Runner path itself has not yet been timed on a phone.
- Emulators are future work. The Runner is designed for a physical phone over USB and its laptop side is unit-tested against a scripted phone; VPN capture, the consent watcher and Shizuku on an emulator have not been tested.

## Troubleshooting

| The Runner says | Do this |
| --- | --- |
| `No phone is connected` / `… is unauthorized` | Connect the phone, unlock it, tap **Allow** on the USB debugging prompt. |
| `This TraceVeil build has no Developer automation` | Install a TraceVeil build that includes it. |
| `The phone refused the run: Developer automation is off` | Step 3 of the phone setup. |
| `The pairing token does not match` | Copy the token again (step 4); after **Regenerate**, update the CI secret. |
| `… is not on this phone's list of test apps the runner may reset` | Step 5, or use `--mode quick`. |
| `Android has not allowed TraceVeil's VPN yet` / `consent watcher … is off` | Steps 6 and 7. |
| `A full run needs Shizuku` | Step 8, or use `--mode quick`. |
| `TraceVeil wrote no status within 30 s` | Unlock the phone and check that TraceVeil opened. |
| `Timed out after … s` | The check may still be finishing on the phone; raise `--timeout` and watch the phone's live screen. |
| `Android brought an earlier TraceVeil screen to the front` | Close TraceVeil's runner screen on the phone (or force-stop TraceVeil) and run again; use matching TraceVeil and traceveil-ci builds. |
| `bundle.json is not signed with this run's pairing token` | Run again; if it repeats, check which apps on the phone have shell rights through Shizuku. |

See also: [CI mode](../CI_MODE.md) for `check`, `diff`, policies and bundle formats, and [github-runner-example.yml](github-runner-example.yml) for a self-hosted CI runner with a USB test phone.
