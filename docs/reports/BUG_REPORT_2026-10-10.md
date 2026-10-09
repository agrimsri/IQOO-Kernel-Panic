# TraceVeil bug report: live iQOO testing

10 October 2026 · Agrim Srivastava

## Summary

Five bugs and four integration gaps need fixing before the demo; the most serious is BUG-1, where one Full audit in two missed the Reject plan and scored 51 instead of 21. Agrim's evidence engine (A1–A4) worked on live data: Full audits gave leaky **21 / At risk** (C2, C1, C3, C4, C10) and fixed **100 / DPDP-ready**, matching Nitya's recorded run.

All bugs below were seen on one phone on 10 Oct 2026. Each section gives repro steps, evidence, a suspected cause (labelled as a hypothesis where not proven) and an acceptance check.

| ID | Severity | Owner | Area | Summary |
| --- | --- | --- | --- | --- |
| BUG-1 | High | Nitya | Consent watcher / orchestrator | Reject plan sometimes sees no consent screen, so no Reject, no C2: score 51 instead of 21 |
| BUG-3 | High | Agrim (rules) + Nitya (UI) | Results | A plan that never reached its consent choice is not flagged, so a missing C2 looks like a clean refusal |
| BUG-2 | Medium | Nitya | Home preflight | Consent-watcher status stays "Turn on" after an app update; Audit an app is disabled until relaunch |
| BUG-4 | Medium | Nitya (setup) | Team-setup JDK | JDK 17 extraction is missing 61 files incl. extnet.dll; every Gradle test run crashes |
| BUG-5 | Low | Nitya | Quick audit UX | Quick audit without Shizuku on an already-consented app silently ends Inconclusive |

For Yashvi there are no bugs; the contract changes in the section below affect Network Proof and the map.

## Test environment

One iQOO I2501 (Android 16), driven over USB with adb; results on other phones may differ.

| Item | Value |
| --- | --- |
| Device | iQOO I2501, Android 16, adb serial 10BFAU155Z000XR |
| Network | Wi-Fi; system resolvers 8.8.8.8 and 1.1.1.1 |
| Private DNS | Automatic (Android default), not Off |
| TraceVeil build | Debug, branch `agrim/a4-capture-health` at 7c98799 (capture) and 56af781 (notice wording) |
| Shizuku | v13.6.0 from team-setup (SHA-256 verified), started over adb, TraceVeil authorised by the owner on the phone |
| Accessibility | TraceVeil consent watcher and HackTracker both enabled |
| AI model | Not loaded; screen review used the accessibility tree only |
| Targets | `com.leakyshop.demo` and `com.leakyshop.demo.fixed` only |

Safety limits followed: only the LeakyShop demos were reset (`pm clear` or Shizuku). No Android permission dialog was tapped by automation; the owner granted VPN, Accessibility and Shizuku by hand. The agent tapped only TraceVeil's own buttons and LeakyShop's own consent buttons.

## BUG-1: Reject plan sometimes never sees the consent screen (High, Nitya)

In 1 of 2 Full audits of the leaky demo, the Reject plan recorded no consent screen, so Reject was never pressed and the audit scored **51 instead of 21** with no C2. The same app, build and phone scored 21 two minutes later.

**Files:** `app/.../consent/ConsentAccessibilityService.kt`, `app/.../consent/ConsentBus.kt`, `app/.../audit/AuditOrchestrator.kt` (`runPlan`).

**Steps to reproduce**

1. Shizuku running and authorised; consent watcher on; agent toggle on.
2. Audit an app → LeakyShop (`com.leakyshop.demo`) → Full audit → Start audit.
3. Repeat until a run ends at 51; it happened on the first of two runs.

**Expected:** every plan sees the consent screen at about 0.9 s; Reject goes through Manage → Reject all; score 21 with C2.

**Actual (audit-1791585447780):**

| Plan | Consent events | Plan note | Lookups |
| --- | --- | --- | --- |
| Silent | Screen appeared at 0.9 s | AI model not loaded | 5, all at 0.1 s |
| Reject | **none** | **No consent screen detected within 20 s.** | 5, all at 0.1 s |
| Accept | Screen at 0.9 s, Accept at 3.5 s | AI model not loaded | 7 |

The good run (audit-1791585591307) had, in the Reject plan: screen at 0.9 s, Manage at 3.5 s, Reject all at 5.0 s, then AppsFlyer, Firebase Analytics and Meta looked up again at 5.0 s. Capture was healthy in both runs (every reply COMPLETE, nothing dropped), so this is not a capture problem.

**Suspected cause (hypothesis, not proven):** the app did reach the screen (the plan would otherwise fail with "did not come to the screen"), but detection is event-driven only. `checkScreen()` runs once, `SETTLE_MS` after the last window event. If that single check runs before the consent dialog is drawn, or `targetRoot()` returns a window without the dialog, and no further window event arrives, nothing re-checks until the 20 s timeout.

