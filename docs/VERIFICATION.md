# TraceVeil baseline verification

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
