# Next work, ordered for a defensible mentor demo

Normal Full remains the controlled-demo default. Optional Fast Preview is a short first look, not a complete consent audit or privacy pass. Do not expand the mentor checkpoint until current evidence and device behaviour are understood.

| Priority | Owner / boundary | Acceptance still needed |
| --- | --- | --- |
| 0. Reconcile current main | Yashvi's three owned conflict files; Nitya integration; Agrim acceptance | Main has advanced to PR#7. Preserve both the newer voice/camera features and tested pack recovery/map/Preview fixes, resolve the recorded conflicts, then build/retest the combined tree before merging this checkpoint. |
| 1. Capture reliability | Nitya device controller; Agrim capture/core | Ten consecutive approved Full LeakyShop runs, actual Reject/Accept each time, all failed/retry attempts retained, saved health and sidecars matched. One successful run is not ten-run acceptance. |
| 2. Voice quality and input | Yashvi `voice/**`; human checks | A person speaks distinct questions and hears appropriate distinct replies offline on all three phones. Verify language-pack recovery, permission denial/approval and audible TTS quality. Chips working alone does not prove recognition. |
| 3. Model-review latency | Claude first diagnoses read-only; Yashvi `ai/**`; Nitya orchestration | Measure cold load, launch/wait, observation, retry and post-capture native review separately. Request one file lease before edits. Preserve normal capture, cancellation truth and deterministic scoring; no first-tracker shortcut. |
| 4. New upstream reconciliation | Yashvi voice/camera; Nitya routes/consent integration | Review the newer optional online-voice and keep-Camera commits after the stable checkpoint. Preserve regional offline-pack recovery, stale-answer cancellation and coverage caveats. Any online mode is off by default with provider/audio/text disclosure and explicit opt-in. No India-only service dependency for the global core. |
| 5. Real endpoint-country data | Yashvi geo/map; team licence decision | A cleared data source, applicable attribution/distribution/update obligations, compatibility checks and an actual filled-map phone test. Unknown countries remain unknown without it. Country inference/company registration are not proof of physical hosting or illegal transfer. |
| 6. NPU experiment | Yashvi AI module; Nitya device validation | Exact SM8850 Gemma 3 1B text bundle, reviewed model/QAIRT terms, matching dispatch/QNN V81 runtime, wrong-SoC guards, truthful backend evidence and measured performance. See NPU_RECOMMENDATION.md. Not installed or accepted yet. |
| 7. Delivery/rehearsal | Nitya documentation/CI; Agrim main merge | Hosted CI status, organiser confirmation of permitted baseline reuse, missing teammate setup-kit scripts, Office Kit file-transfer acceptance, signed release decision, demo rehearsal/video and round tags. Local tests do not prove these. |

## Scope boundaries

TraceVeil helps APK developers and privacy reviewers connect observed consent choices to captured DNS evidence, identify possible problems, inspect SDK/domain clues, draft reviewed notices and test a fix. Users can understand a limited observation of an app they authorise. It does not inspect encrypted payloads, guarantee every tracker/fraud is detected, decide legal applicability or stop Android permissions at every point.

Jovi remains an optional Office Kit desktop assistant investigation; no supported embedded Android SDK or NPU integration has been verified. Its terms were read, but no installation/acceptance/integration was performed. `Hey TraceVeil` remains unimplemented: a background wake word needs a separately approved microphone/lifecycle/privacy design and actual Android/OEM validation. Google remains the default assistant.

## Avoid duplicate work

Current main is `dac54e5` (Yashvi PR#7), not the older `ebdafae` snapshot. A read-only merge diagnosis of product checkpoint `619a0d5` against main finds conflicts in `ToolScreens.kt`, `ui/screens/map/EndpointMapCard.kt` and `voice/VoiceViewModel.kt`. Yashvi should coordinate those owned files, preserving both the new features and the tested integration fixes. The combined tree has not been built or phone-tested. No merge was performed by the diagnostic.

- Continue in separate checkouts/branches; do not reset the original dirty `kernel-panic` checkout.
- Nitya owns the current audit/Preview and phone/publication lease. Claude's timing assignment is read-only until a new explicit file lease; it must not replay an already implemented timing change.
- Agrim owns core rules/capture. Yashvi owns voice/AI/map/Network Proof/demo. Interface changes are proposed before the other owner mounts them.
- The Settings-copy correction in `ToolScreens.kt` changes one line only; preserve it when merging the newer Yashvi branch.
- Share tested commit hashes and APK hashes, not assurances that another device must behave identically. Never impersonate contributors, manipulate dates or hide required provenance.
