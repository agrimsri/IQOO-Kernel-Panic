# TraceVeil — detailed handoff for the second terminal

Snapshot: 10 October 2026, before the Round 1 mentor session at 10:00 IST.
This document distinguishes implemented code, verified observations and unfinished work. Read it completely before commands or edits.

## 1. Objective and current priorities

Team Kernel Panic is building TraceVeil for the DPDPP-XRay track. It is a phone-first developer/privacy-review tool: observe an authorised app's DNS lookups around recorded consent choices, explain evidence and limits, offer practical developer fixes and export a report. It is not a universal fraud detector, permission firewall, payload inspector or legal certificate.

The user's latest priorities are:
1. Coordinate this second terminal without disturbing existing code or phone tests.
2. Publish the verified integration checkpoint so Agrim and Yashvi can pull it.
3. Establish whether Office Kit's Jovi can be installed and integrated through a supported interface.
4. Establish the actual status of “Hey TraceVeil” while keeping Google as the default assistant.
5. Reduce audit latency without fabricating results or silently shrinking evidence coverage.
6. Continue the approved milestones and prepare a defensible mentor demo.

User explicitly requests real-app demonstrations as well as controlled demos. Real-app tests preserve data, do not automate consent/permission decisions, and avoid sensitive apps.

## 2. Exact workspace and safety boundaries

Remote: https://github.com/agrimsri/IQOO-Kernel-Panic.git

| Location | Meaning | Allowed action |
| --- | --- | --- |
| D:\Hackathons\IQOO | Shared workspace, not itself the active repository | Read the supplied documents; avoid broad cleanup |
| D:\Hackathons\IQOO\traceveil-verification | Current coordinator's isolated linked worktree | Second terminal reads only; do not edit, switch branches, stage, commit, pull or run Gradle here |
| D:\Hackathons\IQOO\kernel-panic | Original dirty user checkout, setup/traceveil-baseline at 445db1d | Preserve every edit; no reset, clean, checkout, deletion or migration |
| D:\Hackathons\IQOO\traceveil-claude-max | Suggested independent checkout for the second terminal | Create only if the path is absent and after the integration branch is published |
| D:\Hackathons\IQOO\tmp\pdfs | Local verification artifacts from our own live report | Not product source; do not add to Git or delete broadly |

Current integration branch: nitya/evidence-integration.
Latest reviewed/fixed product-code commit: **35daed6cc35e384994d1bf3ce528179518275f15**.
This handoff and later phone-evidence documentation may add commits above it without changing the APK.
The branch was local-only when this handoff started. The publication target is origin/nitya/evidence-integration; verify its current remote HEAD after publication. Main is not being changed by this handoff.

No AGENTS.md was found during the coordinator's inspection. Re-check in your own checkout and follow applicable instructions.
Do not start a second ADB server, restart Office Kit/Explorer, force-stop unrelated apps, change Google assistant, grant permissions by ADB, or inspect credentials.

## 3. Coordination: one owner per file and one controller per phone

Terminal A/current coordinator owns:
- Publication of nitya/evidence-integration and its verification/handoff records.
- Installation and live testing on Nitya's phone.
- Jovi official-documentation feasibility research.
- Integration changes until explicitly handed over.

Terminal B/new Claude starts with **read-only audit-latency analysis**.
Do not immediately “complete everything,” alter timing constants, install SDKs, create cloud accounts, or implement a wake word.
Return a measured/current-code breakdown and a proposed bounded change first.

A safe parallel task after explicit approval is PDF pagination polish in report/Exporters.kt and new report tests in your independent branch. It must not change audit evidence, scoring, voice, manifests or navigation.

AuditOrchestrator.kt is shared-risk: no concurrent writes by the two terminals. Request its file lease before implementation.
Do not run Gradle concurrently against the same worktree. The shared Gradle cache may introduce contention; coordinate full builds.
Do not control ADB while Terminal A is testing. “USB connected” is not a second-controller lease.
Exchange exact branch, commit, changed paths, test results, pending acceptance and merge dependencies at every handoff.

## 4. Team ownership and identity

Nitya: navigation/manifest, XrayViewModel, Home/Results/Live UI, audit orchestration, consent service, data persistence, report/export, app fixtures, tooling and docs.
Agrim: core models/contracts, capture, DNS, tracker catalogue, deterministic rules, score/confidence and rights notices.
Yashvi: voice, Network Proof, map UI, AI, core geo/copilot and controlled demo apps.