**Suggested fix**

- Log each `checkScreen()` outcome per plan (no tree, tree without consent labels, consent found) to confirm the cause.
- While a plan waits for a consent screen, also re-check on a timer (for example every 1 s), not only after window events.
- If an Accept or Reject plan still sees no screen, retry that plan once (reset, relaunch) and add a plan note saying so.

**Acceptance check:** 10 consecutive Full audits of the leaky demo on one phone all score 21 with C2, C1, C3, C4 and C10; any plan that still misses the screen is retried once and noted.

## BUG-2: Consent-watcher status stays stale after an app update (Medium, Nitya)

After `adb install -r` of a new TraceVeil build, Home showed **Consent watcher: Turn on** and *Audit an app* did nothing, although the service was enabled and bound. Pressing Home and relaunching TraceVeil fixed it.

**Files:** `app/.../ui/XrayViewModel.kt` (`refreshPreflight`), `app/.../ui/screens/HomeScreen.kt`, `app/.../MainActivity.kt` (refresh on `RESUMED`), `app/.../control/DeviceChecks.kt`.

**Steps to reproduce**

1. Enable the TraceVeil consent watcher; open TraceVeil.
2. Install a new debug build over it with `adb install -r app-debug.apk`.
3. Open TraceVeil and go back to Home (it may first show the last results screen).

**Expected:** Home shows Consent watcher **On**, and *Audit an app* opens the picker.

**Actual:** Home showed **Turn on** and *Audit an app* ignored taps. At the same moment `settings get secure enabled_accessibility_services` listed the service, and `dumpsys accessibility` showed it bound with no crash. After Home → relaunch, Home showed **On**.

**Suspected cause (not confirmed):** `accessibilityOn()` checks the right setting, so the cached `Preflight` is stale. It is refreshed only on `RESUMED`; going back to Home from another screen does not trigger it, and Android may drop the service from the enabled list briefly while the package updates.

**Suggested fix**

- Refresh preflight whenever Home is shown, and observe `ENABLED_ACCESSIBILITY_SERVICES` with a `ContentObserver`.
- Re-check live when *Audit an app* is tapped; if the watcher is really off, say why instead of ignoring the tap.

**Acceptance check:** after `adb install -r`, Home shows the true watcher state within 2 s of being shown, without a relaunch.

## BUG-3: A plan that never reached its choice is not flagged (High, Agrim + Nitya)

When BUG-1 struck, Results showed **51 / At risk** with no hint that the Reject plan never refused anything, so C2 was **not tested**, not passed. A judge would read 51 as "no tracking after refusal".

**Files:** `core/.../rules/RulesEngine.kt` (Agrim: the signal), `app/.../ui/screens/ResultScreens.kt` (Nitya: the display).

**Steps to reproduce:** any Full audit where the Reject plan logs "No consent screen detected within 20 s" (BUG-1), or a Reject plan whose consent events contain no `REJECT`.

**Expected:** the result says C2 could not be evaluated because the Reject plan never reached a refusal, and the score is visibly qualified (for example "51, C2 not tested").

**Actual:** the plan note exists only under *Audit notes* at the bottom; score, band and the summary line read as a complete audit.

**Suggested fix**

- Agrim: in `RulesEngine`, when a `REJECT` plan has no `REJECT` consent event (or an `ACCEPT` plan no `ACCEPT`), add a deterministic INFO finding "Plan B never reached a refusal; C2 not tested" that does not change the score arithmetic.
- Nitya: show that finding next to the score and in the report header, not only in Audit notes.

**Acceptance check:** replaying audit-1791585447780's saved sessions shows "C2 not tested" beside the score; audit-1791585591307 (Reject reached) shows nothing extra and still scores 21.

## BUG-4: Team-setup JDK 17 is missing 61 files, so Gradle crashes (Medium, Nitya / setup)

On Agrim's laptop the team-setup JDK (`tools\jdk-17.0.20.1+1`) lacked 61 of the files in its own verified archive, including `bin\extnet.dll`. Every Gradle run failed until they were restored. Anyone using the same kit may hit this.

**Where:** `developer-tool\team-setup` (`setup.ps1` extraction, `activate.ps1` sets `JAVA_HOME` to that JDK). Not in the repository.

**Steps to reproduce**

1. Dot-source `activate.ps1`, then run `gradlew.bat :core:test --console=plain`.
2. Without a cached Gradle: the wrapper download fails with `java.lang.UnsatisfiedLinkError: no extnet in system library path`.
3. With Gradle running on another JDK: `:core:test` fails with "Process 'Gradle Test Executor 1' finished with non-zero exit value 1" and the same `extnet` error.

