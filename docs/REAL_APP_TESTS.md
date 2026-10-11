# Real-app test plan (run on the test phone)

This plan checks that TraceVeil gives a clear, truthful result on real third-party apps, not only on LeakyShop, and that
an "Inconclusive" result now says why and offers a one-tap fix. It is written for the person running the phone; nothing
here needs a laptop except the optional `traceveil audit` lines.

## Ground rules (read first)

- **Observations, never accusations.** A finding about a real app describes what it did on our phone during one short
  check: "HackTracker 2.3 looked up `app-measurement.com` (Google Analytics for Firebase) 1.2 s after launch, before any
  consent choice". Never write or say that an app is illegal, non-compliant, unsafe, malicious or "stealing data". A DNS
  lookup is an attempt to contact a host, not proof that personal data was sent.
- **No server probing.** TraceVeil only watches the audited app on our own phone (its DNS lookups, its consent screen,
  Android's permission dialogs). Do not send requests to an app's servers, do not try other people's accounts, do not
  scrape, and do not share captures that show someone's personal data.
- **Personal apps: restart only, never reset.** HackTracker and every app you use with your own account are checked with a
  *restart* (TraceVeil closes and reopens the app; your data, sign-in and settings stay). Never switch on "Reset: clear
  this app's data" for them. The result screen only offers "Check again with a reset" for an app you develop (a debuggable
  build or LeakyShop), and asks you to confirm first.
- **TraceVeil never answers for you.** It never grants a permission, never signs in, never types a password, code, phone
  number or e-mail. When an app needs one of those, the check pauses and asks you; tap **Continue** on the card when you
  are through (it also carries on by itself).

## Phone setup (once per session)

| Item | Where | Needed for |
|---|---|---|
| TraceVeil build from `nitya/real-apps` (or the merged integration build) | install | everything below |
| VPN allowed for TraceVeil, no other VPN on | Home › Setup | capture |
| Consent watcher (Accessibility) on | Home › Setup | consent screen, blockers, crash dialog |
| Shizuku running and allowed for TraceVeil | Shizuku app | **restart before each plan**, open-connection count |
| Private DNS **Off** | Settings › Network › Private DNS | lookups visible to TraceVeil |
| Notifications allowed for TraceVeil | Android settings | the "needs you" / "unlock" notices |
| Wi-Fi or mobile data working | | |

Run each app with the **Everyday Quick check** ("say No", one Reject run) unless the row says otherwise. Developer mode's
Quick (one Accept run) is fine too; note which one you used.

## Matrix

