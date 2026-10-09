# Feature Gap Audit and Team Execution Plan (TraceVeil, `IQOO-Kernel-Panic` `main`)

Audited: Fri 9 Oct 2026, 23:39 IST (the printed schedule puts this in a 🔴 Red window; 🟢 Green starts at 00:00).
Inputs: `DPDP_XRay_Build_Plan.md` ("Build Plan"), `COMBINED_FEATURE_ROADMAP.md` ("Roadmap"), `hackathon_scoring.md` ("Scoring"), `THREE_PERSON_EXECUTION_PLAN.md` ("Execution Plan"), and the code on `IQOO-Kernel-Panic` `main` (tip `7b153de`).
No application code was modified. The only files written are this one and git-ignored Gradle build output.

---

## 0. How this audit was done, and what it cannot tell you

| Check | Result |
|---|---|
| Branch inspected | `main` = `origin/main` = `7b153de`. `git log main..origin/{agrim/rules-reports, yashvi/ui-ai, setup/traceveil-baseline}` is empty for all three: no feature work exists on any other branch. `git status` clean. |
| Content of `main` | 90 tracked files, ≈5,700 lines of Kotlin: `core/` (pure JVM), `app/` (Android), `leakyshop/` (demo app). All 15 commits are `import(...)` or `docs(...)`; no teammate-authored feature commit exists yet. |
| `:core:test` | `bash ./gradlew :core:test --offline` → **BUILD SUCCESSFUL; 65 tests, 0 failures, 0 errors, 0 skipped** (counted from `core/build/test-results`). The `:core:test` task was `FROM-CACHE`, so this confirms the cached result, not a fresh execution. |
| `:app` / LeakyShop build | **Not run in this audit.** `docs/VERIFICATION.md` records a successful assemble of all three debug APKs on 9 Oct; I did not reproduce it. |
| Phone behaviour | **Nothing verified on a device.** `docs/VERIFICATION.md` itself says "no phone APK installation, model inference, live audit, or UI smoke test has been performed". Every "Implemented" below means *code present*, not *works on the iQOO*. |
| Tests outside `core/` | None. `app/src/test` and `app/src/androidTest` do not exist. |
| Wrapper | `gradlew` is committed with mode `100644` (not executable). On Linux/macOS, `./gradlew` fails with "Permission denied"; `bash ./gradlew` works. |
| Hygiene files | No `.github/`, `ATTRIBUTIONS.md`, `CHANGELOG.md`, `CONTRIBUTING.md` or `docs/demo-script.md` on `main`. |
| Downloaded data | `team_downloads/` holds the two DPDP PDFs, the Gemma/EmbeddingGemma model files and a `gh` zip. **No tracker dataset (Exodus/DuckDuckGo) and no IP-geography dataset has been downloaded.** |

**Status legend**

- ✅ **Implemented**: code present and covered by passing core tests where logic is pure. Device behaviour still unverified.
- 🟡 **Partial**: code present, but something the plans require is missing.
- ❌ **Missing**: no code found (searched with grep, cited below).
- ⚠️ **Unverified**: depends on a device, a licence, or an external fact I could not check here.

