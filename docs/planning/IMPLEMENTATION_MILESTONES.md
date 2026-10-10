# TraceVeil: Implementation Milestones (build now)

Source: `FEATURE_GAP_AND_TEAM_PLAN.md` (revision 2). This file lists only the features being built. Cut items, audit evidence, scoring tables and the licence register stay in that document.
Repo: `IQOO-Kernel-Panic`, branch `main`, modules `core/`, `app/`, `leakyshop/`. Effort is an estimate (S ≈ 1–2 h, M ≈ 3–5 h, L ≈ 6–9 h).

## Rules for everyone
- **Deadlines (IST):** ER1 Sat 10:00 (no risky merges after 09:15) · ER2 Sat 19:00 (freeze 18:30) · **feature freeze Sun 06:30** · **code freeze Sun 08:30** · done by 09:00.
- Each person works on their own branch from `main`, commits under their own identity, with real timestamps. Merge commits, not squash. No force-push to `main`.
- One owner per file. New code goes in new files. Edit another person's file only by asking them in a PR comment.
- Nothing external (dataset, library, service) is added until its licence is read and recorded in `ATTRIBUTIONS.md`.
- Every feature must keep leaky = **21** and fixed = **100** unchanged.
- Wording: say "observed" or "inferred". Never claim data was sent or a law was broken.

## The features (impact order)
| # | Feature | What the judge sees |
|---|---|---|
| 1 | Rights notice | One tap drafts a dated request naming the trackers contacted after the user pressed Reject |
| 2 | Voice Copilot | Ask the audit a question (spoken or tapped); spoken answer using only that audit's evidence |
| 3 | Disguised-tracker tag | Chip "metrics.shop.test → tracker.example (alias)" |
| 4 | Confidence badge | "Evidence confidence: High: 41 lookups, none malformed" |
| 5 | Device-verified core loop | Full Audit on the iQOO: leaky 21, fixed 100 |
| 5 | Network Proof | "TraceVeil sent X bytes; all but Y were DNS lookups" (measured) |
| 7 | Country map | Where tracker lookups were answered, by consent phase, kept apart from where the company is registered |
| 8 | Before/after card | "+79 points, N findings resolved" |
| 9 | Company country data | Each tracker company's country with a source link |
| — | Camera Check | **Kept** (team decision, 10 Oct): the Home button, the camera screen and its code stay; the removal is cancelled |

---

## Agrim: Evidence engine
Owns: `core/**` dns, trackers, rules, score, notice; `core/src/main/resources/trackers`; `app/.../capture/**`; `Model.kt` (one edit only).

| Milestone | Deliverable | Files | Done when | Needs | Effort |
|---|---|---|---|---|---|
| **A0** (prereq, first) | Add `NetEvent.cnameChain`, `answerIps`, `disguisedVia` and `AuditSession.captureHealth` with defaults; new `CaptureHealth` type | `core/.../model/Model.kt`, new `model/CaptureHealth.kt` | Old saved audits still decode; all 65 core tests pass. Merge first: everyone waits on this | none | S |
| **A1** (impact 1) | Rights notice builder: app, package, date, time of Reject, each tracker contacted after it with delay in seconds; cites only ids in `passages.json` (`s6(4) s6(6) s6(10) r3(c)`) | new `core/.../notice/RightsNotice.kt`, `RightsNoticeBuilder.kt` + test | Golden-text test on the sample leaky audit; fails if a cited id is missing; no C2 finding returns null; no sentence asserts a violation | A0 | M |
| **A2** (impact 3) | Read DNS answer records (A, AAAA, CNAME; chain capped at 8; loops and truncation return a partial result) and attach them to events | new `core/.../dns/DnsAnswers.kt`, `TestPackets.kt`, `DnsAnswersTest.kt`; `capture/XrayVpnService.kt`, `capture/DnsForwarder.kt` | Golden packets pass and never throw; a real lookup on the phone yields `answerIps` | A0 | M–L |
| **A3** (impact 3) | Alias-aware classification: check the alias before the first-party shortcut; set `disguisedVia`; mention it in C1/C2 evidence text | `core/.../trackers/TrackerCatalog.kt`, `rules/RulesEngine.kt` | Alias to a catalogue tracker classifies as that tracker even if the host is marked first-party; ordinary lookups unchanged; 21 / 100 unchanged; text says "alias observed" | A0, sample fixtures from Nitya | M |
| **A4** (impact 4) | Bounded event buffer (cap 5,000, drop counter), health counters (queries, malformed, dropped, first/last lookup, DNS bytes relayed) and `ConfidenceGrader` with a reason string | `capture/CaptureBus.kt`, `capture/DnsForwarder.kt`, new `core/.../score/ConfidenceGrader.kt` + test | Buffer caps and counts drops; zero events grades Low and keeps V0 behaviour; boundary tests at every threshold (thresholds are proposals, tune on the phone) | A0 | M–L |
| **A5** (impact 9) | `hqCountry` and `hqSource` on tracker entries; fill only where a public source is found, else `null` | `core/.../trackers/TrackerCatalog.kt`, `resources/trackers/trackers.json` | Every non-null `hqCountry` has a source URL; classifier tests pass | none | S–M |

