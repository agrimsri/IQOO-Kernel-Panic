# Fast Preview and normal audit coverage

Approved scope: an explicitly labelled short single-run check, while normal Full audits retain stronger three-plan evidence. No score, lookup, choice, country or measured time is manufactured.

| Mode | Plans | Consent-screen wait ceiling | Agent dwell | Observation |
| --- | --- | --- | --- | --- |
| Fast Preview | Accept only | 12 s | 1.5 s | 8 s |
| Normal Quick | Accept only | 20 s | 2.5 s | 15 s |
| Normal Full | Silent, Reject, Accept | 20 s per plan | 2.5 s when tapping | Silent 25 s; Reject/Accept 15 s each |

These are capture budgets, not end-to-end promises. A screen found early ends its wait early. App launch, model cold-loading/review, refusal behind Manage and a bounded approved demo retry add time. Manual choice still waits up to 45 seconds; Preview never auto-approves permissions or personal-app consent.

`AuditConfig.fastPreview` is immutable per run and valid only for one Accept plan. `AuditTimingPolicy` is the production timing source. The old `fastDemo` preference now selects Preview by default for single-run setup; it cannot shorten Full capture. Controlled fresh-state demo apps still default to normal Full. Real apps keep reset and automatic tapping off unless the user explicitly changes those controls.

The selected profile is saved as `AuditMeta.timingProfile`: `fast_preview`, `normal`, or unknown for old records. Live progress, Results and exported reports disclose the profile. Preview session notes preserve its limitations. Old live reports gain an unknown-timing warning at render/export time; their saved evidence is not rewritten. Synthetic samples do not acquire invented live timing.

Score comparisons require the same known live timing profile as well as matching package, mode, plans and completed choices. A shorter run cannot masquerade as an improvement over a longer one. Old live records with no timing provenance cannot establish comparable timing. A matching observation budget still does not establish identical coverage or that a finding was fixed.

## Coordination

Nitya owns this Preview implementation and its audit/integration/UI/report files. Claude Max's separate `nitya/claude-audit-timing` checkout remains read-only for overlapping timing work until a new explicit file lease. The one-line Settings label in Yashvi-owned `ToolScreens.kt` is an integration-copy correction only: keep it when merging her newer work; do not replace her voice/camera/AI implementations.

## Regression evidence

The initial 11 behavioral tests ran with six real assertion failures before implementation. The app test harness needed the matching coroutine-test dependency, now resolved and test-only. Virtual-time tests cover delayed traffic missed by Preview but observed by normal capture, normal Silent duration, consent-wait and dwell distinction, invalid Full/Preview configuration, metadata round-trip, legacy timing, report idempotence and comparison guards. Two added legacy-report tests failed before their fix, then passed with the existing coverage tests.

Device acceptance and the final build receipt are in [VERIFICATION.md](VERIFICATION.md). A one-lookup Preview may be Inconclusive; that is not a failure to generate a friendly score. Preview is useful for a first look and repeated developer smoke checks. Use normal Full on an authorised fresh-state test app when refusal and no-interaction evidence matter.

During an active capture, do not use shell `uiautomator dump` to monitor progress: its UiAutomation connection can suppress other accessibility services. Android [documents this default suppression](https://developer.android.com/reference/android/app/UiAutomation#FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES); an instrumented test must explicitly preserve the existing services. Inspect saved audit files or take ordinary screenshots instead, and inspect UI XML before/after the run. One Full run lost Accept during an in-run dump; its failed evidence remains saved and is not counted as a successful acceptance run.

## Latency still to investigate

The existing screen review is started during capture, but remaining review is awaited afterwards. `REVIEW_TIMEOUT_MS` is 90 seconds; the underlying synchronous native model call may not respond immediately to coroutine cancellation. Do not claim that value is a strict wall-clock cap. Separate cold-load, consent wait, actual observation, retry and model-review timings before changing that lifecycle. NPU is an unvalidated text-only experiment described in [NPU_RECOMMENDATION.md](NPU_RECOMMENDATION.md), not an automatic cure for long observation windows.
