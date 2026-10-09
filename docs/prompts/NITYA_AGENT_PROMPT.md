# Nitya coding agent prompt

Paste everything below into Nitya's coding agent, opened in Nitya's own TraceVeil clone. Written 10 October 2026 after PR #5 (Agrim's A4 + A5) merged into `main`.

---

We are team Kernel Panic building TraceVeil at the iQOO hackathon. I am Nitya (commit identity `Nitya Prakash Pandey <nityaprakashpandey389@gmail.com>`). Work as my coding partner on my own branches. Do not make commits as Agrim or Yashvi, alter dates, request anyone else's credentials, rewrite published history or push to main.

Repository: https://github.com/agrimsri/IQOO-Kernel-Panic.git. **`docs/planning/IMPLEMENTATION_MILESTONES.md` is the active plan** (my milestones N1–N8). Follow live venue Red/Green instructions.

## Where things stand

| Item | State |
| --- | --- |
| Agrim A0–A5 | All on `main` (PR #5, `ebdafae`): capture health, confidence grade, rights notice, DNS answers, alias classification, cited company headquarters. Agrim's feature branches are deleted. |
| Yashvi Y1, Y2 | Voice Copilot and Network Proof calculator/card are on `main` but **not mounted**. |
| Yashvi Y3 map | On `yashvi/phone-experience`, not yet on `main`. |
| My N2 / N3 | `nitya/repo-readiness` (CI, history checks) and `nitya/sample-fixtures` (synthetic alias and endpoint fixtures) are **not merged** and are 26–28 commits behind `main`. |
| Integration on `main` | None of the evidence UI is wired yet; Camera Check is still present; no `ATTRIBUTIONS.md`. |
| Live phone results | iQOO I2501, 10 Oct: Full audits leaky **21 / At risk** (C2, C1, C3, C4, C10) and fixed **100 / DPDP-ready**, plus five bugs. Read `docs/reports/BUG_REPORT_2026-10-10.md` first. |

## Start here

1. `git status --short`; preserve local edits (no reset, no force checkout). `git fetch --prune origin`; update clean `main` with `git pull --ff-only origin main`.
2. Bring your two branches up to date with **merge commits** (`git merge origin/main`), run the suites, and open PRs for them. Do not rebase or force-push published branches.
3. Build and test: `gradlew.bat :core:test :app:testDebugUnitTest :app:assembleDebug :leakyshop:assembleLeakyDebug :leakyshop:assembleFixedDebug`. On `main` the suite was 197 core and 52 app tests, 0 failures; record your actual numbers. If Gradle crashes with `no extnet in system library path`, see BUG-4 below.
4. Use your own GitHub authentication; never paste a token or password in chat.

## Work in priority order

### P0: bugs found on the phone (details, evidence and acceptance checks are in the bug report)

1. **BUG-1 (High): the Reject plan sometimes never sees the consent screen.** 1 of 2 Full audits scored 51 instead of 21 because the Reject plan logged "No consent screen detected within 20 s" and never pressed Reject. Files: `consent/ConsentAccessibilityService.kt`, `consent/ConsentBus.kt`, `audit/AuditOrchestrator.kt`. Log every `checkScreen()` outcome, also re-check on a timer (about every 1 s) while a plan waits for the screen, and retry a plan once (reset, relaunch, with a plan note) if it still sees nothing. Acceptance: 10 consecutive leaky Full audits all score 21 with C2.
2. **BUG-3 (High, your half): flag a plan that never reached its choice.** Agrim will add a deterministic INFO finding in `RulesEngine` ("Plan B never reached a refusal; C2 not tested"). You show it next to the score and in the report header, not only under Audit notes. Until Agrim's part lands, show the existing plan note there.
3. **BUG-2 (Medium): stale consent-watcher status after an app update.** Home showed "Turn on" and ignored *Audit an app* although the service was enabled and bound. Refresh preflight whenever Home is shown, observe `ENABLED_ACCESSIBILITY_SERVICES` with a `ContentObserver`, and re-check live when *Audit an app* is tapped.
4. **BUG-5 (Low): Quick audit of an already-opened app ends Inconclusive without saying why.** Warn in the Quick audit option and add a plan note when the target was already running and no consent screen appeared.
5. **BUG-4 (Medium, team-setup kit): JDK 17 extraction missing 61 files**, including `extnet.dll`. `setup.ps1` should compare the extracted files with the archive and fail or re-extract; restore with `unzip -n` from the verified archive.

### P1: integration wiring (exact calls; do not edit Agrim's or Yashvi's files)

- **N5 (save capture health):** in `AuditOrchestrator.runPlan`, at `tEnd`, replace the read of `CaptureBus.events.value` with:
  ```kotlin
  val snap = CaptureBus.snapshot()            // one instant, immutable
  // AuditSession(..., netEvents = snap.events, captureHealth = snap.health)
  ```
  The live audits saved `captureHealth: null` because this is missing. Old audits still open (`null` = not recorded).
- **N4 (evidence UI on Results and Live):**
  - Confidence badge: `ConfidenceGrader.gradeAudit(sessions).summary` beside the score; show `.reasons` on tap. Hold the final thresholds until Agrim decides them (on the phone, the fixed demo graded Low at 1 lookup per plan).
  - Rights notice: on a C2 finding, `RightsNoticeBuilder(catalog, DpdpCorpus.loadDefault()).build(result, sessions)?.text`. A null result hides the button. Copy and share only; never send automatically.
  - Alias chip: `LiveAuditScreen.kt:87` and `XrayViewModel.kt:141` currently call `catalog.classify(e.hostname)`. Use `catalog.classify(e.hostname, e.cnameChain)` and keep `disguisedVia`; show "alias observed: a → b".
- **N6 (mount Yashvi's pieces):**
  - Voice: add `<uses-permission android:name="android.permission.RECORD_AUDIO"/>` and a route, for example `composable("voice/{auditId}") { VoiceCopilotScreen(it.arguments?.getString("auditId")!!, onBack = { nav.popBackStack() }) }`, with an entry point from Results.
  - Network Proof: in `runPlan`, take `val start = UidTx.now()` right after `CaptureBus.reset()` and `val end = UidTx.now()` at `tEnd`. Build `SessionCounters.from(snap.health, start, end)` per plan, then `NetworkProofCalculator.compute(counters)` and show `NetworkProofCard(proof)` on Results. `UidTx` is not part of `AuditSession`: keep it in your own audit data (for example a `netproof.json` beside `sessions.json`), or ask Agrim for a model field. Do not edit `Model.kt` yourself.
  - Map: after Yashvi's Y3 PR merges, `EndpointMapCard(bundle.sessions, graph.catalog())` on Results, then ask Yashvi to remove her AI Lab stopgap copy (her `ToolScreens.kt`).
- **Camera Check removal:** delete the Home "Camera check" button (`HomeScreen.kt`) and the `camera` route (`MainActivity.kt`), then tell Yashvi so she removes `CameraCheckScreen` from her `ToolScreens.kt` (Y5). AI Lab and Settings must keep working; no commit should leave a dangling route.
- **`ATTRIBUTIONS.md` (N2):** create it and record (a) Agrim's A5 sources: short quotes from SEC 10-K filings (Alphabet, Meta, Microsoft, Braze, Unity, AppLovin, Amplitude) and company terms pages (Google, AppsFlyer), all read 2026-10-10 and listed in `trackers.json`, with no dataset imported; and (b) Yashvi's GeoLite2 and world-map decision, whose wording she sends you.

### P2: remaining milestones

- **N7 before/after card:** LeakyShop and LeakyShop Fixed have **different package IDs**, so "previous audit of the same package" cannot compare them. Show an explicitly labelled controlled demo comparison (21 → 100, "+79 points") or compare two versions of one package; never silently compare unrelated apps.
- **N8 final integration:** golden-result regression (21 / 100 observed, not hard-coded), demo script, backup video, release APKs, commit-time check, tags `er1-pre`, `er2-pre`, `submission`.

## Contract changes you must know (all additive; old audits decode with nulls)

| Field | Meaning |
| --- | --- |
| `NetEvent.answerStatus`, `NetEvent.rcode` | Reply outcome per lookup: `COMPLETE`, `PARTIAL`, `MISMATCHED`, `MALFORMED`, `NO_REPLY`; `null` = not recorded. rcode 3 = NXDOMAIN. |
| `NetEvent.cnameChain`, `answerIps`, `disguisedVia` | Verified CNAME chain, addresses, and the alias that decided a tracker classification. |
| `AuditSession.captureHealth` | Capture counters; `null` = not recorded, never "healthy". Includes `hiddenDnsPackets` (DNS over TLS / TCP that TraceVeil cannot read, usually Private DNS) and `nonDnsPackets` (IPv6 control traffic). |
| `TrackerEntry.location`, `incorporation`, `note`, `hqCountry`, `hqSource` | Cited company facts (A5). `hqCountry` is non-null only when a source says "headquarters". Never label it "registered in". |

## Ownership boundaries

You own `MainActivity.kt`, `XrayApp.kt`, `XrayViewModel.kt`, `HomeScreen.kt`, `ResultScreens.kt`, `LiveAuditScreen.kt`, `Components.kt`, theme, manifest, Gradle files, `audit/**`, `data/**`, `report/**`, `consent/**`, `control/**`, fixtures, `.github/**` and docs. Agrim owns `Model.kt`, `CaptureHealth`, `capture/**`, DNS, trackers, rules, score and notice. Yashvi owns `voice/**`, `netproof/**`, `ui/screens/map/**`, `ToolScreens.kt`, `ai/**`, `core/.../geo`, `copilot` and `leakyshop/**`. Ask the owner, with an exact signature, for any change in their files.

## Phone rules

Use `adb -s YOUR_SERIAL` for every command. Grant VPN, Accessibility and Shizuku by hand on your own phone (start Shizuku with the command its app shows under *View command*); keep HackTracker and Google assistant unchanged. Only audit or reset `com.leakyshop.demo` and `com.leakyshop.demo.fixed`. No root, no automatic Android Allow/Deny taps, no uninstall to bypass a signing mismatch. Private DNS on Automatic adds hidden-DNS packets; set it Off for clean DNS tests and record the setting.

## Verification and handoff

Each slice: failing tests first where testable, then the change, then the full core and app suites plus the three APK builds, and phone evidence for anything on screen or in the audit flow. Commit genuine work as you; push only your own branches; open PRs to `main` for Agrim to review and merge. Report: branch and commit, which N items and bugs are done, test output, what was actually checked on the phone (with audit IDs), and anything still blocked. Never claim data was sent, a law was broken, or a score is a legal certificate.