The coordinator's urgent voice and geo/copilot review patches are separately identifiable at 52fb834 and 35daed6. They must be reconciled with Yashvi, not overwritten as supposedly stale code.
A5 is the single company-fact authority; do not restore a competing owner registry.

Commits in Nitya's checkout use the genuine identity:
Nitya Prakash Pandey <nityaprakashpandey389@gmail.com>.
Use Nitya's own authorised GitHub session only. Do not request teammates' tokens/passwords, impersonate their authors, manipulate dates, invent commits for quotas, remove provenance or rewrite published history.
Preserve real contributor commits and attribution. Follow required event AI-use disclosure; a fresh repo does not prove event-time originality.

## 5. Read these documents in order

1. This handoff.
2. D:\Hackathons\IQOO\hackathon_scoring.md — source-tagged official/local rules and schedule; some guide facts still need organiser confirmation.
3. docs/planning/IMPLEMENTATION_MILESTONES.md — approved active plan, N1–N8.
4. docs/VERIFICATION.md — latest evidence at the top; lower sections are historical checkpoints, not current status.
5. docs/BUG_FIX_HANDOFF.md and docs/EVIDENCE_INTEGRATION.md.
6. docs/MENTOR_ROUND_1.md — truthful pitch, limitations and demo.
7. ATTRIBUTIONS.md plus docs/ATTRIBUTION.md — distinct attribution records, including map/GeoLite limitations.
8. D:\Hackathons\IQOO\bug_agrim_claude_suggest_fixes.md.
9. D:\Hackathons\IQOO\Nitya_agent_prompt_from_agrim_A5done.md.
10. D:\Hackathons\IQOO\Y3_ENDPOINT_MAP.md.

Agrim's A5 prompt describes an earlier main snapshot. Do not redo its “unmounted/missing” items blindly: they are integrated on our branch. Its suggested UID sampling order was strengthened after independent review; preserve the actual separately bracketed timing contract described below.

## 6. Git state and meaningful checkpoints

Verified upstream inputs:
- origin/main at ebdafae: Agrim A0–A5, including a00ca72 sourced company facts.
- origin/yashvi/phone-experience at 5679a5a: published Y3 and prior voice/Network Proof.
- A4 capture-health changes at 56af781 already incorporated.

Fresh fetch during this handoff: main remains ebdafae, but Yashvi is now at cd7cfae. The later optional online-voice work at 7f1718b and the keep-Camera decision at cd7cfae are NOT merged into this tested checkpoint. Do not claim they are installed here. Coordinate a reviewed merge that preserves our offline pack recovery, missing-choice qualifiers and answer/mic cancellation fixes. Online mode needs explicit consent, accurate privacy wording and international locale/voice selection; do not force an India-only product default. **Update (10 October, after this checkpoint):** the online-voice work (`7f1718b`) and the keep-Camera decision (`cd7cfae`) are now merged into `yashvi/phone-experience`; the merged tree passes the JVM suites (303+ core, 150+ app), but it has **not** been reinstalled or re-checked on a phone since the merge.

Local integration checkpoints:
- bb8b357: saved capture health/UID readings and voice mounting.
- 52fb834: offline speech-pack recovery and quality-first offline TTS selection.
- fb45a0f: alias/notice evidence UI and same-package comparisons.
- 14a0d2a: bounded consent retry, Home readiness refresh, coverage warnings and real-app data protection.
- 83db945: merge main ebdafae.
- 55cac49: merge Yashvi 5679a5a.
- f37f6cd: mounted endpoint card using A5 owner facts and qualified legal context.
- 35daed6: final review fixes for country phases, reply status, corrupt MMDB, actual consent choice and voice races.

N2/N3 branch history remains preserved. A separate merge of old branches is not a prerequisite to repeat their work in this checkout; inspect ancestry and files first.
Do not force-push or merge an old rewritten Agrim branch back into the new history.

After publication, an independent second-terminal setup can use:

~~~powershell
# First verify the destination does not already contain user work.
Test-Path 'D:\Hackathons\IQOO\traceveil-claude-max'
# If false, create a separate clone:
git clone https://github.com/agrimsri/IQOO-Kernel-Panic.git 'D:\Hackathons\IQOO\traceveil-claude-max'
Set-Location 'D:\Hackathons\IQOO\traceveil-claude-max'
git fetch origin
git switch -c nitya/claude-audit-timing origin/nitya/evidence-integration
git merge-base --is-ancestor 35daed6 HEAD
git status --short
~~~

The ancestor check must exit 0. If the remote branch is absent, wait for publication; do not reconstruct work or start from stale main.
An existing destination requires inspection and a new safe path, not deletion/reset.

Agrim owns merging main. Use a reviewed PR; no direct push to main, merge tag, demo-stable update or release claim without the appropriate acceptance.

## 7. What is implemented

### DNS and consent evidence

Per-app VPN capture observes supported DNS queries and resolver answers. It does not decrypt TLS payloads or install a TLS certificate.
Recorded CNAME chains inform tracker classification; UI says “alias observed,” not that every alias is disguise.
Private/encrypted/cached DNS, shared Play-services traffic and other limitations remain explicit.

The armed accessibility service watches the selected target only.
It re-probes while waiting instead of relying solely on UI events.
Asynchronous screenshot completions are guarded by the arm generation.
A dialog disappearing **does not emit Accept or Reject**. It records an unconfirmed closure; the choice remains unknown.
Observed button actions and successful explicit agent actions may establish choices.
Legacy saved inferred events are not retroactively rewritten and must not be presented as verified taps.

Retry is at most once, only for the two developer-owned demo packages, only with approved successful fresh-state reset. Failed attempts and unique measurement IDs are retained.
Real apps default to no reset/manual interaction. Full setup requires approved reset plus Shizuku.
Do not extend automatic retries/data clearing to personal apps.

### Saved capture health and Network Proof

PlanCaptureRecorder saves one immutable CaptureBus snapshot with capture health.
network-readings.json is a versioned nullable sidecar paired by session ID/window.
UID reads bracket reset/snapshot, with actual txSamplingStartedNanos, snapshotRequestedNanos and txSamplingEndedNanos retained.
Sampling is not atomic and is labelled estimated; do not relabel sample timestamps as capture timestamps.

Missing, unsupported, negative, reset, corrupt, overlapping, gapped, duplicate or mismatched counters are unavailable, not zero.
Older audits without the sidecar still open.
Network Proof includes the 28-byte IPv4/UDP allowance per datagram.
The remainder is an estimate. A zero remainder is not proof of no uploads; OEM accounting has not been fully characterised.

### UI/report, notices and comparison

Home refreshes on entry, observes accessibility-setting changes and re-checks readiness on Audit.
Missing Accept/Reject appears beside the score, in reports and in EN/HI voice answers.
Comparison requires the same package, sample/live mode, plans and completed choices.
Different leaky/fixed package IDs are **not** silently compared; the demo contrast is explicit.
C2 exposes a reviewed raw rights-notice draft with Copy/Share, never automatic sending.
Camera Home entry and route were absent in the checkpoint tested on the phone. The team decided on 10 October that Camera Check stays; the merge into `yashvi/phone-experience` restores the Home button, the `camera` route and the import, and the helpers are kept. Do not replay the older removal proposal.
AI Lab and Settings remain.

Android report display/export wraps old Markdown to remove the unmeasured zero-byte claim and add coverage/scope.
Core score arithmetic and confidence thresholds were not changed to make the demos look better.
An INFO core finding for incomplete plans still belongs to Agrim; do not invent a CheckId.

### Offline voice

RECORD_AUDIO is declared with Android runtime permission handling. No permission is auto-granted.
Voice route and Ask this audit/Ask latest audit are mounted.
Actual installed regional packs are selected: the phone reports en-US, not a forced en-IN.
Missing-pack download is explicit user action through the provider, followed by Check again.
No automatic cloud fallback or audio retention.
Quality-first TTS chooses among available offline voices; nice audibility still depends on the installed phone engine/voice.
Answers are deterministic across eight supported intents with at most three retrieved DPDP passages. Unsupported questions are refused.
New question/language/pause/microphone input invalidates obsolete answer completions. Starting recognition cancels an in-flight answer before it can speak into the microphone.

