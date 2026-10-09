# TraceVeil Safety Coach design

Status: Deferred after the team's 10 October implementation milestones and teammate-first handoff. This is a design reference, not implemented functionality. No Safety Coach product code is included. See [current ownership and handoff](../../TEAM_HANDOFF.md) before resuming it.

## Purpose and agreed constraints

TraceVeil will help users understand important app access requests and observed privacy behavior, while giving developers reproducible findings and suggested fixes. Protection must be useful without interrupting every action. Google remains the default digital assistant, normal touch controls remain available, and publication waits until Nitya tests and approves the build on the iQOO 15.

The implementation will be original TraceVeil code. Similar open-source applications are references for ideas, not sources of copied application code. Existing prototype provenance and required library notices remain intact. New features must not imply that the imported baseline was written during the event.

Success means repeatable checks, clear evidence, preserved app data, quiet alerts, and visible coverage limits. It does not mean a malware-free guarantee, a legal compliance certificate, or interception of every Android permission dialog.

## Delivery boundaries

The first focused increment is Safety Coach and non-destructive consumer observation. It includes installed-app metadata checks, opt-in monitoring of supported install and permission events, advice, optional spoken summaries, and a safe path into the existing audit and report system.

The next increment adds the voice command router, microphone permission flow, and an optional local listener for "Hey TraceVeil" while retaining Google Assistant. Its design and implementation plan require separate review. Custom wake recognition, speaker feedback, concurrent Google audio use, background screen opening, screen-off behavior, and battery use require physical device tests; they are not prerequisites for the first Safety Coach increment.

Further developer work can add deeper APK analysis and comparison between rebuilt versions. This first increment does not edit, re-sign, patch, or install another app's APK. Arbitrary downloaded files are not automatically inspected. Pre-install APK analysis through explicit sharing or file selection is a later increment, not an advertised first-version capability.

## Important checks

Metadata checks and runtime evidence are separate. A permission declaration is not a runtime request, a granted permission is not proof of data use, and a contacted domain is not proof of a particular uploaded payload.

| Check | Evidence and meaning | Guidance and alert policy |
| --- | --- | --- |
| Sensitive permissions | Requested permissions and grant status reported by PackageManager, including camera, microphone, location, contacts, SMS, call logs, and media access. | Explain the access and applicable lower-access options. Camera or microphone alone does not trigger a repeated urgent warning. Do not label a permission unnecessary without purpose evidence. |
| Powerful capabilities | Declared accessibility services, notification-listener services, overlay permission, installation permission, and broad storage capabilities where exposed by supported public APIs. | Explain the potential impact. Separate declared capability from enabled special access; show enabled status as unknown when not reliably observable. A declaration alone is not an urgent incident. |
| Significant version changes | Compare a previously stored snapshot with a new version of the same installed package. Detect added sensitive permissions and changed reported signer metadata. | Give one grouped change notice. The first snapshot is a baseline, not evidence of newly added permissions. Signing lineage and legitimate rotation must not be treated as malicious replacement. |
| Developer build hygiene | Report debuggable builds, manifest cleartext allowance, and target SDK. | Explain these as configuration signals, not proof of insecure traffic or exploitation. Display in the report without interrupting ordinary use. |
| Consent choices | Reuse consent-tree checks for preselected choices, visible refusal, bundled purposes, and visible notice information during an explicit observation session. | Show observed facts and uncertainty. Findings apply to the inspected screen, not every screen in the app. |
| Tracker timing | Reuse per-app DNS evidence and timestamps relative to an observed consent choice. | Highlight known tracker-domain contacts before a choice or after refusal. Do not claim that payloads, advertising IDs, all traffic, or a lawful basis were verified. |
| Coverage | Record missing permissions, unrecognized dialogs, unknown target attribution, missing reference snapshots, and low or hidden DNS visibility. | Mark unchecked or inconclusive conditions explicitly. No evidence is not a clean bill of health. |

