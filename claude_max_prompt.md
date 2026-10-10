# Claude Max — first assignment

Continuation update: Nitya has implemented the approved explicitly labelled single-run Fast Preview. Normal Full remains the controlled-demo default and keeps its longer timing; do not duplicate this work or shorten Full. Read the latest section 0 in handoff.md, docs/FAST_PREVIEW.md, docs/NEXT_WORK.md and docs/NPU_RECOMMENDATION.md first. The final app suite now has 471 tests; verify the current checkpoint/hash from the handoff instead of assuming the older 455-test build.

Your timing work is still read-only: focus on cold GPU loading and the synchronous native screen review awaited after capture, including why coroutine cancellation may not impose a strict wall-clock cap. Return measured vs inferred latency, a bounded proposed lifecycle change, test cases and exact requested file lease. Nitya retains audit/UI files and ADB/publication. No concurrent phone control, source edits, dependency/model download or licence acceptance. AI/NPU model-selection/runtime changes must be coordinated with Yashvi. Never use shell uiautomator dump during an active audit: it can suppress accessibility services; inspect saved files instead.

Read D:\Hackathons\IQOO\handoff.md completely, then its required document list. Our app is TraceVeil; the tested integration branch is nitya/evidence-integration. Do not treat main or Agrim's older A5 prompt as the latest integrated state.

First report back the current code checkpoint, phone-tested APK hash, completed vs pending work, ownership boundaries and newly unmerged Yashvi commits. Preserve genuine contributor history and any required AI-use disclosure.

Do not edit D:\Hackathons\IQOO\traceveil-verification or the original dirty kernel-panic checkout. After checking that the destination is absent, clone into D:\Hackathons\IQOO\traceveil-claude-max and branch nitya/claude-audit-timing from origin/nitya/evidence-integration. Verify 35daed6 is an ancestor. If the branch is not published yet, wait; do not reconstruct it from stale main.

Your first task is READ-ONLY audit latency diagnosis. Inspect AuditOrchestrator, consent wait/retry, model loading/review, timing settings and saved timestamps. Explain the normal and existing Fast profiles, cold vs warm overhead, manual-choice waits and Full vs Quick coverage. Return a bounded, evidence-preserving optimisation proposal with relevant tests. No source edits until the user approves the proposal and the coordinator explicitly leases the relevant files.

Never fabricate scores, DNS, choices, countries, counters or live results. Static rules/catalogues/configuration and explicitly labelled fixtures are legitimate; do not erase them. No personal-app reset, permission automation, credentials in chat/files, cloud uploads, Jovi installer, wake-word implementation or unsupported SDK use.

Do not control ADB, restart Office Kit/Explorer or run concurrent builds in the coordinator's worktree. The coordinator owns Nitya's phone and publication. Agrim owns core/capture/rules; Yashvi owns voice/geo/map/netproof/AI/demo; Nitya owns app integration/orchestration/UI/report. New Yashvi optional online voice and keep-Camera decisions are unmerged; coordinate, do not overwrite fixes or silently restore/delete routes.

Finish with: workspace/branch/base hash; diagnosis and measured vs inferred timings; no-changes statement; proposal, coverage tradeoffs, test plan and requested file lease. Do not push main or merge anyone's branch. Keep the product usable beyond India; legal modules and vendor availability must remain explicitly scoped.