Order of work: A0 → A1 → A2 → A3 → A4 → A5. Cut from the bottom if short (A5, then the A4 grader; keep the buffer).

---

## Yashvi: Phone experience
Owns: `app/.../voice/**`, `netproof/**`, `ui/screens/map/**`, `ToolScreens.kt`, `ai/**`; `core/.../geo`, `copilot`; `leakyshop/**`.

| Milestone | Deliverable | Files | Done when | Needs | Effort |
|---|---|---|---|---|---|
| **Y1** (impact 2) | **Voice Copilot.** Question input: on-device speech recognition when available, otherwise tappable question chips. Answer built only from the audit's findings plus the top-3 retrieved passages; refuses outside the evidence; spoken back in English or Hindi. No cloud fallback | new `app/.../voice/*`, `VoiceCopilotScreen.kt`, `VoiceViewModel.kt`, new `core/.../copilot/GroundedAnswer.kt` + test | "Safe for children?" on the sample audit cites an `s9` passage; an off-topic question returns "I have no evidence about that in this audit"; with recognition unavailable the mic is disabled with a reason and the chips still work | none (ask Nitya to add `RECORD_AUDIO` and the route) | L |
| **Y2** (impact 5) | **Network Proof.** Measure TraceVeil's transmitted bytes during an audit (`TrafficStats` by UID), subtract DNS bytes relayed, show the remainder; separate line for any optional online lookup | new `app/.../netproof/*`, `NetworkProofCard.kt` | Remainder is within a stated tolerance of zero on a real audit and the number is recorded in `docs/`; arithmetic unit test; nothing hard-coded to 0 | A4 counters (use fakes until then) | M |
| **Y3** (impact 7) | **Country map, list view.** Offline country lookup, aggregator by consent phase, `EndpointMapCard` list. Each country labelled "inferred"; CDN-only countries marked "uncertain"; unknown IPs omitted | new `core/.../geo/*`, `core/src/main/resources/geo/`, `tools/build-geo-table.*`, `app/.../ui/screens/map/EndpointMapCard.kt` | Fixture test: three hosts with known IPs aggregate to the expected `country → phase → hosts`; card renders with 0, 1 and 20 countries; card text never says "transfer", "violat" or "illegal" | A0, A2; IP-country source with a cleared licence | L |
| **Y4** (impact 7) | **Company-jurisdiction layer** on the same card: separate toggle, outlined tiles, never coloured by consent phase, labelled "company registered in…" | `EndpointMapCard.kt`, new `JurisdictionLayer.kt` | Toggle changes the heading; shows only entries with a `hqSource` | Y3, A5 | S–M |
| **Y5** | ~~Delete the Camera Check screen and its helpers~~ **Cancelled.** The team keeps Camera Check and its Home button | none | Nothing to remove; AI Lab and Settings unchanged | none | none |

**Map data decision, Sat 14:00:** use an offline country table if its licence allows redistribution with attribution. Otherwise use the one permitted cloud feature: an opt-in, off-by-default online lookup that sends only public server IPs. If neither works, ship the jurisdiction layer alone. Record the outcome in `ATTRIBUTIONS.md`.

Order of work: Y1 → Y2 → Y3 → Y4. Cut from the bottom: Y4, Y2.

---

## Nitya: Integration and validation
Owns: `MainActivity.kt`, `XrayApp.kt`, `XrayViewModel.kt`, `HomeScreen.kt`, `ResultScreens.kt`, `LiveAuditScreen.kt`, `Components.kt`, manifest, `app/build.gradle.kts`, `audit/**`, `data/**`, `report/**`, `.github/**`, docs.