**Evidence:** `downloads\jdk17-windows-x64.zip` matches its recorded SHA-256 (`e53a79c3…cc7cb0`) and contains `bin/extnet.dll`; the extracted folder did not. Missing files included `extnet.dll`, `attach.dll`, `instrument.dll`, `awt.dll`, `jar.exe`, `jarsigner.exe` and the `api-ms-win-*.dll` set. The `release` file still declares `jdk.net`, so the JDK looked complete.

**Workaround used (no overwrite):** `unzip -n -q downloads/jdk17-windows-x64.zip -d tools` restored only the missing files; afterwards 65/65 baseline tests passed and all three APKs built.

**Suggested fix:** after extraction, `setup.ps1` should compare the extracted file list with the archive and fail (or re-extract) on any difference, and `STATUS.txt` should only say "OK: JDK 17" after a `java -XshowSettings` plus socket smoke test.

**Acceptance check:** a fresh run of `setup.ps1` on a clean folder gives a JDK with 0 missing files, and `gradlew.bat :core:test` passes with only `activate.ps1`.

## BUG-5: Quick audit of an already-used app ends Inconclusive without saying why (Low, Nitya)

Without Shizuku, a Quick audit of LeakyShop right after an earlier audit captured **0 lookups** in 35 s and ended Inconclusive. The app was already running with its consent stored, so its launch-time lookups had happened before capture. After `pm clear` the same audit captured 7 lookups and scored 51.

**Files:** `app/.../audit/AuditOrchestrator.kt` (`runPlan`), `app/.../ui/screens/AppPickerScreen.kt` (Quick audit option).

**Steps to reproduce**

1. Shizuku not running. Run a Quick audit of LeakyShop (agent on) so it accepts consent.
2. Immediately run a second Quick audit of LeakyShop.

**Expected:** the setup sheet warns that, without a reset, an app already opened may show nothing new; the result explains that likely cause first.

**Actual:** V0 "Low network visibility" with notes "App data was not cleared" and "No consent screen detected within 20 s"; the V0 text leads with Private DNS and DNS caching.

**Suggested fix:** in the Quick audit option, say "best on an app that has not been opened since install, or start Shizuku"; when the target was already running at launch and no consent screen appears, add a plan note saying so (Nitya); Agrim then leads V0's explanation with that note.

**Acceptance check:** repeating the steps shows the warning before start and names "app already opened" as the first likely cause.

## Integration gaps seen on the phone

The evidence engine produced every value below on live data, but the app does not yet save or show it. These are planned wiring tasks, not defects.

| Gap | Owner | What the phone showed | Wiring request |
| --- | --- | --- | --- |
| Capture health not saved (N5) | Nitya | Saved `sessions.json` has `captureHealth: null`; counters exist only in Logcat (`TraceVeilCapture`) | In `AuditOrchestrator.runPlan` at `tEnd`: `val snap = CaptureBus.snapshot()`, then `netEvents = snap.events, captureHealth = snap.health` |
| Confidence badge (N4) | Nitya | Not shown | `ConfidenceGrader.gradeAudit(sessions).summary` beside the score; `reasons` on tap. Wait for the threshold decision under Agrim's open items |
| Rights notice button (N4) | Nitya | Not shown; the builder made a correct notice from audit-1791585591307 | On C2: `RightsNoticeBuilder(catalog, DpdpCorpus.loadDefault()).build(result, sessions)?.text`; null hides the button; copy/share only, never auto-send |
| Alias chip (N4) | Nitya | Real chain `graph.facebook.com → star.c10r.facebook.com` saved, not shown | `LiveAuditScreen.kt:87` and `XrayViewModel.kt:141`: `catalog.classify(e.hostname, e.cnameChain)` and keep `disguisedVia` |
| Camera Check still on Home | Nitya, then Yashvi (Y5) | Button present | Planned removal: Nitya's route first, then Yashvi's `ToolScreens.kt` cleanup |

The exact API, field meanings and usage examples are in draft PR #3 (`agrim/a4-capture-health`).

## Contract changes to agree before integrating

Three additive changes on `agrim/a4-capture-health` need Nitya's and Yashvi's agreement; none breaks old audits, which decode with these fields as null.

| Change | Type | Meaning | Who it affects |
| --- | --- | --- | --- |
| `NetEvent.answerStatus` (`NO_REPLY`, `COMPLETE`, `PARTIAL`, `MISMATCHED`, `MALFORMED`) and `NetEvent.rcode` | Model.kt (second additive edit) | Reply outcome per lookup; null = not recorded. On the phone every reply was `COMPLETE`; `api.leakyshop.example` had rcode 3 (NXDOMAIN) | Yashvi (map: plot only `COMPLETE`/`PARTIAL` addresses, labelled inferred), Nitya (fixtures) |
| `CaptureHealth.hiddenDnsPackets` | Appended field | Packets on port 853 or TCP 53 that the relay cannot read; lookups sent that way are missing from the evidence | Yashvi (Network Proof notes), Nitya (badge reasons) |
| `CaptureHealth.nonDnsPackets` | Appended field | Tunnel packets that are not DNS (IPv6 control traffic); part of `malformedPackets` | Yashvi (Network Proof) |

