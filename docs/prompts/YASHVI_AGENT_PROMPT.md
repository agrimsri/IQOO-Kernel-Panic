# Yashvi coding agent prompt

Paste everything below into Yashvi's coding agent, opened in Yashvi's own TraceVeil clone.

---

We are team Kernel Panic building TraceVeil at the iQOO hackathon. I am Yashvi, GitHub `yashviup`. Work as my coding partner on my own branch. Do not make commits as Agrim or Nitya, alter dates, request anyone else's credentials, rewrite published history or push to main.

Repository: https://github.com/agrimsri/IQOO-Kernel-Panic.git. Nitya has prepared `nitya/integration`, a working documentation/handoff checkpoint based on the integrated application at `7b153de`. **First confirm Agrim reviewed and merged this checkpoint into `origin/main`.** If not, inspect its documents read-only and tell me to wait for that merge before feature commits. Do not clone only the original six commits or import baseline files again.

## Inspect before implementation

1. Check `git status --short`, remotes, active branch and recent commits. Preserve local edits; do not reset or force checkout. Fetch, update clean main with `git pull --ff-only origin main`, then create `yashvi/phone-experience` if absent. Inspect existing feature branches instead of recreating them. Shared branches receive safe merge updates from main, not rebases/force pushes.
2. Use repository-local identity `Yashvi Upadhyay <24f2007780@ds.study.iitm.ac.in>`, confirm my verified email, and use my private existing GitHub authentication. Never ask for a token/password in chat.
3. Read `docs/TEAM_HANDOFF.md`, the three new planning files under `docs/planning`, `docs/VERIFICATION.md`, README, PROVENANCE and the scoring register. Read the supplied reference ZIP if available. **IMPLEMENTATION_MILESTONES.md is the active plan**; older greenfield/camera-upgrade roadmaps do not override it. Follow live venue Red/Green instructions.
4. Inspect the whole app flow before touching code: `XrayApp.kt`, MainActivity/shared ViewModel, saved sessions/results/sample data, AI services/LlmEngine/ModelFiles, ToolScreens, existing corpus/retrievers/rules, capture/audit, reports and the demo module. The real modules are `core`, `app`, `leakyshop`; keep that layout.
5. Run the existing core suite and assemble all three debug APKs. Start from a working local baseline; request needed SDK/dependency installation approval. Reuse pinned versions and existing APIs rather than fabricating interfaces.

## Your work and order

Implement **Yashvi Y1–Y6** from `IMPLEMENTATION_MILESTONES.md`, test-first, using new self-contained files:

- **Y1 first, independent of Agrim A0:** push-to-talk Voice Copilot, tappable question chips, evidence-grounded answers, offline TTS, English/Hindi and a self-contained screen/ViewModel. Read only the selected audit's actual evidence and at most three retrieved corpus passages. Validate citations and assertions against those sources; refuse off-topic questions and say unknown rather than declare an app safe. A sample must remain visibly sample. Keep questions usable without model/speech availability.
- **Observed AI defect:** the GPU model loads and benchmarks, but existing English explanation attempts twice and Hindi once returned “The model returned no text. Try again.” All six full-audit screen reviews fell back to deterministic checks. `LlmEngine.generate` uses `runCatching(...).getOrNull()`, hiding the underlying failure. Diagnose with a controlled local prompt and safe error reporting, preserve coroutine cancellation and existing call-site compatibility, then fix the demonstrated cause with tests. Do not guess that temperature zero is invalid, assume a CPU backend can run any GPU bundle, or replace a failed model answer with text labelled AI-generated.
- For microphone input, use **only verified on-device recognition** when available, not a generic recognizer with a preference flag that can send audio to a service. If unavailable or denied, disable the mic with a clear reason and retain question chips. Release recognition/mic on pause/dispose; no continuous listener, recording history, cloud fallback or silent model/voice downloads. Nitya owns adding `RECORD_AUDIO`, any required package-visibility queries and the navigation route. Send her exact one-line manifest/interface requests; do not edit her files yourself.
- Speech output uses a verified local voice, separate user-controlled speaker toggle, normal audio routing and audio focus. Stop/pause appropriately on calls, interruptions and leaving the screen. Do not force public speaker output over headphones or silently use a network-required TTS voice. Keep Google as the default assistant. This is audit Q&A, not “Hey TraceVeil” while closed.
- **Y2:** self-contained measured Network Proof adapter/card, arithmetic tests and actual device measurements. Start with fakes until Agrim's A4 supplies agreed byte counters. Treat `TrafficStats.UNSUPPORTED`, counter reset/wrap, interval mismatch and layer differences explicitly. UID bytes minus relay payload bytes is an estimated residual, **not proof of zero upload, malicious upload or a measured audit payload**. Do not clamp a negative/unknown measurement into a claimed zero. Display raw figures, units, formula, tolerance and uncertainty; coordinate begin/end persistence with Nitya rather than editing the orchestrator.
- **Y3:** new pure geo lookup/aggregator types and self-contained endpoint-country list card, with controlled fixtures while waiting for A0/A2. Keep offline data out until the original source's licence and redistribution/attribution terms are read and recorded with Nitya. Never reuse TrackerControl's map/database/assets. Unknown IPs stay unknown/omitted; CDN/anycast is uncertain; no countries guessed from hostnames. Label DNS-IP country as inferred, not a transfer or legal violation. If no data source is cleared, ship an honest jurisdiction/IP-only fallback; optional cloud lookup needs an explicit team decision and off-by-default consent.
- **Y4:** separate company jurisdiction layer only for sourced entries from Agrim A5. Headquarters is not automatically registration; use labels supported by each source. Do not phase-color this reference-data layer.
- **Y5:** after Nitya removes the Camera Check Home entry/route, remove its screen and unused camera helpers from **your owned** `ToolScreens.kt`. Preserve AI Lab and Settings. Do not improve Camera Check: the newest milestone plan explicitly removes it. Coordinate the two commits; do not push a broken dangling route as ready.
- **Y6 last:** a controlled aliased host in the leaky flavor only if the team controls a real CNAME and Agrim's parser/classifier has passed. Otherwise retain a labelled synthetic fixture; no fake live-domain claim. Preserve Fixed behavior and the demo contrast.

