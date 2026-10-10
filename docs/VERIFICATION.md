# TraceVeil baseline verification

## Latest local integration checkpoint — 10 October 2026

The sections below retain their historical test results. Current local work additionally includes voice-pack recovery, alias/notice UI, same-package comparison, Agrim-report reliability fixes and the published Y3/A5 components. `origin/main` at `ebdafae` and `origin/yashvi/phone-experience` at `5679a5a` merged cleanly into `nitya/evidence-integration` as `83db945` and `55cac49`. Bug fixes are saved at `14a0d2a`; the subsequent map mount is separately identifiable.

After the independent review fixes, a fresh full offline rerun passed in 37s. The publication rerun passed again: **BUILD SUCCESSFUL in 52s**, 128 tasks executed; **303 core + 152 app JVM = 455 tests**, zero failures/errors/skips. All three debug APKs rebuilt. The APK hash remains identical to the phone-tested artifact:

```text
247c7ac79c899e977f9e57125732218a481105a83744daf6fe77572d4ffc2e6b
```

These tests cover data/logic contracts, not Compose rendering or OEM behavior. The previous pre-map build passed 326 tests and the pre-review map build passed 444. Existing 21/100 demo score regressions remain unchanged. The final read-only review identified six Important issues and two Minor issues; its single fix pass addressed them. Regression tests first recorded eight core and three app failures, then all 65 targeted tests passed. Fixes preserve each reply's country/phase association, reject non-COMPLETE/PARTIAL location evidence, fail closed on corrupt MMDB pointers/dates, never infer the requested choice from dialog disappearance, qualify missing Accept as well as Reject in voice, and cancel/invalidate pending answers before listening. First-party map settings now refresh, and the stale blanket redistribution comment is corrected. No second review or full legal/licence clearance is implied.

**Device status:** Nitya's iQOO 15 (`10BFBL24YS001GM`, I2501) is authorised in ADB. All three APKs installed successfully with `install -r`; data was retained. A second fresh full rerun after the Home wording correction also passed 455 tests in 37s, and the final APK above was installed successfully. Home immediately showed VPN allowed and watcher On, Private DNS off and Shizuku ready. Reset wording now makes availability conditional on approval, not automatic for every app. The previous 444-test APK showed installed `en-US` and entered Listening without the missing-pack error; no human speech was captured in that attempt. A score-chip answer was clearly labelled sample and requested playback, but actual audibility/quality was not confirmed. No microphone grant, cloud fallback, personal-app reset or default-assistant change was performed by the agent.

### Fresh phone evidence

On the first 455-test APK (`53c9068c…`), Full LeakyShop completed as live audit `audit-1791590915883`: **21/100**, C1/C2/C3/C4/C10, 5/8/7 lookups for Silent/Reject/Accept. Saved Reject has `SCREEN_APPEARED, SETTINGS, REJECT` with label `Reject all`; Accept has `SCREEN_APPEARED, ACCEPT` with label `Accept & Continue`. No inferred or missing choice was substituted. Each saved capture health record and Network Proof reading matches its unique session/window. Results rendered Medium capture confidence and the actual 1,379-byte/20-packet UID estimate with its OEM/zero-upload caveat. This is one successful full leaky run, not the ten-run reliability criterion.

PDF and Markdown exported to `Downloads/TraceVeil`, without sending/sharing externally. The live PDF has five pages; all five were rendered and inspected using the available PyMuPDF fallback (Poppler is unavailable). Evidence, scope warning and 21/100 result are readable; the old zero-byte assertion is absent. Minor layout polish remains: a few headings/metadata sit at a page bottom while their body starts on the next page. Export function passes; polished pagination is not claimed. Existing older Downloads files were retained, with MediaStore naming the new PDF `TraceVeil-LeakyShop (1).pdf`.

On final APK `247c7ac7…`, Full fixed demo completed as `audit-1791591219563`: **100/100**, zero findings, one lookup per plan and actual Reject/Accept actions. Results correctly showed Low capture confidence; this is not a safety certificate. Network Proof displayed 201 estimated bytes/3 packets with its accounting caveat.

