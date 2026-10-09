# iQOO Hackathon 2026 — Requirements, Scoring, Schedule (IST) and Git Rules

Sources: [Official iQOO × Reskilll Guide](https://iqoo.reskilll.com/guide) · [Problem Statements](https://iqoo.reskilll.com/problems) · printed event schedule (`schedule.jpg`, photographed at check-in) · `DPDP_XRay_Build_Plan.md` (Global Constraint 1 quotes the official originality rule).

> **Verification status.** The guide page could not be fetched by the tooling used to update this file (403 / client-rendered). Every rule below carries a source tag: **[Guide]** previously captured from the official guide, **[Schedule]** read from the printed schedule, **[Blog]** Reskilll blog posts (these describe the one-day City Battle format, not the Finale), **[Plan]** our own policy. Items tagged [Blog] or [Plan] must be re-checked against the live guide before relying on them.

---

## 1. Participation requirements
- Eligibility: students and working professionals across India. [Guide]
- Team size: solo or 2–3 members (maximum 3). [Guide][Blog]
- No mixing students and working professionals in one team. [Guide]
- Registration free; no referral, invitation or campus nomination needed. [Guide]
- Selection: register → idea screening/shortlisting → on-site hackathon. [Guide]
- Format: phone-first, on an iQOO loaner phone, laptop restricted during designated periods. [Guide]
- Technology: any stack; local or open-source AI models encouraged. [Guide]
- Device integration: keep the phone involved through **iQOO Office Kit** (screen mirroring, shared clipboard, file transfer, remote control). [Guide]

## 2. Scoring rubric (100 points: 75 % jury + 25 % HackTracker device data)
| Criterion | Weight | Measured by | What it rewards |
|---|---|---|---|
| End product quality | 30 % | Jury | Working, useful product people would keep using |
| Novelty and impact | 20 % | Jury | Originality and real-world impact |
| Creative phone use | 15 % | **HackTracker device data** | Camera, voice, on-device AI in the build |
| Technical depth | 15 % | Jury | Architecture, code quality, robustness, meaningful hardware use |
| Office Kit usage | 10 % | **HackTracker device data** | Demonstrated phone–laptop bridge use |
| Demo and presentation | 10 % | Jury | Compelling 3–5 minute pitch |

> **Discrepancy to resolve.** A Reskilll blog about the one-day City Battle lists a different rubric (Office Kit 25 %, phone-first 25 %, AI-native 20 %, problem fit 20 %, craft/pitch 10 %). We treat the table above as authoritative for the Finale because it is the one captured from the official guide, but both versions agree that phone and Office Kit usage are measured from device data and are about a quarter of the score. Re-check on the live guide.

## 3. What they want us to build
| Requirement | What to demonstrate |
|---|---|
| Real utility | A clear problem and a solution users would actually use |
| Working product | Functional end-to-end demo, not mockups |
| Innovation | A distinctive idea or better approach |
| AI integration | Preferably a local or open-source model at the core |
| Phone-first workflow | Meaningful use of phone capabilities and Office Kit |
| Engineering quality | Reliable implementation, sensible architecture, robust handling |
| Clear pitch | Problem, solution, impact, and a live demonstration |

Local/open-source models earn additional consideration, but the published rubric gives them no separate percentage.

## 4. Problem tracks (Grand Finale, Bengaluru, 9–11 Oct 2026)
Mobility · Community App · Smart Living · Productivity · Developer Tools · Open Innovation (any domain, local/open-source model at its core). Earlier City Battles also had FinTech and Commerce, Smart Education and HealthTech; the Finale has its own six-track list.

## 5. Format and restrictions
- City Battles: 30 hours; Grand Finale: 48 hours (printed schedule shows the build clock — see §6). [Guide]
- **Green Light:** phone and laptop both usable. **Red Light:** phone-only; any laptop interaction goes through Office Kit. [Guide][Schedule legend]
- Grand Finale build allocation stated as about 54 % Red / 46 % Green of pure build time. [Guide] (§7 recomputes this from the printed chart.)
- Evaluation: three scored evaluation rounds on the printed schedule feed the Top Teams; the Top Teams pitch decides winners. [Schedule] (the guide text mentions two scored rounds — see discrepancy list in §10.)
- The final demo must run on the iQOO phone; pitch 3–5 minutes. [Guide]
- The 25 % HackTracker portion is based on device data, not self-reported usage. [Guide]
- Loaner phone must not be modified or unlocked: no root, no bootloader unlock. Developer options, wireless debugging, Shizuku and sideloaded APKs are allowed. [Plan, from Build Plan Constraint 4]

---

## 6. Official Finale schedule — all times IST (24-hour), read from `schedule.jpg`

**Legend on the printed schedule:** white = *Attentions* (team-attention events), orange = *Food*, **green = Green Light** (phone + laptop), **red = Red Light** (phone only).

> **Reading accuracy.** Times are read from a photograph. The vertical axis on the printed sheet is not strictly linear (labels jump 19:00 → 21:00, 22:00 → 00:00 and 01:00 → 03:00 over one row each), so block edges below are read against the nearest printed hour label and marked `≈` where they fall between labels. Treat `≈` times as ±30 minutes until confirmed at the venue.

### 6.1 Friday 9 October
| IST | Block | Mode |
|---|---|---|
| 17:00–19:00 | **Check-in and device handover** (attention) | — (setup only) |
| 19:00–22:00 | **Hacking begins** — dinner icon at ≈21:00–22:00 | 🟢 Green |
| 22:00–00:00 | Build | 🔴 Red |
| 00:00–03:00 | Build — refuel/snack icon at ≈03:00 | 🟢 Green |
| 03:00–≈04:00 (chart edge) | Build | 🔴 Red |
| ≈04:00–06:00 | **Not printed** (assume rest window) | unspecified |

### 6.2 Saturday 10 October
| IST | Block | Mode |
|---|---|---|
| 06:00–08:00 | Build — breakfast icon at ≈07:00–08:00 | 🟢 Green |
| 08:00–10:00 | Build | 🔴 Red |
| **10:00–12:00** | **Evaluation Round 1** (attention) | — |
| 12:00–14:00 | Build — lunch icon at ≈13:00–14:00 | 🟢 Green |
| 14:00–≈15:30 | Build | 🔴 Red |
| ≈15:30–≈16:30 | Build | 🟢 Green |
| ≈16:30–19:00 | Build | 🔴 Red |
| **19:00–22:00** | **Evaluation Round 2** (attention) — dinner icon at ≈22:00 | — |
| 22:00–01:00 | Build | 🔴 Red |
| 01:00–03:00 | Build — refuel/snack icon at ≈03:00 | 🟢 Green |
| ≈03:00–06:00 | **Not printed** (assume rest window) | unspecified |

### 6.3 Sunday 11 October
| IST | Block | Mode |
|---|---|---|
| 06:00–≈06:30 | Build | 🟢 Green |
| ≈06:30–09:00 | Build — breakfast icon at ≈08:00–09:00 | 🔴 Red |
| **09:00–12:00** | **Evaluation Round 3** (attention) | — |
| ≈12:00–13:00 | Lunch icon | — |
| ≈12:15–14:00 | **Top Teams Announcement** | — |
| 14:00–16:00 | **Top Teams Pitch** | — |
| 16:00–17:00 | **Awards** | — |

**Consequences for planning (important):**
1. The last printed build window ends at **Sun ≈09:00**, when Evaluation Round 3 starts. Planning for a Sunday-afternoon submission is unsafe: treat **Sun 08:30 as the code freeze and submission deadline** until organisers say otherwise.
2. The evaluation rounds coincide with our checkpoints: **ER1 Sat 10:00**, **ER2 Sat 19:00**, **ER3 Sun 09:00**. Whatever is on the phone at each of those moments is what gets scored, so `main` must be installable and demo-ready 30 minutes before each.
3. Sat 10:00–12:00 and 19:00–22:00 are long attention blocks. Plan that they consume the whole team's time and do not rely on them for building (no development is printed in white blocks).
4. Two unprinted rest gaps (≈04:00–06:00 Fri night, ≈03:00–06:00 Sat night) exist: sleep rotation must fit around them, and nobody merges risky changes then.

---

## 7. Red/Green budget computed from the printed chart (build time only)
| Day | 🟢 Green | 🔴 Red |
|---|---|---|
| Fri 19:00 → Sat 04:00 | 19:00–22:00 (3 h) + 00:00–03:00 (3 h) = **6 h** | 22:00–00:00 (2 h) + 03:00–04:00 (1 h) = **3 h** |
| Sat 06:00 → Sun 04:00 (ex. attention blocks) | 06–08 (2) + 12–14 (2) + 15:30–16:30 (1) + 01–03 (2) = **7 h** | 08–10 (2) + 14–15:30 (1.5) + 16:30–19 (2.5) + 22–01 (3) = **9 h** |
| Sun 06:00 → 09:00 | 0.5 h | 2.5 h |
| **Total** | **≈ 13.5 h (≈ 47 %)** | **≈ 14.5 h (≈ 53 %)** |

This matches the stated ≈ 54 % Red / 46 % Green within reading error. Total printed build time is about **28 hours** inside a 48-hour event; the rest is evaluation, meals, ceremonies and unprinted rest.

**What each mode means for engineering work**
| Mode | Allowed | Good for |
|---|---|---|
| 🟢 Green | Phone + laptop | Gradle, Kotlin-heavy modules, CI fixes, merges, PDF/Compose work |
| 🔴 Red | Phone only; laptop only through Office Kit (mirror, clipboard, file transfer, remote control) | Prompt tuning, JSON assets, UI text, running audits and logging failures, rehearsal, deck review, `git pull/push` and small edits from Termux/Code on the Go |

Use Office Kit throughout both modes — HackTracker counts it (10 %), and Red Light forces it.

---

## 8. Git commit and push schedule (IST) — tied to the printed schedule

**Why this matters.** The organisers state they read submitted code and act against plagiarism [Blog snippet; the official originality text is in the Build Plan's Global Constraint 1]. Our commit history is our evidence of original, in-event work, so it must show: a first commit after hacking begins, steady commits from all three members across the real build windows, and no bulk dump at the end.

### 8.1 Hard rules
1. **First commit of the new repo is on or after Fri 9 Oct 19:00 IST** (Hacking begins). Nothing from the pre-event prototype repository is copied, cherry-picked or added as a remote. [Plan, from Guide originality rule]
2. **No history rewriting after 19:00 Fri.** No force-push to `main`; feature branches may be rebased only before they are shared. Preserve granular commits — **use "rebase and merge" or merge commits for PRs, not squash**, so the individual timestamps and authors stay visible. (This overrides the squash-merge default mentioned in `THREE_PERSON_EXECUTION_PLAN.md` §12.1.)
3. **Each developer commits under their own GitHub identity** (`user.name`, `user.email` set and verified on every machine and in Termux on the phone). No shared account, no committing on someone else's behalf.
4. **Do not alter commit dates** (`GIT_AUTHOR_DATE`, `--date`) and do not backdate. Timestamps are evidence.
5. No third-party repository code is pasted in. Open-source libraries and frameworks used as dependencies are allowed and recorded in `ATTRIBUTIONS.md`. AI coding assistants used while building are allowed (per Build Plan) and disclosed in the README.
6. No secrets, model weights or `google-services.json` in git.

### 8.2 Cadence targets (per developer)
- Commit every **30–45 minutes** of active work; push **at least hourly**; WIP commits on branches are fine.
- Each developer lands at least **one merged PR every 3–4 hours** of active build time.
- Commit messages: `type(scope): summary` (e.g. `feat(sensing): parse HTTPS qtype`). Author must be able to explain every line (the code may be read by the team).
- Phone-only commits (Red Light) are encouraged and not exceptional: they prove phone-first work.

### 8.3 Commit schedule by window
| IST window | Mode | Git expectation | Tags / branch actions |
|---|---|---|---|
| Fri 17:00–19:00 | Check-in | **No product commits.** Device setup only; docs may be edited locally. Create repo structure notes, not code. | — |
| Fri 19:00–19:30 | 🟢 | Kickoff. Create the empty repo; protect `main`; first commit (README, `.gitignore`, CODEOWNERS) at **≥ 19:00**. | `h0` on first commit |
| Fri 19:30–22:00 | 🟢 | Contract + Gradle skeleton PR (C) by ≈21:30; A and B each push first module commits (codec, trackers/rules) before 22:00. Contract freeze announced. | `contract-v1` |
| Fri 22:00–00:00 | 🔴 | Phone-only commits: prompts, tracker/fix JSON, LeakyShop copy, lexicon strings, docs. Push from phone. Open PRs for the 19:30–22:00 work if not yet opened. | — |
| Fri 00:00–03:00 | 🟢 | Heaviest Kotlin window: VPN capture, consent service, LeakyShop-leaky. Merge to `main` at least once per developer. **Gate G1 (hostnames stream on the iQOO) by ≈01:30.** | `g1-capture` when passing |
| Fri 03:00–≈04:00 | 🔴 | Final push of the night; **everyone pushes before sleeping**; leave `main` green. | `night1-end` |
| ≈04:00–06:00 | rest | No merges. One person on call for CI breakage. | — |
| Sat 06:00–08:00 | 🟢 | Integration pass (device smoke test of `main`); consent clock + Room + timeline merges. | — |
| Sat 08:00–09:45 | 🔴 | Phone-only: run audits on the real device, fix prompts/lexicon, record failures as issues. **Stabilise for ER1**: no risky merges after 09:15. | `er1-pre` at ≈09:45 on a smoke-tested `main`; fast-forward `demo-stable` |
| **Sat 10:00–12:00** | ER1 | **Evaluation Round 1.** Demo from `demo-stable`. Do not merge; commit notes only. | `er1` |
| Sat 12:00–14:00 | 🟢 | Post-feedback merges: auditor, rules, findings UI, Plan B/C. | — |
| Sat 14:00–15:30 | 🔴 | Phone-only commits; prompt/JSON/UI-text; test audits. | — |
| Sat 15:30–16:30 | 🟢 | Short Kotlin window: merge anything blocked on laptop; run integration pass. | — |
| Sat 16:30–18:30 | 🔴 | Phone-only polish; rehearse; report text. | — |
| Sat 18:30–19:00 | — | **Freeze for ER2**: smoke-test `main`, fast-forward `demo-stable`. No merges after 18:30. | `er2-pre` at ≈18:45 |
| **Sat 19:00–22:00** | ER2 | **Evaluation Round 2.** Demo from `demo-stable`; no merges. | `er2` |
| Sat 22:00–01:00 | 🔴 | Phone-only: RAG/Copilot prompts, Camera Check tuning, deck, demo script. Commit and push hourly. | — |
| Sat 01:00–03:00 | 🟢 | Last Kotlin-heavy window: Camera/Voice screens, fixed LeakyShop, bug fixes. Everyone pushes before sleeping. | `night2-end` |
| ≈03:00–06:00 | rest | No merges; revert-only if CI is red. | — |
| Sun 06:00–06:30 | 🟢 | Final laptop-allowed window: bug-fix PRs only. | — |
| Sun 06:30–08:30 | 🔴 | Phone-only: rehearsals, backup video, README/ATTRIBUTIONS, last prompt fixes. **Feature freeze at 06:30; bug-fix PRs only until 08:00.** | — |
| Sun 08:00–08:30 | — | **Code freeze.** Final smoke test; `demo-stable` = `main`; release APKs attached. | `submission` at ≈08:30 |
| **Sun 09:00–12:00** | ER3 | **Evaluation Round 3.** No commits to `main`. | `er3` |
| Sun ≈12:15–17:00 | — | Announcement, Top Teams Pitch (14:00–16:00), Awards (16:00–17:00). Repository frozen on tag `submission` unless organisers request otherwise. | — |

### 8.4 Commit-history sanity check (run before each evaluation round)
```
git log --reverse --format='%h %an <%ae> %ad %s' --date=iso-strict | head -1   # first commit ≥ 2026-10-09T19:00+05:30
git shortlog -sne                                                               # all three members present
git log --since='2026-10-09T19:00+05:30' --format='%ad' --date=format:'%a %H' | sort | uniq -c   # activity spread across windows
```
Expect: first commit timestamp after 19:00 IST Fri; each member contributes in Red windows (phone-authored commits) and Green windows; no hour-long gaps during active windows except the printed rest gaps; no single commit larger than a few hundred lines without a reason.

---

## 9. Rules register (conduct, device, demo, submission)
| # | Rule | Source | Action for us |
|---|---|---|---|
| 1 | Original work only; all code written during the event; no pre-built or recycled codebases. Open-source libraries and frameworks are fine; carrying in a completed app is not. | [Guide] (quoted in Build Plan) | New repo after 19:00 Fri; prototype archived, never merged |
| 2 | Submissions are created during the event; pre-event drafting of ideas is permitted. | [Guide] | Disclose pre-event docs/ideas in README |
| 3 | Plagiarism leads to cancellation/disqualification; code may be read by the team. | [Blog] | Section 8 commit rules; cite dependencies |
| 4 | Demo must run on the iQOO phone; web-only apps forfeit phone/Office Kit points. | [Guide][Blog] | Native Android app |
| 5 | Office Kit use is tracked (HackTracker) and part of the score. | [Guide] | Use mirroring, clipboard, file transfer in dev and demo |
| 6 | Loaner phone: do not modify or unlock; no root or bootloader unlock. | [Plan from Guide terms] | Shizuku via wireless debugging only |
| 7 | Participants sign an NDA at entry; phones/devices collected and handed over at check-in. | [Blog] | Follow venue instructions; do not publish venue-confidential details |
| 8 | Maximum team size 3; no mixing students and working professionals. | [Guide][Blog] | Verify at check-in |
| 9 | Laptop restricted during Red Light; interaction only via Office Kit. | [Guide][Schedule] | Red-eligible task pool per developer |
| 10 | Pitch 3–5 minutes; Top Teams pitch Sun 14:00–16:00. | [Guide][Schedule] | Rehearse to ≈4:15 |
| 11 | Local/open-source AI gets additional consideration; Open Innovation requires a local model at its core. | [Guide] | On-device Gemma + embeddings |
| 12 | Hacking window, meals and evaluation rounds follow the printed schedule; times may change on site. | [Schedule] | Re-confirm at Fri 17:00 briefing |

---

## 10. Discrepancies and items to verify (do this at check-in or on the live guide)
1. **Number of evaluation rounds:** the guide text says two scored rounds feed the Top 10; the printed schedule shows **three** (ER1 Sat 10:00, ER2 Sat 19:00, ER3 Sun 09:00). Plan for three.
2. **End of build:** the printed schedule ends building at Sun ≈09:00, earlier than the "Sunday after lunch" assumption in `THREE_PERSON_EXECUTION_PLAN.md`. Re-time that plan's Sunday blocks and its H-hour mapping (it assumes building until ≈Sun 12:30–14:00) to the §6 schedule.
3. **Rubric weights:** guide-captured rubric vs the City Battle blog's rubric (§2). Confirm which applies to the Finale.
4. **Red/Green split:** guide says ≈54/46; the printed chart computes ≈53/47 (§7). Consistent within reading error.
5. **Unprinted hours** (≈04:00–06:00 Fri night, ≈03:00–06:00 Sat night): confirm whether the venue stays open and whether building is allowed.
6. **Axis labels on the printed sheet** skip hours (19:00→21:00, 22:00→00:00, 01:00→03:00): confirm the exact edges of the 19:00–22:00 blocks (Hacking Begins, Evaluation Round 2) and the ≈ boundaries marked above.
7. **Guide content not retrievable here:** the full text of the official RULES (submission format, repository/GitHub requirements, judging process, conduct) should be pasted in from the live page and added to §9 with [Guide] tags. Until then, rules tagged [Blog] or [Plan] are provisional.

---

## 11. What to prioritise for the highest score (in order of impact)
1. Build a complete, reliable product — 30 %. Prioritise the working core flow over extra features.
2. Show measurable differentiation — 20 %. Explain why the solution is meaningfully better and who benefits.
3. Use the phone creatively — 15 %. Put camera, voice and on-device AI into the actual workflow (device-measured).
4. Demonstrate strong engineering — 15 %. Robust architecture, real functionality, appropriate hardware use.
5. Use Office Kit meaningfully — 10 %. Keep phone–laptop interaction integrated throughout development (device-measured).
6. Prepare a crisp demo — 10 %. Make the value obvious within the 3–5 minute pitch.

Phone usage and Office Kit together are 25 % of the total, so a laptop-only AI application would miss a substantial part of the rubric.
