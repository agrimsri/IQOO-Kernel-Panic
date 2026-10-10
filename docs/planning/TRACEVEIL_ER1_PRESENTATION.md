# Traceveil: Evaluation Round 1 presentation (3–5 min + live APK demo)

*Reveal what apps do behind the scenes.*

## Read this first

**Assumptions (confirm before presenting)**
- The endpoint country map works on the demo phone (GeoLite2 file installed, Owner / legal-authority data filled with *sourced* entries only). Before the show, open both tabs on a real audit. If either looks empty, drop it from slide 6.
- Rights-notice drafting is finished and has a button on C2 findings.
- Baseline reuse is resolved, so the provenance caveat is removed.
- **Still not shown as working:** Voice Copilot, Network Proof, APK Risk Check, malware detection, Safety Coach. Say them as "next".
- The 21 / 100 scores come from audits saved on Nitya's iQOO 15 (`audit-1791570081266`, `audit-1791570260617`, documented in `docs/VERIFICATION.md`). Say "run earlier on our phone" unless you re-run both live.
- The audit **clears the chosen app's data**. Only audit our own LeakyShop demo app.
- I did not run the app or open the source links myself. Re-check each link before you present.

**Status tags:** ✅ working · 🟡 working with setup · 🔜 planned (say it, don't demo it)

**Palette:** `#5C374C` and `#7B435B` backgrounds · `#FAA275`, `#FF8C61`, `#CE6A85` accents · `#BCF8EC`, `#AED9E0` "evidence / good" · `#9FA0C3`, `#8B687F` muted text · `#985277` panels.

---

## Canva setup cues (Canva has no app context, so paste these)

- **Canvas:** 16:9. Background `#5C374C`. Body text `#FFFFFF` or `#BCF8EC`. Never put `#9FA0C3` text on `#7B435B`; it's too faint.
- **Fonts:** one bold sans for titles (the app uses Archivo; use it if Canva has it, otherwise Montserrat), one plain sans for body. Titles 40–48 pt, body 24 pt minimum.
- **Layout:** one idea per slide, at most 3 bullets. A gradient bar (`#FAA275` → `#FF8C61` → `#CE6A85`) across the top of every slide, 8 px high.
- **Evidence chips:** rounded labels. *Observed* = `#BCF8EC` fill with `#5C374C` text. *Inferred* = `#FAA275` fill. *Not established* = `#9FA0C3` fill. Reuse the same three everywhere.
- **Status badges (slide 8 only):** ✅ `#BCF8EC`, 🟡 `#AED9E0`, 🔜 dashed `#9FA0C3` outline.
- **Screenshots:** put each phone screenshot in a phone frame at about 40% of slide width, with a `#CE6A85` 3 px outline. Don't crop the timeline.
- **Screenshot files:** in `IQOO-Kernel-Panic/docs/screenshots/`: `home`, `picker`, `setup`, `timeline-reject`, `results`, `finding`, `fixes`, `report`, `pdf`, `leakyshop-consent`, `leakyshop-fixed-consent`, `fixed-result`. These README shots are from the older baseline and may show the former product name, so **retake them on the current build** before using them. Note that the README says the Camera Check screen is being removed, so don't show it.
- **Animation:** only "fade in" for the red tracker rows on slide 4. Nothing else.
- **Presenter notes:** paste each "Presenter cue" into Canva's notes field.

---

## Slide 1: Title

**On-slide**
- **Traceveil**
- *Reveal what apps do behind the scenes.*
- An on-device privacy and consent auditor for Android
- Team Kernel Panic

**Visual / Canva cue:** Logo (`assets/logo.png`) centred, gradient bar on top, nothing else. Leave room; this slide is on screen for 10 seconds.

**Presenter cue:** "Apps talk to other companies in the background, and you can't see it. Traceveil shows you."

**APK action:** Have the Traceveil Home screen open on the phone.

---

## Slide 2: The problem

**On-slide**
- **You tap "Reject". Does the app listen?**
- EFF reported that some ad SDKs can collect precise location **by default** once an app has location permission, and developers may not realise it. ([EFF](https://www.eff.org/deeplinks/2026/07/developers-beware-ad-libraries-betray-your-users-location-privacy))
- Two apps in that investigation had **60 million downloads combined**. ([TechCrunch](https://techcrunch.com/2026/08/04/android-app-developers-may-be-unwittingly-sharing-their-users-location-data-with-advertisers/))
- A study of **1.1 million Play Store apps** found privacy labels that don't match reality. ([USENIX Security 2024](https://www.usenix.org/conference/usenixsecurity24/presentation/khandelwal))

### B. Novelty & Impact (20%) — *Projected: 17–19 / 20*
* **Why it scores high:** DPDP penalties take effect starting **13 November 2026** (up to ₹50 Cr for consent breaches, ₹200 Cr for children's data under Section 9). It solves a proven industry blind spot: 29%+ of Android apps invoke third-party tracking SDKs *before* the consent screen appears.
* **Differentiation:** Cloud scanners (Google Checks, Appknox) upload source code or binaries to third-party servers. DPDP X-Ray runs completely on the device—**0 bytes of audit telemetry leave the phone**.

**Visual / Canva cue:** Left, a big mock "Reject" button (`#CE6A85`) with a red line leading to three small grey boxes labelled "tracker". Right, two large numbers, **60 million** and **1.1 million**, in `#FAA275`, with the source name in small `#BCF8EC` text underneath.

**Presenter cue:** "Everyone gets one privacy choice and nobody can check whether the app honoured it. Even a permission can quietly switch on a third party."

**APK action:** None. Stay on the slide.

**Human explanation:** Imagine telling a shop "don't follow me" and then seeing someone still trailing you. Traceveil is the way to check.

**Wording rules**
- Say "can", because that is what EFF reported. Don't name a specific app as leaking unless Traceveil has audited that exact app.
- The 60 million figure is from TechCrunch's coverage of two apps in the investigation.

**Optional Indian-fraud hook (swap in if judges want a local story):** Fake e-Challan APKs spread by message; CERT-In described a dropper that installed a second app and asked for SMS, VPN and background access ([CERT-In, 17 Mar 2026](https://www.cert-in.org.in/s2cMainServlet?CACODE=CICA-2026-3492&pageid=PUBADV01)). Chandigarh police reported 185 APK-fraud complaints in three months ([Indian Express](https://indianexpress.com/article/cities/chandigarh/fraudsters-new-weapon-to-target-android-smartphones-apk-files-10332841/)); that is a local figure, not a national one. Add the line: "Traceveil is not an antivirus; it explains risky access and shows what an app contacts."

---

## Slide 3: Our solution

**On-slide**
- **Traceveil plays the user, then times everything.**
- 1 Open the app fresh → 2 Say **No** → 3 Say **Yes**
- Every company the app contacts is **timed against your tap**
- Runs **on the phone**: no account, no upload

**Visual / Canva cue:** A three-step horizontal timeline: *Before any choice → After Reject → After Accept*. A red marker at the Reject point labelled "tracker contacted 0.1 s later" (the README's example screenshot shows this; use that exact figure only if your retaken screenshot shows it). Three evidence chips along the bottom.

**Presenter cue:** "We don't guess from a privacy policy. We watch the phone's own network lookups and line them up with your tap."

**APK action:** On Home, point at the **Before you audit** checklist (VPN, consent watcher, Private DNS off).

**Human explanation:** It works like a stopwatch next to a "No thanks" button. If something happens after "No", the stopwatch shows it.

**How it works (one line):** a local VPN sees only DNS lookups for the one app being audited (it doesn't decrypt anything), and an accessibility service reads the consent screen.

---

## Slide 4: APK demo, Audit ✅

**On-slide**
- **Pick an app. Run the audit.**
- Full audit about 2 min · Quick audit about 40 s
- Demo app: **LeakyShop** (controlled test app)
- *The audit resets the app's data. We only use our own test app.*

**Visual / Canva cue:** Three phone frames in a row: `picker` → `setup` → `timeline-reject`. Fade in the red rows on the third.

**Presenter cue:** "I'll audit LeakyShop, our test shop. The red rows are trackers contacted after I refused."

**APK action:** Home → pick an app → **LeakyShop (leaky)** → **Quick audit** → watch the live timeline. Start the audit during slide 3 so it's ready, or use a saved result. Fallback: **Open sample audit** (clearly labelled as a sample).

**Impact:** Nobody needs a laptop, a proxy or a certificate. Anyone with a phone can run it.

**Human explanation:** The app opens fresh, as if you just installed it. Traceveil taps Reject for you and writes down what happens next, second by second.

---

## Slide 5: APK demo, Evidence ✅

**On-slide**
- **Score 21 / 100: At risk** (leaky app) → **100: DPDP-ready** (fixed app)
- Found: trackers **before consent**, **after Reject (critical)**, a pre-ticked box, Reject hidden behind "Manage"
- Device IDs *likely* shared = **Inferred**
- **Limit:** DNS shows an app *tried to contact* a tracker, not *what it sent*

**Visual / Canva cue:** `results` and `fixed-result` side by side with a big arrow between them. A legend of the three evidence chips below.

**Presenter cue:** "Tap a finding to see the evidence, the exact law text and the fix. After the fix, the same audit scores 100."

**APK action:** Results → tap the **C2** finding → show the evidence and the DPDP text → **How to fix** → open the fixed app's saved audit (100).

**Impact:** Findings become repeatable. A developer fixes, re-runs and gets a number that moved.

**Human explanation:** It's a report card for consent. 21 means "fix this now"; 100 means "nothing wrong found in what we captured". It doesn't mean "safe".

**Findings reference (for Q&A)**

| Check | Plain meaning |
|---|---|
| C1 | Trackers contacted before any choice |
| C2 | Trackers contacted after the user refused |
| C3 | A consent box is pre-ticked |
| C4 | Refusal is hidden behind "Manage" |
| C10 | Ad or device IDs likely shared (**inferred**) |
| V0 | Too little data, so the result is **inconclusive**, never "clean" |

Penalty ceilings in the README are the Act's statutory maximums, not predicted fines. Don't put them on a slide.

---

## Slide 6: Privacy intelligence 🟡 (country map and rights notice)

**On-slide**
- **Who is on the other end?**
- **Source country / DNS:** where tracker lookups were answered *(inferred)*
- **Owner / legal authority:** who runs the service and which law may apply *(sourced)*
- **Rights notice:** one tap drafts a dated request naming the trackers contacted after you pressed Reject
- *Not proof that personal data crossed a border.*

**Visual / Canva cue:** Left, the endpoint map card with the Source tab selected (screenshot from the final build). Right, a mock of the notice draft. Use two map colours: `#AED9E0` for source countries, `#CE6A85` for company jurisdiction. Never use one colour for both.

**Presenter cue:** "Where a server answers from is not where the company is registered, so we show them separately. And if a tracker kept going after Reject, one tap drafts a notice to the developer."

**APK action:** Open the **Endpoint map** card on a finished audit → toggle between **Source country / DNS** and **Owner / legal authority** → on the C2 finding tap **Draft notice**. Where the card lives depends on how it is mounted in the final build. At the time of this check it was in the AI lab.

**Impact:** Moves "there are trackers" to "this company, in this place, after this tap". That gives a user or a developer something concrete to act on.

**Human explanation:** The map is like the postmark on a letter: it says where the answer came from, not what was inside. The owner tab says who the company is. The notice is a polite, pre-filled email: "you contacted these services 0.1 seconds after I said no; please explain."

**Wording rules**
- Notice text states what was *observed*. It never says "violated" or "illegal".
- Owner country and legal authority appear only where a source link exists. Anything else shows *Unknown*.
- Never say "your data left India". Say "a lookup was answered from this country".

---

## Slide 7: Impact and innovation

**On-slide**
- **Users:** see if "Reject" meant anything
- **Developers:** find the SDK that fires too early, copy the fix, re-test
- **Legal and product teams:** evidence, not just a design mock-up
- **What's different:** on-phone · timed to the exact tap · honest labels (Observed / Inferred / Inconclusive)
- **DPDP:** Section 6 asks for consent that is specific, informed and unambiguous, and as easy to withdraw as to give

**Visual / Canva cue:** Three columns (Users / Developers / Legal) in `#CE6A85`, `#985277`, `#7B435B`, each with one icon. A thin "DPDP s.6" badge in `#FAA275` in the corner.

**Presenter cue:** "This helps people see their choice respected or ignored, and gives developers a way to prove they fixed it."

**APK action:** Show the exported **PDF report** (Report → **Save PDF to Downloads**).

**Human explanation:** The Act says your "yes" must be informed and your "no" must count. Traceveil is a way to see whether that happened, without being a lawyer.

**DPDP wording to keep:** "relevant to DPDP consent requirements", "technical assessment, not legal advice". Don't say "this app breaks the law" or "certified compliant". Some processing may rely on a legitimate-use exception, and that is a legal judgement.

---

## Slide 8: Closing and future

**On-slide**
- **"Consent is only real if you can check it."**
- ✅ Working: on-device audit, consent timeline, findings with DPDP text, fixes, PDF report
- 🟡 Working with setup: country map, owner view (GeoLite2 file on the phone), rights-notice drafting
- 🔜 Next: voice questions about an audit, Network Proof ("what Traceveil itself uploaded"), APK Risk Check (identity and permission explainer)

**Visual / Canva cue:** Three stacked rows using the status badges. Logo bottom-right.

**Presenter cue:** "Today it audits consent. Next it explains risky permissions and answers questions by voice, still on your phone."

**APK action:** Return to Home. Optional: **Open sample audit**.

**Closing line for judges:** "No findings does not mean an app is safe. Traceveil reports what it observed, labels what it inferred, and says when it can't tell."

---

## Feature-by-feature impact and plain-language explanation

| Feature | Status | Human explanation | Impact |
|---|---|---|---|
| Per-app DNS capture | ✅ | Writes down every company name the app looks up, with a timestamp | Makes background activity visible without root or a laptop |
| Consent watcher | ✅ | Reads the consent screen and records the exact moment of Accept or Reject | Lets tracker timing be tied to the user's choice |
| Full / Quick audit | ✅ | Runs the app three ways (no touch, reject, accept) or one way | Shows what fires before a choice and after "No" |
| Findings C1–C10, NC, V0 | ✅ | A checklist of consent problems, each marked Observed or Inferred | Clear, repeatable results |
| Readiness score | ✅ | One number out of 100 with a band | Easy to compare before and after a fix |
| DPDP citations | ✅ | Each finding shows the exact Act text it relates to | Connects tech behaviour to the law without legal advice |
| Fixes | ✅ | Copy-paste code for each SDK problem | Developers can act immediately |
| PDF / Markdown report | ✅ | A shareable summary | Evidence a team or reviewer can read |
| LeakyShop | ✅ | A safe pretend shop that behaves badly on purpose (leaky and fixed versions) | Reproducible demo; proves fixes work |
| Country map: Source / DNS | 🟡 | Shows which countries answered tracker lookups | Cross-border context, labelled as inferred |
| Country map: Owner / legal authority | 🟡 | Shows who runs a tracker and which law may apply, only with sources | Separates "where it answered" from "who is responsible" |
| Rights-notice drafting | 🟡 | Pre-fills a dated message naming trackers contacted after Reject | Turns a finding into an action a user or developer can send |
| DNS alias (CNAME) detection | 🟡 | Spots a tracker hiding behind a first-party-looking name | Harder for trackers to dodge. The chip may not be in the UI yet |
| Voice Copilot | 🔜 | Ask the audit a question and hear an answer drawn only from its evidence | Accessibility, and a helpful explainer for non-experts |
| Network Proof | 🔜 | Measures what Traceveil itself sent, to back "0 bytes leave the device" | Trust in the auditor. The result is an estimate, never "proof of zero" |
| APK Risk Check | 🔜 | Before install, explains identity and risky permissions | Helps against sideloaded fraud. Not malware detection |

---

## Q&A cheat-sheet

- **"Is it an antivirus?"** No. It is a privacy and consent auditor. It can explain risky access but can't detect every malicious app. A missing match never means "safe".
- **"Does DNS prove data was sent?"** No. It proves the app tried to contact a host. Payload claims are marked Inferred.
- **"What if an app uses encrypted DNS?"** It may hide lookups. Traceveil asks for Private DNS off and reports **inconclusive**, not clean, when it sees too little.
- **"Does it send my data anywhere?"** No cloud service, account or upload. Reports leave the phone only if you share them.
- **"Is the map proof of a cross-border transfer?"** No. It shows where lookups were answered and, separately, where a company is registered.
- **"Is 21 a legal verdict?"** No. It is a technical score. Legitimate-use exceptions are a legal question.
- **"Why LeakyShop?"** The audit clears app data, so we test on an app we own. It has no real SDKs; it makes the same lookups and sends no data.
- **"Is the AI used?"** Optional. Earlier phone tests had AI explanations return empty and fall back to deterministic checks. Don't present AI explanations as a working feature unless re-tested.

---

## Sources

- [EFF: Ad libraries and location privacy](https://www.eff.org/deeplinks/2026/07/developers-beware-ad-libraries-betray-your-users-location-privacy)
- [TechCrunch: SDKs and location-data sharing, Aug 2026](https://techcrunch.com/2026/08/04/android-app-developers-may-be-unwittingly-sharing-their-users-location-data-with-advertisers/)
- [USENIX Security 2024: Unpacking Privacy Labels](https://www.usenix.org/conference/usenixsecurity24/presentation/khandelwal)
- [CERT-In: e-Challan APK campaign, Mar 2026](https://www.cert-in.org.in/s2cMainServlet?CACODE=CICA-2026-3492&pageid=PUBADV01)
- [Indian Express: Chandigarh APK fraud, Oct 2025](https://indianexpress.com/article/cities/chandigarh/fraudsters-new-weapon-to-target-android-smartphones-apk-files-10332841/)
- Internal: `README.md`, `docs/VERIFICATION.md`, `docs/TEAM_HANDOFF.md`, `docs/planning/TRACEVEIL_ANDROID_APK_MISUSE_CASES.md`