| Milestone | Deliverable | Files | Done when | Needs | Effort |
|---|---|---|---|---|---|
| **N1** (impact 5, start now) | **Device verification.** Install the three APKs on the iQOO. Run Full Audit on leaky and fixed, export a report, test Office Kit copy and file transfer. File one issue per defect | `docs/VERIFICATION.md` (dated device section) | Leaky 21 "At risk" and fixed 100 "DPDP-ready", or deviations recorded with screenshots. Blocking defects outrank new features for everyone | none | M |
| **N2** (start now) | Make `gradlew` executable; commit-time check script (every commit at or after 2026-10-09T19:00+05:30); CI running `:core:test` and that check; `ATTRIBUTIONS.md`; ask the organisers to confirm baseline reuse and record the answer in `PROVENANCE.md` | `.github/workflows/checks.yml`, `tools/check-commit-times.sh`, `ATTRIBUTIONS.md`, `PROVENANCE.md` | CI green; script fails on an early commit | none | S |
| **N3** | Sample fixtures: `SampleAudit` variants with an alias and with endpoint IPs, so nobody waits on Agrim | `data/SampleAudit.kt` | Fixtures decode and render | A0 | S |
| **N4** (impact 1, 3, 4) | **Wire the evidence UI:** alias chip on the timeline and finding detail; confidence badge on Results; "Draft notice to developer" button on C2 findings with copy and share | `LiveAuditScreen.kt`, `ResultScreens.kt`, `Components.kt`, `XrayViewModel.kt` | Chip shows "alias → tracker.example"; badge shows level and reason; button absent without C2; notice text equals the builder's golden file | A0, N4; then A1, A3, A4 | M–L |
| **N5** | Save `CaptureHealth` with each audit | `audit/AuditOrchestrator.kt` | Saved `sessions.json` contains `captureHealth`; old audits still open | A4 | S |
| **N6** (impact 2, 5, 7) | **Mount Yashvi's pieces:** voice route and Home entry, `RECORD_AUDIO` in the manifest, map card and Network Proof card | `MainActivity.kt`, `HomeScreen.kt`, `ResultScreens.kt`, manifest | Each renders; no edit to another owner's file | Y1–Y4 | S |
| **N7** (impact 8) | **Before/after card** on Results: finds the previous audit of the same package, shows score change and findings resolved | `ResultScreens.kt` | Leaky then fixed shows "+79 points, N findings resolved" | N1 (both audits exist) | S–M |
| **N8** | **Final integration:** golden-result regression (21 / 100), demo script, backup video, release APKs, commit-time check, tags `er1-pre`, `er2-pre`, `submission` | `docs/demo-script.md`, tags | Device smoke passes before each evaluation round | everything | L |

Order of work: N1, N2, N3 and N4 immediately (they touch nobody else's files) → N5 → N6 → N7 → N8 → N9. Cut from the bottom: N8, then rehearsal depth. Do not cut N1 or N3.

---

## Merge order (bottom-up)
1. **Now:** Nitya N2, N3 · Agrim A0 · Yashvi Y1 (no dependency).
2. **After A0 merges:** Nitya N4 (fixtures) · Agrim A1, A2 · Yashvi Y1 continues.
3. **After fixtures merge:** Agrim A3, A4 · Nitya N5, N6 · Yashvi Y2, Y3 against fixtures.
4. **Integration pass 1 (Sat 08:00–09:45), before ER1:** device smoke, 21 / 100 unchanged.
5. **Integration pass 2 (Sat 15:30–18:45), before ER2:** real data replaces fixtures; Nitya N7; map-data decision at 14:00.
6. **Sat 22:00 – Sun 06:30:** N8, Y4, A5, remaining fixes.
7. **Sun 06:30–08:30:** bug fixes only, then tag `submission` at 08:30.

## Visible at each evaluation (anything unmerged at the freeze is not shown)
- **ER1 (Sat 10:00):** verified core loop (21 → 100), Camera Check kept; alias chip, confidence badge and voice screen if merged.
- **ER2 (Sat 19:00):** adds the rights notice, country map list and Network Proof.
- **ER3 (Sun 09:00):** adds the before/after card and jurisdiction layer.

## Not verified
Nothing has run on the iQOO. The resolver's CNAME behaviour, on-device speech recognition (including Hindi), and every external licence are unconfirmed. Effort figures are estimates.