The authorised real-app Quick check on Block Blast! (`com.block.juggle`) completed as `audit-1791591583098`: **66/100**, three findings, 30 DNS lookups and a known catalogue match. Data was retained; reset, automated consent, child-facing and Play-services flags were off. No consent screen or choice was captured. Results explicitly said the planned Accept was not completed. Historical consent is unknown, so this does not establish first-install behaviour, fraud, personal-data upload or unlawful processing. The measured run was about 35 seconds; the two Full demo examples took about 67–70 seconds.

On the final APK, the fixed audit's score chip answered 100/100 with zero findings; its tracker chip answered that no finding named a known tracker. These were distinct question-specific answers on live saved evidence, not a recognised-human-speech test. The TTS control showed playback requested; audible quality remains unverified.

Repository checker tests passed again 15/15 in 18.796s (earlier 15.801s); read-only cutoff metadata passed all 68 reachable commits at `35daed6`. This does not establish originality or organiser eligibility.

**Honest remaining work:** repeated consent detection on the device (Agrim requests ten full leaky runs; no batch has started), microphone denial/download/recheck and human speech/TTS checks, pause/cancel cleanup, 0/1/20-country rendering, actual IP-country data licensing/provisioning if wanted, and OEM network accounting. Home readiness refresh and live PDF/Markdown export have been observed, with the limitations above. Agrim's JDK-kit scripts are missing, so its extraction integrity patch cannot yet be applied. Camera entry/route are absent in this checkpoint; upstream Yashvi now says to keep Camera Check, requiring an explicit integration decision rather than blindly deleting helpers.

A fresh fetch found `origin/main` still at `ebdafae`, but Yashvi advanced from our merged `5679a5a` to `cd7cfae`, including optional online voice at `7f1718b` and a changed Camera Check decision. These later changes are **not merged or verified in this checkpoint**. Coordinate them through review; do not imply the tested offline build includes them. Read the root `handoff.md` for the second-terminal file/phone leases and latency task.

Read [bug-fix handoff](BUG_FIX_HANDOFF.md) and the [Round 1 brief](MENTOR_ROUND_1.md) before demoing. Publication does not certify these unperformed device checks.

## N5/N6 local integration checks on 10 October 2026

Branch `nitya/evidence-integration` combines published `main` at `07a7eb9` and Agrim's A4 branch, initially through `b547a1d` (`0a7c31d`) and then `e2b5946` (`56af781`). The latter brings measurement-before-state publication, unparsed-packet classification and immediate-after-Reject notice wording fixes. Nitya's changes wire Y1 Voice Copilot, Y2 Network Proof and A4 saved capture health/confidence. Shared core, capture, voice, AI, Network Proof and demo implementation files have not been manually changed by this integration.

The manifest already contained `INTERNET`. It now declares `RECORD_AUDIO` and speech/TTS service visibility. Y1's existing runtime permission launcher is reused; no permission is granted automatically. Results opens `voice/{id}` through **Ask this audit**, and Home provides **Ask latest audit**. Google remains the default assistant; this is not a background wake-word implementation. Online voice and its consent flow are not enabled by this change: the approved Y1 milestone specifies no cloud fallback.

Each plan now records nullable UID transmission readings and an atomic capture snapshot. Snapshot events and health are saved together; readings are persisted in a versioned `network-readings.json` sidecar, keyed by session ID and capture window. Actual UID sampling start/end and the snapshot request time are separately recorded: UID sampling brackets reset/snapshot, but is not atomic with them. Results uses these saved readings, not the phone's counters at viewing time. Missing, unsupported, reset, corrupt, future-schema, duplicate, overlapping or mismatched readings yield **unavailable**. Stopped/gapped or uncertain capture windows are also unavailable. Unsupported packet totals stay unknown even when byte totals are valid. Older audits still load without a sidecar. Negative residuals are not clamped to zero.

Network Proof subtracts Y2's estimated DNS wire bytes, including its 28-byte IPv4/UDP header allowance per datagram. The residual is an estimate, not proof of no uploads: how this phone accounts for tunnel writes and other UID traffic has not been measured. Confidence describes capture coverage, not app safety or legal compliance; synthetic samples explicitly have no measured confidence.

Fresh local verification:

```text
gradlew.bat --offline :core:test :app:testDebugUnitTest :app:assembleDebug :leakyshop:assembleLeakyDebug :leakyshop:assembleFixedDebug --rerun-tasks --console=plain

BUILD SUCCESSFUL in 48s
128 actionable tasks: 128 executed
Core: 21 suites, 187 tests, 0 failures, 0 errors, 0 skipped
App JVM: 12 suites, 83 tests, 0 failures, 0 errors, 0 skipped
```

The 15 new tests first ran with six runtime assertion failures for the missing persistence, byte calculation, route and microphone-permission behavior. The complete suites passed after implementation. Tests exercise real JSON round trips, older/corrupt/future sidecars, ID/window pairing, per-plan arithmetic, unsupported/reset counters, unclamped residuals, exact route arguments and the manifest's effect on microphone availability. Existing model, offline voice, DNS, scoring and sample regression suites also passed. These JVM tests do not verify actual speech recognition, audible TTS or OEM TrafficStats accounting.

An independent read-only integration review identified a stopped-window timing defect and unsupported packet normalization. Nine additional regression tests failed at runtime first, including real `CaptureSessions` stop/restart and boundary-send scenarios. The interval fix passed eight; the packet fix resolved the remaining failure. The first full fix-pass build passed **259 tests** in 38s. After incorporating Agrim's three subsequent fixes, the fresh full build above passed **270 tests**. Repository checker tests previously passed **15 tests** in 19.666s, and the read-only author/committer cutoff check passed all 45 reachable commits at `b547a1d`. Metadata checks do not establish originality or event eligibility.

Rebuilt integration APK SHA-256 (not installed):

```text
c96cce469aae3876ec02fa9f97555f51ab4c858db633cb6edad87a98a4f502b8
```

**Status:** local implementation and the review fix pass are verified; complete iQOO acceptance and branch publication are pending. The latest 48s-build APK has not been installed. The preceding 38s-build APK (`db97aae40db018bb9c4c3f1058950a8972944ba3e962e5e4afddc58c9863d518`) was installed with `adb install -r`: data was preserved, Home rendered, and **Ask latest audit** opened Voice Copilot on the saved alias/IP sample. The English screen showed **Allow the microphone** and question chips. The Android permission-dialog attempt could not be verified because subsequent ADB shell/UI commands hung; those checks were interrupted. No permission was granted by the agent, and no speech-input/output or fresh live-capture success is claimed.

The last successful read-only settings check confirmed Google as the selected voice-interaction service, Private DNS off and both accessibility services enabled. No cloud voice request, forced setting change or personal-app data reset occurred. The Windows taskbar interruption was separately diagnosed as absent Explorer; starting Explorer restored the visible shell/taskbar and the user confirmed the desktop was usable again. That recovery does not establish the cause of the ADB hang; reconnecting the unlocked phone is pending.

### Y3 handoff boundary

The user supplied `Y3_ENDPOINT_MAP.md`, which describes a local implementation on Yashvi's branch. A fresh GitHub fetch still showed `origin/yashvi/phone-experience` at `39c915a`, without Y3 map files. No placeholder map import or guessed API was added. Yashvi needs to publish source/tests/docs before Nitya mounts the card. The current citation corpus has no Section 3 passage; a legal-context panel must provide an independently verified official source rather than invent a corpus citation.

Before publishing the integration, test Results and Home voice navigation/back, an older audit's unavailable Network Proof, question-chip answers, microphone denial/approval and offline recognition/TTS availability. Then run a controlled demo audit and inspect the saved snapshot/sidecar and displayed measurements. Never clear a personal app's data for this test. See [integration handoff](EVIDENCE_INTEGRATION.md).

This is not completion of all N4/N6 work: rights notices, alias UI and the map card have separate acceptance. Camera Check is unchanged; its removal is a separate approved Nitya/Yashvi dependency. The older report body's zero-byte assertion remains unmeasured and must not be presented as evidence.

## N3 sample fixtures on 10 October 2026

Branch `nitya/sample-fixtures` includes the N2 checkpoint and the already-merged A0 model contract through merge `3d8bb34`. Product changes are limited to Nitya-owned sample data, the sample picker, the shared ViewModel's sample entry point and an app-owned report-warning prefix. Core rules, capture, voice and both demo flavours are unchanged from the A0 `main` checkpoint `67a41bc`.