In this tested tree, Sarvam exists only as a disconnected/off-by-default prototype. Yashvi's newer remote commit includes optional online voice, which has not been merged/reviewed/tested here. Do not silently enable it, transmit audio, collect keys or weaken consent/privacy statements.

### Endpoint map and sourced owners

Published EndpointMapCard is mounted on Results.
EndpointMapIntegration adapts A5's catalogue fields into OwnerRegistry; no second owner-fact authority.
Only COMPLETE/PARTIAL replies supply location addresses. Legacy/null/unusable replies remain unknown.
Country grouping retains the exact country/consent-phase association for each usable reply.
Country is inferred from an IP table, not where data was sent or processed.
Company headquarters, stated contract address, incorporation and domain registration are separate facts. Unknown stays unknown.
Corrupt MMDB node/pointer/date metadata fails closed rather than crashing Results.
First-party settings invalidate the map catalogue.

No licensed GeoLite2-Country.mmdb has been provisioned here; filled source-IP map rendering is pending.
A5 sourced owner mode can work without that IP database.
The outline is amCharts/GeoMap CC BY-NC, not blanket commercial-release clearance. Keep credits and licence scope; do not assume competition prizes or future commercial distribution are automatically permitted.
The legal panel labels selected condensed clauses and commencement context. Section 3 is scheduled for 13 May 2027 by the referenced official notification; this is readiness context, not a present jurisdiction/violation verdict.

## 8. Fresh verification and APK identity

Fresh publication rerun of the unchanged product tree:
BUILD SUCCESSFUL in 52s; all 128 actionable tasks executed.
303 core + 152 app JVM = **455 tests**, zero failures/errors/skips.
All three debug APKs built.

Final TraceVeil APK:
D:\Hackathons\IQOO\traceveil-verification\app\build\outputs\apk\debug\app-debug.apk

SHA-256:
247c7ac79c899e977f9e57125732218a481105a83744daf6fe77572d4ffc2e6b

Installed with adb install -r successfully. Both demo APKs also updated without clearing data during installation.
Local review found six Important issues and two Minor; all were fixed in one pass.
Targeted regressions first failed: 55 core tests/8 failures and 10 app tests/3 failures, then 65 targeted passed.
There was no second review of the same changes.
JVM tests are not a claim of complete OEM/Compose/audio/phone acceptance.

Repository checker: publication rerun passed 15 tests in 18.796s (earlier run 15.801s).
Read-only author/committer cutoff check passed 68 reachable commits at 35daed6.
Metadata checks do not prove originality or competition eligibility.

## 9. Actual phone observations

Nitya phone: **10BFBL24YS001GM**, vivo I2501/iQOO 15, Android 16.
Always use adb -s 10BFBL24YS001GM.
Do not touch Yashvi 10BFAU14E0000XR or Agrim 10BFAU155Z000XR.

Google remains default assistant. Private DNS is Off. TraceVeil watcher and HackTracker remain enabled.
Home shows VPN allowed/watcher On immediately after updates. Shizuku is ready.
No rooted/bootloader modification or automatic Android Allow/Deny.

| Audit | Build | Actual saved result |
| --- | --- | --- |
| audit-1791590915883, com.leakyshop.demo | First 455-test build 53c9068c…, before Home-copy-only correction | Full live 21/100, C1/C2/C3/C4/C10, 5/8/7 lookups for Silent/Reject/Accept. Reject all and Accept & Continue recorded; no inferred choice. |
| audit-1791591219563, com.leakyshop.demo.fixed | Final 247c7ac7… | Full live 100/100, no findings, one lookup each plan. Actual Reject and Accept. Low capture confidence: not a safety certificate. |
| audit-1791591583098, com.block.juggle / Block Blast! | Final 247c7ac7… | Quick live 66/100, three findings, 30 DNS lookups. Data retained; auto-tapping/Play-services/child flags off. No consent screen/choice captured. Results visibly warns planned Accept incomplete. |

Block Blast's existing/historical consent state is unknown. Its tracker catalogue match is not proof of fraud, personal-data upload or unlawful processing. Explain findings in the context of the retained state and captured window; do not claim first-install consent was tested.

Saved sidecar IDs/windows and health were inspected on the leaky live audit.
Network Proof rendered 1,379 bytes/20 packets with caveats; fixed rendered 201 bytes/3 packets. Zero residual is not zero-upload proof.

