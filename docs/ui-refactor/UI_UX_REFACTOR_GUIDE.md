# TraceVeil UI/UX refactor and design guide

Branch: `ui-refactor` (from `main` at `d9c7602`)
Scope: the Android app (`app/src/main/java/com/dpdpxray/app/ui/**` plus the screen files inside feature packages).
Out of scope: audit engine, capture, scoring, legal lens rules, exporters, voice engines, CLI, Python tooling.

> **The rule that governs everything below:** this is a skin-and-structure refactor. Every capability the app has today
> must still exist, still be reachable, and still behave identically. We change what is shown, how much is shown, where
> it lives and how it looks. We never change what the engine does.

---

## 1. Why this exists

TraceVeil answers one question: *does this app respect the user's choice?* The engine is strong. The front of the app
does not match it: every screen explains itself, repeats itself, and decorates itself. A first-time user meets a hero,
a status chip, a setup list, a guide banner, a mode switch and five cards before they can start a check.

Target feeling: **Apple-like**. Calm, spacious, one thing at a time, quiet colour, content before chrome.

---

## 2. How the review was done (and its limits)

* Device: vivo iQOO `I2501`, Android 16, 1440 × 3168. The screens were run on the phone and captured with `adb`.
* **Two builds were captured.** The build already installed on the phone was older than `main`: it showed the older
  single-mode Home ("Before you audit"), Settings, AI lab, Fixes and Report screens (`13`–`19` in `baseline/`, marked
  *legacy*). It was signed with a different key, so it was uninstalled and the current `main` debug build was
  installed (`01`–`12`). Saved audits from the old install were lost; that was agreed beforehand.
* **Screens run and captured:** onboarding (3 pages), Everyday Home (full scroll), APK Guard hub, Reports (empty),
  More, a sample result (score, guidance, findings). Legacy build: developer Home, a real result, Fixes, Report,
  Settings, AI lab.
* **Screens reviewed from code only** (they need an armed VPN and accessibility service, or live data, to reach):
  app picker and check sheet, live audit, finding detail, camera check, voice copilot, quarantine, phone checkup, APK
  result screens, Trust, dialogs. Recommendations for these are marked **(code review)**. Capture them on the new
  UI before sign-off.
* Evidence lives in [`baseline/`](baseline/) (600 px wide, numbered). File names are referenced as `[01]`, `[13]`.

---

## 3. Non-negotiables: what must not change

### 3.1 Behaviour contracts

| Contract | Where | Rule |
|---|---|---|
| Route names and arguments | `MainActivity.kt` NavHost (`home`, `pick?q=`, `live`, `results/{id}`, `finding/{id}/{check}`, `fixes/{id}`, `report/{id}`, `lab`, `camera`, `settings`, `apkguard`, `voice/{id}`, quarantine, checkup, trust, onboarding) | Same strings, same args. Voice assistant, notifications, shortcuts and `HeyTraceVeil` deep-link into them. |
| View-model API | `XrayViewModel`, `VoiceViewModel`, `AuditOrchestrator`, `SettingsStore` | UI may call it; it may not change behaviour. New UI state lives in composables or a new UI-only holder. |
| Gates | `vm.prepareAudit()`, `SetupSteps`, tab gate (`Check` → Home when VPN/watcher missing) | Same preconditions, same order. |
| Setting keys and codecs | `SettingsStore`, `SettingsCodecTest` | No renames. Adding a UI-only key is allowed. |
| Both audiences | `Audience.USER` (Everyday) and developer | Both stay. Same engine, different front door. |
| Hindi + read-aloud | `GuideVoice`, `GuideLang`, `OnboardingCopy`, `EverydayCopy` | Stay. Their *placement* changes (§6.4). |
| Accessibility | TalkBack roles, headings, `stateDescription`, 48 dp targets, reduce-motion, stage mode (1.18× text) | Must be equal or better. |
| Exports | `Exporters`, `EvidenceReport`, PDF/Markdown/HTML/JSON | Output unchanged. Only the preview on screen changes. |
| Layout width | content capped at 680 dp, centred | Keep. |

### 3.2 Tests that pin UI-adjacent facts (decide deliberately, never by accident)

| Test | Pins | Plan |
|---|---|---|
| `AppTabsTest` | five tabs, order, labels ≤ 10 chars, route ↔ tab mapping | Phase 1 keeps it green. Any tab change is decision **D1** and edits this test in the same commit. |
| `BrandPaletteTest` / `BrandPalette.kt` | ten map colours "exactly as given", contrast ≥ 4.5 / 3 | The endpoint-map palette is a *data-viz* palette. Keep until the map card is restyled (§7.7), then update the test with the new values and keep the contrast assertions. |
| `TextDietTest` | `leadSentence()` logic, reduce-motion rule | Logic stays. `FoldText` usage shrinks (§5). |
| `EverydayCopyTest`, `OnboardingCopyTest`, `ResultGuideTest`, `SetupAndRecentTest`, `GuideFlowTest`, `CheckSheetTest`, `TrustTextTest`, `QuarantineRoutesTextTest` | copy objects and their logic | Copy is rewritten (§8). Update the test expectations in the same commit as the copy, with a reviewer reading both. |
| `ScreenMotionTest` | tab-switch vs push transitions | Keep behaviour; retune durations only. |
| `BrandMarkTest` | logo drawing | Keep. |

Run `./gradlew :app:testDebugUnitTest` after every phase. A red UI test means a contract was touched.

---

## 4. Critical analysis of the current UI

### 4.1 One-paragraph verdict

The app is **informative but loud**. It has roughly nine hues, gradients on buttons, borders, washes and the tab pill,
glows, grid backgrounds, a sweeping scanline, staggered reveal animations on 111 elements, and about 100 expandable text
blocks. It reads like a lab instrument ("radiograph", "film", "scan"), which suits a security tool but not the
calm, trustworthy, premium feel we want. The information is mostly right; there is simply far too much of it on the
first layer.

### 4.2 Measured symptoms (from `main`)