**Estimates** (S ≈ 1–2 h, M ≈ 3–5 h, L ≈ 6–9 h of one person's active time) are mine, not measured. Scores in §4 are my judgement on a 0–5 scale, not data.

---

## 1. Decisions that change the plan (read first)

| # | Decision | Why it matters | Default used here |
|---|---|---|---|
| D1 | **Eligibility of the imported baseline.** `PROVENANCE.md` says the code is "an imported baseline, not newly authored event code", dated 5 Oct, and that the organiser's written clarification must be recorded before claiming eligibility. `hackathon_scoring.md` §8.1 and §9 rule 1 say all code is written during the event. | Everything below is built on top of this baseline. If the ruling is "no", the baseline cannot be the submission and this plan changes. | Proceed on the baseline, because the team reports that reuse is allowed. Nitya obtains and records the ruling (task N-01). Every new line is written fresh and committed under its author's own identity. |
| D2 | **Module layout.** The Execution Plan and Roadmap use a greenfield layout (`contract/`, `sensing/`, `analysis/`). `main` actually has `core/` + `app/` + `leakyshop/`. | File paths in the earlier docs do not exist. | **This plan uses the real paths on `main`.** |
| D3 | **Voice speech-to-text must be on-device.** The product promise is "0 bytes leave the phone". Android's default recognizer may use a server. | A cloud recogniser would falsify the central claim. | Use only on-device recognition; if unavailable, disable the mic and say so (U02). |
| D4 | **Geo database licence** (country map). | Cannot bundle a dataset whose terms are unread. | Block the data commit until a named source's licence text is read and recorded (U10). |
| D5 | **CNAME demo needs a real aliased name.** | The leaky app's lookups are resolved by the real resolver; a CNAME only appears if a name we control points at a tracker domain. | Fixture first; one team-controlled alias second (U07). |
| D6 | **Schedule end.** The printed chart ends building at Sun ≈09:00. | Plans that run into Sunday afternoon are unsafe. | Code freeze Sun 08:30; feature freeze Sun 06:30 (`hackathon_scoring.md` §8.3). |

---

## 2. What is actually on `main` (verified)

| Area | Files (`app/src/main/java/com/dpdpxray/app/…` unless `core/` or `leakyshop/`) | What I confirmed in code |
|---|---|---|
| DNS codec | `core/.../dns/DnsPacketCodec.kt` (230 lines), tests 151 + 108 lines | Parses questions; `zeroTtls` rewrites answer TTLs; builds SERVFAIL. **Reads only the question section; answer records are not parsed.** |
| Per-app VPN | `capture/XrayVpnService.kt`, `DnsForwarder.kt`, `CaptureBus.kt` | `addAllowedApplication(pkg)` (+ optional `GMS_PACKAGE`); `CaptureBus.record` appends `NetEvent(tNanos, hostname, queryType)` only. `CaptureBus._events.update { it + event }` copies the whole list per event. `DnsForwarder.resolve()` returns the reply bytes, so answers are available there but unused. |
| Consent | `consent/ConsentAccessibilityService.kt`, `ConsentBus.kt`, `NodeMapper.kt`, `core/.../consent/ConsentLexicon.kt` | Lexicon covers English, Hindi and Kannada (file comment); "Mark consent now" exists (`LiveAuditScreen.kt:97`). |
| Audit plans | `audit/AuditOrchestrator.kt` (278 lines) | Plans via `AuditPlan.SILENT/REJECT/ACCEPT`; Shizuku `pm clear`/launch when granted; `fastDemo` timing profile (`:70`); **"agent" tapping is deterministic lexicon-driven** (`:251`), not an LLM tool loop. |
| Phases | `core/.../audit/PhaseAssigner.kt` (27 lines) | BEFORE_CONSENT / AFTER_ACCEPT / AFTER_REJECT / NO_CONSENT_SEEN. |
| Rules + score | `core/.../rules/RulesEngine.kt` (255 lines) | C1–C10, NC, V0; `MIN_EVENTS = 2` (`:237`); `ScoreCalculator` bands 85/60. |
| Trackers | `core/.../trackers/TrackerCatalog.kt`, `resources/trackers/trackers.json` | **36 entities, 52 domains.** `classify()` returns FIRST_PARTY from the user's first-party set *before* consulting the catalogue (`:36`). |
| Legal corpus | `resources/dpdp/passages.json`, `ai/AiServices.kt` (`EmbeddingRetriever`) | **16 passages**: `s4(1) s5(1) s5(3) s6(1) s6(2) s6(3) s6(4) s6(6) s6(10) s9(1) s9(2) s9(3) r3(a) r3(b) r3(c) schedule`. No access/correction/grievance/s.16 passages. |
| Fixes | `core/.../fixes/FixCatalog.kt`, `fix_templates.json` | 18 templates (9 SDK + 9 UI). |
| Reports | `core/.../report/Reports.kt`, `report/Exporters.kt` | Markdown, JSON, PDF; Save to `Downloads/TraceVeil`; Share; clipboard copy (`ExportManager.copy`). |
| History | `data/AuditRepository.kt`, `SampleAudit.kt` | Audits stored as JSON + Markdown under `filesDir/audits/<id>/` (not Room). Home lists them; a labelled sample audit works with no VPN/model. `AuditJson` uses `ignoreUnknownKeys` + `encodeDefaults`, so **adding defaulted fields keeps old saved audits readable**. |
| AI | `ai/LlmEngine.kt`, `AiServices.kt`, `ModelFiles.kt`, `ui/screens/ToolScreens.kt` | Screen audit, finding explainer (English/Hindi), embeddings, GPU/NPU/CPU benchmark screen; audio backend configured (`LlmEngine.kt:80`) but unused. |
| Camera Check | `ToolScreens.kt:114` | Uses `TakePicturePreview()` (a small thumbnail bitmap) and an image picker. No blur/glare gate. |
| Preflight | `control/DeviceChecks.kt`, `HomeScreen.kt` | Private-DNS, VPN, Accessibility, Shizuku, model status rows. |
| LeakyShop | `leakyshop/src/main/java/com/leakyshop/demo/Shop.kt` (214 lines) | Leaky and fixed flavours. No aliased host. |
| Origin tag | `NetEvent.viaPackage` (`Model.kt:62`) | Read by `RulesEngine.describe` (`:230`); **no code sets it** (grep over all `*.kt`). |

---

## 3. Source reconciliation: every feature, one ID

Source keys: **BP** = Build Plan (M, A, W, C-checks, T-tasks), **EP** = Execution Plan (F1–F26), **RM** = Roadmap (F-01…F-14), **TC/PC/EX** = the three comparison memos, **MS** = `EXODUS_INTEGRATION_MILESTONES.md`.

| ID | Feature | Sources merged |
|---|---|---|
| U01 | Core audit loop verified on the iQOO (capture, consent, plans A/B/C, rules, report) | BP M1–M7, EP F1–F6, F8, F10–F12, F23 |
| U02 | Voice Copilot (spoken Q&A grounded in the audit) | BP W3, EP F16, F26 |
| U03 | Laptop Handoff completed (copy fix, send patch + report, "open on laptop") | BP A5, EP F14 |
| U04 | Leaky → fixed before/after comparison | EP F13, BP B2 |
| U05 | Network Proof (measured bytes sent by TraceVeil during an audit) | EP F20 |
| U06 | Stage mode completed (large-type toggle already exists; add stage checklist and model warm-up) | EP F22 |
| U07 | DNS answer parsing + "disguised tracker" (CNAME) tag | RM F-01, F-02, TC R1, PC A5 |
| U08 | Evidence-filled DPDP request notice | RM F-04, TC R2 |
| U09 | Evidence-confidence grade (replaces binary V0) | RM F-05, PC A2 |
| U10 | Country map (observed endpoint geography + reference company jurisdiction) | RM F-06, TC Countries, PC A5, MS jurisdiction |
| U11 | Tracker data v2: provenance, docs URLs, licence-separated sources, company country | RM F-03, EX T1.1–1.3, MS M0–M2, BP T5 |
| U12 | DPDP corpus expansion (access, correction, grievance, s.16 text) | EP F17, RM F-04 prerequisite |
| U13 | Pipeline hardening: bounded buffer + codec fuzz tests | RM F-07, PC A6 |
| U14 | Camera Check quality (full-resolution capture, blur/glare gate) | EP F15, BP W2 |
| U15 | Re-evaluate a saved audit with the current catalogue | RM F-09, EX T2.7, MS M5 |
| U16 | Repo hygiene and attribution (CI, ATTRIBUTIONS, CONTRIBUTING, CHANGELOG, demo script) | RM F-11, EX T1.4, MS M9, EP §5 |
| U17 | Test scaffolding for `app/` (UI-state tests) | EP §13.1 |
| U18 | Origin tag: lookups issued by Play services | RM F-08, PC A1, BP Review Focus #2 |
| U19 | Permissions-at-launch finding | RM F-10, EX T2.6, MS M6 |
| U20 | One-image evidence card | RM F-12, TC R3 |
| U21 | Explorer agent: LLM-driven tapping | BP W1, EP F25 |
| U22 | Declared-vs-observed manifest chips | RM F-13, EX T2.5, MS M4 |
| U23 | Encrypted-DNS resolver tripwire | RM F-14, PC A3 |
| U24 | NPU lane for explanations | BP A4, EP F19 |

Already covered and **not** separate work items: C1–C10/NC/V0 rules, fix screen and copy-fix, PDF/Markdown/JSON export, history list, sample audit, children toggle (C9), Hindi explanation, Mark-as-first-party, Mark-consent-now, AI Lab benchmark, preflight rows, Fast-demo profile. See §5.

---

## 4. Verified gap analysis and priority

### 4.1 Rubric mapping used for ranking

Weights from `hackathon_scoring.md` §2: **Q** end-product quality 30, **N** novelty/impact 20, **P** creative phone use 15 (HackTracker device data), **T** technical depth 15, **O** Office Kit 10 (HackTracker device data), **D** demo 10. Score each feature 0–5 per criterion; weighted total = Σ(weight × score) / 5, max 100. Phone-use and Office Kit are **measured from device data**, so a feature only earns those if it really exercises the camera, microphone, on-device model or Office Kit on the phone.

### 4.2 Ranking (highest first)

| Rank | ID | Feature | Q | N | P | T | O | D | **Total** | Status on `main` | Why this rank |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | U08 | Rights notice | 4 | 5 | 1 | 3 | 3 | 5 | **72** | ❌ | Only feature that turns our unique asset (timestamped, consent-linked evidence) into a user action. The shared notice also exercises the Office Kit clipboard. |
| 2 | U02 | Voice Copilot | 3 | 4 | 5 | 3 | 2 | 4 | **70** | ❌ | The only missing feature that earns *microphone* use and on-device AI in the live workflow (15 % device-measured). Listed "P0 for demo" in EP F16 and "never cut" in EP §15.4. |
| 3 | U07 | Disguised-tracker (CNAME) tag | 4 | 4 | 1 | 5 | 1 | 4 | **68** | ❌ | Fixes a real false-"clean" path (first-party shortcut precedes any alias check, `TrackerCatalog.kt:36`) and unlocks U10. Honest framing: correctness, not invention (TrackerControl uncloaks too). |
| 4 | U09 | Confidence grade | 3 | 4 | 1 | 5 | 1 | 4 | **62** | 🟡 (binary V0 only) | Visible "how sure are we" badge; strongest technical-depth story; replaces `MIN_EVENTS = 2`. |
| 5 | U01 | Device-verified core loop | 5 | 1 | 1 | 3 | 1 | 5 | **58** | ⚠️ | Not new code, but 40 % of the score (quality + demo) collapses if it fails on stage. **Gates everything**, so it runs first in time (§8) despite rank 5. |
| 5 | U05 | Network Proof | 3 | 3 | 2 | 4 | 1 | 4 | **58** | ❌ | The "0 bytes leave the phone" claim appears in the README, report and pitch (`Reports.kt:25`) but is **not measured anywhere** (grep for `TrafficStats`/`bytes` finds nothing). A judge can challenge it. |
| 7 | U10 | Country map | 3 | 3 | 1 | 3 | 1 | 5 | **54** | ❌ | Most instantly readable visual, but evidence is inferred and needs a licensed dataset, so it ranks below the evidence features. |
| 8 | U04 | Before/after comparison | 4 | 2 | 1 | 2 | 1 | 5 | **53** | ❌ (both flavours exist, no comparison view) | The pitch's closing beat (21 → 100). `README` claims it; no screen shows two audits side by side (grep `compar` finds only a sort). |
| 9 | U03 | Laptop Handoff completed | 3 | 1 | 2 | 1 | 5 | 3 | **47** | 🟡 | Copy-fix, Save to Downloads and Share exist. Missing: a send-patch action and an "open on laptop" guide. Cheap; supports the 10 % Office Kit criterion. |
| 9 | U14 | Camera Check quality | 3 | 2 | 5 | 2 | 0 | 3 | **47** | 🟡 | Camera is device-measured, but the preview-thumbnail capture will misread dense consent text. |
| 11 | U12 | DPDP corpus expansion | 3 | 2 | 0 | 3 | 0 | 2 | **39** | 🟡 (16 passages) | Prerequisite for an honest U08 beyond four cited ids. |
| 12 | U15 | Re-evaluate saved audit | 2 | 3 | 0 | 2 | 0 | 3 | **36** | ❌ | Good 20-second demo; storage already supports it. |
| 13 | U11 | Tracker data v2 | 3 | 1 | 0 | 3 | 0 | 2 | **35** | 🟡 (36 entities) | Credibility and provenance. Low wow. |
| 14 | U13 | Pipeline hardening | 3 | 0 | 0 | 3 | 0 | 3 | **33** | ❌ | Invisible, but protects the live demo. |
| 15 | U06 | Stage-mode completion | 2 | 0 | 0 | 1 | 0 | 4 | **23** | 🟡 (text-size toggle only) | Presentation polish; the larger-type part already exists. |
| 16 | U16/U17 | Hygiene, CI, app tests | 2 | 0 | 0 | 2 | 0 | 1 | **~17** | ❌ | Credibility for the code-reading jury; required by the commit rules anyway. |
| — | U18, U19, U20 | Origin tag, permissions finding, evidence card | | | | | | | optional | ❌ | §10 |
| — | U21–U24 | Explorer agent, manifest chips, DoH tripwire, NPU lane | | | | | | | stretch / risky | 🟡/❌ | §10 |

The Plan's cut order (EP §15.4) puts the explorer agent first to cut; this plan agrees.

### 4.3 Gap detail with evidence and remaining work

| ID | Status | Evidence on `main` | Remaining work |
|---|---|---|---|
| U01 | ⚠️ | `docs/VERIFICATION.md` "Remaining device checks"; 65 core tests pass (this audit) | Install `app`, `leakyshop-leaky`, `leakyshop-fixed` on an iQOO; run Full Audit on leaky (expect 21 "At risk") and fixed (expect 100 "DPDP-ready") as README states; record each failure as an issue with the screenshot. |
| U02 | ❌ | grep `voice\|speech\|RecognizerIntent\|RECORD_AUDIO\|TextToSpeech` over `app/src` finds only comments and `audioBackend` at `LlmEngine.kt:80` | Everything: on-device STT, grounded answer composer, TTS, screen, refusal guard, Hindi/English. |
| U03 | 🟡 | `ExportManager.copy/share/saveToDownloads` (`Exporters.kt:123–146`); `ResultScreens.kt:279,284` copy buttons | "Send patch + report" bundle, "Open on laptop" instructions, and a visible Office Kit hint. |
| U04 | ❌ | No comparison composable; `SampleAudit` is one audit | Pick two stored audits of the same package; show score, band and findings diff. |
| U05 | ❌ | `Reports.kt:25` states the claim; no measurement | Measure UID transmit bytes around an audit; subtract the DNS bytes the relay itself sent; show the remainder. |
| U06 | 🟡 | `XrayTheme(stageMode)` scales type 1.18× (`Theme.kt:88`), wired at `MainActivity.kt:57`; toggle "Presentation mode" at `ToolScreens.kt:188`; `SettingsStore.stageMode` | Pre-flight checklist view and model warm-up; nothing else. |
| U07 | ❌ | `DnsPacketCodec` question-only; `NetEvent` has no alias field; `classify()` shortcut at `TrackerCatalog.kt:36` | Answer-record reader (bounded), `NetEvent` fields, alias-aware classification, chip, fixture and one live alias. |
| U08 | ❌ | grep `notice` finds only finding-explanation text in `RulesEngine.kt` and the VPN notification helper | Builder, golden test, button, share. |
| U09 | 🟡 | `RulesEngine.kt:35,237` | `CaptureHealth` counters, `ConfidenceGrader`, badge, tests. |
| U10 | ❌ | grep `geo\|country\|mmdb` finds nothing outside `languageOptions` | Dataset choice + licence, offline lookup, aggregator, list, tile grid, reference-jurisdiction layer. |
| U11 | 🟡 | 36 entities / 52 domains (`trackers.json`); `TrackerEntry` has no source/docs/country fields; no Exodus/DDG data in `team_downloads/` | Schema v2, importer, per-source files, licence reading. |
| U12 | 🟡 | 16 passages | Add official-text passages (from the PDFs in `team_downloads/legal/`) for access, correction, grievance; s.16 only if a team lawyer-level read allows. |
| U13 | ❌ | `CaptureBus.kt` unbounded list; no fuzz test | Bounded buffer, drop counter, fuzz test. |
| U14 | 🟡 | `ToolScreens.kt:116` `TakePicturePreview` | Full-resolution capture via `TakePicture` + `FileProvider` (provider exists: `res/xml/file_paths.xml`); blur/glare gate. |
| U15 | ❌ | `AuditRepository` stores sessions with events | Re-classify stored hostnames; diff against stored result. |
| U16 | ❌ | file listing | `.github/workflows`, docs, `chmod +x gradlew` in the index. |
| U17 | ❌ | no `app/src/test` | Source set + first tests. |

---

## 5. Already implemented: do not rebuild

| Capability | Evidence | Caveat |
|---|---|---|
| Per-app DNS-only VPN, TTL-0, SERVFAIL | `XrayVpnService.kt`, `DnsPacketCodec.kt`, `DnsPacketCodecTest.kt` | Device unverified |
| Consent detection (EN/HI/KN), taps, manual mark | `ConsentLexicon.kt` + test, `ConsentAccessibilityService.kt` | WebView/canvas UIs unverified |
| Plans C/B/A, Shizuku fresh-state, fast-demo timing | `AuditOrchestrator.kt`, `ShizukuBridge.kt` | Needs Shizuku running on the phone |
| Rules C1–C10, NC, V0, score and bands | `RulesEngine.kt`, `RulesEngineTest.kt` (172 lines) | |
| Consent-screen audit with tree/VLM cross-check | `ScreenAuditing.kt`, `ScreenAuditTest.kt` | Needs model on device |
| Fix catalogue + copy buttons | `FixCatalog.kt`, `ResultScreens.kt:266–290` | |
| Citations via embeddings | `AiServices.kt`, `FixesCitationsReportTest.kt` | Only 16 passages |
| Report Markdown/JSON/PDF, share, save | `Reports.kt`, `Exporters.kt` | |
| History + sample audit | `AuditRepository.kt`, `SampleAudit.kt` | |
| Camera Check (basic) | `ToolScreens.kt:114` | Quality: U14 |
| AI Lab benchmark | `ToolScreens.kt:54`, `LlmEngine.kt` | NPU model not in `team_downloads` |
| Presentation mode (larger type) | `Theme.kt:88`, `SettingsStore.kt:11`, `ToolScreens.kt:188` | Checklist and warm-up still missing (U06) |
| Children toggle (C9), Hindi explanation, Mark first-party | `AppPickerScreen.kt`, `ResultScreens.kt:243,193` | |

Wording caution: the picker toggle says "Let the agent tap Accept and Reject" (`AppPickerScreen.kt:115`). The behaviour is lexicon-driven; do not describe it as an LLM agent in the pitch unless U21 is built.

---

## 6. Country map specification (U10)

### 6.1 Two different things the map can show: never mix them

| Layer | Meaning | Evidence class | Source | On-screen label |
|---|---|---|---|---|
| **A. Observed endpoint geography** (default) | Country of the IP address(es) in the DNS answer for a tracker hostname, split by consent phase | **Inferred** from observed DNS answers | A/AAAA records parsed by U07; offline IP-to-country table | "Servers answering these lookups appear to be in…" with an "inferred" tag |
| **B. Company jurisdiction** (optional toggle) | Country where the tracker company is registered/headquartered | **Reference data**, not evidence of anything the app did | New `hqCountry` field per entity in the tracker data (U11), hand-verified from each company's public filing or website, with a source URL; `null` if unverified | "Company registered in…". Outlined tiles; never coloured by consent phase |

Layer A says *where servers answered*; layer B says *who owns them*. A CDN-hosted tracker can answer from a country unrelated to its owner; showing both side by side is informative precisely because they differ.

### 6.2 What the map must not claim
- No sentence such as "data was transferred to X", "cross-border transfer", or "violates s.16". The DNS evidence shows a lookup was answered, not that personal data was sent. The corpus has no s.16 passage, so no legal conclusion is available.
- A country backed only by CDN-looking hosts is shown as "uncertain" or omitted, never asserted.
- Unknown IP → omitted, never guessed. IPv6 is out of scope for v1 and reported as "not mapped".

### 6.3 Data source and licence (blocking decision D4)
- TrackerControl bundles a GeoLite2 `.mmdb` and an SVG map credited to a third party. **Do not reuse either file.**
- Candidate offline sources, none confirmed here: a free monthly country-level IP database such as DB-IP Lite, or IP2Location LITE, or MaxMind GeoLite2. My recollection is that the first two are attribution-licensed and that GeoLite2 needs an account and has its own licence terms, but **this is from memory and unverified**.
- Before any data commit, the owner must (1) open the licence text of the chosen source, (2) record name, version, URL, licence and required attribution in `ATTRIBUTIONS.md`, (3) confirm redistribution inside an MIT app is allowed. If no source passes, ship layer B alone (reference data) plus a list of resolved IPs without country, and say so.
- Generate our own compact table (sorted IPv4 ranges → ISO-2 code, binary-searched) with a script kept in `tools/`; ship only the table.

### 6.4 Minimal UI
- A card on the Results screen: title "Where tracker lookups were answered", one row per country, split into "before choice / after Accept / after Reject" counts, red dot for after-Reject. Tap → hostnames.
- Tile grid second (hand-placed squares in a Compose `Canvas`, no SVG parser, no map library); if time is short the list ships alone.
- Mirrors to the laptop via Office Kit like any other screen.

### 6.5 Acceptance tests
1. Core test: three hosts with fixed IPs and phases aggregate to the expected `country → phase → hosts` map.
2. Unknown IP → absent; CDN-flagged host → `uncertain = true`.
3. UI renders with 0, 1 and 20 countries.
4. Text audit: the strings in the card contain none of "transfer", "violat", "illegal".

---

## 7. Ownership rules that prevent conflicts

1. **One owner per file** (table in §9). Nobody edits another owner's file; they request a change in a PR comment or in the contract commits below.
2. **New code goes in new files.** Each feature adds its own files and, where a UI screen is needed, ships a **self-contained composable with its own ViewModel**. Nitya mounts it with a one-line change to a hot file she owns.
3. **Hot files (Nitya only):** `MainActivity.kt` (routes), `XrayApp.kt`, `XrayViewModel.kt`, `HomeScreen.kt`, `ResultScreens.kt`, `LiveAuditScreen.kt`, `Components.kt`, `AuditOrchestrator.kt`, `data/*`, `report/*`, `app/build.gradle.kts`.
4. **`Model.kt` is edited once** by Agrim in contract commit K03, as additive fields with defaults. All later types live in new files.
5. **Slot contracts** (what Nitya mounts):

| Slot | Producer | Signature (names are proposals) | Mounted in |
|---|---|---|---|
| Alias chip data | Agrim | `NetEvent.disguisedVia: String?` | `LiveAuditScreen`, `FindingDetailScreen` |
| Confidence badge data | Agrim | `ConfidenceGrader.grade(CaptureHealth, List<NetEvent>) : ConfidenceGrade{level, reasons}` | `ResultsScreen` |
| Notice | Agrim | `RightsNoticeBuilder.build(finding, sessions, targetLabel, nowMs) : RightsNotice?` (null when no C2) | `FindingDetailScreen` |
| Map card | Yashvi | `@Composable EndpointMapCard(summary: EndpointGeoSummary, modifier)` | `ResultsScreen` |
| Network Proof card | Yashvi | `@Composable NetworkProofCard(proof: NetworkProof, modifier)` | `HomeScreen`, report |
| Voice screen | Yashvi | `@Composable VoiceCopilotScreen(auditId: String?, onBack: () -> Unit)` with its own `VoiceViewModel` | route `voice` |
| Re-evaluate | Agrim | `Reevaluator.run(bundle sessions, catalog) : ReevalDiff` | `ResultsScreen` button |

6. **Branches:** each person works on a new branch from `main` (`agrim/evidence-engine`, `yashvi/phone-experience`, `nitya/integration`). The three existing remote branches are ancestors of `main` and carry no work. Merge commits, not squash (`hackathon_scoring.md` §8.1). Rebase only before a branch is shared. Own identity on every commit; no backdating.
7. **Commit style:** `type(scope): summary`. Commit every 30–45 min of active work; push hourly.

---

## 8. Three workstreams

Time pressure (from the printed schedule, §6 of Scoring): remaining build windows are Sat 00:00–03:00 🟢, Sat 03:00–04:00 🔴, [rest], Sat 06:00–08:00 🟢, Sat 08:00–09:45 🔴 (stabilise for ER1 at 10:00), Sat 12:00–14:00 🟢, 14:00–15:30 🔴, 15:30–16:30 🟢, 16:30–18:30 🔴 (freeze 18:30 for ER2 at 19:00), Sat 22:00–01:00 🔴, Sun 01:00–03:00 🟢, [rest], Sun 06:00–06:30 🟢, 06:30–08:30 🔴, code freeze 08:30. That is roughly **22 h of build per person**, minus evaluation blocks and sleep. Workloads below are sized so the **P0/P1 core** fits in about 17–19 h each, with the remainder explicitly cuttable.

### 8.1 Agrim: Evidence engine (`core/` evidence logic + `capture/`)

Order is highest judge impact first, subject to what each item needs.

| # | ID | Deliverable | Priority | Depends on | Files (all Agrim-owned) | Acceptance test | Effort |
|---|---|---|---|---|---|---|---|
| A1 | U07 (contract) | Additive fields: `NetEvent.cnameChain: List<String>`, `answerIps: List<String>`, `disguisedVia: String?`; `AuditSession.captureHealth: CaptureHealth?`; new `core/.../model/CaptureHealth.kt` | P0 | none | `core/.../model/Model.kt` (this edit only), new `CaptureHealth.kt` | Old `sessions.json` without the new keys still decodes (core test); all 65 existing tests still pass | S |
| A2 | U07 | `DnsAnswers` reader: A/AAAA/CNAME from a reply, CNAME chain capped (propose 8), compression-pointer loops and truncation return a partial result | P0 | A1 | new `core/.../dns/DnsAnswers.kt`, tests in `dns/TestPackets.kt` + new `DnsAnswersTest.kt` | Golden packets: chain of 2, A+AAAA, pointer loop, truncated, zero-length record → expected result, never throws | M |
| A3 | U07 | Attach answers to events | P0 | A2 | `capture/XrayVpnService.kt`, `capture/DnsForwarder.kt` | Instrumented smoke on device (Nitya): events from a lookup carry `answerIps`; unit-level: forwarder passes reply bytes to `DnsAnswers` | S–M |
| A4 | U07 | Alias-aware classification **before** the first-party shortcut; flag `disguisedVia` | P0 | A1, fixtures (K07) | `trackers/TrackerCatalog.kt`, `rules/RulesEngine.kt` (evidence wording only) | (1) alias to a catalogue tracker → that entity + `disguisedVia`, even if the alias host is user-marked first-party; (2) non-alias lookups unchanged; (3) leaky/fixed golden scores 21/100 unchanged; (4) wording says "alias observed", not "data sent" | M |
| A5 | U09 | Bounded event buffer + counters (queries, malformed, dropped, first/last lookup time, DNS bytes relayed) | P0 | A1 | `capture/CaptureBus.kt`, `capture/DnsForwarder.kt` | Buffer caps at N (propose 5,000) and counts drops; counters correct in unit test | S–M |
| A6 | U09 | `ConfidenceGrader` (pure function, named thresholds, reason strings listing the numbers used) | P0 | A1 | new `core/.../score/ConfidenceGrader.kt` + test | Zero events → Low and V0 behaviour preserved; boundary tests at each threshold; reason string cites the measured values. Starting thresholds are proposals to tune on the phone, not facts | M |
| A7 | U12 | Corpus: add access/correction/grievance passages from the official PDFs; keep ids stable | P1 | none (data) | `resources/dpdp/passages.json`, `core/.../dpdp/Dpdp.kt` if a loader change is needed | Existing citation tests pass; every new id traces to a section of `team_downloads/legal/*.pdf` (checked by hand); no s.16 text unless read in full | M |
| A8 | U08 | `RightsNoticeBuilder`: names app, package, date, the time the user tapped Reject, each tracker contacted after it and the delay in seconds; cites only corpus ids that exist | P0 | A1 (types), A7 for any new ids | new `core/.../notice/RightsNotice.kt`, `RightsNoticeBuilder.kt`, test | Golden-text test on the sample leaky audit; citation-id check fails if an id is absent from `passages.json`; no C2 → returns null; no sentence asserts a violation or that personal data was sent (request wording: "please confirm") | M |
| A9 | U11 | Tracker schema v2: `source`, `docUrls`, `hqCountry` + `hqSource`, per-source files; importer script | P1 | licence reading | `trackers/TrackerCatalog.kt` (entry type), `resources/trackers/*`, `tools/import-trackers.*` | Existing classifier tests pass; importer prints per-source counts; no network call to Exodus from the app; any Exodus-derived data in its own file with attribution | M–L |
| A10 | U13 | Fuzz test of the DNS codec and answer reader | P1 | A2 | new `core/src/test/.../dns/DnsFuzzTest.kt` | 10,000 random/truncated packets: no exception, completes within a time limit | S |
| A11 | U15 | `Reevaluator` (re-classify stored hostnames; diff findings) | P2 | A4, A9 | new `core/.../reeval/Reevaluator.kt` + test | Add a catalogue entry in a test; a stored leaky session shows the new tracker without a re-run | S–M |
| A12 | U18 | Origin tag (Play services) after an on-device spike | P2 / ⚠️ | on-device spike | `capture/XrayVpnService.kt` | On device a lookup from Play services is tagged; unknown origin stays null | M |
| A13 | U19 | Permissions-at-launch finding (read `PackageManager`, rule) | P2 | A1 | new `core/.../rules/PermissionRule.kt`, `capture/` reader | Fake permission list → expected finding, labelled inferred | M |

**Agrim's core total A1–A10: ≈ 20–22 h** (S=1.5, M=4, L=7). If hours run short: cut A9's importer (keep schema + hand-curated `hqCountry`), then A10, then A7.

### 8.2 Yashvi: Phone experience (`app/` new screens, `ai/`, `leakyshop/`, geo)

| # | ID | Deliverable | Priority | Depends on | Files (all Yashvi-owned) | Acceptance test | Effort |
|---|---|---|---|---|---|---|---|
| Y1 | U02 | **Voice Copilot.** (a) On-device speech-in with an availability check; (b) answer composer that uses only the audit's findings plus top-3 retrieved passages and refuses outside the evidence; (c) speech-out; (d) English and Hindi; (e) screen with transcript | P0 | none (uses existing `FindingExplainer`/`EmbeddingRetriever`) | new `app/.../voice/*`, new `VoiceCopilotScreen.kt`, new `VoiceViewModel.kt`, pure logic in new `core/.../copilot/GroundedAnswer.kt` + test, `AndroidManifest.xml` (add `RECORD_AUDIO`: **coordinate with Nitya, who owns the manifest**, or add via a one-line request) | Core test: "is this safe for children?" on a sample audit → answer cites C9/s9 passage ids present in the audit; an out-of-scope question → "I have no evidence about that in this audit"; manual: speak a question on the phone, hear an answer; **if on-device recognition is unavailable the mic button is disabled with a reason, never a silent cloud fallback (D3)** | L |
| Y2 | U10 | **Country map, layer A.** `GeoLocator` (offline lookup), `EndpointGeoAggregator`, `EndpointMapCard` list view | P1 | A2 types via K05; licence (D4) for the real table | new `core/.../geo/*`, `core/src/main/resources/geo/`, `tools/build-geo-table.*`, new `app/.../ui/screens/map/EndpointMapCard.kt` | See §6.5 | L (list M, data M, tiles S–M) |
| Y3 | U05 | **Network Proof.** Measure TraceVeil's own transmitted bytes over an audit (`TrafficStats` by UID); subtract DNS bytes relayed (Agrim's counter A5); show "X bytes sent by TraceVeil; Y of them were the DNS lookups relayed to your resolver" | P1 | A5 | new `app/.../netproof/*`, `NetworkProofCard.kt` | Run an audit with the app's Wi-Fi/mobile data on: shown remainder is within a stated tolerance of zero (record the actual number in `docs/`); unit test of the arithmetic with fake counters. **Do not hard-code "0"** | M |
| Y4 | U14 | Camera Check quality: full-resolution `TakePicture` into app cache via the existing `FileProvider`; blur/glare check (variance of Laplacian or brightness histogram) with a retake prompt | P1 | none | `ui/screens/ToolScreens.kt` (Yashvi owns the whole file, including AI Lab and Settings), manifest/`file_paths.xml` only if needed | Photograph a dense consent screen from the laptop: full-resolution file saved; a deliberately blurred photo is rejected with a message | M |
| Y5 | U04 | LeakyShop leaky flavour: one aliased host (only if the team-controlled alias exists, D5) and keep the fixed flavour clean | P1 | D5 | `leakyshop/**` | Leaky audit shows the alias chip; fixed audit unchanged (score 100) | S |
| Y6 | U10 | Tile grid and layer B (company jurisdiction toggle) | P2 | Y2, A9 (`hqCountry`) | `EndpointMapCard.kt`, new `JurisdictionLayer.kt` | Layer toggle changes heading text; layer-B tiles are outlined and never phase-coloured | M |
| Y7 | U24 | NPU lane check (only if an NPU model is loaded) | P3 / ⚠️ | model file | `ai/*` | Benchmark shows real numbers or says "no NPU model" | S |
| Y8 | U21 | LLM explorer agent | P3 / risky | — | `ai/*`, `audit/` (Nitya) | Not scheduled | L |

