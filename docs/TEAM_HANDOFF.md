# TraceVeil teammate handoff

Updated 10 October 2026. Start from the integrated repository, not the original six-commit import branch. This checkpoint adds the current plans, device evidence and teammate prompts; it does not add Safety Coach, voice recognition, a wake word or fraud detection.

## Start order

1. Agrim reviews and merges `nitya/integration` into `main` using a merge commit. Do not squash or rewrite published history.
2. Both teammates fetch and pull that `main` in their own clean clones.
3. Agrim creates `agrim/evidence-engine` and implements **A0 first**. Push and review its additive contracts and compatibility tests, then merge them into `main` before dependent implementations use the new fields.
4. Yashvi creates `yashvi/phone-experience` and starts **Y1 Voice Copilot** immediately. It does not depend on A0. Map and Network Proof integration wait for their specified contracts; isolated arithmetic and aggregation tests can use clearly labelled fakes.
5. Nitya resumes integration after the teammate contracts land. Shared routes, manifest changes, fixtures, report persistence and phone acceptance tests remain Nitya's work.

Use [Agrim's prompt](prompts/AGRIM_AGENT_PROMPT.md) and [Yashvi's prompt](prompts/YASHVI_AGENT_PROMPT.md). Copy the whole relevant file into that person's coding agent.

## Which plan controls the build

The active feature and ownership plan is [Implementation milestones](planning/IMPLEMENTATION_MILESTONES.md). [Feature gap and team plan](planning/FEATURE_GAP_AND_TEAM_PLAN.md) supplies background, not conflicting task orders. [APK misuse cases](planning/TRACEVEIL_ANDROID_APK_MISUSE_CASES.md) are product research, not implemented coverage or a verified malware feed. Follow the venue's current rules; [the local scoring register](planning/hackathon_scoring.md) distinguishes captured rules from provisional team policy.

The team reference ZIP contains 11 Markdown documents: README, Combined Feature Roadmap, DPDP XRay Build Plan, Exodus Integration Milestones, Exodus vs XRay Analysis, PCAPdroid vs XRay Analysis, TrackerControl Comparison, TrackerControl Review Instructions, Three Person Execution Plan, Hackathon Winning Analysis, and the scoring register. All were read for this handoff. The ZIP stays with the team's supplied references; it is not an application dependency and is not executed or copied as application code.

The older six-module greenfield plans do not describe this repository. Keep the actual `core/`, `app/`, and `leakyshop/` layout. Do not recreate existing features or copy competitor implementations, assets or datasets.

The earlier [Safety Coach design](superpowers/specs/2026-10-10-safety-coach-design.md) is deferred. Its unfinished test drafts are local and outside the repository. They are **not** part of the build, and no Safety Coach product code exists. Do not implement that design, custom wake-word support or permission interception in parallel with the active milestones without a new ownership decision.

## Already built and observed

The application already has per-app DNS capture, the consent watcher, three audit plans, Shizuku control, tracker classification, deterministic screen checks, C1–C10/NC/V0, scores, fixes, citations, saved audits, sample data, Markdown/PDF export, AI Lab, Settings and a basic Camera Check. Inspect each existing path before adding code.

Fresh handoff verification: **65 core tests, 0 failures, 0 errors, 0 skipped**, plus all three debug APKs assembled with `--rerun-tasks` (121 tasks executed). See [verification](VERIFICATION.md) for commands and limits.

Real, non-sample full audits on Nitya's iQOO 15 produced:

| Controlled target | Audit | Score | Findings |
| --- | --- | --- | --- |
| `com.leakyshop.demo` | `audit-1791570081266` | 21 / At risk | C2, C1, C3, C4, C10 |
| `com.leakyshop.demo.fixed` | `audit-1791570260617` | 100 / DPDP-ready | None in the captured evidence |

Both ran SILENT, REJECT and ACCEPT. The saved metadata was rechecked on the connected phone during this handoff. This is not a new full audit on either teammate's device, a legal certificate, or proof that arbitrary apps are safe.