| Symptom | Count / evidence |
|---|---|
| `reveal(...)` staggered entrance animations | 111 call sites |
| `FoldText(...)` (text cut off with "…" and a "More ▾" toggle) | 55 call sites |
| `LearnMore(...)` / `CollapsibleCard(...)` | 51 call sites |
| Files using brand gradient, glow or sweep | 25 |
| Distinct hues on one screen (Everyday Home) | peach, coral, rose, scan-cyan, green, amber, orange, red, grey `[04]`–`[06]` |
| Text truncated mid-sentence with "…" | "…which addresses the checked ap…", "…TraceVeil knows …" `[03]`, `[04]` |
| Languages controls repeated | "Read aloud" + "हिन्दी" pills on every onboarding page `[01]`–`[03]`, speaker + हि on every guide banner `[11]` |
| Same fact shown in 3+ places | "Reads app files, never installs them" (Home hero, APK Guard hero, APK help text); "Checks run on this phone" (hero chip, onboarding, Trust, Settings, More) |

### 4.3 Screen by screen

Legend: **U** useless (no decision or action depends on it), **R** redundant (said elsewhere on the same or the
adjacent screen), **S** static (never changes, so it should be learned once, not re-read on every visit).

#### Onboarding `[01]`–`[03]`
* Five pages for a two-switch setup. Page 1 and 3 repeat each other ("results stay on this phone").
* Illustration phone with orbiting dots (**S**) takes 40 % of page 1.
* Audience choice (`I use apps` / `I build apps`) asks a question the user cannot yet answer, and gates *Next*
  (disabled "Pick one above").
* Every page carries *Read aloud*, *हिन्दी* and a *More ▾* fold. Three controls before the first sentence.
* Good: honest permission explanations ("what we need and why"), progress segments, Skip.

#### Everyday Home `[04]`–`[06]` (the front door for most users)
Stack, top to bottom: logo + title + gradient rule + settings gear → mode pill → hero card (chip, headline, subtitle,
two reassurance chips, big gradient button, "2 steps first" chip, "How it works ▾", paragraph, fold) → setup step 1
→ step 2 → step 3 → card "Check a file someone sent me" → card "Check my apps" → card "New-app alerts" → card "Why you
can trust TraceVeil" → "Recent checks" (empty state with illustration) → "Switch to developer mode".

* **R** Mode pill (Everyday/Developer) appears on Home *and* in More *and* in onboarding. Three places to switch a
  setting that is changed once.
* **R/S** Hero chips ("Checks run on this phone", "Reads app files, never installs them") are static trust copy
  already owned by the Trust screen.
* **R** "2 steps first" chip, the numbered steps, and the disabled-looking primary button all say the same thing.
* **U** "How it works ▾" on Home duplicates onboarding and More › How it works.
* **R** Setup items are repeated verbatim under the hero; the third (Private DNS) is optional yet looks equal to
  the two required ones.
* **S** "Why you can trust TraceVeil" is a permanent card on the busiest screen; it is reference material.
* **S** "New-app alerts" shows a *state* ("Only while TraceVeil is open") inside a card that navigates away to
  change it. It is a setting wearing a card.
* Four cards of identical weight: nothing says which matters. The primary action is visually 1 of 8.
* Scroll depth: Recent checks start only after the hero, three setup steps and four cards (about two screens down).

#### Developer Home (legacy build `[13]`)
* The "Before you audit" list shows five diagnostics (VPN, consent watcher, fresh-install reset, Private DNS,
  on-device AI) as coloured dots with sentences. Only two block a check. The rest are **U** until they matter.
* The disabled "Audit an app" button plus an amber sentence under it repeats the red rows above.
* Recent audits: 20+ rows, each an identical card with a coloured left bar, a count and a score. **R**: the same list
  is the Reports tab.

#### APK Guard `[07]`
* Hero card with three pills ("Not an antivirus", "Reads only, never installs", "Stays on this phone") plus a
  "How APK Guard works ▾" that opens a 100-word block. All **S**.
* "My phone checkup" and "New-install alerts" are two different kinds of thing (an action, a setting) styled as
  the same card.
* "Check an installed app": 19 rows with icon, name, *monospace package name* and an install date line. The package
  name and the "Install date not reported (preloaded)" line are **U** for the 95 % case; they are useful when
  searching, not scanning.
* The page is a hub with four jobs. It has no hierarchy among them.

#### Reports `[09]`
* Fine structure. Intro sentence + "More ▾" for one extra sentence (**U**). Empty state repeats the Home one **R**.

#### More `[08]`
* "Mode: Everyday" card with two big tiles and a *More ▾* explanation; the page's first screen is a setting that
  is almost never changed.
* "Tools" mixes real tools (Camera check) with demo content (See a sample, More samples) and help (How it works).
* Subtitle lines are all truncated to two lines.

#### Result `[10]`–`[12]`, legacy `[14]`
Order today: top bar → score card (ring, band, "Consent readiness", 2 tags, legal lens block with fold,
"Findings by severity" strip + 5 count chips) → headline sentence → guide banner ("Step 4 of 4, Read your result")
→ "What this means" card (+ caveat + "More about this result (1) ▾") → "What you can do next" (5 rows) → Findings (each with
3 tags + title + entity names + "Observed on device") → Evidence and limits (4 collapsible cards).

* **R** The headline sentence, the "What this means" card and the first finding all state the same conclusion.
* **S** Guide banner "Step 4 of 4" is a static coaching step with speaker and language toggles. It tells the user to
  "start with What this means", which is the thing directly under it.
* **R** Severity strip with 5 chips duplicates the finding list that follows and the ring colour.
* **R** "What you can do next": *See the evidence* = tap the first finding; *How to fix* / *Report* / *Ask* = actions
  that belong in one toolbar; *Check a real app* only matters for samples.
* **U/R** Each finding shows severity tag, "Observed", legal-lens relation tag ("Global baseline: Good practice"), title,
  entities in severity colour, and "Observed on device". Three of those are the same on all ten rows.
* **U** The legal lens appears in the score card, in every finding row, and in Settings.
* **S** "Consent readiness" under the band is a fixed label.
* The ring (60 % of the first screen) says "8 of 100" and the band says "At risk" and the headline says it again.
* Good: evidence/limits are collapsed; caveats ("a lookup is not proof personal data was sent") are honest and should
  survive in one place.

#### Findings detail, Fixes `[16]`, Report `[17]` **(partly code review)**
* Fixes: good content (copyable code, "Check the exact API… in the vendor docs"). The label "Code" above every block
  is **U**; the intro paragraph is **S**. Code lines run off the card with no scroll hint.
* Report: the screen shows the **raw Markdown source** (`**Score**`, `| --- |`, `> —`). It is a preview of a file, not a
  readable report. Three export buttons sit above a wall of syntax. This is the least premium screen in the app.

