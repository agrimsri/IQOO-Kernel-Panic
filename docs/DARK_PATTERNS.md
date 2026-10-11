# Dark-pattern signals (India CCPA 2023)

TraceVeil reads the consent screen it already records, plus the taps around it, and reports **signals** that resemble
patterns in the Central Consumer Protection Authority's (CCPA) 2023 list of dark patterns.

- They are **signals for review, not legal findings**.
- They are **not part of the score**. They do not add a check, a finding or a weight. The locked regressions stay at
  LeakyShop leaky 21/100 (rules only), 8/100 (with the AI screen reading) and fixed 100/100. See
  `DemoScoreRegressionTest`, "a popup shown again after Reject keeps 21, 8 and 100".
- Every row says "resembles the pattern described in CCPA 2023 Annexure 1 #n". It never says that a screen "is" a dark
  pattern, or that anything is illegal or a violation. The reason is that Annexure 1 itself says it is guidance (quoted
  below).

## Source

**Official text:** Central Consumer Protection Authority, *Guidelines for Prevention and Regulation of Dark Patterns,
2023*.
- Published in the Gazette of India, Extraordinary, Part III Sec. 4.
- F. No. CCPA-1/1/2023-CCPA-(Reg), "New Delhi, the 30th November, 2023". Gazette ID CG-DL-E-30112023-250339.
- PDF on the CCPA's site, read 11 Oct 2026:
  https://ccpa.doca.gov.in/files/The%20Guidelines%20for%20Prevention%20and%20Regulation%20of%20Dark%20Patterns,%202023_1732707717.pdf
- The quotes below were taken from that PDF's text layer. Line-break hyphens were joined; nothing else was changed.

**Status of Annexure 1**, quoted exactly:

> "The dark pattern practices and illustrations specified below provide only guidance and shall not be construed as an
> interpretation of law or as a binding opinion or decision as different facts or conditions may entail different
> interpretations"

**Scope** (clause 3): "(i) all platforms, systematically offering goods or services in India; (ii) advertisers; (iii) sellers."

### The six patterns TraceVeil checks, with their official definitions

| # | Pattern | Definition (verbatim) | Illustration TraceVeil mirrors (verbatim) |
| --- | --- | --- | --- |
| 1 | False Urgency | "falsely stating or implying the sense of urgency or scarcity so as to mislead a user into making an immediate purchase or taking an immediate action, which may lead to a purchase, including - (i) showing false popularity of a product or service to manipulate user decision; (ii) stating that quantities of a particular product or service are more limited than they actually are." | (a) "Only 2 rooms left! 30 others are looking at this right now"; (b) "describing a sale as an 'exclusive' sale for a limited time only for a select group of users" |
| 3 | Confirm shaming | "using a phrase, video, audio or any other means to create a sense of fear or shame or ridicule or guilt in the mind of the user so as to nudge the user to act in a certain way that results in the user purchasing a product or service from the platform or continuing a subscription of a service, primarily for the purpose of making commercial gains by subverting consumer choice." | (a) "I will stay unsecured"; (b) "charity is for rich, I don't care" |
| 4 | Forced action | "forcing a user into taking an action that would require the user to buy any additional goods or subscribe or sign up for an unrelated service or share personal information in order to buy or subscribe to the product or service originally intended by the user." | (f) "Making it difficult for consumers to understand and alter their privacy settings, thereby encouraging them to give more personal information than they mean to while making the intended purchase." |
| 6 | Interface interference | "a design element that manipulates the user interface in ways that (a) highlights certain specific information; and (b) obscures other relevant information relative to the other information; to misdirect a user from taking an action as desired." | (a) "designing a light colored option for selecting "No" in response to a pop-up asking a user if they wish to make a purchase or concealing the cancellation symbol in tiny font" |
| 10 | Nagging | "a dark pattern practice due to which a user is disrupted and annoyed by repeated and persistent interactions, in the form of requests, information, options, or interruptions, to effectuate a transaction and make some commercial gains, unless specifically permitted by the user." | (c) "constant request to turn on or accept notifications or cookies with no option to say "NO"." |
| 11 | Trick Question | "the deliberate use of confusing or vague language like confusing wording, double negatives, or other similar tricks, in order to misguide or misdirect a user from taking desired action or leading consumer to take a specific response or action" | (a) "Do you wish to opt out of receiving updates on our collection and discounts forever?" |

