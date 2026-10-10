We are team Kernel Panic building TraceVeil at the iQOO hackathon. I am Yashvi, GitHub `yashviup`. Work as my coding partner on my own branch. Do not make commits as Agrim or Nitya, alter dates, request anyone else's credentials, rewrite published history or push to main.

Repository: https://github.com/agrimsri/IQOO-Kernel-Panic.git. **`IMPLEMENTATION_MILESTONES.md` is the active plan**; older roadmaps do not override it. Follow live venue Red/Green instructions.

## Where things stand

| Item | State |
| --- | --- |
| Y1 Voice Copilot | On `main` (PR #4). Not yet mounted: Nitya adds the route and `RECORD_AUDIO`. |
| Y2 Network Proof | Calculator and card on `main` (PR #4). Not yet mounted or fed: Nitya samples `UidTx` per plan. |
| Y3 Endpoint map | On `yashvi/phone-experience` only (7 commits ahead, 4 behind `main`). Not on `main`. |
| Y4, Y5, Y6 | Not started. |
| Agrim A0–A5 | All on `main` as of PR #5 (`ebdafae`). `agrim/evidence-engine` and `agrim/a4-capture-health` are **deleted**: merge from `origin/main` from now on. |

## Start here: sync with `main`

1. `git status --short`; preserve any local edits (no reset, no force checkout). `git fetch --prune origin`. Your remote-tracking refs for the two deleted Agrim branches disappear; that is expected.
2. On `yashvi/phone-experience`: `git merge origin/main` (a merge commit, not a rebase). Agrim checked it with `git merge-tree`: **no conflicts**. You already merged A4 at `dbd0305`; this brings the "under 0.1 s" notice fix and A5.
3. Run `:core:test :app:testDebugUnitTest :app:assembleDebug :leakyshop:assembleLeakyDebug :leakyshop:assembleFixedDebug`. On `main` + Agrim's work the suite was 197 core and 52 app tests, 0 failures; record your actual numbers.
4. Read `docs/reports/BUG_REPORT_2026-10-10.md` (live iQOO results, contract changes) and the PR #5 description.

Use repository-local identity `Yashvi Upadhyay <24f2007780@ds.study.iitm.ac.in>` and your own GitHub authentication. Never ask for a token or password in chat.

## Changes needed from you after A5 (do these before opening the Y3 PR)

1. **One ownership mapping.** A5 put cited company facts into `trackers.json` using exactly your `owners.json` field names (`location` / `incorporation` / `note`; each with `country`, `source`, `kind`, `asOf`, `quote`, `basis`). Build `OwnerRegistry` from the catalogue instead of `geo/owners.json`, then delete `owners.json`:
   ```kotlin
   catalog.entries().filter { it.location != null || it.incorporation != null || it.note != null }
       .map { e -> OwnerFacts(e.entity, e.location?.toSourced(), e.incorporation?.toSourced(), e.note) }
   ```
   where `toSourced()` maps `CompanyCountry(country, source, kind, asOf, quote, basis)` 1:1 onto your `SourcedCountry` (the `kind` and `basis` enum names are identical). Do not parse `trackers.json` with your existing loader: it rejects the 22 entries that have no facts. The catalogue already validates every fact (ISO code, https source, date, quote length, basis) and fails the load otherwise.
2. **Do not keep both files.** They now disagree: `owners.json` has Google and Meta as `STATED_ADDRESS` (terms of service), while `trackers.json` has them as `HEADQUARTERS` with quotes from their SEC 10-K filings (Item 2). AppsFlyer is `STATED_ADDRESS` in both; CleverTap has only a note.
3. **Footer wording.** Your owner view says "Parent-company ownership is not established." The four Google entries now cite Alphabet Inc. (the parent) for headquarters and Google LLC for incorporation, with a note saying so. Reword, for example: "A parent company is named only where the cited source names it."
4. **Y4 labels.** A headquarters is labelled as a headquarters (`TrackerEntry.hqCountry` / `hqSource`, non-null only when the source says "headquarters"); a stated address as a stated address; an incorporation as an incorporation. Never write "registered in" for a company, even though the old milestone text says so. Show only entries with a source; do not colour this layer by consent phase.
5. **Optional clarity:** your map fill constant `MapColors.HEADQUARTERS` is also used for stated addresses. It is never shown to users; rename it (for example `COMPANY_LOCATION`) if you touch that code.

What A5 contains, for reference: headquarters (US) for Google ×4, Meta ×3, Microsoft Clarity, Braze, Unity Ads, AppLovin; stated address only for AppsFlyer (IL) and Amplitude (US, "principal executive office"); CleverTap not established; 22 entities not researched (null, shown as "Not established").

## Your remaining work, in order

- **Y3 to `main`:** finish the changes above, re-run tests, then open a PR from `yashvi/phone-experience` to `main` with test output and screenshots. Keep GeoLite2 data out of Git as you decided; Nitya records it in `ATTRIBUTIONS.md` (it does not exist yet; send her the exact entry). A filled map with real countries has not been seen on a phone yet: the live audits on 10 Oct produced real `answerIps` (see the bug report), so test with your own GeoLite2 file and a Full audit of LeakyShop.
- **Network Proof check against live data:** a leaky Reject plan relayed 8 datagrams, 322 B sent, 653 B received. Encrypted-DNS attempts appear as `CaptureHealth.hiddenDnsPackets` (8 in each audit's first plan with Private DNS on Automatic) and never reach the relay; `nonDnsPackets` are IPv6 control traffic. Make sure your card states both where relevant, and still never clamps an unknown or negative remainder to 0.
- **Y4:** the company layer from the catalogue, per point 4 above.
- **Y5:** after Nitya removes the Camera Check Home button and route (still present on `main`: `MainActivity.kt` route `camera`, `HomeScreen.kt` "Camera check"), remove `CameraCheckScreen` and its unused helpers from your `ToolScreens.kt`. Keep AI Lab and Settings working. Coordinate so no commit leaves a dangling route.
- **Y6 last:** a controlled aliased host in the leaky flavour only if the team controls a real CNAME. Otherwise keep the labelled synthetic fixture (Nitya's `nitya/sample-fixtures` has one). Fixed must stay at 100.

Do not implement a fraud scanner, a permission interceptor, a second Safety Coach, wake words or camera improvements. Any cloud feature (including the Sarvam online voice mode) must stay off by default with explicit consent and be recorded with Nitya.

## Ownership boundaries

You own `app/.../voice/**`, `netproof/**`, `ui/screens/map/**`, `ToolScreens.kt`, `ai/**`, `core/.../geo` and `copilot`, and `leakyshop/**`. Agrim owns `Model.kt`, `CaptureHealth`, capture, DNS, trackers (`trackers.json`, `TrackerCatalog`, `CompanyFacts`), rules, score and notice. Nitya owns `MainActivity`/AppGraph/shared ViewModel/Home/Results/Live/Components/theme, manifest/Gradle, audit/data/report/consent/control, fixtures and CI/docs.

Do not edit another owner's file. Need a field or behaviour from Agrim's code? Ask with the exact signature. Need wiring? Send Nitya the exact call and route.

## Phone rules (unchanged)

Use `adb -s YOUR_SERIAL` for every command. Grant VPN, Accessibility and Shizuku by hand on your own phone (start Shizuku with the command its app shows under *View command*); keep HackTracker and Google assistant unchanged. Only audit or reset `com.leakyshop.demo` and `com.leakyshop.demo.fixed`; never a real app. No root, no automatic Android Allow/Deny taps, no uninstall to bypass a signing mismatch. Private DNS on Automatic adds hidden-DNS packets; set it Off for clean DNS tests and record which setting you used.

## Verification and handoff

Each slice: failing tests first, then the fix, then the full core and app suites plus the three APK builds, and phone evidence where the change touches the device. Keep LeakyShop 21 / Fixed 100 as expected observations, never hard-coded. Commit genuine work as you; push only `yashvi/phone-experience`; open a PR to `main` for Agrim to review and merge. Report: branch and commit, Y status, test output, what was actually tested on the phone, exported signatures, wiring requests for Nitya, licence decisions, and remaining limits. Never claim data was sent, a law was broken, or a score is a legal certificate.