#### Settings `[18]`
* Three toggles, "Your own domains" (empty), Privacy paragraph, "Not legal advice" paragraph. Clear, but "Presentation
  mode" and "Fast audits" are demo switches shown to every user; legal-lens, voice, language and developer automation
  settings (present in code) have no grouping.

#### AI lab `[19]` (advanced feature, shown as a top-level destination in the legacy build)
* GPU/NPU/CPU buttons twice, a thermal number, an `adb push` command and a storage path. Meaningful to engineers,
  **U** to everyone else. Must stay reachable; must not be on the main path.

### 4.4 Cross-cutting problems

1. **Everything is a card.** Bordered, gradient-lit cards nest inside cards (Panel in Panel in Home). Depth has no
   meaning, so nothing is emphasised.
2. **Colour carries too much.** Severity, category, brand, status, mode and trust each use a hue. A red text link, an
   amber caveat, a cyan link, a peach button and a green chip can share one viewport.
3. **Truncate-then-expand.** Sentences are cut with "…" and hidden behind *More ▾*. Users must tap to finish a
   sentence. Either the sentence is needed (show it) or it is not (delete it).
4. **Teaching on every screen.** `GuideBanner`, `LearnMore`, helper paragraphs, chips. Learning content should be
   given once (onboarding), kept in one place (Help), and shown contextually only when state demands it.
5. **Dev vocabulary in consumer paths.** "Plan C · No interaction", "Thermal headroom 0.65", "BM25", "NPU", `adb push`,
   package names, check IDs `C1`/`C10`.
6. **Motion everywhere.** 111 staggered reveals, sweeping gradients, breathing dots, shimmering skeletons. Motion should
   indicate cause and effect, not decorate.
7. **Condensed display type.** Archivo at width 75–82 gives a "film label" voice. It is distinctive and not calm.
8. **Tab bar gradient pill** is the loudest control on screen, always visible.
9. **No consistent list pattern.** Cards, rows, tiles, chips and tags each re-solve the same problem.

---

## 5. Design principles

1. **One focus per screen.** One primary action, visually dominant. Everything else is secondary or hidden.
2. **Space is the divider.** Group with whitespace and tone, not borders and glows. 32 dp between sections is the norm.
3. **Say it once.** If a fact is on the screen, it is not repeated in a chip, a card and a sentence.
4. **Show, don't coach.** State changes the UI (a step disappears when done). Static coaching goes to onboarding/Help.
5. **Progressive disclosure that does not cut sentences.** First layer: complete, short. Detail: a separate screen or a
   sheet. No "…" truncation of prose.
6. **Colour is a signal.** Neutral by default. One accent for action. Status colour only on a dot or one word.
7. **Quiet motion.** Spring/ease on transitions and presses. Nothing animates at rest.
8. **Honest and plain.** Keep every caveat the product needs; write them in one calm voice. No jargon on the main path.

---

## 6. The new design language

### 6.1 Colour

Neutral-first, two themes that follow the system (dark remains the default look on first install for continuity;
light is new). Brand peach is demoted from "everywhere" to **one accent used for the single primary action and
selection**. Tokens below are proposals. Verify every text pair with the existing `Contrast` helper (≥ 4.5:1 text,
≥ 3:1 graphics) before merging.

| Token | Light | Dark | Use |
|---|---|---|---|
| `bg` | `#F5F5F7` | `#0E0E10` | screen background (flat, no gradient) |
| `surface` | `#FFFFFF` | `#1A1A1D` | grouped sections / cards |
| `surfaceRaised` | `#FFFFFF` | `#232327` | sheets, dialogs |
| `separator` | `#E5E5EA` | `#2C2C30` | hairlines inside groups (inset 16 dp) |
| `textPrimary` | `#1D1D1F` | `#F5F5F7` | titles, body |
| `textSecondary` | `#6E6E73` | `#A1A1A6` | supporting text (≥ 4.5:1 on `bg`) |
| `textTertiary` | `#8E8E93` | `#6E6E73` | captions only, never required info |
| `accent` | `#BF4F33` | `#FF9E80` | links, selection, primary fill (see below) |
| `onAccent` | `#FFFFFF` | `#1A0F0B` | text on a filled accent |
| `risk` | `#C9382F` | `#FF6B62` | critical/high, "At risk" |
| `caution` | `#A8690A` | `#F2B35A` | medium, "Needs work" |
| `ok` | `#2E7D4F` | `#6FD39A` | "Ready", fixed |
| `neutralStatus` | `#6E6E73` | `#A1A1A6` | low, info, inconclusive |

Rules:
* **Maximum three hues visible at once**: neutral + accent + at most one status colour per row.
* Status colour appears as an 8 dp dot, or as text of one word. **Never as a card fill, left bar, glow or gradient.**
* Severity visuals collapse to three tiers (`risk`, `caution`, `neutralStatus`); the *word* (Critical, High, Medium,
  Low, Info) distinguishes within a tier. This is a colour change only; `Severity` and `ScoreBand` are untouched.
* Primary button: filled `accent`, `onAccent` label. If the final accent fails 4.5:1 for the label, use a `textPrimary`
  fill with `bg` label (the Apple "black button") and keep accent for links only.
* No gradients anywhere except: the score ring may use a single solid band colour on a neutral track. The old
  `Brand.horizontal/vertical/heroWash/underline` and `FilmExtra.backdrop/card/cardEdge` are removed.
* The ten-colour map palette stays inside the endpoint map (§7.7) and nowhere else.

### 6.2 Typography

Replace the condensed Archivo display with a single calm humanist/grotesque family. **Recommendation: Inter
(SIL OFL, variable)**, bundled in `res/font/`, with `FontFamily.SansSerif` as fallback; add it to `ATTRIBUTIONS.md`.
(SF Pro cannot be bundled.) Archivo stays only inside `BrandMark` if the logo needs it.

| Role | Size / line | Weight | Tracking | Replaces |
|---|---|---|---|---|
| Large title | 34 / 41 | 700 | −0.4 | `headlineMedium` for tab roots ("Reports", "More") |
| Title | 22 / 28 | 600 | −0.2 | hero question, section screens |
| Headline | 17 / 22 | 600 | 0 | row titles, group titles |
| Body | 16 / 24 | 400 | 0 | prose |
| Callout | 15 / 22 | 400 | 0 | row secondary text |
| Footnote | 13 / 18 | 400 | 0 | captions, timestamps |
| Score | 72 / 72 | 600 | −1 | the result number |