Installed-app metadata comes from Android's [PackageManager API](https://developer.android.com/reference/android/content/pm/PackageManager). Android's [runtime permission flow](https://developer.android.com/training/permissions/requesting) remains system-controlled; TraceVeil advises the user rather than replacing Allow or Deny.

## Quiet warning behavior

Protection starts disabled and requires a clear in-app disclosure and affirmative opt-in. Monitoring and spoken warnings have separate controls. Disabling protection stops new observations and alerts; it does not disable HackTracker, Office Kit, Google Assistant, or another accessibility service.

The initial scan creates a baseline without producing a notification for every existing app. Routine results remain in the Safety Coach screen. A recognized high-impact permission request or a significant permission expansion can produce one dismissible notification with a Review action. The warning does not cover permission buttons, trap navigation, press Allow or Deny, dismiss a system dialog, or start a full audit automatically.

For this increment, the runtime alert allowlist is `READ_SMS`, `RECEIVE_SMS`, `SEND_SMS`, `READ_CALL_LOG`, `WRITE_CALL_LOG`, `READ_CONTACTS`, `WRITE_CONTACTS`, and `ACCESS_BACKGROUND_LOCATION`, all in the `android.permission` namespace. These identify potentially impactful access, not malicious intent. Other recognized requests receive in-app guidance only. A version-change notice groups additions from this allowlist together with camera, microphone, foreground location, media-read permissions, and the declared powerful capabilities listed above. Version-change notices use normal notification priority; capability declarations are not urgent runtime incidents. Changes in SDK level, debuggability, or cleartext allowance remain report-only.

Deduplicate alerts by package, version, check category, and relevant evidence. Acknowledging a notice suppresses repeats for unchanged evidence. A newly added permission or a different version can produce a new grouped notice. Combine events received within a 500 millisecond window for the same package. Rate-limit automatic notices to one per package in 60 seconds, queuing additional facts in its review card rather than discarding them. Dismissal does not grant access or classify the app as safe.

Unrecognized or ambiguously attributed permission windows produce no app-specific warning. Monitoring must fail quietly when target identification is uncertain. Reports show the resulting coverage limitation. Notifications can be delayed or suppressed by Android or Do Not Disturb; no before-every-tap guarantee is made.

Spoken warnings are off by default. When enabled, speak a short summary only, not private screen text. A Read summary button is always available if an appropriate text-to-speech engine is installed. Use available local voices; if a requested language needs a download or network voice, disclose that and keep text usable. Respect audio focus, pause on calls, and do not force loudspeaker output over connected private audio devices.

## Architecture and data flow

Keep deterministic decisions in the pure Kotlin core and Android data collection in the app module.

- `AppSafetySnapshot` holds package identity, version, reported permission state, declared capabilities, configuration signals, and available signer metadata. Optional fields retain an explicit unknown state.
- `SafetyRules` turns snapshots and optional earlier snapshots into typed findings with evidence, severity, advice, and coverage. It does not depend on Gemma or Android UI classes.
- `PackageSnapshotReader` uses supported platform APIs off the main thread. `SafetyRepository` stores only the facts needed for local checks and version comparisons.
- `ProtectionMonitor` handles installation callbacks while active and lifecycle reconciliation. [LauncherApps.Callback](https://developer.android.com/reference/android/content/pm/LauncherApps.Callback) provides package callbacks; it is not a guarantee that a stopped process receives every installation. A resumed monitor reconciles metadata without claiming the original event was observed.
- `PermissionRequestObserver` is a read-only collaborator of the existing accessibility service, independent from the audit-only consent watcher. It restricts content inspection to tested permission-controller package and activity identifiers belonging to a system package. It correlates a request with a foreground target observed within five seconds and an exact normalized displayed app identity; duplicate labels, missing identity, stale targets, system requests, and own-app events are not attributed to another app. Unsupported languages or window layouts fall back to in-app metadata guidance rather than guessed parsing.
- `WarningPolicy` groups, filters, and deduplicates findings before `SafetyNotifier` posts a notification. Notification taps open the review screen using normal platform mechanisms; the monitor does not force background activity launches.
- `SafetyCoachScreen` exposes the evidence, recommended lower-access choices, coverage, Read summary, supported app-settings links, and Start observation.
- `SpokenSummary` owns text-to-speech initialization, audio focus, language availability, cancellation, and shutdown. It reads deterministic summaries even when Gemma fails.

The flow is: explicit enablement or user-selected check, metadata or supported event collection, deterministic evaluation, quiet warning policy, user review, optional explicit runtime observation, then an evidence report. The same typed findings feed user guidance and developer reports without conflating metadata signals with runtime observations.

Extending accessibility observation requires a new prominent disclosure, affirmative consent, and accurate explanation of the accessed data. Do not claim that TraceVeil is a disability accessibility tool to avoid these requirements. Any future Play distribution needs the appropriate declaration and review. See Google's [AccessibilityService policy](https://support.google.com/googleplay/android-developer/answer/10964491?hl=en).

## Non-destructive consumer observation

The existing `AuditOrchestrator.runPlan` force-stops and clears app data when Shizuku is authorized. Changing only the Accept and Reject automation setting does not make that path safe.

Introduce an explicit audit mode at the orchestration boundary and persist it in new sessions and reports. Consumer observation must never call app-data clearing, force-stop the target, press consent buttons, grant or revoke permissions, include unrelated Google Play services traffic, or silently launch another app. It observes one selected app in its current state after the user explicitly starts the session and authorizes the required VPN and watcher access.

A consumer session records choices the user actually makes. Do not run three supposedly independent Accept, Reject, and Silent plans against a single preserved state and describe them as fresh-install comparisons. Use a distinct observation plan with compatible phase assignment and report wording. Observe for 45 seconds by default without waiting for Gemma to load. Stop observation ends collection early and saves a report labelled with the actual duration and partial coverage. Explicit cancellation discards the incomplete session. Both paths release capture and disarm the watcher. Completion produces a report-available notification without pulling the user away from their current screen.

Controlled developer testing remains available separately. Resetting data and automatically exercising choices require an explicit developer-owned-app confirmation that names the target and explains the data loss. The confirmation applies only to that requested audit and target, never a globally remembered reset permission. Passive monitoring can never supply that confirmation. Existing LeakyShop controlled tests remain the regression fixtures.

Older stored audits remain readable. Missing new mode metadata is labelled legacy or unspecified rather than retroactively claimed to be non-destructive. New metadata findings do not change the old DPDP score or imply a legal verdict. Coverage and technical findings are presented separately from legacy scoring.

## Privacy and failure handling

All Safety Coach processing and caches remain local. Store package metadata, permission identifiers, relevant check timestamps, and deduplication state for enabled monitoring of user-installed apps. Do not persist unrelated screen trees, chats, typed text, passwords, raw microphone audio, screenshots of system permission dialogs, or a general foreground-app history. The foreground target needed for dialog correlation is transient. Remove obsolete snapshots for uninstalled apps during reconciliation; existing user audit reports remain untouched. A clear monitoring-history action deletes only Safety Coach snapshots and acknowledgements, with confirmation.

Missing accessibility access, unavailable notifications, unsupported permission UI, absent TTS voices, a disconnected monitor, or failed package reads must leave normal app use working. Show monitoring availability accurately; an enabled preference is not proof that protection is active. Denying or revoking an optional permission must not create a repeated permission prompt loop.

Use short deterministic advice when Gemma is unavailable or returns no text. The currently observed AI explanation failure needs a separate diagnosis; the Safety Coach must not hide it or use fabricated generated responses. No new cloud fallback is introduced.

## Open source references and reuse

[App Manager](https://github.com/MuntashirAkon/AppManager) demonstrates package, permission, signature, and tracker inspection and identifies its application as GPLv3+. [TrackerControl](https://github.com/TrackerControl/tracker-control-android) demonstrates local tracker visibility and identifies its code as GPLv3 except where noted. They inform product ideas, not copied implementation. Do not install a second VPN scanner alongside TraceVeil for routine operation.

The later voice increment may use [Vosk](https://github.com/alphacep/vosk-api), whose library is Apache 2.0. Its [model catalogue](https://alphacephei.com/vosk/models) lists small US and Indian English models under Apache 2.0. These are candidates for measured device trials, not proof that the brand phrase or every teammate's accent will be recognized. No speech library or model is installed as part of this design draft.

Record the exact versions, sources, checksums, model terms, and required notices for any dependency that is eventually added. Keep weights, APKs, credentials, local SDK paths, and build output out of Git. Reuse does not remove the existing hackathon requirement to disclose prototype provenance and confirm any organizer reuse exception.

## Acceptance tests

The implementation plan must turn these criteria into concrete unit and device tests.

1. Permission mapping distinguishes declared, requested, reported granted, and unknown state. Special-access declarations are not reported as enabled access without evidence. Ordinary expected camera and microphone requests do not generate repeated urgent alerts.
2. A first snapshot creates no invented permission-change finding. An actual newly added sensitive permission produces one grouped change finding; unchanged metadata and legitimate signing lineage do not imply malicious replacement.
3. Warning tests cover duplicates, grouping, acknowledgement, changed evidence, ambiguous attribution, unsupported windows, self-events, and disabled protection.
4. With Shizuku running and authorized, a consumer observation preserves a controlled demo's seeded data and login-like state. It performs no app reset, force-stop, permission change, or consent click. Consumer start and cancellation release all session resources correctly.
5. Controlled LeakyShop and Fixed audits still reproduce the existing technical contrast. Existing core tests are rerun, not assumed to pass from earlier results.
6. A benign demo remains quiet; controlled high-impact permission requests show correct advice and leave Allow and Deny usable. The user manually makes all system permission decisions.
7. Supported install and update events are tested while the monitor is active. A stopped or interrupted monitor displays reduced coverage; resumed reconciliation does not invent a real-time warning history. Uninstallation does not leave live stale targets.
8. Notification denial, watcher loss, unknown UI, missing TTS voices, model failure, background operation, and screen locking do not crash or block ordinary use. Lock-screen notices do not expose private audit details. With warm metadata, recognized requests reach the warning decision within one second in controlled device tests; this measures TraceVeil processing, not guaranteed Android notification delivery.
9. On the iQOO 15, the user verifies spoken summaries, quiet warning behavior, normal app use, Google Assistant, Office Kit, HackTracker, reports, PDF export, and non-destructive observation. Future voice recognition needs actual spoken trials; text injection is not a microphone test.
10. The final handoff records APK identity, source commit, executed tests, user-confirmed checks, and remaining limitations. Push occurs only after Nitya's explicit approval of the tested build.

## Branch and team handoff

Preserve the existing `kernel-panic` checkout and its untracked user files. The clean `traceveil-verification` worktree currently contains the merged baseline at `7b153de`. Start product work from the current main baseline in an isolated Nitya feature branch after the written design and implementation plan are approved. Do not silently overwrite or switch the occupied `setup/traceveil-baseline` checkout.

Use Nitya's verified Git identity for work performed here. Do not author commits as Agrim or Yashvi. Local development commits are distinct from publication; no push, PR, or merge happens before the user tests and approves. The approved branch name and exact push target are checked at handoff. Agrim merges the reviewed branch into main; teammates then pull main into their own working branches.

Design review is the next gate. Approval of this written spec allows preparation of the implementation plan, not immediate product changes. The user then reviews the plan and selects execution in this session before implementation starts.