PDF/Markdown export, history, cancellation cleanup, GPU model loading and a GPU benchmark were observed. **AI generation has not passed:** English explanations returned no text twice, Hindi once; all six full-audit screen reviews used deterministic fallback. Root cause is not established. Camera analysis, microphone recognition, TTS, CNAME evidence, maps, Network Proof and Office Kit clipboard/file delivery have not passed acceptance. Opening a share chooser or pressing Copy is not proof of delivery to a laptop.

## Ownership and shared changes

| Owner | Owns now | Does not edit without coordination |
| --- | --- | --- |
| Agrim | A0–A5: DNS answers, trackers, rules, scoring/confidence, notice builder, capture; one additive `Model.kt` contract change | UI routes/screens, audit orchestration, repositories, manifest, Yashvi's voice/AI/geo files |
| Yashvi | Y1–Y4: voice, AI adapters, Network Proof, map/geo/copilot, `ToolScreens.kt`, demo module | `Model.kt`, capture/rules, main routes, shared ViewModel, manifest, Gradle files, reports/repositories |
| Nitya | Integration: `MainActivity.kt`, `XrayApp.kt`, `XrayViewModel.kt`, Home/Results/Live/Components/theme, manifest/build configuration, audit/data/report/control/consent, fixtures, CI/docs | Agrim's evidence engine and Yashvi's new components without their review |

Use new feature files and self-contained components. Give Nitya exact signatures and a short wiring request rather than editing the shared hot files. Include the tests and expected error states with each handoff.

In particular, **Yashvi owns TTS and microphone code now**; Nitya will not build a competing speech stack. Agrim owns model/rule contracts now; Nitya will not change their consent behavior while he implements A0–A5.

## Plan corrections to carry forward

- The plans' “nothing has run on the iQOO” statements are older snapshots; use the qualified results above, not a claim of complete phone validation.
- `N4` lists itself as a prerequisite, and the order mentions an undefined `N9`. Treat these as numbering errors: fixtures depend on A0; real evidence UI depends on A1/A3/A4. Do not invent an N9 feature.
- Camera Check stays (team decision, 10 October): its Home entry, route, screen and helpers are kept. The earlier removal plan and older camera-upgrade proposals are superseded; camera analysis itself has not passed acceptance, so do not present it as verified.
- The demo flavors have **different package IDs**. A “previous audit of the same package” query cannot compare them. Later show an explicitly labelled controlled demo comparison, or use actual before/after versions of one package. Never silently compare unrelated apps.
- A0's `CaptureHealth` needs field names, counter units and meanings agreed before Yashvi implements Network Proof. The UID transmit counter and relay payload bytes may measure different layers. A residual is an estimate, **not proof of audit-data uploads or zero uploads**. Unsupported/reset/inconsistent counters must yield unavailable/uncertain, not be clamped into a reassuring zero.
- `hqCountry` is not automatically a company's registered jurisdiction. Label headquarters as headquarters unless the cited source actually establishes registration.
- A synthetic alias/IP fixture must remain visibly sample data. A live CNAME demonstration requires a team-controlled name and resolver evidence; do not add someone else's domain record or claim a `.test` fixture was observed live.
- On-device microphone recognition is push-to-talk in Y1, not “Hey TraceVeil” while closed. Keep Google as the default assistant. Tappable questions remain available if speech packs, the microphone or the model are unavailable.

## Important safety limitation of this baseline

**Existing Quick and Full audits may clear the selected app's data whenever Shizuku is authorized—even with the agent switch off.** Consumer-safe observation is not implemented. Run resets and automated consent choices only on the controlled LeakyShop demos or an explicitly authorized developer-owned test package, never a personal banking, messaging or other real-user app. Do not automatically press Android Allow/Deny buttons.

Keep HackTracker and Office Kit enabled. Do not root, unlock, force-stop TraceVeil, change the default assistant, grant permissions by shell commands, or download a real malicious APK for a demonstration.

## Clone and branch commands

On a new machine, choose a fresh directory rather than overwriting an existing checkout:

```powershell
git clone https://github.com/agrimsri/IQOO-Kernel-Panic.git TraceVeil
cd TraceVeil
git switch main
git pull --ff-only origin main
git status --short
```