Min text 12 sp. All sizes scale with `LocalScale` (stage mode ×1.18) and the system font scale. Layouts must survive
200 % font scale without clipping (use `heightIn(min=)`, never fixed heights for text rows).

### 6.3 Space, shape, depth

* 4 dp grid. Screen gutter **20 dp**. Section gap **32 dp**. Row height **min 56 dp** (48 dp touch target).
* Radii: group/card **20 dp**, button **14 dp** (full-width) or **pill** (compact), input **12 dp**, chip **full**.
* Depth: **no shadows, no borders** on grouped lists; the group is `surface` on `bg`. A 0.5 dp `separator` only
  between rows, inset 16 dp from the leading edge. Shadows only on floating layers (sheet, menu, dialog).
* Icons: keep the existing line `Glyph` set, drawn at 1.5 dp stroke, `textSecondary` by default, `accent` only when
  selected. The coloured circle `IconBadge` is replaced by a bare glyph or, for lists with leading icons, a 28 dp
  rounded square in `bg` tone.

### 6.4 Navigation shell

* Bottom bar: flat `surface` with a 0.5 dp top separator, line icon + 11–12 sp label, **selected = accent tint, no
  pill, no gradient**. Hide on push screens exactly as today.
* Top bars: large-title (34 sp) collapsing to inline on scroll for tab roots; back chevron + inline title on pushes.
* **Language and voice:** one control, top-trailing, an "Aa/हिं" menu button (or a row in Settings › Language &
  voice). Choosing it sets language and read-aloud globally. The per-screen *Read aloud* / *हिन्दी* pills and the
  per-banner speaker/lang toggles are removed. Behaviour in `GuideVoice` is unchanged.
* **Mode (Everyday/Developer):** set during onboarding (defaulting to Everyday if skipped) and in
  Settings › Mode. The Home mode pill and the "Switch to developer mode" link are removed. The
  "How will you use TraceVeil?" onboarding question moves *after* the permissions pages and is skippable.
* **Tabs (decision D1):** Phase 1 keeps the five tabs. Recommended Phase 4: **Home · Check · Reports · More**; APK
  Guard becomes a section in *Check* ("Apps", "Files", "This phone"). Routes (`apkguard`, `pick`) stay alive for the
  voice assistant and notifications; only the bar and `AppTabsTest` change.

---

## 7. Screen specifications

Each spec lists **Keep** (functionality that must still be present), **Remove**, **Move**, and a wireframe. Copy
shown is indicative; final strings come from §8.

### 7.1 Onboarding (3 pages)

```
 ●  ○  ○                               Skip
 
 
            [ mark, 72 dp ]
 
 
 Apps promise.
 TraceVeil checks.
 
 It watches what an app contacts when you
 accept or refuse its consent popup, and
 shows you what it found.
 
 
 
                               [  Continue  ]
```

1. **Promise**: mark + headline + two sentences. No illustration, no fold. *(Replaces page 1.)*
2. **What we need** : two grouped rows (VPN, Consent watcher), each: icon, name, a **one-line why**, trailing
   "Required" in `textSecondary`. A footer line: "Results stay on this phone." *(Replaces pages 2 and 3, `Needs`.)*
3. **Make it yours**: "I use apps / I build apps" as a two-row radio group (preselected *I use apps*), plus a
   "Language" row. Button "Get started". *(Replaces the audience page and `TryChoices`.)*

Keep: Skip, back, progress, audience selection, language, permission explanations, `onSample` / `onDemo` / `onApk`
entry points (surface them on page 3 as "Try a sample" text link). Remove: *Read aloud* and Hindi pills per page,
the illustration, every `More ▾`. `OnboardingGate` unchanged; replay from Settings › Help › "Replay introduction".

### 7.2 Home (one layout; Developer adds rows, not a different screen)

```
 TraceVeil                                   ⚙
 
 
 ┌───────────────────────────────────────────┐
 │                                           │
 │  Does an app respect your choice?         │
 │                                           │
 │  Check one app while you accept or        │
 │  refuse its consent popup.                │
 │                                           │
 │  [        Check an app        ]           │
 │  Ready · 2 steps to finish setup  ›       │
 │                                           │
 └───────────────────────────────────────────┘
 
 Recent                                See all
 ┌───────────────────────────────────────────┐
 │ LeakyShop              Today        21  › │
 │ ───────────────────────────────────────── │
 │ AstroGuru              Yesterday    70  › │
 │ ───────────────────────────────────────── │
 │ NetMirror              Yesterday    66  › │
 └───────────────────────────────────────────┘
 
 More ways to check
 ┌───────────────────────────────────────────┐
 │ ⬡  An app file (APK)                    › │
 │ ───────────────────────────────────────── │
 │ ☐  Apps on this phone                   › │
 └───────────────────────────────────────────┘
```

* **One hero.** Flat `surface` card, title + one sentence + the primary button. No chips, grid, scanline, sweep or
  glow. The button is the only coloured element on the screen.
* **Readiness is one line under the button**, driven by `SetupSteps`: "Ready" (neutral dot `ok`) or
  "2 steps to finish setup ›". Tapping opens a **Setup sheet** (below). When blocked, the primary button stays
  enabled and opens the Setup sheet instead of showing a dead disabled button (same gate: `prepareAudit()` is still
  the check; the UI explains rather than greys out).
* **Setup sheet**: a grouped list, *Required* group first (VPN permission, Consent watcher), then *Optional* (Private
  DNS, Fresh-install reset, On-device AI). Each row: title, one-line state, trailing **Fix / Allow / Open** text
  button; a check mark when done. Same `fix(kind)` handlers as today.
* **Recent**: three rows (title, relative date, score as a plain number tinted by band, chevron). "See all" → Reports.
  Empty state: a single line "No checks yet" and a text button "See a sample"; no illustration.
* **More ways to check**: *Check an APK file* and *Apps on this phone* (checkup) as list rows. *New-app alerts*
  moves to Settings › Notifications (it is a setting). *Why you can trust TraceVeil* moves to More › About. *Ask about
  my last check*, *Camera check*, *See a sample*, *More samples* move to More › Tools (they stay one tap from Home's
  top-right menu for Developer mode).
* **Developer mode adds** (rows under "More ways to check"): *Automate it* (existing `onAutomation`) and *Camera
  check*. Nothing else differs.