**Yashvi's core total Y1–Y5: ≈ 24 h.** Heavy; cut order: Y6 → Y5 → Y4 → tile grid inside Y2 → Y3.

### 8.3 Nitya: Integration, validation, delivery (`app/` hot files, `audit/`, `data/`, `report/`, docs, CI)

Starts at once on work that touches no one else's files, then integrates as Agrim and Yashvi's slots land.

| # | ID | Deliverable | Priority | Depends on | Files (Nitya-owned) | Acceptance test | Effort |
|---|---|---|---|---|---|---|---|
| N1 | D1 / U16 | Record the organiser ruling and the AI-use disclosure; update `PROVENANCE.md` / README accordingly | P0 | organiser reply | `PROVENANCE.md`, `README.md` | Ruling text and date present, or a clear "pending" line | S |
| N2 | U01 | **Device verification** of the baseline on the iQOO (install three APKs, run leaky and fixed, Full Audit, camera, report export, Office Kit copy/transfer); file one issue per defect | P0 | none | `docs/VERIFICATION.md` (append a dated device section) | Leaky = 21 / At risk, fixed = 100 / DPDP-ready on device, or recorded deviations with screenshots | M |
| N3 | U16 | `chmod +x gradlew` in the index (`git update-index --chmod=+x gradlew`), `.github/workflows/core-tests.yml` (runs `:core:test`), `ATTRIBUTIONS.md`, `CONTRIBUTING.md` (clean-room rule: no GPL source open while writing a feature), `CHANGELOG.md` | P0 | none | those files | CI green on a PR; `./gradlew` runs on Linux | S–M |
| N4 | U17 | Unit-test source set for `app/` and first state tests | P1 | none | `app/build.gradle.kts`, `app/src/test/**` | `./gradlew :app:testDebugUnitTest` passes | S–M |
| N5 | U07/U09/U08 UI | Alias chip (timeline + finding detail), confidence badge (result), "Draft notice to developer" button with copy/share (C2 findings only) | P0 | slot data from A1/A4/A6/A8; fixtures K07 until then | `LiveAuditScreen.kt`, `ResultScreens.kt`, `Components.kt`, `XrayViewModel.kt` | Chip shows "alias → tracker.example"; badge shows level + reason string; button absent without C2; notice text identical to the builder's golden file | M–L |
| N6 | U09 | Persist `CaptureHealth` into the saved session at audit end | P0 | A5 | `audit/AuditOrchestrator.kt` | Saved `sessions.json` contains `captureHealth`; old audits still open | S |
| N7 | U10, U05, U02 | Mount `EndpointMapCard`, `NetworkProofCard`, the `voice` route and entry button | P1 | Y2, Y3, Y1 | `MainActivity.kt`, `ResultScreens.kt`, `HomeScreen.kt` | Each slot renders; no change to another owner's file | S |
| N8 | U04 | Before/after comparison screen over two stored audits of one package | P1 | N2 (both audits exist) | new `ui/screens/CompareScreen.kt`, route in `MainActivity.kt` | Leaky(21) vs fixed(100) shows score delta and which findings disappeared | M |
| N9 | U03 | Handoff completion: "Send patch + report" (files into `Downloads/TraceVeil`), "Open on laptop" guide, Office Kit hint | P1 | none | `ResultScreens.kt` (FixesScreen/ReportScreen), `report/Exporters.kt` | Copy-fix pastes into a laptop editor through Office Kit clipboard; files appear on the laptop | S–M |
| N10 | U06 | Finish stage mode: pre-flight checklist view and model warm-up (large type already exists) | P2 | none | `HomeScreen.kt`, `data/SettingsStore.kt` (the existing toggle sits in Yashvi's `ToolScreens.kt`; do not move it) | Checklist lists every preflight row; first Gemma call after warm-up is faster than a cold call (record both numbers) | S |
| N11 | U15 | Re-evaluate button wired to `Reevaluator` | P2 | A11 | `ResultScreens.kt` | Shows the diff in a dialog | S |
| N12 | all | Integration passes, golden-result regression (leaky 21 / fixed 100), release APKs, demo script, backup video, `demo-stable` tag | P0 | all slots | `docs/demo-script.md`, tags | See §9 gates | M–L |
| N13 | U20 | Evidence card (one image) | P3 | A6 | new `report/EvidenceCard.kt` | Non-empty bitmap; text derives from the same assessment object as the report | S–M |

**Nitya's total ≈ 23–26 h.** Cut order: N13 → N11 → N10 → N8 (only if the leaky/fixed demo is retold verbally) → N9.

---

## 9. Bottom-up commit and merge sequence

Rule: contract commits first, then independent implementations on three separate branches, then integration. "Gate" = on `main` before dependants begin real work. Where a dependency is only for the final data, the dependant works against fixtures (K07) and swaps in real data at integration.

| # | Owner | Commit (type(scope): summary) | Depends on | Window | Gate |
|---|---|---|---|---|---|
| K00 | Nitya | `docs(provenance): record eligibility ruling status` | organiser reply | any | |
| K01 | Nitya | `chore(build): make gradlew executable; add core-test workflow` | none | 🔴 now | |
| K02 | Nitya | `docs: add ATTRIBUTIONS, CONTRIBUTING (clean-room rule), CHANGELOG` | none | 🔴 now | |
| K03 | Agrim | `feat(model): add NetEvent alias/answer fields and AuditSession.captureHealth with defaults` | none | Sat 00:00 🟢 | **G0** (Agrim, Yashvi, Nitya) |
| K04 | Agrim | `test(model): old saved audits without new fields still decode` | K03 | 🟢 | |
| K05 | Yashvi | `feat(geo): add EndpointGeo types and GeoLocator interface` | K03 | 🟢 | |
| K06 | Agrim | `feat(notice): add RightsNotice type and builder signature` | K03 | 🟢 | |
| K07 | Nitya | `feat(sample): SampleAudit alias and endpoint fixtures` | K03, K05 | 🟢 | **G1: fixtures; Yashvi and Nitya stop waiting on Agrim** |
| K08 | Agrim | `test(dns): golden replies with CNAME chain, A/AAAA, loops, truncation` | K03 | 🟢 | |
| K09 | Agrim | `feat(dns): bounded answer-record reader` | K08 | 🟢 | |
| K10 | Agrim | `feat(capture): bounded event buffer with health counters` | K03 | 🟢 | |
| K11 | Yashvi | `feat(voice): on-device speech wrapper with availability check` | none | 🟢 | |
| K12 | Yashvi | `test(copilot): grounded-answer and refusal cases` | none | 🟢/🔴 | |
| K13 | Yashvi | `feat(copilot): grounded answer composer` | K12 | 🟢/🔴 | |
| K14 | Nitya | `test(app): add unit-test source set and first state tests` | none | 🟢 | |
| K15 | Agrim | `feat(capture): attach answer records to NetEvent` | K09, K10 | Sat 06:00 🟢 | |
| K16 | Agrim | `test(trackers): alias cases and first-party ordering` | K03, K07 | 🟢 | |
| K17 | Agrim | `feat(trackers): alias-aware classification before first-party shortcut` | K16 | 🟢 | **G2: evidence core** |
| K18 | Agrim | `test(score): confidence grader thresholds` → `feat(score): ConfidenceGrader` (two commits) | K10 | 🟢/🔴 | |
| K19 | Yashvi | `feat(voice): VoiceCopilotScreen and ViewModel` | K11, K13 | 🟢 | |
| K20 | Yashvi | `test(geo): aggregation fixtures` → `feat(geo): aggregator and list model` | K05, K07 | 🟢/🔴 | |
| K21 | Nitya | `feat(app): alias chip on timeline and finding detail` | K07 (fakes) then K17 | 🔴/🟢 | |
| K22 | Nitya | `feat(app): confidence badge on result screen` | K07, then K18 | 🟢 | |
| K23 | Nitya | `feat(audit): persist CaptureHealth with the session` | K10 | 🟢 | |
| K24 | Nitya | `feat(app): mount voice route` | K19 | 🟢 | |
| **I1** | all | **Integration pass 1:** smoke on the iQOO, golden scores unchanged (21 / 100); freeze 09:15; `er1-pre` tag | K15–K24 | Sat 08:00–09:45 | **ER1 at 10:00 from `demo-stable`** |
| K25 | Agrim | `data(dpdp): add official-text passages` → `test(notice): golden text and citation check` → `feat(notice): builder` | K06 | Sat 12:00 🟢 | |
| K26 | Nitya | `feat(app): draft-notice button with copy and share` | K25 (or fakes) | 🟢 | |
| K27 | Yashvi | **Licence check recorded**, then `data(geo): generate country table` → `feat(geo): offline GeoLocator` | D4 | 🟢/🔴 | |
| K28 | Yashvi | `feat(app): endpoint country list card` | K20, K27 | 🔴 | |
| K29 | Yashvi | `feat(netproof): measured upload card` | K10 | 🔴 | |
| K30 | Nitya | `feat(app): mount map and network-proof cards` | K28, K29 | 🟢 15:30 | |
| K31 | Agrim | `feat(trackers): schema v2, provenance, hqCountry; per-source files` | licence reading | 🟢/🔴 | |
| K32 | Yashvi | `feat(camera): full-resolution capture and quality gate` | none | 🔴 | |
| **I2** | all | **Integration pass 2:** real data replaces fakes; device smoke; freeze 18:30; `er2-pre` | K25–K32 | Sat 15:30–18:45 | **ER2 at 19:00** |
| K33 | Nitya | `feat(app): before/after comparison screen` | I1 | Sat 22:00 🔴 | |
| K34 | Nitya | `feat(app): send-patch, open-on-laptop guide` | none | 🔴 | |
| K35 | Yashvi | `feat(app): tile grid and jurisdiction layer` | K28, K31 | 🔴/🟢 | |
| K36 | Yashvi | `feat(leakyshop): aliased host in leaky flavour` | D5 | 🟢 Sun 01:00 | |
| K37 | Nitya | `feat(app): stage checklist and model warm-up` | none | 🟢 | |
| K38 | Agrim | `test(dns): fuzz codec and answer reader` | K09 | 🟢 | |
| K39 | Agrim / Nitya | P2: `Reevaluator` + button; origin tag; permissions finding | spikes pass | 🟢 | |
| K40 | Nitya | `test(app): golden results leaky 21 / fixed 100` | I2 | 🟢 | |
| **I3** | all | **Integration pass 3 and freeze:** feature freeze Sun 06:30, bug-fix PRs only to 08:00, code freeze 08:30, tag `submission`, backup video, release APKs | everything | Sun 06:00–08:30 | **ER3 at 09:00** |

**Parallelism.** After G0, Agrim (K04–K10), Yashvi (K11–K13) and Nitya (K14, N2 device work) share no files. After G1, every UI item has fixtures. Only the three integration passes serialise work.

**What must be on the phone at each evaluation** (anything not merged by the freeze is not shown):
- **ER1 (Sat 10:00):** verified core loop (U01), alias chip and confidence badge if K17/K18 landed, Voice Copilot skeleton if K19 landed. Do not demo anything not on `demo-stable`.
- **ER2 (Sat 19:00):** adds the rights notice, country map list, Network Proof, camera quality.
- **ER3 (Sun 09:00):** adds before/after, handoff, stage mode, and any P2 items that passed their tests.

---

## 10. Optional, risky or low-scoring work: labelled, not dropped

| ID | Item | Label | Reason | Decision |
|---|---|---|---|---|
| U18 | Origin tag (Play services) | **Risky** (device-dependent) | `viaPackage` exists with no producer; owner-lookup behaviour for UDP on the iQOO is unverified. Only matters if "Include Play services" is on in the demo | A12, spike first |
| U19 | Permissions-at-launch finding | **Optional** | New finding class; wording risk (do not claim a breach) | A13, P2 |
| U20 | Evidence card | **Optional, low scoring** | Polish | N13 last |
| U21 | LLM explorer agent | **Risky** | Tap loop over the accessibility tree is brittle; the deterministic tapper already works | Not scheduled |
| U22 | Manifest declared-vs-observed chips | **Risky**, Plan non-goal (static analysis) | High depth but off-plan and time-hungry | Park |
| U23 | Encrypted-DNS resolver tripwire | **Risky** | Needs routing experiments on the device; `DeviceChecks` already detects Private DNS | Park (spike if time) |
| U24 | NPU lane | **Risky** (no NPU bundle in `team_downloads`) | AI Lab already shows CPU/GPU numbers | Y7 only if a model is present |
| — | Companion console / control channel | **Rejected** | Office Kit mirroring covers the laptop story; not what HackTracker measures | PC A4 |
| — | Blocking/firewall, DoH client, WireGuard, full TCP/IP stack, MITM, PCAP/HAR export | **Rejected** | Turns the product into TrackerControl/PCAPdroid; contradicts the "DNS only, minimal evidence" design | — |
| — | Signed offline catalogue, CLI/CI mode, population statistics, fuzzy search, simulate-block, Data-Safety diff | **Deferred / rejected** | Not demo-visible, off-plan, or contradict "0 bytes leave" | See `COMBINED_FEATURE_ROADMAP.md` §6 |

---

## 11. Definition of done (every feature)

A feature is **done** only when all of the following hold:
1. A user-visible outcome exists on the phone (or a mirrored laptop screen), reachable in ≤ 3 taps from Home.
2. A reproducible test exists: a core unit test for pure logic, plus a written manual step with an expected result for device behaviour.
3. Leaky = 21 and fixed = 100 are unchanged (regression, K40).
4. Wording passes the honesty check: observed vs inferred labelled; no sentence claims a payload was sent or a law was broken.
5. Licence/attribution recorded for any data or library added.
6. A one-sentence judge explanation is in `docs/demo-script.md`.

| ID | Visible outcome | Reproducible test | One-sentence judge explanation |
|---|---|---|---|
| U01 | Full Audit on LeakyShop ends at 21 "At risk"; fixed at 100 "DPDP-ready" | Manual on iQOO + core tests green | "It audits a real app on the phone and the score moves from 21 to 100 after the fix." |
| U02 | Mic button; spoken question → spoken answer with section ids; refuses off-topic | Core grounded-answer tests + manual Hindi and English question | "Ask the audit a question out loud, in Hindi or English; the answer only uses evidence from that audit." |
| U03 | "Copy fix" pastes on the laptop; report + patch appear in laptop Downloads | Manual via Office Kit | "From finding to fix in the developer's editor without leaving the phone." |
| U04 | Side-by-side leaky vs fixed card | Two stored audits → expected deltas | "The same app before and after the fix." |
| U05 | "TraceVeil sent X bytes during this audit; all but Y were DNS lookups" | Arithmetic test + recorded measurement | "We measured the 'nothing leaves the phone' claim instead of asserting it." |
| U06 | Stage mode with checklist and warmed model | Checklist lists every preflight row; recorded cold vs warm latency | "Readable from the back of the room." |
| U07 | Chip: "metrics.shop.test → tracker.example (alias)" | Golden packets; fixture; one live alias | "A tracker hidden behind a harmless-looking name is caught." |
| U08 | "Draft notice to developer" with times and companies | Golden text + citation-id check | "One tap turns the evidence into a dated request the user can send." |
| U09 | Badge "Evidence confidence: High — 41 lookups, none malformed" | Threshold tests | "The tool says how sure it is, and why." |
| U10 | Country card: "answered from US, IE, DE (inferred)" split by consent phase | §6.5 | "Where the tracker servers are, before and after the user said no, labelled as inference." |
| U11 | Footer "Tracker data: N entries; sources; licences" | Importer counts | "Every tracker claim links to its source." |
| U12 | Notice and citations draw on more official passages | Passage-id test | "Citations come from the official text, not memory." |
| U13 | None visible | Fuzz test | "It does not crash on bad packets." |
| U14 | Retake prompt on blur; sharp photo audited | Manual with blurred and sharp photos | "Point the camera at any consent screen and it tells you what's wrong." |
| U15 | "Re-evaluate" shows new findings | Catalogue-addition test | "Old audits improve when the tracker list does." |
| U16 | CI green; attribution file | CI run | "Every dependency and idea is credited." |
| U17 | None visible | `:app:testDebugUnitTest` | "UI logic is tested." |

---

## 12. Team execution table

| | **Agrim: Evidence engine** | **Yashvi: Phone experience** | **Nitya: Integration and validation** |
|---|---|---|---|
| Features (priority order) | U07 data path, U09 collect + grade, U08 builder (+U12), U11 schema, U13 fuzz; P2: U15, U18, U19 | U02 Voice, U10 map (list → data → tiles → jurisdiction), U05 Network Proof, U14 camera quality, leaky alias host | U01 device verification, U16 hygiene, U07/U09/U08 UI, mounts, U04 comparison, U03 handoff, U06 stage mode; P2: U15 button, U20 |
| Owns (single owner) | `core/**/dns,trackers,rules,score,notice,dpdp,reeval`, `core/src/main/resources/{trackers,dpdp}`, `capture/**`, `Model.kt` (K03 only) | `app/**/voice,netproof,ui/screens/map`, `ToolScreens.kt`, `ai/**`, `core/**/geo,copilot`, `core/src/main/resources/geo`, `leakyshop/**` | `MainActivity.kt`, `XrayApp.kt`, `XrayViewModel.kt`, `HomeScreen.kt`, `ResultScreens.kt`, `LiveAuditScreen.kt`, `Components.kt`, `theme/**`, `audit/**`, `consent/**`, `control/**`, `data/**`, `report/**`, `app/build.gradle.kts`, manifest, `.github/**`, docs |
| First action (now) | Branch `agrim/evidence-engine`; K03 at 00:00 | Branch `yashvi/phone-experience`; K11 (needs nothing) | Branch `nitya/integration`; K01, K02, N2 device checks, ask the organisers (N1) |
| Starts after | Nothing (K03 is first) | G0 for K05; K11 immediately | Nothing for N1–N4; G1 for UI wiring |
| Hands to others | Gate G0 types; `answerIps`/`disguisedVia`; `CaptureHealth`; `ConfidenceGrader`; `RightsNoticeBuilder` | `EndpointMapCard`, `NetworkProofCard`, `VoiceCopilotScreen` | Fixtures (K07), mounts, regression goldens, release |
| Core effort | ≈ 20–22 h | ≈ 24 h | ≈ 23–26 h |
| Cut order if short | A13 → A12 → A11 → A10 → A9 importer → A7 | Y6 → Y5 → Y4 → map tiles → Y3 | N13 → N11 → N10 → N8 → N9 |
| Biggest risk | Resolver may return flattened CNAMEs (**unverified** on the iQOO); alias demo needs a controlled name (D5) | On-device speech recognition availability (D3); geo dataset licence (D4) | Breadth; the baseline's device behaviour is unproven (N2 may reveal defects that outrank new features) |
| Done when | `:core:test` green with the new golden and fuzz tests; goldens 21 / 100 unchanged | Voice and map slots render on the phone; Network Proof number recorded | Three evaluation snapshots tagged; `submission` tag at ≈08:30 Sun |

### Immediate next 60 minutes
1. **Agrim:** create branch, write K03 and K04, push at 00:00.
2. **Yashvi:** create branch, start K11 (speech wrapper) and K12 (tests) with no dependency on anyone.
3. **Nitya:** make `gradlew` executable (K01), add ATTRIBUTIONS/CONTRIBUTING/CHANGELOG (K02), then install the three APKs and begin N2; send the eligibility question to the organisers (N1).

### What I could not verify
- Whether any feature works on the iQOO. No device was available to this audit.
- Whether `:app` and LeakyShop still build today (not reproduced here).
- Whether the iQOO's resolver returns un-flattened CNAME chains.
- Whether on-device speech recognition (including Hindi) is available on the loaner phones.
- The licence terms of every candidate geography or tracker dataset. No dataset has been downloaded.
- The effort numbers and rubric scores: they are estimates and judgement.