For an existing clone, first run `git status --short`. If it shows changes, preserve them and stop before switching or pulling; do not reset or discard them. In a clean clone:

```powershell
git fetch origin
git switch main
git pull --ff-only origin main
```

After the handoff merge, run **only your own** branch command:

```powershell
# Agrim only
git switch -c agrim/evidence-engine
# Yashvi only, in her own clone instead
git switch -c yashvi/phone-experience
```

If that feature branch already exists, inspect it and update it safely instead of running `switch -c` again. Published branches receive reviewed merge updates from `origin/main`, not rebases or force pushes. Commit as the actual contributor, using that person's own private GitHub authentication; passwords/tokens are never shared in chat.

## Make it run on each teammate's phone

Same revision means the same code, **not identical device state or guaranteed identical results**. Connecting ADB or Office Kit does not transfer permissions, models, reports, settings or Shizuku authorization.

1. Install Git, JDK 17 and Android SDK platform 37/build-tools 36.0.0/platform-tools on the laptop. Use the repository's pinned Gradle wrapper and dependency versions; create ignored local SDK configuration. Do not copy Nitya's absolute laptop paths into source.
2. Run `gradlew.bat :core:test :app:assembleDebug :leakyshop:assembleLeakyDebug :leakyshop:assembleFixedDebug --console=plain`. On Linux/macOS use `bash ./gradlew` until the executable-bit maintenance change lands. Use `--offline` only with a populated compatible cache.
3. Run `adb devices -l` and identify **your own** iQOO serial. Every install/test command uses `adb -s YOUR_SERIAL ...`; never assume Nitya's serial or whichever phone is first.
4. Install the three generated APKs on that phone. Use `install -r` for compatible updates; if signing differs, stop and coordinate a shared debug signing configuration. Do not uninstall to bypass a signature failure and silently erase data.
5. Open TraceVeil. Grant VPN consent yourself, enable its accessibility watcher yourself while keeping HackTracker enabled, and set Private DNS off for the controlled DNS tests. Existing launch-time notification prompts are handled manually.
6. Install/start Shizuku by the official supported non-root method and authorize TraceVeil on **that phone** for the controlled full audit. A running Shizuku on Nitya's phone does not authorize another phone.
7. Optional model: install the verified GPU file through the application's existing model workflow or to `/sdcard/Android/data/com.dpdpxray.app/files/models/`. Model setup is separate from cloning. Verify its hash; do not assume an SM8750 NPU bundle works on SM8850.
8. Pair and test Office Kit independently: mirror the real app, copy a controlled fix and confirm it pastes on that laptop, then send/open a report there. ADB installs/debugs; Office Kit supplies the phone–laptop experience. Both serve different purposes.
9. Run a controlled Full Audit of LeakyShop and Fixed. Expect the observed baseline contrast 21/100; record actual scores, findings, consent sources and coverage. Timing, network, first-party settings, model output and access state can change results. Investigate differences; never hard-code the expected scores.

GPU model reference for this baseline: `gemma-4-E2B-it-gpu.litertlm`, 2,008,432,640 bytes, SHA-256 `a53a59001894c58e6bdb5b9b227709f91a2e3e556baa7d85acf9c55402ba5cf5`. Nitya's phone loaded it and benchmarked it; explanation generation still failed. Embedding and device-compatible NPU models remain unverified/missing. Model licences require each user's appropriate acceptance.

## Each teammate reports back

Send Nitya and the team: branch/commit, milestone completed, owned files changed, exported signatures, full test commands/results, actual phone evidence, new dependencies and licence decisions, remaining limitations and exact integration requests. Push only your branch through your own authenticated account. Agrim reviews and merges into `main`; everyone pulls the merged revision and reruns their affected tests. Do not say “ready” for an unmerged or failing build.

Keep [provenance](../PROVENANCE.md) and the AI-use disclosure intact. The baseline is a pre-event import. Its reuse exception still needs the organiser's recorded clarification; fresh commit dates do not make imported code newly written during the event.