* **Running check:** while `AuditProgress.Running`, the hero swaps to "Checking *AppName*" with a progress line and a
  "Watch" button (today's `homeRunning` behaviour, same `onLive`).

Keep: `vm.prepareAudit()`, preflight observers, VPN/accessibility/DNS/Shizuku fixers, `onVoice`, `onCamera`, `onApk`,
`onAutomation`, `onLive`, samples dialog. Remove: mode pill, switch-to-developer link, hero chips, "How it works ▾",
`GuideBanner`, numbered setup steps on Home, brand logo + gradient rule (a small mark left of the title is fine),
cards for Trust and Alerts.

### 7.3 Check / app picker and check sheet **(code review)**

* List: app icon, name; package name only as a secondary line while *searching* or when two apps share a name.
  Sticky search field (12 dp radius, no border, `surface` fill). System-apps toggle moves into a filter menu.
* Tapping an app opens a **bottom sheet** (today `CheckSheet`): title = app name, **Quick check** as the default
  filled action, a text row "Customize…" revealing plans (A accept / B reject / C no interaction), timing and
  reset options (same options, same defaults, `PlanOption`/`Toggle` unchanged). Developer-only wording ("Plan C") is
  replaced by plain labels on the Everyday path.
* Keep quarantine entry (`onQuarantine`), `initialQuery`, `vm.startAudit` arguments.

### 7.4 Live audit **(code review)**

* One calm screen: app name, a large elapsed/step indicator ("Step 2 of 3 · Rejecting"), a thin determinate or
  indeterminate bar, then the live lookup list (`Timeline`/`TimelineRow`): host on the left in body size, category
  as secondary text, **status dot only** (no coloured host text, no monospace unless it is a raw IP).
* "Cancel" as a text button bottom, destructive confirm. Keep `RunFrames`, `Counter` data; remove sweep/breathing
  animation, keep `RecordingDot` only as a small static-pulse indicator that respects reduce-motion.

### 7.5 Result (the most important screen)

```
 ‹                                      ⋯
 
 LeakyShop
 Checked today, 04:09
 
 
              21
           of 100
 
            At risk
 
 Trackers were contacted after you
 refused consent: AppsFlyer, Google
 Analytics for Firebase, Meta.
 
 [   See findings   ]   [ Fix ]  [ Report ]
 
 
 Findings                                  5
 ┌───────────────────────────────────────────┐
 │ ●  Trackers contacted after refusal     › │
 │    Critical · AppsFlyer, Google, Meta     │
 │ ───────────────────────────────────────── │
 │ ●  Trackers contacted before consent    › │
 │    High · AppsFlyer, Google, Meta         │
 │ ───────────────────────────────────────── │
 │ ●  Pre-ticked consent option            › │
 │    High                                   │
 └───────────────────────────────────────────┘
 
 About this result
 ┌───────────────────────────────────────────┐
 │ Evidence & coverage              High   › │
 │ ───────────────────────────────────────── │
 │ Endpoint map                            › │
 │ ───────────────────────────────────────── │
 │ Before / after                    +12   › │
 │ ───────────────────────────────────────── │
 │ Timeline                                › │
 │ ───────────────────────────────────────── │
 │ Legal lens                 Global baseline › │
 └───────────────────────────────────────────┘
 
 A lookup shows an app reached an address. It is not
 proof that personal data was sent.   Not legal advice.
```

* **Hero:** large score numeral (72 sp, band colour), "of 100", band word. Ring becomes a thin 6 dp track + arc in the
  band's single colour (no gradient), or is dropped; decide in the prototype (**D3**). Inconclusive keeps "?" and a dashed track.
* **Verdict sentence:** keep `headline(findings)` (the logic already handles V0/U1/inconclusive). It replaces the
  "What this means" card and the guide banner.
* **Actions:** *See findings* scrolls/opens the first finding; *Fix (n)* → `fixes/{id}`; *Report* → `report/{id}`;
  overflow menu `⋯`: Ask about this check (`onAsk`), Check again (`onCheckAgain`), Legal lens, Share. Same callbacks.
* **Finding row:** status dot (3-tier colour), title, one secondary line "Severity · entities". Evidence type
  (Observed/Inferred), legal-lens relation and the "Observed on device" line move into the **finding detail**.
* **Caveat banners** (`AuditPlanCoverage.gaps`, fresh-state hint, fast-preview, "Reject not tested"): shown **above
  the verdict as one calm inline notice** (icon + sentence + "Details"), only when present, max one at a time with a
  "+n more" link. Never removed: coverage honesty is a product value.
* **About this result:** one grouped list of drill-ins. `CollapsibleCard` content moves to its own detail screens
  or sheets (route `results/{id}` stays; sub-screens are new UI destinations or sheets, no new routes required if
  sheets are used). `ConfidenceGrader`, `NetworkProofCard`, `EndpointMapCard`, `PlanTimelines`, comparison and
  notes are reused as content.
* Remove: severity strip + chips, "Consent readiness" label, guide banner, "What this means" panel, "What you can do
  next" list, per-finding tags, lens block in the score card, tags row ("Sample data · Confidence …" collapse to one
  line "Sample · illustrative only" when `meta.sample`).

### 7.6 Finding detail and Fixes **(code review + `[16]`)**

* Finding detail: title, one-line what happened, then three plain sections: **What we saw** (evidence rows), **Why it
  matters** (DPDP basis, with the citation), **How to fix** (button → Fixes). Lens relation as a single footnote row.
* Fixes: each fix is a section: title, one sentence, a code block (monospace 13 sp, horizontal scroll, `bg`
  fill, 12 dp radius) with a trailing "Copy" icon button (replaces the "Code" label and "Copy code" link). Intro
  paragraph removed; "Check the exact API" becomes a footnote under the block.

### 7.7 Report preview `[17]`

* Replace the raw Markdown dump with a **rendered preview**: score, verdict, findings table as a grouped list,
  evidence rows. Exporters are unchanged; the preview reads `EvidenceReport` data (not parsing Markdown). If a rendered
  preview is out of budget, show a *summary card* + the three actions and omit the source text entirely.
* Actions: one filled "Save PDF", two secondary (Share, Markdown). Saved-path note becomes a toast/snackbar.

### 7.8 Endpoint map and evidence cards

* Keep the map and its ten-colour palette (tests pin it). Wrap in the new `Group` surface, drop card glow, move legend
  into a single row of dots. Revisit palette in a later pass; update `BrandPaletteTest` values and keep contrast asserts.

### 7.9 APK Guard hub and app/file results **(`[07]` + code review)**

