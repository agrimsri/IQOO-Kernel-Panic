# TraceVeil Console

A local-first team dashboard for TraceVeil exports. Drop the JSON files the TraceVeil Android app exports and get a
portfolio view, per-audit consent timelines, audit comparison, quarantine behaviour reports and a release-gate
policy editor.

**Data never leaves the browser.** Files are parsed in the page and stored in this browser's IndexedDB (database
`traceveil-console`). There is no server, no analytics and no font, script or image fetched from the internet. If a
browser blocks IndexedDB, the console keeps data in memory for that tab and says so in the top bar.

## Run it

```bash
npm install
npm run dev            # http://localhost:5191
npm run build          # static site in dist/ (relative paths)
npm run preview        # serves dist/ on http://localhost:4191
npm run build:single   # ONE self-contained file: dist-single/index.html
```

`dist-single/index.html` (about 0.8 MB) inlines every script, style, font and image. Open it straight from disk
(`file://`), copy it to a USB stick or another laptop: no server, no install, no network needed.

Checks:

```bash
npm run samples   # rebuild the sample bundles and re-check them against the measured report
npm run smoke     # end-to-end test of dist-single from file:// in the local Chrome (imports, compare, policy, delete)
npm run screens   # 1440x900 screenshots of the main pages into screens/ (add --mobile for 390 px)
```

`smoke` and `screens` drive the locally installed Chrome through `puppeteer-core`; set `CHROME` if it is not at
`C:/Program Files/Google/Chrome/Application/chrome.exe`.

## What it reads

| Schema | Shape |
|---|---|
| `traceveil.audit.v1` | `{ schema, exportedAt, app{package,label}, meta, sessions[AuditSession], result{findings[],score,band}, networkReadings, tool }` |
| `traceveil.quarantine.v1` | `{ schema, exportedAt, report{QuarantineReport} }` |
| bare `QuarantineReport` | the phone's saved quarantine JSON; summary and timeline are recomputed with the core rules if missing |
| `traceveil.console-backup.v1` | this console's own "Download all data" file |

Field names follow `core/src/main/kotlin/com/dpdpxray/core/model/Model.kt` and `core/.../quarantine/*.kt`.
Unknown fields are ignored; a broken or foreign file is rejected with a reason and nothing else changes. Lookups are
re-phased exactly like `PhaseAssigner` and classified with the same tracker catalogue the app ships; the tracker
catalogue, fix templates and DPDP passages are imported at build time from `../core/src/main/resources`, so the
console never drifts from the app.

## Pages

- **Portfolio**: apps checked, audits, open critical findings, median score, quarantine runs; score by app over
  time; needs attention; findings by check; tracker lookups by company split by consent phase; recent activity.
- **Audit detail**: coverage first (each plan, consent screen, the exact recorded tap, missing choices, fresh state,
  capture health, Private DNS), score, consent timeline (lanes C, B, A; lookups as marks, red diamonds after Reject,
  tooltips with host, entity, time, phase), findings with OBSERVED / INFERRED / NOT ESTABLISHED labels, first evidence
  lines, DPDP context and copyable fix snippets, the captured consent screen and every host contacted.
- **Compare**: resolved, new and unchanged findings by check and the score change. Different packages, plan sets,
  timing profiles, sample/live mixes and inconclusive scores are labelled, never passed off as a before-and-after.
- **Quarantine**: blocked names and addresses, the inferred hard-coded-address signal, permission dialogs, sensitive
  settings screens, the behaviour timeline and the run's own limits.
- **Release policy**: edit every `traceveil-ci` policy key (`failOn`, `allowNotEstablished`, `minScore`, `maxHigh`,
  `requireFreshState`, `allowInconclusive`, `requirePlans`) with the CLI's defaults; see which loaded audits would
  pass under the CLI's rules (a `failOn` check the plans could not test is NOT ESTABLISHED and fails unless
  `allowNotEstablished`; `maxHigh` counts High and Critical findings; a fresh state that was not recorded fails
  `requireFreshState`; Accept and Reject plans count only with their choice recorded); download a
  `traceveil-policy.json` the CLI accepts; copy the real `traceveil-ci` commands and the repository's GitHub Actions
  example (`docs/ci/traceveil-gate.yml`, read at build time). The preview uses the findings stored in each export;
  the CLI re-scores the sessions first, so an export scored by an older app version can differ.