Cut lower-priority items using the milestone plan rather than adding unrelated features. Do not implement a full fraud scanner, permission interceptor or a second Safety Coach.

## Ownership boundaries

You own `app/.../voice/**`, `netproof/**`, `ui/screens/map/**`, `ToolScreens.kt`, `ai/**`, `core/.../geo` and `copilot`, and `leakyshop/**`. Agrim owns Model.kt/evidence/capture/rules/score/notice/tracker resources. Nitya owns MainActivity/AppGraph/shared ViewModel/Home/Results/Live/Components/theme, manifest/Gradle configuration, audit/data/report/consent/control, fixtures and CI/docs.

Do not modify another owner's file to make integration easier. Deliver exact signatures, standalone components, error states and short wiring requests. Nitya will mount the voice screen, add permissions/routes, persist proof, and place cards. Request licence register updates rather than inventing clearance. Shared documentation changes can be proposed for Nitya to integrate.

## Run it on my own iQOO 15

Connecting Office Kit/ADB does not copy Nitya's phone state. Identify **my own** serial; every device command uses `adb -s MY_SERIAL`. Build/install TraceVeil plus both demos; manually enable VPN and TraceVeil accessibility while keeping HackTracker enabled, set Private DNS off for DNS tests, and start/authorize Shizuku on this phone. Keep Google assistant unchanged. No root/bootloader changes, automatic Android Allow/Deny clicks or silent uninstall after a signing mismatch.

Current Quick/Full audits can clear target data with Shizuku, even with the agent toggle off. Only test on controlled LeakyShop or explicitly confirmed developer-owned test packages; never banking/messaging/personal apps. No live malware downloads.

Optional GPU model: `gemma-4-E2B-it-gpu.litertlm`, 2,008,432,640 bytes, SHA-256 `a53a59001894c58e6bdb5b9b227709f91a2e3e556baa7d85acf9c55402ba5cf5`, app external models directory `/sdcard/Android/data/com.dpdpxray.app/files/models/`. Cloning does not transfer it; verify hash/licence and use the existing model-loading flow. Embedding/NPU models are not validated; do not install an SM8750 bundle on SM8850 assuming compatibility.

Verify actual English/Hindi speech recognition, denied permission, missing pack/model, interruption cleanup, local speech output and offline behavior on this phone after Nitya's manifest wiring lands. Typed or injected transcript input tests only the answer path, **not microphone recognition**. Confirm Office Kit mirroring, real clipboard paste and report transfer separately. Different devices/networks/settings can produce different observations; never promise identical results.

## Verification and handoff

Each completed slice needs new failing tests first, the observed failure, implementation, fresh full core tests plus affected builds, and phone evidence where required. Preserve baseline controlled regression expectations LeakyShop 21 and Fixed 100 without hardcoding; recorded model failures do not count as working AI. Tests must also cover grounded refusal, absent C9 evidence, invented citation rejection, missing speech/model services, and uncertain counters.

Commit genuine work as me and push only `yashvi/phone-experience` through my authenticated account. Keep public history, truthful provenance and AI-use disclosure; no artificial commit counts or changed timestamps. Agrim reviews and merges into main.

Report: branch/commit, Y milestone status, tests/actual outputs, phone behaviors genuinely tested, exported component/signature contracts, requested Nitya wiring, licences/dependencies, model diagnosis evidence, and remaining coverage limits. Never claim comprehensive fraud prevention, payload exfiltration or legal certification from permissions/DNS. On-device recognition and local TTS requirements should be checked against the official Android documentation, not memory.

Primary Android references: [SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer), [TTS Voice](https://developer.android.com/reference/android/speech/tts/Voice), and [TrafficStats](https://developer.android.com/reference/android/net/TrafficStats).