Voice:
- Earlier map build entered Listening with installed en-US; no human phrase was captured.
- Final build detects en-US and enables Tap to speak.
- Final live fixed-audit score chip said 100/100 with zero findings; tracker chip gave a different grounded answer that no findings name a known tracker.
- TTS playback request/Stop speaking was visible, but actual sound quality and recognised human speech are still unconfirmed.
- Do not confuse a chip-generated transcript with recognised speech.
- Scrolling/ending TTS shifts chip coordinates. Locate current UI nodes, not stale coordinates.

Export:
- PDF and Markdown saved for the live leaky audit in Downloads/TraceVeil.
- Older files retained; new PDF named TraceVeil-LeakyShop (1).pdf.
- All five pages rendered/inspected using available PyMuPDF; scope/evidence readable and old zero-byte claim absent.
- Minor pagination polish remains: headings/metadata can be separated from body across page breaks. Do not claim a polished final PDF.

## 10. Remaining acceptance and blockers

- Agrim's requested ten consecutive Full leaky runs are **not completed**. One succeeded. The batch was about to start, then the user prioritised this handoff; no ten-run job is running.
- Actual recognised human phrases, Hindi packs, audible TTS quality, denial/download flows, pause/cancel cleanup.
- Filled 0/1/20-country display with an appropriately licensed optional database; no guessed countries.
- OEM TrafficStats/tunnel accounting; do not claim measured absence of uploads.
- Minor PDF keep-heading-with-body polish.
- Teammate kit BUG4: setup.ps1, activate.ps1, verified JDK archive and extraction folder were not supplied/found. Current DevTools JDK builds; that does not verify Agrim's kit. Need original kit/path; no made-up extraction patch.
- Baseline reuse/originality eligibility requires organiser confirmation. New Git dates do not resolve it.
- Hosted CI, final release APKs, backup video, evaluation/submission tags and full main acceptance are not yet completed.
- No release/demo tag should be created merely because local tests pass.

## 11. Audit speed: facts, not fabricated targets

Current Timing in AuditOrchestrator.kt:
normal: waitConsent=20s, dwell=2.5s, observe=15s, silent=25s.
existing Fast audits setting: waitConsent=12s, dwell=1.5s, observe=8s, silent=12s.
These are configured observation limits, not predetermined findings/scores.
The screen wait returns early when a dialog is actually observed.
Manual choice may wait up to45s. GPU model loading and screen review can add startup/final latency.

Our measured normal Full examples took about67–70s; “about2minutes” is a conservative static UI estimate, not measured current progress. The no-dialog real Quick run took about35s, consistent with20s wait+15s observation.
A shorter observation window can miss delayed trackers. Do not promise exhaustive detection or stop after the first tracker to manufacture a quick result.
Do not skip Reject and call a scan a completed Full consent audit.

Terminal B's first deliverable:
1. Read actual timing and model/capture/control paths.
2. Separate cold model startup, launch/detection, manual interaction, observation and report time using actual timestamps.
3. Propose using the existing Fast profile first, with truthful mode/coverage/time labels.
4. Identify safe bottleneck improvements and tests; request approval/file ownership before code changes.
5. Report effects on expected coverage, not only wall-clock speed.

Do not alter confidence/score thresholds, inject expected21/100 or100/100, ignore failed attempts, auto-approve permissions, or fake “AI progress.”

## 12. What “nothing hardcoded” means here

Live inputs must come from actual captured DNS/UI events, session times and measured/nullable counters.
No app-name-to-score shortcuts, fake countries, fixed success messages or sample substitution for a live run.

Some static references are legitimate and necessary:
- Versioned deterministic rules, score weights, consent lexicon and tracker catalogue.
- Sourced legal/company passages, SDK remediation examples and attribution.
- Configured timeouts/limits and supported voice intents.
- Clearly labelled synthetic samples and developer-owned regression/demo fixtures.

Those are not evidence of what a real app did. Keep that distinction visible. Do not delete deterministic safeguards simply because the user dislikes fabricated hardcoding.

## 13. Jovi and “Hey TraceVeil”: status boundary

Jovi:
User sees it under Office Kit Tools and supplied:
https://pc.vivoglobal.com/#/userProtocolVclawEx?version=3&dark=false&language=en_US
https://pc.vivoglobal.com/#/privacyPolicyVclawEx?version=3&dark=false&language=en_US