- **Plans**: Free, Pro team, Enterprise. "Available" only for phone-verified features (consent audit, timeline,
  fixes, voice copilot, Network Proof, map, notice) and this console; "Built · phone check pending" for APK Guard,
  Quarantine, Hey TraceVeil, the guided UI, the HTML report and AI explanations; the CI gate is "Built · GitHub run
  pending"; the rest "Coming soon". No prices beyond "Free during beta" and "Contact", no customer names.
- **Settings & privacy**: storage location and size, download all data, load or remove samples, delete everything,
  theme, guided tour.

A guided tour (the UI kit's tour engine) starts once on the first visit and can be replayed from the top bar.
Phones get the kit's bottom tab bar and a menu sheet; wide tables scroll inside their cards.

## Samples

The first visit loads three bundles, each marked **Sample** everywhere they appear and removable in Settings.
`scripts/make-samples.mjs` builds them and fails if they drift from their source.

| Sample | Basis |
|---|---|
| LeakyShop, 21/100 At risk, C2 C1 C3 C4 C10 | Live audit `audit-1791590915883` (vivo I2501, Android 16): hosts, plan order, lookup times at 0.1 s resolution (5/8/7 lookups), Reject and Accept taps, findings and score are as measured. Consent-screen times are derived from the agent's dwell; plan windows and capture counters are approximate; upload readings are not included. |
| LeakyShop Fixed, 100/100 | Live audit `audit-1791591219563`: zero findings, one lookup per plan, real Reject and Accept. The lookup and screen times are set like the leaky build's. |
| TEST Challan quarantine | Illustrative, not a recorded phone run: what the `riskdemo` fixture is coded to do (lookups of `c2.traceveil-demo.invalid`, `pay-challan-update.invalid`, `otp-relay.example`, a TCP SYN to the documentation address `203.0.113.10:443`) plus its SMS permission dialog. |

## What it never claims

Not legal advice and not a malware verdict. A DNS lookup is an attempt to contact a host, not proof that data was
sent. A score covers captured evidence on one device during the recorded runs; 100 means no finding in that
evidence, not a certificate. Unknown stays unknown, never zero. DPDP text is context; the Act's main provisions
commence on 13 May 2027, so findings describe readiness. Penalty bands present in exports are not displayed.

## Limitations

- Browser storage can be cleared by private windows or "clear site data"; keep the original exports.
- Timestamps above about 104 days of phone uptime exceed JavaScript's exact integer range (nanosecond precision is
  lost, sub-microsecond only; displayed times are unaffected).
- Comparison matches findings by check id; it does not diff individual evidence lines.
- `traceveil-ci` is built and tested in `cli/`, but the example GitHub workflow has not been run on GitHub yet.
  Encrypted team sync is not built.
- `npm run smoke` imports the CLI's own sample bundles from `../docs/samples` to check that both read the same files.

## Credits

- UI: the team's UI kit (dark glass/HUD design system: tokens, components, charts, responsive shell, themes and
  guided-tour engine), copied into `src/styles`, `src/ui`, `src/shell`, `src/lib` and `src/tour`. Console additions
  are the `traceveil` theme block in `tokens.css`, `styles/console.css`, `ui/tv.jsx` and everything under
  `components/`, `pages/` and `data/`.
- Logo: TraceVeil brand assets.
- Libraries: React (MIT), motion (MIT), lucide-react icons (ISC), Vite and vite-plugin-singlefile (MIT),
  puppeteer-core for tests (Apache-2.0). Fonts: Inter, Space Grotesk and JetBrains Mono via Fontsource (SIL Open
  Font License 1.1).