**For Yashvi's Network Proof**, from the phone:

- Leaky Reject plan: 8 upstream datagrams, 322 B DNS payload sent, 653 B received. Relay resolvers are IPv4, so wire bytes ≈ payload + 28 B per datagram (`CaptureHealth.IPV4_UDP_HEADER_BYTES`).
- `TrafficStats` sampling was not tried, so whether it also counts tunnel writes or other TraceVeil traffic is unknown. Treat any remainder as an estimate, never proof that nothing was uploaded, and never clamp an unknown or negative value to 0.
- Encrypted-DNS attempts (8 in each audit's first plan) never reach the relay, so they add no relay bytes.

## Open items on Agrim's side

The confidence thresholds misfire on live data and need a decision before Nitya shows the badge; the rest are untested paths.

**Confidence thresholds.** The proposed bar of 20 lookups for High never fires on the demo, and a quiet but perfectly captured app grades Low:

| Audit | Lookups per plan (Silent / Reject / Accept) | Score | Current grade | Limiting reason |
| --- | --- | --- | --- | --- |
| Leaky (audit-1791585591307) | 5 / 8 / 7 | 21 | Medium | Fewer than 20 lookups; 8 encrypted-DNS packets in Silent |
| Fixed (audit-1791585753690) | 1 / 1 / 1 | 100 | Low | 1 lookup per plan, below the minimum of 2 |

Proposal: High from 5 lookups, and a healthy capture with 1 lookup grades Medium with the reason "the app made very few lookups". Not changed yet.

**Not verified on the phone**

- Resolver retries and relay failures: every lookup was answered by the first resolver, so `noReply` stayed 0. Covered by unit tests only.
- Private DNS Off and Strict: all runs used Automatic. Strict may hide every lookup.
- The A1 notice was produced from saved phone data through a test harness, because the app has no notice button yet.

## Appendix: raw evidence and safe reproduction

All audits below are saved on the test phone under `files/audits/` and can be pulled with `adb -s <serial> exec-out run-as com.dpdpxray.app cat files/audits/<id>/sessions.json` (debug build only).

| Audit ID | Target | Plans | Score | Note |
| --- | --- | --- | --- | --- |
| audit-1791585753690 | Fixed | Silent, Reject, Accept | 100 | 1 lookup per plan, NXDOMAIN |
| audit-1791585591307 | Leaky | Silent, Reject, Accept | 21 | Reference run: C2 after Reject all at 5.0 s |
| audit-1791585447780 | Leaky | Silent, Reject, Accept | 51 | BUG-1: Reject plan saw no consent screen |
| audit-1791584047833 and 3 later | Leaky | Accept (Quick) | 51 | After `pm clear`; 7 lookups each |
| audit-1791583818968 | Leaky | Accept (Quick) | Inconclusive | BUG-5: app already running, 0 lookups |
| audit-1791583580446 | Leaky | Accept (Quick) | 51 | Run by Agrim by hand |

**Capture log lines** (`adb logcat -s TraceVeilCapture`, counts only), reference leaky Full audit:

```
silent: lookups=5 kept=5 dropped=0 malformedPackets=12 hiddenDns=8 nonDns=4 replies=5 noReply=0 status={COMPLETE=5} upstreamSent=5/206B upstreamReceived=5/417B windowMs=26094
reject: lookups=8 kept=8 dropped=0 malformedPackets=1 hiddenDns=0 nonDns=1 replies=8 noReply=0 status={COMPLETE=8} upstreamSent=8/322B upstreamReceived=8/653B windowMs=20123
accept: lookups=7 kept=7 dropped=0 malformedPackets=1 hiddenDns=0 nonDns=1 replies=7 noReply=0 status={COMPLETE=7} upstreamSent=7/291B upstreamReceived=7/582B windowMs=18547
```

**Unparsed tunnel packets**, one Quick audit, from a temporary header-only diagnostic (removed, never committed): 8 IPv4 TCP to port 853 (DNS over TLS), 4 IPv6 hop-by-hop, 3 ICMPv6. None was broken DNS.

**Reproduce safely**

1. Use only `com.leakyshop.demo` or `com.leakyshop.demo.fixed`; never reset or auto-consent a real app.
2. Grant VPN, Accessibility and Shizuku by hand on your own phone; start Shizuku with the command its app shows under *View command*.
3. Use `adb -s <your serial>` for every command, and reset demos with `adb shell pm clear com.leakyshop.demo` or a Full audit.