Both served English documents were read completely on 10 October: agreement updated 20 August 2026; privacy terms updated 1 September 2026. The JS-only pages were read through their publicly referenced English content modules after browser setup failed, without logging in, executing service operations or accepting terms. See docs/JOVI_STATUS.md for source links and the decision.

Office Kit officially lists Jovi as a PC automation assistant and includes India among supported regions, but not every country. Installation/use on this exact setup remains unverified. It is optional, not a dependency of our globally usable core.

Agreement sections 2.1/2.4 mention third-party tools, APIs and Skills; that is not a documented Android speech SDK. Sections 3.4 and 6.1 restrict commercial use/redistribution; AI Appendix 3 restricts scripted output extraction, professional advice to third parties and hiding AI markings. Obtain specific supported-interface/licence approval before distributing a Jovi integration.

Privacy terms cover prompts/voice/files, outputs and history, possible consented uploads, AI-provider sharing and cross-border processing. Bring-your-own-key mode sends content directly to that chosen provider, not offline. Never supply confidential APKs, credentials, sensitive logs or live recordings by default.

No Jovi installation, legal acceptance, integration or extra scoring is verified. A supervised personal trial via Office Kit is a possible next step after the user reviews/accepts the terms; do not launch an installer or accept agreements on their behalf.

Hey TraceVeil:
**Not implemented.** Current voice is in-app push-to-talk; Google remains default.
Launching via the existing assistant is distinct from an independent background wake word.
A keep-Google wake-word companion is a new subsystem needing supported microphone/foreground-service lifecycle, user arming and notification, battery/privacy/offline-engine/licence decisions and actual Android16/OEM testing.
Do not set TraceVeil as default assistant, loop SpeechRecognizer forever, start covert background recording or claim locked-screen/always-on hotword access.
No Jovi/Sarvam/wake-word implementation should enter the mentor-stable checkpoint without an approved design and acceptance.

## 14. Reproduce build and checks in your own checkout

Prerequisites already available on this laptop:
- D:\DevTools\jdk-17.0.20.1+1
- D:\DevTools\android-sdk
- D:\DevTools\android-sdk\platform-tools\adb.exe
- D:\DevTools\gradle-home (dependencies cached)
- D:\DevTools\android-user-home
- C:\Program Files\Git\bin\bash.exe
- Python with pypdf/PyMuPDF/reportlab. Poppler/pdfplumber absent in the checked environment.

Do not redownload everything, accept account licences or reset global environments unnecessarily.

~~~powershell
# Run in your own clean clone/worktree only.
$env:JAVA_HOME='D:\DevTools\jdk-17.0.20.1+1'
$env:ANDROID_HOME='D:\DevTools\android-sdk'
$env:ANDROID_USER_HOME='D:\DevTools\android-user-home'
$env:GRADLE_USER_HOME='D:\DevTools\gradle-home'
.\gradlew.bat --offline :core:test :app:testDebugUnitTest :app:assembleDebug :leakyshop:assembleLeakyDebug :leakyshop:assembleFixedDebug --rerun-tasks --console=plain

$env:TRACEVEIL_BASH='C:\Program Files\Git\bin\bash.exe'
python -m unittest discover -s tools/tests -v
& 'C:\Program Files\Git\bin\bash.exe' tools/check-commit-times.sh HEAD
git diff --check
~~~

The Gradle wrapper is in the repo. local.properties is machine-local/ignored; do not commit it or absolute paths/secrets into product configuration.
Use test-first fixes and inspect XML results. Report actual counts; future counts may legitimately differ from455.
Phone APK installation/control remains Terminal A's lease until handed over.

## 15. Definition of a safe handoff back

Return:
- Your independent workspace, branch and base hash.
- A short list of changed paths or “read-only, no files changed.”
- Measured diagnosis/design and why it preserves real evidence.
- Tests run with output, APK hash if built and exact device checks if authorised.
- Deferred issues/risks, interface changes and ownership requests.
- Genuine commits and publication status; no direct merge to main.

Current checkpoint is suitable for a **reviewable branch**, with remaining phone/licence/eligibility checks disclosed. It is not “everything complete” or an automatic merge instruction. See claude_max_prompt.md for the ready-to-paste second-terminal assignment.
