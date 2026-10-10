# RiskDemo: "TEST Challan (TraceVeil demo)"

RiskDemo is a **harmless test app**. It copies the *outward signals* of the fake e-Challan, pension and bank APKs described in public advisories, so the team can show TraceVeil reacting to a suspicious-looking APK without using any real malware.

It **does nothing harmful and sends no data**. It is a controlled fixture like LeakyShop, not a malware sample. TraceVeil flagging it shows that the rules work on those signals. It does not mean RiskDemo is malicious, and it does not mean TraceVeil can detect real malware.

| | |
| --- | --- |
| Module | `:riskdemo` (`riskdemo/`) |
| Package | `com.traceveil.riskdemo`, version `1.0-test` |
| Launcher label | **TEST Challan (TraceVeil demo)** |
| Icon | White block letters **TEST** on solid red |
| Android | minSdk 26, targetSdk 36, compileSdk 37 (same as LeakyShop) |
| Build | `:riskdemo:assembleDebug` → `riskdemo/build/outputs/apk/debug/riskdemo-debug.apk` |
| Signed with | The Android debug certificate (it is a test build) |

## Sources for the imitated signals

- CERT-In advisory CICA-2026-3492 (March 2026): fake RTO/e-Challan APKs that asked for SMS access, showed fake payment screens and forwarded OTPs.
- Cyber Swachhta Kendra SpyMax alert (June 2025): fake apps that obtained contacts, SMS and OTPs and contacted an attacker-controlled server.
- Google Play Protect developer guidance: SMS, notification-listener and accessibility access count as high risk for apps sideloaded from the internet.

See `TRACEVEIL_ANDROID_APK_MISUSE_CASES.md` (rows 1, 3 and 13) for the links.

## What it declares and what it actually does

Android shows declared permissions and services to anyone who inspects an APK. That static view is what APK Guard reads, so RiskDemo **declares** these items. The code uses none of them.

| Declared in the manifest | Why (signal from the research) | What the code does with it |
| --- | --- | --- |
| `READ_SMS`, `RECEIVE_SMS` | OTP-stealing apps ask for SMS | **Nothing.** One button shows Android's own SMS permission dialog. Whatever you answer, the app never reads an SMS and has no SMS receiver. |
| `READ_CONTACTS` | SpyMax-style contact harvesting | **Nothing.** It never asks for contacts at runtime and never reads them. |
| `SYSTEM_ALERT_WINDOW` (display over other apps) | Fake screens drawn over banking apps | **Nothing.** One button opens Android's "Display over other apps" settings page. The app never draws over other apps. |
| An accessibility service (`DoNothingAccessibilityService`) | Accessibility abuse for screen reading and auto-tapping | **Nothing.** `onAccessibilityEvent` and `onInterrupt` are empty. Its config subscribes to **no event types**, is limited to RiskDemo's own package, sets `canRetrieveWindowContent="false"` and asks for no gestures or key events. Its description in Settings reads "TraceVeil demo — does nothing". The app never switches it on. Only a person can, in Android Settings. |
| `INTERNET` | Contact with a C2 server or a hard-coded IP address | Used only for the reserved-name lookups and the single connection attempt described below. |

It does **not** declare or include a device admin, notification listener, VPN service, SMS sending, boot receiver, "install other apps" permission, analytics or advertising SDK, file access, or any code that stores or uploads data. `allowBackup` is off.

The merged manifest in the built APK also contains two standard AndroidX library components, which LeakyShop has as well:

- `androidx.startup.InitializationProvider`, which initialises AndroidX libraries at start-up.
- `androidx.profileinstaller.ProfileInstallReceiver`, which installs ART performance profiles and is protected by the `DUMP` permission.

They come from the Compose dependencies and do not touch personal data.

## Network attempts (on launch, background thread)

All network code is in `DemoSignals.kt`. Each launch makes these four attempts once and shows each result on screen. A **Repeat the network attempts** button runs them again.