```
 APK Guard
 
 ┌───────────────────────────────────────────┐
 │ Check an app file                         │
 │ See what an app can do before you install │
 │ it. TraceVeil only reads the file.        │
 │ [      Choose a file      ]               │
 └───────────────────────────────────────────┘
 
 ┌───────────────────────────────────────────┐
 │ ☐  Phone checkup                        › │
 │ ───────────────────────────────────────── │
 │ 🔔 New-install alerts             On   › │
 └───────────────────────────────────────────┘
 
 Installed apps  19              🔍 Search
 ┌───────────────────────────────────────────┐
 │ [icon]  AstroGuru                       › │
 │ ───────────────────────────────────────── │
 │ [icon]  NetMirror                       › │
 └───────────────────────────────────────────┘
```

* Three static pills and the 100-word "How it works" fold are removed; one sentence stays ("Not an antivirus. Reads
  only, never installs.") in the card footnote, full explanation in Help.
* Settings-like items (alerts) get a trailing state ("On") not a card. List rows lose the package line and install-date
  line; they appear in the detail view. Result screens (`ApkGuardResultScreen`): the same hero-verdict + grouped
  details pattern as §7.5.

### 7.10 Reports `[09]`

* Large title "Reports". Optional band filter as a segmented control (All · At risk · Needs work · Ready) instead of
  count chips. Rows identical to Home's *Recent* rows, grouped by day with date headers. Remove the intro paragraph.
  Empty state: one line + "See a sample".

### 7.11 More → "More" (keep tab) `[08]`

Grouped lists only, no mode card:

```
 More

 Tools            Camera check ›  Ask about my last check ›  Samples ›
 Settings         ›
 Advanced         AI lab ›  Developer automation ›
 Help             How it works ›  Replay introduction ›
 About            Why you can trust TraceVeil ›  Version 0.3.x
```

### 7.12 Settings `[18]`

Grouped, iOS-style sections: **Mode** (Everyday / Developer), **Language & voice** (language, read aloud, voice
copilot options), **Checks** (Fast audits, Load AI when a check starts, Legal lens), **Notifications**
(New-app alerts, weekly checkup), **Display** (Presentation mode with its current text), **Your domains**, **Privacy**,
**Legal**. Each toggle: title + at most one short sentence. Developer automation appears only in Developer mode.

### 7.13 AI lab `[19]` **(advanced)**

Reachable from More › Advanced and from the Setup sheet's "On-device AI" row. Group: *Status*, *Load on* (segmented
GPU · NPU · CPU), *Speed test* (the three buttons become one "Run benchmark" with the processor picker), *Models*
(path and `adb` command inside a collapsed "Install a model" disclosure with a Copy button). Thermal headroom as a
labelled value row. Content unchanged.

### 7.14 Other screens **(code review)**

* **Voice copilot:** full-bleed, one mic control, transcript as plain text bubbles on `bg`; remove card chrome.
* **Camera check:** viewfinder first, one capture button; verdict as a sheet using `Verdict`/`VerdictLine` data.
* **Quarantine run (`QuarantineScreen`, 665 lines):** step list with one active step, calm progress, summary as the
  Result pattern.
* **Phone checkup (`CheckupScreen`):** grouped list by risk tier; each app row: name, one-line power summary, chevron.
* **Trust screen:** it receives the static claims removed elsewhere. Sectioned plain list, no cards in cards.
* **Dialogs (`AlertDialog`):** `surfaceRaised`, 28 dp radius, text buttons in `accent`.
* **Empty / loading / error:** one pattern: centred glyph (48 dp, `textTertiary`), one line, optional text button.
  Skeletons are flat `separator`-tone bars, no shimmer sweep (static or a single slow fade).

---

## 8. Content and copy rules

* **Sentence case.** Titles ≤ 28 characters, row subtitles ≤ 1 line (~48 characters), body ≤ 3 lines.
* **Never truncate prose.** `FoldText`/`leadSentence()` stay for genuinely long, user-supplied or localised text only.
  Every other use is rewritten shorter or moved to a detail screen. Target: `FoldText` call sites 55 → ≤ 10.
* **One idea, one place.** Trust claims live on Trust; "not legal advice" lives on Result footer + Settings; setup
  explanations live on the Setup sheet.
* **No jargon on the main path.** Replace "Plan A/B/C", "lookups", "DNS", "NPU", `C1`/`C10` with the plain label; keep
  the technical term in the detail view or Developer mode. (Check IDs remain in exports.)
* **Honesty stays.** Keep and sharpen: lookup ≠ data sent, coverage gaps, fast-preview limits, not legal advice,
  inferred vs observed. The caveat is *placed* once, not duplicated.
* **Hindi parity.** Every string in `EverydayCopy` / `OnboardingCopy` / `GuideFlow` that moves or shortens must be
  updated in both languages in the same commit.

---

## 9. Component system

Introduce a small `ui/system/` package and migrate screens onto it. Old components are deprecated, then deleted when
unused (`grep` shows zero call sites).

| New | Replaces | Notes |
|---|---|---|
| `Tokens` (`LocalColors`, `LocalType`, `Space`, `Radius`) | `Film`, `FilmExtra`, `Brand` gradients, `Radii` | Light/dark; `Film`/`Brand.all` kept temporarily for the map and tests. |
| `Screen { LargeTitle / InlineTitle, content }` | `TopBar`, ad-hoc `Column(verticalScroll)` | Handles insets, collapse-on-scroll, max-width 680. |
| `Group(title?, footer?) { rows }` | `SectionCard`, `Panel`, `cardSurface` | `surface` fill, 20 dp radius, no border/shadow. |
| `ListRow(title, subtitle?, leading?, trailing?, onClick)` | `NavRow`, `ActionTile`, `RecentCheckCard`, `SetupRow`, `AppRow`, `Choice` | One row pattern; separators drawn by `Group`. |
| `PrimaryButton`, `SecondaryButton`, `TextAction` | `GradientAction`, `OutlineAction`, `PillButton`, `LinkAction`, `PrimaryAction`, `SecondaryAction` | Press = scale 0.98 + 80 ms; haptic tick on primary. |
| `StatusDot(level)` + `StatusText` | `Tag`, `ScoreChip`, `StatusDot`, severity strip | Three levels; word is always present for accessibility. |
| `Sheet`, `Menu` | `AlertDialog` uses, `LearnMore` | Bottom sheet for detail and setup. |
| `Notice(kind, text, action?)` | `Callout`, `KeyCaveat`, `Note` | Caveats; one at a time. |
| `EmptyState(line, action?)` | `EmptyState`, `EmptyHint` | No illustration. |
| `ScoreHero(score, band)` | `ScoreRing`, `ScorePanel` | Number + word (+ thin arc optional). |
| `Disclosure(title) { content }` | `CollapsibleCard`, `DisclosureRow`, `FoldText` (long text only) | Chevron rotates; content never truncated when open. |

