# Agrim coding agent prompt

Paste everything below into Agrim's coding agent, opened in Agrim's own TraceVeil clone.

---

We are team Kernel Panic building TraceVeil at the iQOO hackathon. I am Agrim, GitHub `agrimsri`. Work as my coding partner on my own branch. Do not make commits as Nitya or Yashvi, alter dates, request anyone else's credentials, rewrite published history or push to main.

Repository: https://github.com/agrimsri/IQOO-Kernel-Panic.git. Nitya has prepared `nitya/integration`, a working documentation/handoff checkpoint based on the integrated application at `7b153de`. **First confirm the handoff branch has been reviewed and merged into `origin/main`.** If not, inspect that branch read-only and tell me to merge it before making feature commits. Do not return to the incomplete original six-commit baseline or import files again.

## Inspect before implementation

1. Run `git status --short`, `git remote -v`, `git branch --show-current` and inspect recent commits. Preserve local changes; no resets or destructive checkout. Fetch remote branches, update clean main with `git pull --ff-only origin main`, then create `agrim/evidence-engine` if absent. If it already exists, inspect and safely merge current main rather than overwriting it.
2. Set repository-local Git identity to `Agrim Srivastava <agrimsri26@gmail.com>` and confirm that email belongs to my GitHub account. Use my existing private authentication; never ask me to paste a password or token.
3. Read `docs/TEAM_HANDOFF.md`, `docs/planning/IMPLEMENTATION_MILESTONES.md`, `FEATURE_GAP_AND_TEAM_PLAN.md` in that planning folder, `docs/VERIFICATION.md`, README, PROVENANCE and the scoring register. Read the supplied team reference ZIP and APK misuse document if present. The **implementation milestones control ownership and ordering** when older plans disagree. Follow current venue Red/Green rules.
4. Inspect the whole real application flow: `core/.../model/Model.kt`, DNS codec and fixtures, tracker catalog/resources, phase assignment, rules/score, legal corpus, fixes/reports, `app/.../capture/**`, audit orchestration, saved sessions and the demo. The actual modules are `core`, `app`, `leakyshop`; do not rebuild a six-module greenfield app.
5. Run the existing 65 core tests and assemble all three debug APKs before claiming the baseline works locally. Missing dependencies/SDKs should be explained and installed only with the necessary approval. Do not bump pinned toolchain versions to work around a diagnosis you have not made.

## Your work and order

Implement **Agrim A0–A5** from `IMPLEMENTATION_MILESTONES.md`, test-first, in genuine focused commits:

- **A0 first:** additive, serialization-safe `NetEvent.cnameChain`, `answerIps`, `disguisedVia`, and `AuditSession.captureHealth`, all with defaults; `CaptureHealth` in a new file. Agree counter fields, units and semantics with Yashvi/Nitya, especially query/response DNS bytes and dropped/malformed counts. Add a test that old saved sessions still decode. This is your one coordinated `Model.kt` edit. Build, push this contract slice, and review/merge it first so dependants can pull it.
- **A1:** deterministic evidence-filled rights notice builder with golden text and citation-ID validation. Use the existing DPDP corpus; with no C2 return null. Associate each event with an actually observed preceding Reject and the same session clock. Ask the developer to confirm facts; do not assert a legal violation or personal-data transmission, invent a recipient, or send the notice automatically.
- **A2:** original bounded DNS answer parser for A, AAAA and CNAME, capped chains and pointer-loop guards. Partial/truncated/malformed input must never crash or hang. Attach verified response evidence to events through the capture path. Match response questions and record owners before attributing aliases/IPs; do not treat unrelated additional records as answers for this app's queried host. Do not change VPN scope or introduce full payload capture.
- **A3:** alias-aware classification before the first-party shortcut; record `disguisedVia` and explicit “alias observed” evidence. Non-alias behavior and existing controlled demo score arithmetic remain unchanged. Write literal packet/classifier tests, including first-party aliases and malformed responses. Request fixtures from Nitya or create isolated test-only fixtures; do not edit `app/data/SampleAudit.kt` yourself.
- **A4:** bounded capture buffer, accurate health counters and pure `ConfidenceGrader` with named thresholds/reasons and boundary tests. Keep V0/low-visibility honesty and existing scoring behavior. Unknown counters are not healthy zeroes. Give Yashvi the measured-byte contract for Network Proof and Nitya the persistence/UI signature.
- **A5:** additive tracker company fields with source URLs. Leave unavailable countries null. Do not label headquarters as a registered jurisdiction. Add external data only after its original licence/redistribution obligations are checked and recorded with Nitya; provided comparison memos are not licence clearance.

The baseline core already works; do not recreate existing C1–C10 rules, fixes, reports or import commits. Cut lower-priority work as the milestone plan prescribes rather than silently expanding scope.

## Ownership boundaries

You own the evidence engine, its tests/resources and `app/.../capture/**`. Nitya owns MainActivity/AppGraph/shared ViewModel/Home/Results/Live, manifest/build configuration, audit/data/report/control/consent, fixtures, CI/docs. Yashvi owns voice/AI/geo/copilot/map/Network Proof, `ToolScreens.kt` and demos. Use new files for new types. Cross-owner edits require an explicit coordination request, not “accept theirs” or a full-file replacement.

Do not implement the deferred Safety Coach, a competing speech stack, camera improvements, wake words, antivirus or permission interception. Camera Check removal is coordinated between Nitya's routes and Yashvi's ToolScreens cleanup.

## Phone safety and acceptance

Connecting my iQOO is not enough: identify **my** serial with `adb devices -l`; use explicit `-s` for all commands. Build/install the three APKs, manually grant VPN consent, enable TraceVeil accessibility without disabling HackTracker, set Private DNS off for the controlled tests, start/authorize Shizuku on my own phone, and install optional models separately. Keep Google default assistant and Office Kit intact. Never uninstall an app to bypass a signing mismatch without discussing data preservation.

The current Quick/Full orchestrator clears target data when Shizuku is authorized, even with auto-drive off. Only use LeakyShop demo packages or an explicitly confirmed developer-owned test target. Never reset a real user's app or automatically accept Android permission dialogs. No root, bootloader changes or malicious-APK downloads.

Nitya previously observed real full demo audits: LeakyShop 21 with C2/C1/C3/C4/C10, Fixed 100 with no captured findings. Those are expected regressions, not constants to program into results. AI generation failed on that phone, so score evidence came from deterministic fallback; model/voice reliability belongs to Yashvi. On your phone, record actual results and investigate differences.

Before each usable slice: observe failing new tests, implement, run the full core suite plus affected builds, inspect the diff for unrelated/shared files, then commit as me and push only `agrim/evidence-engine`. Do not push a failing slice as a ready integration. Preserve granular real authorship; no artificial commit counts or rewritten dates.

Finish each handoff with: commit/branch, A milestone status, tests with actual outputs, old-session compatibility evidence, owned files, exported signatures/units, exact Nitya/Yashvi wiring requests, phone-test evidence, new licences/dependencies and unresolved limits. Review and merge the A0 contract before continuing dependent integration. Never claim all frauds are prevented, DNS proves data exfiltration, or a score is legal certification.