| Attempt | Target | Why it cannot reach a real server |
| --- | --- | --- |
| DNS lookup (`InetAddress.getAllByName`) | `c2.traceveil-demo.invalid` | `.invalid` is reserved and can never exist (RFC 2606 §2, RFC 6761 §6.4). |
| DNS lookup | `pay-challan-update.invalid` | Same reason. |
| DNS lookup | `otp-relay.example` | `.example` is reserved for documentation (RFC 2606 §2, RFC 6761 §6.5). |
| TCP connect, 1.5 s timeout, port 443 | `203.0.113.10` | TEST-NET-3 (RFC 5737), reserved for documentation and not routed on the internet. The address is built from bytes, so no name lookup happens for it. |

- **No payload is ever sent.** The code never opens an output stream. If a connection opened unexpectedly, it would be closed at once with 0 bytes written.
- **Every failure is caught.** The expected result is "No address" for each name and "Not connected" for the IP. If a network resolves a reserved name anyway (some DNS-hijacking or captive networks do this), the screen shows the result in red as **unexpected**. Nothing is sent in that case either.
- **What leaves the phone.** A DNS query for a reserved name may reach the phone's configured DNS resolver, unless TraceVeil's VPN captures it first. The query carries only that fixed, made-up name. A TCP connection attempt is one SYN packet to an unroutable address. Neither contains personal data.

Calling `.invalid` "C2" mimics the name pattern only. These lookups are *attempts to contact a host*. They are not evidence that data was sent, which matches TraceVeil's own evidence rules.

## The screen

A single Compose screen styled like a challan payment page:

- A permanent red banner at the top that cannot be dismissed: **"⚠ TEST APP for TraceVeil — does nothing harmful, sends no data"**, followed by "Not a real challan or payment service…".
- A navy header, "Traffic e-Challan payment", stating that this is a TEST page and not a government or bank service. The page uses no real government or bank name or logo.
- A **Challan details (TEST data)** card with obviously fake values (`TEST-000000`, `XX 00 TEST 0000`, "Nothing to pay"). Its **Pay now** button is permanently disabled.
- A **Permission prompts for TraceVeil to observe** card with three buttons:
  - **Request SMS permission**: a runtime request for `READ_SMS` only.
  - **Open accessibility settings**.
  - **Open display-over-apps settings**.

  Under each button, a line shows the current state, read again on every resume, for example "SMS permission: granted (never used)".
- A **Network attempts made on launch** card listing the four attempts and their outcomes.

## How the safety claims are checked

`riskdemo/src/test` holds JVM tests that run with `:riskdemo:testDebugUnitTest`:

- **`DemoSignalsTest`**
  - Every hostname is under a reserved TLD (`.invalid`, `.example`, `.test` or `.localhost`).
  - The IP is inside an RFC 5737 range.
  - The port is 443 and the timeout is short.
  - `run()` looks up each name exactly once, makes exactly one connection attempt and survives every failure.
  - Unexpected successes are reported, not hidden.

  The test keeps its own reserved lists copied from the RFCs.
- **`SafetyGuardTest`**
  - Scans `src/main` and fails if the code starts using SMS or contacts providers, content queries, output streams, URL connections, datagrams, window overlays, device policy, accessibility actions, package installation or shell processes.
  - Checks that the manifest still declares the demo signals and nothing more powerful.
  - Checks that the accessibility config requests no events.
  - Checks that the TEST label and banner text are present.

## Limits and honest caveats

- **Not malware.** It cannot test detection of real malware, obfuscation, droppers, dynamic code loading or encrypted C2 traffic. A TraceVeil result on RiskDemo is a rules demo, not a detection-rate claim.
- **Restricted settings.** On Android 13 and later, the system may block the accessibility service, and possibly the SMS permission, for an app installed from a file ("Restricted setting"). That is Android protecting the user, and it is worth pointing out in the demo. Do not bypass it unless the demo needs to show the accessibility service enabled, and switch it off afterwards.
- **DNS caching.** Android and the network resolver may cache the negative answers. A quick repeat may not send a new query. Wait for a fresh launch, or treat a missing repeat lookup as caching, not as proof that nothing happened.
- **Unconfirmed on a phone.** No phone check has been done yet. That includes rendering, the Settings intents on the target OEM skin, the permission dialog, and whether TraceVeil's VPN and quarantine observe these lookups and the SYN.
- **Uninstall after demos.** Remove it from Settings > Apps when you are done.