| # | App (package, check in App info) | Kind | How to check | Expected result type(s) | Look for |
|---|---|---|---|---|---|
| 1 | HackTracker by Reskilll (`com.reskill.hacktracker`) | personal | (a) Quick **without** Shizuku, (b) tap **Check again with a restart** with Shizuku on | (a) Inconclusive · "The app was already running" with **Check again with a restart** as the filled button; (b) a **score**, or Inconclusive · consent history unknown (see below) | (b) Audit notes say "Restarted the app before this plan (data kept)." and lookups > 1 |
| 2 | Facebook (`com.facebook.katana`) | personal | Quick | Score with S1 (Meta's own servers, first party) and U1, or Inconclusive · consent history unknown | Sign-in or notification prompt in **Steps that needed you** |
| 3 | Block Blast! (`com.block.juggle`, confirm) | personal / ad-supported game | Quick | Score, or Inconclusive · consent history unknown; ad/attribution SDK lookups listed | A consent form shown in a WebView (Google's UMP) is recognised as a consent screen if it appears |
| 4 | Google Docs (`com.google.android.apps.docs.editors.docs`) | personal, may be preinstalled | Quick | Score with S1 (Google's own servers) | If preinstalled: Audit notes say it is a system app |
| 5 | Instagram (`com.instagram.android`) | personal | Quick | Like Facebook (S1 for Meta) | |
| 6 | Truecaller (`com.truecaller`) | personal | Quick | Score or Inconclusive · consent history unknown | Its onboarding asks for permissions and a phone number: hand-off card, **Continue**, Android permission requests listed (C14 when before any consent screen) |
| 7 | Swiggy (`in.swiggy.android`) | personal | Quick | Score or consent history unknown | Location permission request recorded and handed to you |
| 8 | Chrome (`com.android.chrome`) | system app | Quick | Score or consent history unknown | Audit notes: "Chrome is a system app …"; no reset offered |
| 9 | LeakyShop leaky / fixed (`com.leakyshop.demo`, `.fixed`) | our own test app | Full (reset allowed) | 21/100 At risk / 100 Ready, unchanged | Notes say "Reset the app before this plan (data cleared, as approved)." |

### What the result types mean

| Result | Meaning | Honest? |
|---|---|---|
| A score (Ready / Needs work / At risk) | Enough was captured to judge the plans that ran. A consent screen that never appeared is scored through NC on a fresh state, or noted (U1, no score effect) on kept data with only the app's own lookups. | Yes |
| Inconclusive · **too little was captured** | Fewer than 2 lookups. The **Why there's no score** group names the recorded cause(s) and the filled button is the fix. | Yes, and avoidable causes now say how to avoid them |
| Inconclusive · **consent history unknown** | The app kept its data, showed no consent screen in this check, and contacted third-party tracker hosts. Whether you agreed earlier (for example at sign-up) cannot be seen on a kept-data run. Only a fresh install shows first-launch consent; for a personal app that is not done. | Yes: a real limit, not a capture failure |
| Check did not start / stopped | TraceVeil itself, an app that is not installed, or one with no screen to open is refused by name. An app uninstalled, a capture stopped, or a phone left locked between plans ends the check and **keeps the plans already captured**. | Yes |

### HackTracker: the expected outcome after this change

1. Without Shizuku, a Quick check of an already-open HackTracker should end Inconclusive with **Why there's no score ›
   The app was already running** and the filled button **Check again with a restart**, plus the note "Without the
   Shizuku helper TraceVeil can't restart apps … App info › Force stop".
2. With Shizuku allowed, tap **Check again with a restart**. The live screen shows "Restarting HackTracker (your data
   stays)". The result should no longer be "too little was captured". It is either a score, or "consent history unknown"
   if HackTracker contacts third-party tracker hosts and shows no consent screen. The second is the honest answer for a
   personal app that kept its data; do not reset it to "fix" that.
3. If it is still "too little was captured", record the cause the result names (for example "The app may use its own
   DNS" when it had open connections but no lookups). That is a finding about the capture, not about HackTracker.

## Edge cases to try (each should end with a clear note, never a silent wrong result)

| Case | How | Expected |
|---|---|---|
| Phone locks mid-check | Press power during the observation | The screen should stay on while an app is inspected (new). If it locks anyway: result notes say the phone was locked; diagnosis "The phone was locked" if lookups were few. Locked before a plan: "Unlock the phone to continue" and a notification; carries on after unlock. |
| Private DNS on mid-check | Turn it on during the observation | Note "Private DNS was turned on during this plan…"; V1 lists it; with few lookups the diagnosis names Private DNS and offers **Open Private DNS settings** |
| VPN revoked mid-check | Turn on another VPN during a Full check of LeakyShop | Plans already captured are kept; note "TraceVeil's capture stopped …"; diagnosis "Another VPN was on" / "The capture stopped" |
| Shizuku stops mid-check | Stop Shizuku between plans of a Full LeakyShop check | Next plan's note "Could not restart the app before this plan (…)"; reset not possible, so that plan keeps its data and is judged as such |
| App uninstalled mid-check | Only with LeakyShop: uninstall it between plans | Check ends, earlier plans kept, note "LeakyShop was uninstalled during the check …" |
| App asks for a permission first | Any app with a permission prompt at launch | Card "Answer Android's permission request", **Continue**; TraceVeil never taps Allow; listed in **Steps that needed you**; C14 when before a consent screen |
| Sign-in / OTP / details form | Truecaller, Facebook | Skip button tapped when harmless; details forms filled with test values (never phone, e-mail, password, code); else hand-off card naming the field |
| Consent screen keeps coming back | An app that re-asks after Reject | After 3 returns the note says "came back N times … kept the first 3" |
| TraceVeil itself | `traceveil audit --package com.dpdpxray.app` (debug build) | Refused: "TraceVeil can't check itself" |

## Recording evidence

For every run, fill one row and keep the files together under `evidence/real-apps/<date>/` on your laptop (not in the repo):

| Field | Example |
|---|---|
| Date and time, phone, Android version | 2026-10-11 10:40, test phone (model I2501), Android 16 |
| App, version (App info) | HackTracker 2.3.1 |
| Check | Everyday Quick (Reject), Shizuku on, Private DNS off |
| Result | 82 Needs work / Inconclusive · consent history unknown / Inconclusive · too little captured |
| Causes shown under "Why there's no score" | The app was already running |
| Notes (Evidence and limits › Audit notes) | "Restarted the app before this plan (data kept)." present? |
| Steps that needed you | Sign-in screen: you completed it (42 s) |
| Files | `hacktracker-result.png`, `hacktracker-notes.png`, exported `TraceVeil-audit-….json` |

- Export the audit from **Report and export › Export audit data (JSON)**; it carries each plan's recorded conditions
  (`conditions`: start mode, Private DNS at start/end, network, lock, crash, open connections, first blocker).
- With a debug build and adb on your laptop you can also run `traceveil audit --package <package>`; for an inconclusive
  result it prints "why no score:" and "try:" lines from the phone's own diagnosis.

## What still needs this phone run (cannot be shown in unit tests)

- `am force-stop` through Shizuku before each plan, and `pidof` to tell whether the app was already running.
- Reading the socket tables (`/proc/net/tcp*`, `udp*`) through Shizuku for the open-connection count; if the phone refuses,
  the count stays unknown and the diagnosis says so.
- Android's "keeps stopping" / "isn't responding" dialog recognised from the system window (iQOO's own wording may differ).
- Android's permission dialog detected as a blocker on this phone's permission controller, and the **Continue** button.
- The glow overlay keeping the screen on (`FLAG_KEEP_SCREEN_ON` on an accessibility overlay).
- The network check (`VALIDATED`) while the per-app VPN is up.
