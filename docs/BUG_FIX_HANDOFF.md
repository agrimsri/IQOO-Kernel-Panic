# Agrim bug report: integration handoff

Source: user-supplied `bug_agrim_claude_suggest_fixes.md`, read in full. This records local implementation, not phone acceptance.

| Report item | Local change | Acceptance still needed |
| --- | --- | --- |
| BUG1: intermittent missed Reject dialog | Periodic armed-window probes; generation-safe screenshot completion; one approved fresh-state retry for the two controlled demos, preserving both attempts and unique session/measurement IDs | Ten consecutive Full leaky runs with recorded refusal, expected C1/C2/C3/C4/C10 and score 21; inspect any miss/retry rather than discard it |
| BUG2: stale Home watcher state | Refresh on Home entry; observe accessibility-setting changes; recheck when Audit is tapped and show an actionable reason | After install/update and returning from settings, watcher state updates without relaunch; verify within two seconds |
| BUG3: missed choice resembles privacy pass | Results/report/voice distinguish plan execution from the actual intended choice; missing refusal says C2 not tested; incomplete plans cannot produce score-improvement cards | Induce a missing/wrong choice; verify warnings beside score, in spoken answer and exported report; arithmetic unchanged |
| BUG4: incomplete teammate JDK extraction | Not patched: original setup/activate scripts and kit are absent from supplied folders/ZIPs. Our JDK builds successfully but does not verify that kit | Supply the actual kit; compare extracted files to archive inventory, then test Java and socket support before marking setup healthy |
| BUG5: quiet Quick audit on already-used app | Pre-start no-reset warning and a qualified possible cached-consent/already-opened explanation for empty/no-screen sessions | Quick on an agreed already-used real app, with data retained; no quiet/unknown result presented as proof of safety |

Core score thresholds are unchanged. No new legal verdict or INFO check ID was invented in Agrim-owned rules. Direct core-only report consumers need Agrim's equivalent coverage/scope treatment; the Android UI and exports apply the new app-owned treatment to old saved audits too.

## Review corrections before publication

The integration review found six additional evidence/voice defects. Local regressions now pass for country-by-phase association, COMPLETE/PARTIAL-only IP placement, corrupt MMDB pointer/date handling, unconfirmed dialog disappearance, missing Accept voice warnings and cancelling a delayed answer when microphone input starts. A dialog closing no longer emits the requested Accept/Reject: unsupported click-event toolkits keep the choice unknown and coverage incomplete. Actual observed taps or successful explicit agent button actions are still recorded. Legacy saved inferred choices are not retroactively rewritten; inspect their inference notes and do not present them as verified taps.

The single fix pass also refreshes the map when first-party settings change and corrects its stale licence comment. Fresh full verification is 455 passing tests; that is not completion of the remaining phone, accounting or licence checks. Agrim should review the core geo/copilot changes, and Yashvi should reconcile the voice/map changes before editing the same files.

## Real-app mentor demo

1. Obtain permission and choose a non-sensitive app. Avoid banking/payment/authentication and private content. Do not use an app whose data must be erased to make the demonstration work.
2. On Home, consciously approve VPN capture and enable the TraceVeil consent watcher. Check Private DNS and other active VPNs; explain any visibility limits. Keep Google as default assistant.
3. In setup, keep **Allow clearing this test app's local data** off and **Let the agent tap Accept and Reject** off. Keep Google Play services capture off for cleaner attribution. Choose **Quick**.
4. Interact yourself. Do not approve sensitive permissions just to produce evidence. TraceVeil does not intercept every permission dialog or prevent every fraud.
5. Explain observed host lookups separately from inferred tracker/identifier/country claims. A lookup is not payload proof. If no dialog or DNS appears, show the coverage warning and possible causes; do not force a headline result.
6. Ask the saved audit about score, trackers and refusal. Quick does not run Reject, so the refusal question should say that plan was not run. Compare with the controlled demo's repeatable full test, not a fabricated real-app score.

## Voice acceptance

Tap **Allow the microphone** and use Android's own permission dialog. Denial must leave chips usable. If the selected offline language pack is missing, choose **Download speech pack** consciously, allow the provider's download if desired, then **Check again**; this requests a model download, not cloud recognition. Test at least two different spoken questions and listen to the actual replies. Open **Voice settings** to select/install a preferred offline TTS voice if the best available voice still sounds poor. Phone/provider support cannot be guaranteed by unit tests.

## Team coordination

Agrim reviews the integration branch before merging main. Yashvi should reconcile Nitya's urgent voice fixes, the map legal parameter/A5 owner adapter and removal of the AI-Lab stopgap before resuming overlapping edits. Camera helper deletion is cancelled: Camera Check stays (team decision, 10 October). Nitya owns the orchestration, persistence, navigation and report wiring; teammates should not duplicate these integrations.

After main is accepted, each teammate fetches and merges the updated main into their own branch without resetting local work. Allocate one owner per feature. Preserve every contributor's actual commits; do not force-push teammates' history or manufacture authorship/counts.