Fresh local verification:

```text
gradlew.bat --offline :core:test :app:testDebugUnitTest :app:assembleDebug :leakyshop:assembleLeakyDebug :leakyshop:assembleFixedDebug --rerun-tasks --console=plain

BUILD SUCCESSFUL in 54s
128 actionable tasks: 128 executed
Core: 8 suites, 71 tests, 0 failures, 0 errors, 0 skipped
App JVM: 1 suite, 7 tests, 0 failures, 0 errors, 0 skipped
```

Before committing, the full core/app JVM suites were rerun again with `--rerun-tasks`: **BUILD SUCCESSFUL in 36s**, all **32 tasks executed**. The repository checker suite also passed **15 tests** with Git Bash explicitly selected; the initial Windows default selected the WSL launcher and failed because `/bin/bash` was unavailable, without exercising the checker. Windows command:

```powershell
$env:TRACEVEIL_BASH='C:\Program Files\Git\bin\bash.exe'
python -m unittest discover -s tools/tests -v
```

The fixture tests failed on missing aliases, endpoint addresses and variant identifiers before implementation. A separate report assertion exposed that the core report builder does not render session notes; the app-owned prefix then made that assertion pass. An independent read-only review found no changes required within the fixture scope. It did not perform Android runtime tests; the device checks below were performed separately.

### iQOO 15 sample smoke test

Device `10BFBL24YS001GM` reported model I2501 and an authorized ADB connection. The user confirmed Office Kit access before resuming laptop-side work during the scheduled Red Light window. TraceVeil was updated with `adb install -r`; no app data was cleared, permission granted, setting changed or live audit started.

Installed debug APK SHA-256:

```text
e0228f4a013fee04135ee382f53e90a1564d15c789d9a2c59d14abbe366c709b
```

At approximately 03:14-03:20 IST:

- The sample picker rendered both options and the warning that example IPs do not identify real server locations.
- Alias sample `sample-1791582274163` opened with **Sample data** and score 8. Its saved JSON retained all three plans, 18 events, four aliased events and unknown capture health.
- Endpoint sample `sample-1791582328055` opened with **Sample data** and score 8. Its saved JSON retained the CNAME chain, documentation IPv4/IPv6 addresses, original consent timing and an empty address list for unknown hosts.
- The endpoint report saved as `Downloads/TraceVeil/TraceVeil-LeakyShopaliasIPsample.pdf` and `TraceVeil-sample-1791582328055.md`. Both retained the synthetic-evidence and documentation-IP warnings. The PDF contained six pages; all six were rendered locally with the existing PyMuPDF installation and visually inspected. The first-page warnings were readable, with no new clipping or overlap observed. Existing pagination limitations remain: the REJECT timeline heading ends page 5 while its rows start page 6, and some citation attributions cross page breaks.
- Google remained the assistant, Private DNS remained off, and the TraceVeil and HackTracker accessibility services remained enabled.

Evidence is preserved outside the Git checkout at `D:/Hackathons/IQOO/hackathon-preparation/sample-fixtures/evidence/`. ADB file copying verifies export contents, not Office Kit file delivery or clipboard synchronization.

The attempted share/baseline follow-up encountered the user's Office Kit Remote PC screen instead of TraceVeil. Further phone inputs were stopped; **share-chooser acceptance, sample-picker cancellation and reopening the original sample on the phone remain unverified in this pass**. The original sample is covered by JVM regression tests. No share target was selected by the test.

These checks complete N3's new-fixture decode/render acceptance, not the full application's acceptance. The live 21/100 regression was not repeated. Camera analysis, voice, model-generated answers, alias classification, real DNS answer capture, health counters, maps and Network Proof remain separate integration checks. The report's existing zero-byte sentence is not a measured result and must not be presented as one. See [sample fixture usage](SAMPLE_FIXTURES.md).

## N2 repository checks on 10 October 2026

Branch `nitya/repo-readiness` starts at the integrated `main` merge `1154a9b`. N2 changes repository tooling and documentation only: `app/`, `core/` and `leakyshop/` contents remain identical to that revision. No APK was installed, no audit was rerun, and no phone permission or setting changed during N2.