**Not checked (7 of 13).** These happen at checkout, pricing or billing, not at a consent screen:
- 2 Basket sneaking. A pre-ticked consent box is check C3, not basket sneaking.
- 5 Subscription trap.
- 7 Bait and switch.
- 8 Drip pricing.
- 9 Disguised advertisement.
- 12 SaaS billing.
- 13 Rogue malwares.

**Enforcement context.** This is secondary for this document. The quote below comes from the team's research notes, which are kept outside this repository. When
this was written, PIB returned HTTP 403 to a re-fetch.
- Source: PIB, 3 Jun 2026, "CCPA Acts Against Dark Patterns on Digital Platforms",
  https://www.pib.gov.in/PressReleasePage.aspx?PRID=2268302.
- It describes a "Renew Now" vs "Accept Risk" screen as confirm shaming and as interface interference ("Giving greater
  visual prominence to the renewal option").
- LeakyShop's demo popup is modelled on that screen. No rupee amounts are quoted here, by design.

## What is detected, and how

Code: `core/src/main/kotlin/com/dpdpxray/core/darkpattern/`.
- `DarkPatternScanner` holds the rules, `DarkPatternCues` the word lists and `DarkPatternText` the shown words.
- It is pure. It reads saved `AuditSession`s only:
  - the consent-screen trees (`SCREEN_APPEARED`, `MANUAL_MARK`, `REPROMPT`) that `ConsentLexicon.isConsentScreen`
    accepts;
  - the recorded taps (`SETTINGS`, `ACCEPT`, `REJECT`).

Each signal is a `DarkPatternSignal(pattern, evidence, evidenceType, plans, source)`, where `source` is
`"CCPA India 2023 Annexure 1 #n"`. There is at most one signal per pattern. Signals are sorted by Annexure number.

### Options on a consent screen

Every clickable node with a short label is an option. Compose puts the text on a child node, so that text is read too.
Each option gets one role:

- **Refuse:**
  - a confirm-shaming phrase;
  - a Reject phrase in `ConsentLexicon`;
  - "No", "Later" or "Maybe later".
- **Manage:** a settings phrase in `ConsentLexicon`.
- **Close:** "Close", "Skip", "Cancel", "X", "×", "बंद करें" and similar.
- **Accept:** an accept phrase in `ConsentLexicon`.

An option is **a button** when its node, or a child (Compose's Button role), has a `…Button` class. Otherwise it is
**plain text**.

### The rules

| Pattern | Rule (OBSERVED) | Example evidence line |
| --- | --- | --- |
| False urgency (1) | Any text on a consent screen matches the urgency list (EN/HI/KN): "offer ends", "ends in 4…", "only N left", "limited time", "hurry", "last chance", "N others are looking", "सीमित समय", "केवल N बचे", a countdown followed by "खत्म", "ಸೀಮಿತ ಸಮಯ". | `Consent-screen text: "Offer ends in 09:59".` |
| Confirm shaming (3) | The label of a decline option matches the guilt list: "I prefer paying more", "I'd rather pay full price", "I don't care", "I'll stay unprotected", "Accept Risk", "I'm fine missing out", "मुझे बचत/छूट नहीं चाहिए", "परवाह नहीं", "ज़्यादा पैसे", "ನನಗೆ ಉಳಿತಾಯ ಬೇಡ". Body text is not read. | `The decline option reads "No, I prefer paying more".` |
| Forced action (4) | The consent screen offers Accept and nothing else: no refuse, manage or close option. This overlaps C4, which is scored; the signal is only shown. | `Only "Accept" was offered: no Reject, Manage or close option was found on the consent screen.` |
| Interface interference (6) | The way to refuse is the most prominent Reject-type option, or Manage when there is none. A signal is raised when any of these hold: **(a)** Accept is a button and the way to refuse is plain text; **(b)** the way to refuse covers less than half of Accept's on-screen area (node bounds); **(c)** refusing took more recorded taps than accepting. For (c), the taps are the `SETTINGS` taps before the Reject in Plan B. Acceptance is counted from Plan A's recorded taps, or as one tap when Accept is on the first screen. | `"Accept & Continue" is shown as a button; the way to refuse ("Manage") is plain text.` / `"Accept & Continue" covers 3.4× the screen area of "Manage" (587×171 px vs 171×171 px).` / `Refusing took 2 taps ("Manage" → "Reject all"); accepting took 1 ("Accept & Continue").` |
| Nagging (10) | The plan's recorded choice is Reject, **and** a `REPROMPT` came at least 1 s after it, **and** that screen is still a consent screen with an Accept-type option. | `The consent popup came back 5.2 s after the recorded Reject: "Wait! Get 10% off by allowing personalised offers".` |
| Trick question (11) | Any consent-screen text with a double negative: "uncheck/untick … to not / if you don't", "do not uncheck / do not opt out", "do not wish to not", "not to not", "अनचेक … नहीं". | `Double-negative wording: "Uncheck to not receive offers".` |

**AI readings (INFERRED).** The on-device AI may return free-text `darkPatterns` names. These appear on
`ScreenAudit.darkPatterns` when the screen was reviewed by the model. When such a name matches one of the six patterns,
it is added as an **AI-inferred** row. Two cases:
- An AI-only "no way to refuse" (`rejectOption = NONE`, not observed in the tree) adds an inferred Forced action row.
- **Observed always wins.** An inferred row appears only for a pattern the rules did not observe.

Names that do not match a pattern (for example "pre-ticked checkbox") are left out. C3 already covers a pre-ticked box.

### Watcher change: "shown again after the choice"

`ConsentKind.REPROMPT` is new and is added at the end of the enum.
- The accessibility watcher still records only the first consent screen of a plan as `SCREEN_APPEARED`.
- A consent screen that disappears and then comes back after a recorded Accept/Reject is recorded as `REPROMPT`, with
  its tree. There is no screenshot.
- At most three are recorded per plan. The same kind within 1.5 s is dropped as a duplicate, as for every consent
  event.

The rule lives in `ConsentReappearance` (app), which is pure and has unit tests. No phase, check or score reads
`REPROMPT`:
- `PhaseAssigner` uses the first Accept/Reject;
- C14 and C15 read `SCREEN_APPEARED`/`MANUAL_MARK` only.

## Where it shows

**Results, developer mode.** There is a collapsible card **"Dark-pattern signals (India CCPA 2023)"** right after the
findings and the SDK card.
- **Summary:**
  - with signals: "N of 6 checkable patterns seen at the consent moment";
  - when some rows are AI-only, it adds "(k from the AI reading only)";
  - with no signals: "None seen in this capture";
  - with no tree: "Not checked: no consent-screen tree was captured".
- The card opens by itself when it has a signal.
- **Each row** has:
  - a title, for example "Nagging · Annexure 1 #10";
  - an **Observed** or **AI-inferred** chip;
  - "Resembles the pattern described in CCPA 2023 Annexure 1 #10 (Nagging).";
  - the evidence lines as bullets;
  - the plans, for example "Plan B · Reject".
- **Footer:** "Signals for review, not legal findings, and not counted in the score. Each one resembles a pattern
  described in Annexure 1 of the CCPA's Guidelines for Prevention and Regulation of Dark Patterns, 2023, which says its
  list provides "only guidance". Checked only on the consent screen and the seconds after the choice: 7 of the 13 listed
  patterns happen at checkout, pricing or billing and are not checked here."

**Results, everyday mode.** One line, for example: "Consent popup design: 4 signals resembling India's CCPA dark-pattern
list (false urgency, confirm shaming, interface interference, nagging). Not part of the score."
- With no signals, the line reads "Consent popup design: no dark-pattern signals seen in this capture."
- There is no line when no tree was captured.

**Markdown/PDF and HTML reports.**
- The Markdown/PDF report has its own section, "## Dark-pattern signals (India CCPA 2023)", after the findings. Each
  signal carries its evidence, the verbatim definition and the source URL.
- The HTML report has a "Dark patterns" plate (04) between Findings and Network. The Network and Method plates are now
  05 and 06.

**JSON export (`traceveil.audit.v1`).** It has an optional top-level part:

```json
"darkPatterns": {
  "basis": "Checked on the phone with fixed rules … they do not change the score.",
  "screensChecked": 4,
  "signals": [
    { "pattern": "FALSE_URGENCY", "evidence": ["Consent-screen text: \"Offer ends in 09:59\"."],
      "evidenceType": "OBSERVED", "plans": ["REJECT"], "source": "CCPA India 2023 Annexure 1 #1" }
  ]
}
```

- The phone always writes this part on "Export audit data (JSON)". It is omitted when it is not set, so the CLI samples
  and every bundle built without it keep exactly the existing v1 shape. Readers that predate it ignore it.
- An empty `signals` list with `screensChecked > 0` means "none seen in this capture".
- A missing `darkPatterns` part means "not exported".

## LeakyShop demo

The demo uses a new buildConfig flag, `NAG_AFTER_REJECT`:
- `leaky`: true.
- `fixed` and `leakyV11`: false. Both are unchanged, so V11 stays a clean "partial fix" for the update demo.

With the flag on, after "Reject all" (behind "Manage") the leaky app waits **5 s** and then shows a second popup:
- title: "Wait! Get 10% off by allowing personalised offers";
- "Offer ends in 09:59", counting down once a second, in bold red;
- "Share data with our partners to unlock your discount.";
- a full-width **"Allow & save"** button;
- a small grey **"No, I prefer paying more"** text link.

How the popup behaves:
- "Allow & save" stores Accept. "No, I prefer paying more" closes the popup.
- **Neither contacts any host.**
- Leaving the screen (Home) cancels a pending or open nag.
- The first popup is byte-for-byte unchanged.

### Signals expected per build

These come from the unit fixtures; the leaky and fixed trees are copied from recorded phone audits. They still need the
phone check below.

**leaky (1.0)**, Full audit with reset: 4 of 6 signals.
- **False urgency.** "Offer ends in 09:5x"; the exact second depends on the moment the tree is read.
- **Confirm shaming.** "No, I prefer paying more".
- **Interface interference:**
  - "Accept & Continue" is a button and "Manage" is plain text;
  - "Accept & Continue" covers about 3.4× the area of "Manage";
  - refusing took 2 taps against 1;
  - "Allow & save" is a button and the decline is plain text.
- **Nagging.** The popup came back about 5 s after the recorded Reject.
- The score stays **21**, with the same findings.

**fixed:** "None seen in this capture". 100/100 is unchanged.

**leakyV11 (1.1):** expected "None seen in this capture".
- Reject is a visible OutlinedButton, and refusing takes one tap.
- On the recorded fixed tree, the same "Reject" button is 314×172 px. "Accept & Continue" is 587×171 px. That is a ratio
  of about 0.54, just above the 0.5 threshold.
- A larger font or display size could tip it into a size signal.

## Limits

- **Signals, not legal findings.** The guidelines frame most patterns around purchases and commercial gain. Annexure 1 is
  "only guidance". A word-list match does not prove intent.
- **Only the consent moment is checked:** the recorded consent trees and the 15 s observation window after the choice. A
  nag that comes later, or on another screen, is not seen.
- **Size and style come from accessibility bounds and classes, not from pixels.**
  - Colour and contrast (for example a light grey "No") are not measured.
  - Compose may enlarge a small clickable to a 48 dp touch target, which understates how small it looks.
- **Word lists are short.**
  - English, Hindi and Kannada only.
  - The Hindi and Kannada cues are literal and need a native speaker's review.
  - Other languages are not checked.
- **The tap cost needs the recorded taps.** Older audits made without the agent's "Manage" step, or manual runs where the
  app reports no click events, may show nothing.
- **Older readers.** A `REPROMPT` event inside a bundle cannot be read by an older TraceVeil/`traceveil-ci` build. The
  enum value is new: older apps and the CLI must be updated together.
- The web console (`console/`) is not updated. It labels a `REPROMPT` event as a tap.

## Phone check (not yet done on a device)

1. Build `:app:assembleDebug` and `:leakyshop:assembleLeakyDebug`. Install both: TraceVeil must be this build, or it
   cannot record `REPROMPT`.
2. In TraceVeil, run a **Full** audit of LeakyShop with reset approved and the agent driving.
3. In Plan B, watch the agent tap "Manage", then "Reject all". About 5 s later the second popup appears with the
   countdown. Leave it alone. TraceVeil presses Home at the end of the window, and the popup closes.
4. Open the result in developer mode:
   - the score should still be 21 with the same findings;
   - the "Dark-pattern signals (India CCPA 2023)" card should be open with the four rows listed above.
5. Switch to everyday mode: there should be one line instead of the card.
6. Export the audit as JSON and check that it contains `darkPatterns`.
7. Repeat with LeakyShop Fixed. The card should say "None seen in this capture" and the score should be 100.

## Tests

- **Core:**
  - `DarkPatternScannerTest`: each rule, positive and negative, including the fixed dialog with no signals, Hindi and
    Kannada cues, observed-over-inferred, and neutral wording.
  - `DarkPatternReportTest`: card copy, the Markdown and HTML sections, and the export field.
  - `DemoScoreRegressionTest`: 21, 8 and 100 with the nag.
- **App:**
  - `ConsentReappearanceTest`: the watcher rule and the bus.
  - `JsonExportTest`: the optional field.
  - `DarkPatternViewTest`: the card and everyday line for the in-app sample.