Removed outright: `GuideBanner` (state hints become `Notice`), `gradientSweep`, `BreathingDot`, `reveal()` stagger
(replace with a single 150 ms fade on screen enter), `Brand.heroWash`, hero film-grid canvas, `ModePill`/`ModeSwitch`
on Home, `OnboardingArt` illustrations.

### Motion budget
* Screen push/pop: keep `ScreenMotion` (tuned: 280 ms, standard ease, 8 % horizontal shift, cross-fade).
* Tab switch: cross-fade 150 ms (no slide).
* Press: scale 0.98, 80 ms. Toggles: spring. Score: count-up once, 600 ms.
* Everything else static. All animation uses `LocalReduceMotion`.

---

## 10. Migration plan

Work in small, reviewable commits on `ui-refactor`. Every phase ends with: build, unit tests, a phone run, and updated
screenshots in `docs/ui-refactor/after/`.

| Phase | Deliverable | Touches | Exit check |
|---|---|---|---|
| **0. Baseline** | This guide, `baseline/` screenshots | docs only | done |
| **1. Tokens + shell** | `ui/system/` tokens (light/dark), new type scale + Inter, flat backdrop, new bottom bar and top bars, `Screen`/`Group`/`ListRow`/buttons. Old components re-implemented on top of the new tokens so every screen changes look at once without layout edits. | `theme/`, `shell/`, `components/` | All screens render, no layout break, tests green (`AppTabsTest` unchanged). |
| **2. Home + Setup sheet + onboarding** | §7.1, §7.2, mode/language relocation | `HomeScreen`, `EverydayHomeScreen`, `OnboardingScreen`, `GuideBanner` removal | Everyday and Developer both reach every old destination. Update copy tests. |
| **3. Result family** | §7.5–§7.8 | `ResultScreens.kt` (788 lines), Fixes, Report preview, finding detail | Results from a real audit and a sample match old data field-for-field. |
| **4. Hubs and lists** | §7.9–§7.13 (+ decision D1) | APK Guard, Reports, More, Settings, AI lab, picker | Voice and notification deep links still land correctly. |
| **5. Flows** | §7.4, §7.14 | live audit, camera, voice, quarantine, checkup, trust, dialogs | Full audit on LeakyShop: start → live → result → export. |
| **6. Clean-up** | delete dead components, finalise copy, a11y pass | repo-wide | Zero references to removed components; `FoldText` ≤ 10; palette test updated. |

### Definition of done (per screen)
1. Same destinations reachable with the same or fewer taps.
2. ≤ 1 filled/coloured control, ≤ 3 hues, no gradient, no glow.
3. No truncated prose; at most one disclosure per screen.
4. TalkBack: headings, roles, state descriptions verified; 48 dp targets; passes 200 % font scale and stage mode.
5. Light and dark both verified; contrast ≥ 4.5:1 text, ≥ 3:1 graphics via `Contrast`.
6. `./gradlew :app:testDebugUnitTest` green; one phone screenshot per state added to `after/`.

---

## 11. Decisions (all approved by the owner, 2026-10-10)

| # | Decision | Approved outcome |
|---|---|---|
| **D1** | Tabs: keep five, or four (APK Guard merges into Check)? | Four, but only after Phase 3. Edit `AppTabsTest`; keep routes alive. |
| **D2** | Theme: dark-only, or light + dark following the system? | Light + dark; first-run default follows the system. Dark stays close to today's depth for continuity. |
| **D3** | Score visual: thin arc, or number only? | Number only with a thin arc track; confirm in a prototype. |
| **D4** | Typeface: Inter bundled (OFL, +~300 KB) or system default? | Inter, with system fallback. |
| **D5** | Keep Hindi + read-aloud global menu, or Settings only? | Global menu button on tab roots, full controls in Settings. |
| **D6** | Developer mode: separate Home, or Everyday Home plus rows? | Same Home plus rows (§7.2). |
| **D7** | Report preview: rendered from `EvidenceReport`, or summary only? | Rendered if ≤ 1 day; otherwise summary only. |

---

## 12. Risks

* **Copy tests will churn.** Expected. Change copy and test together; do not loosen assertions.
* **Voice/guide features read `GuideFlow` text.** Removing `GuideBanner` must not silence the narrator for users who
  enabled read-aloud. Keep `GuideFlow` and `GuideVoice` as the source; render guidance through `Notice` when state
  requires it (blocked setup, a running check, an inconclusive result) and let read-aloud trigger from there.
* **Accessibility regressions** when removing chips (they carried state words). Keep the word in the row subtitle.
* **A light theme exposes every hard-coded colour.** 5 `Color(0x…)` literals live outside `Theme.kt`; move them to
  tokens in Phase 1.
* **Screens not run on device** (§2) may hold surprises. Capture them before and after.
* **Stage mode (1.18×)** and Hindi strings are longer; test every new row at both.

---

## 13. Appendix: evidence index

| File | Screen | Build |
|---|---|---|
| `baseline/01-onboarding-promise.png` | Onboarding 1 | current |
| `baseline/02-onboarding-audience.png` | Onboarding: audience | current |
| `baseline/03-onboarding-needs.png` | Onboarding: what we need | current |
| `baseline/04-home-everyday-top.png` | Everyday Home, top | current |
| `baseline/05-home-everyday-mid.png` | Everyday Home, middle | current |
| `baseline/06-home-everyday-bottom.png` | Everyday Home, bottom | current |
| `baseline/07-apk-guard.png` | APK Guard hub | current |
| `baseline/08-more.png` | More | current |
| `baseline/09-reports-empty.png` | Reports (empty) | current |
| `baseline/10-result-score.png` | Sample result: score card | current |
| `baseline/11-result-guidance.png` | Sample result: guidance + next steps | current |
| `baseline/12-result-findings.png` | Sample result: findings | current |
| `baseline/13-legacy-home-developer.png` | Developer Home | legacy |
| `baseline/14-legacy-result.png` | Result | legacy |
| `baseline/15-legacy-result-evidence.png` | Result: evidence, timeline | legacy |
| `baseline/16-legacy-fixes.png` | How to fix | legacy |
| `baseline/17-legacy-report-raw-markdown.png` | Report (raw Markdown) | legacy |
| `baseline/18-legacy-settings.png` | Settings | legacy |
| `baseline/19-legacy-ai-lab.png` | AI lab | legacy |