Fresh local verification in the linked Windows worktree:

- Full `--rerun-tasks` command listed below: **BUILD SUCCESSFUL in 41s**, all **121 tasks executed**, all three debug APKs assembled.
- Core XML results: **7 suites, 65 tests, 0 failures, 0 errors, 0 skipped**. After the review fix, a separate fresh `:core:test --rerun-tasks` run also passed in 17s.
- `python -m unittest discover -s tools/tests -v`: **15 tests passed**, including eight malformed raw-metadata subcases. The eight subcases reproduced a false PASS before the fail-closed fix; the complete suite passed afterward.
- The real reachable history passes the read-only date check. Both author and committer dates are checked, including merged ancestry. This is not evidence that the imported baseline was written during the event.
- Git records `gradlew` and the checker as **100755**. Existing CRLF/binary attributes were preserved; new script/test/workflow patterns use LF. Workflow YAML parses with the intended triggers and read-only permissions.

An independent read-only whole-branch review found one Important malformed-timestamp false-pass defect. Its regression tests failed first, the checker was corrected, and the checker/core suites passed. No Critical or Minor findings were reported. Existing accessibility deprecation and native symbol-stripping build warnings remain unchanged.

Hosted GitHub Actions has **not been verified**. Local YAML parsing and Windows builds do not establish a green Linux-hosted run. N2's organiser reuse clarification and complete release-notice checks also remain pending; see [repository checks](REPOSITORY_CHECKS.md), [attributions](../ATTRIBUTIONS.md) and [provenance](../PROVENANCE.md).

## Current handoff verification on 10 October 2026

This section supersedes the older incomplete-import snapshot below. Remote `main` was checked and fetched at `7b153dea7dd82547a16ed9790260d3570c57af20`. The linked `traceveil-verification` worktree contains that full application plus documentation changes. `git diff origin/main -- app core leakyshop` is empty: the handoff does not change product behavior or include the unfinished Safety Coach tests.

Fresh command in that worktree, using JDK 17.0.20.1, Android SDK platform 37/build-tools 36.0.0 and the existing populated dependency cache:

```text
gradlew.bat --offline :core:test :app:assembleDebug :leakyshop:assembleLeakyDebug :leakyshop:assembleFixedDebug --rerun-tasks --console=plain

BUILD SUCCESSFUL in 42s
121 actionable tasks: 121 executed
Core tests: 7 suites, 65 tests; failures: 0; errors: 0; skipped: 0
```

All three debug APKs were rebuilt. No new product APK was installed during this documentation-only handoff. Existing accessibility deprecation/safe-call and native symbol-stripping warnings remain; they do not fail the build. No Android unit/instrumentation suite exists in this baseline; core success does not establish full device acceptance.

### Phone evidence and its limits

Device: Nitya's iQOO 15, reporting vivo I2501, Android 16, SM8850. The explicit ADB device remained connected. Private DNS was `off`; Google remained the default assistant; TraceVeil and HackTracker accessibility services both remained enabled.

Earlier real full-audit records at the same product revision were re-read directly from TraceVeil's private saved metadata:

| Audit | Target | Sample | Plans | Result |
| --- | --- | --- | --- | --- |
| `audit-1791570081266` | `com.leakyshop.demo` | false | SILENT, REJECT, ACCEPT | 21 / AT_RISK, 5 findings: C2/C1/C3/C4/C10 |
| `audit-1791570260617` | `com.leakyshop.demo.fixed` | false | SILENT, REJECT, ACCEPT | 100 / READY, 0 findings in the captured evidence |

These are persisted earlier runs, not newly repeated audits or results on Agrim's/Yashvi's devices. They observed capture, consent, controlled fresh-state resets, deterministic screen analysis and report flow. Both demo package IDs differ. Scores summarize captured evidence; they are not legal certificates or fraud-prevention guarantees.

Earlier test evidence also records cancellation/capture cleanup, saved-history persistence, Markdown/PDF export, and GPU loading/benchmark. The tested PDF had three readable pages and a heading separated from its body across a page break. The share chooser opened and Copy was exercised, but actual Office Kit clipboard synchronization and laptop file delivery were not confirmed.