Source map for the work: `ui/theme/Theme.kt`, `ui/components/{Kit,Components,Effects}.kt`, `ui/guide/{VisualKit,GuideBanner,
GuideFlow,ResultGuide,SetupSteps}.kt`, `ui/shell/{AppShell,ScreenMotion}.kt`, `ui/screens/{HomeScreen,HubScreens,
ResultScreens,ToolScreens,AppPickerScreen,LiveAuditScreen}.kt`, `ui/modes/*`, `ui/onboarding/*`,
`apkguard/ApkGuardScreens.kt`, `autoguard/CheckupScreen.kt`, `quarantine/QuarantineScreen.kt`, `voice/VoiceCopilotScreen.kt`,
`trust/*`, `MainActivity.kt`.

---

## 14. Implementation status (branch `ui-refactor`)

| Phase | State | Commit |
|---|---|---|
| 0 Baseline | Done | `475e7f9` |
| 1 Tokens and shell | Done: light/dark palettes, Inter, flat components, new tab bar, `ui/system` kit, `PaletteContrastTest` | `f487144` |
| 2 Home, Setup sheet, onboarding | Done: one Home for both modes, Setup sheet, three-page onboarding, global language and voice menu | `cb586ca` |
| 3 Result family | Done: score hero, one primary action, findings list, caveats as notices, legal lens moved into Evidence and limits | `c1435a5` |
| 4 Hubs and lists | Done: four tabs (APK Guard under Check, decision D1), flat picker and APK Guard hub, Reports filter, grouped More | `68f8676` |
| 5 Flows | Partly done: live audit calmed, guide banners retired everywhere (spoken guidance kept through `SpeakStep`) | see git log |
| 6 Clean-up | Partly done: dead components removed (`GuideBanner`, `ModePill`, onboarding art), short text now shows in full, only text over 220 characters folds | see git log |

Not done, still to do:

* **Camera check, voice copilot, quarantine run, phone checkup, Trust screen, AI lab, finding detail, Fixes, Report preview** keep their old structure. They pick up the new colours, type, flat cards and buttons through the shared components, but have not been rebuilt to the layouts in section 7 (7.6, 7.7, 7.13, 7.14).
* **Settings** keeps its card layout (Mode, Display, Checks, Legal lens, Voice, Auto-guard, domains, Advanced, Privacy). Regrouping into the section 7.12 list style is open.
* **Folding:** `FoldText` call sites are still about 46; the target of 10 needs per-screen copy edits, not a global rule.
* **Entrance fade** (`reveal`) is still called about 87 times, but it is now one short fade. Remove the calls screen by screen.
* **Verification not yet done:** TalkBack pass, 200 % font scale, stage mode, Hindi layouts, dark mode on every screen (only Home and onboarding were checked in dark), and the screens that need the VPN and consent watcher (live audit, finding detail, quarantine).
* **Tests:** two `CliAuditTest` tests fail. They sit in the CLI work that was already uncommitted on this branch and are unrelated to the UI. `BrandPaletteTest` (map palette) still passes unchanged.

### Colour scheme update

The section 6.1 hex values were replaced by a scheme built from five supplied colours: Icy Aqua `#BCF8EC`, Light Blue `#AED9E0`, Lavender Grey `#9FA0C3`, Dusty Lavender `#8B687F` and Vintage Berry `#7B435B`. The tokens now live in `ui/theme/Tokens.kt`.

| Token | Light | Dark |
|---|---|---|
| bg / surface / fill | `#F1FBF9` / `#FFFFFF` / `#E1F1F4` (tints of Icy Aqua and Light Blue) | `#151017` / `#1F1822` / `#2C2330` (berry-charcoal) |
| accent / onAccent | Vintage Berry `#7B435B` / `#FFFFFF` | Icy Aqua `#BCF8EC` / `#0F2420` |
| text primary / secondary / tertiary | `#2A1E26` / `#5E4F5A` / Dusty Lavender `#8B687F` | `#F3EEF2` / `#B7ABB4` / Dusty Lavender `#8B687F` |
| risk / caution / ok | `#A62F4E` / `#565991` (deep Lavender Grey) / `#1E7468` | `#F08AA8` / Lavender Grey `#9FA0C3` / `#7FD9C0` |

The five colours have no red, amber or green, so the three status colours are derived from the same hue families (berry, lavender, aqua) and are always accompanied by a word. `PaletteContrastTest` checks every pair in both themes.

The logo keeps its original colours (peach, coral and rose veil, cyan arcs, red lookup dot, dark navy lens) in every theme: `BrandMark` no longer reads the UI palette. It is back in the Home header beside the title, as it was before the refactor.

### Appearance setting and contrast pass

* **Appearance:** Settings › Display now has a System / Light / Dark control (stored as `themeMode`, default `system`, so existing installs see no change). The status and navigation bar icons follow the chosen theme, not only the phone's.
* **Light-mode contrast:** the background was deepened to `#E9F5F4` and the quiet fill to `#D5EAED` so white cards read as cards; the separator is `#D1E3E6`. Caption text (`textTertiary`) is now `#735668`, which passes 4.5:1 on every surface. Dusty Lavender `#8B687F` stays for icons and switch thumbs through a new `graphic` token (3:1). "Ready" green is `#1B6B60`.
* **Dark-mode contrast:** surfaces were lifted one step (`#231B26` on `#151017`) so cards separate from the screen; caption text is `#A08E9B`.
* `PaletteContrastTest` now also checks caption text on all three surfaces, graphics at 3:1, and the card-versus-background step, in both themes.

### Logo

The logo is now `assets/logo.png` (the original 2048 px image), cropped to the mark without the corner watermark and bundled at 512 px as `res/drawable-nodpi/logo_mark.png`. `BrandMark` shows it as a rounded tile (so it looks the same in light and dark) in the Home header and on the first onboarding page. The launcher icon uses the same image on the logo's navy (`#111834`), inset 17% to stay inside the adaptive icon's safe zone. The old vector mark is no longer drawn; its geometry constants stay because `BrandMarkTest` still checks them against the SVG in `docs/brand`.