**Known AI failure:** the loaded GPU model returned no explanation text twice in English and once in Hindi. All six full-audit screen reviews used deterministic fallback. Root cause remains unestablished; Yashvi owns diagnosis in the active plan. GPU load was approximately 5.5 seconds; an earlier real benchmark reported 40.1 writing tokens/second, 1287 reading tokens/second and 0.22-second first token. These are single-device observations, not fresh benchmark averages or a guarantee on another phone.

Camera Check opened but camera analysis has not passed. Voice recognition/TTS, CNAME capture, confidence health metrics, maps and Network Proof are not implemented or device-verified in this checkpoint. Embedding retrieval used the BM25 fallback; no compatible NPU inference was tested. The Safety Coach design is deferred and its incomplete test drafts remain outside Git.

Existing Quick/Full audits may clear target data whenever Shizuku is authorized, even if automated tapping is disabled. Use controlled demos or explicitly authorized developer-owned targets only. Do not test a personal app until a non-destructive mode exists. Do not force-stop TraceVeil: this OEM previously disabled its accessibility watcher after a force-stop and the user had to re-enable it.

Each teammate must separately build/install, grant permissions, authorize Shizuku, install optional models and confirm Office Kit behavior on their own phone. See [the handoff checklist](TEAM_HANDOFF.md).

## Historical import verification on 9 October 2026

Verified on 9 October 2026 from the clean `kernel-panic` directory.

```text
gradlew.bat --offline :core:test :app:assembleDebug :leakyshop:assembleLeakyDebug :leakyshop:assembleFixedDebug --console=plain

BUILD SUCCESSFUL in 1m 10s
121 actionable tasks: 70 executed, 51 from cache
Core tests: 65; failures: 0; errors: 0; skipped: 0
```

The core tests executed in the destination directory. Kotlin compilation and APK packaging executed for the rebranded application; some unchanged dependency/resource tasks used the local Gradle cache.

Before creating the first six local import commits, the same full command was run again on 9 October 2026: `BUILD SUCCESSFUL in 3s`, with all 121 actionable tasks up-to-date. This second run checked the complete local working tree, not a checkout of the partial committed branch.

Toolchain: JDK 17.0.20.1, Gradle 9.6.0, Android SDK platform 37, build-tools 36.0.0. Local SDK configuration is ignored. Dependency/model installation remains per developer/device.

## Outputs

- `app/build/outputs/apk/debug/app-debug.apk`
- `leakyshop/build/outputs/apk/leaky/debug/leakyshop-leaky-debug.apk`
- `leakyshop/build/outputs/apk/fixed/debug/leakyshop-fixed-debug.apk`

APK files and generated build directories are excluded from source commits.

## Source and repository checks

- All 86 imported files match `docs/baseline-manifest.json`; 15 files differ intentionally from the original, comprising branding/setup text and ignore rules.
- The source MIT licence is byte-for-byte identical.
- No old product labels remain in copied Kotlin or XML files; internal package/class identifiers retain compatibility.
- Local Git HEAD references `setup/traceveil-baseline`.
- `origin` is `https://github.com/agrimsri/IQOO-Kernel-Panic.git`.
- Six import commits exist under Nitya's configured identity; HEAD is `445db1d`. The index is empty after those commits. All six were pushed to `origin/setup/traceveil-baseline`.
- Remote `setup/traceveil-baseline`, `agrim/rules-reports`, and `yashvi/ui-ai` were verified at `445db1d597d2ab374367d5bad06b12841416ba12` after an atomic push. No teammate-authored commits or changes to `main` were made.
- Remaining source files are untracked. The committed branch alone is incomplete; the successful builds above used the complete local working tree.

## Remaining device checks

The team confirms Office Kit installation and pairing on phones and laptops. The earlier ADB probe listed no devices; no phone APK installation, model inference, live audit, or UI smoke test has been performed in this pass.

The build emits existing warnings for an unnecessary safe call, a deprecated accessibility-node property, and native libraries whose debug symbols cannot be stripped. These do not fail debug assembly.

Event eligibility remains separate from build correctness. See `PROVENANCE.md` for the copied baseline and the unresolved reuse-rule conflict.
