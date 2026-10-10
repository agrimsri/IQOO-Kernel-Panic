# TraceVeil CI: capture on a phone, gate the release in CI

TraceVeil captures consent evidence on a real phone: the DNS lookups an app makes before and after a consent choice, and what its consent screen shows. **TraceVeil CI** lets a team use that evidence in its release pipeline. The phone exports the audit as one JSON file; the team commits it next to the release; a small command-line tool, `traceveil-ci`, re-checks it with the latest rules and fails the build when the team's policy is violated.

What it is not: CI does not capture anything, install the app or talk to a phone. It reads the evidence a person captured and exported. It sends nothing over the network. A pass is not a compliance certificate and a failure is not a legal verdict: a DNS lookup is an attempt to contact a host, not proof that personal data was sent, and the evidence covers one phone during one run.

## The flow

```text
 Phone (TraceVeil)                    Laptop / repository                   CI (any runner with Java 17)
 ─────────────────                    ───────────────────                   ────────────────────────────
 1. Full audit of the release         3. Copy the JSON into the repo        5. traceveil-ci check <bundles>
    candidate (Accept, Reject,           (Office Kit file transfer,            re-evaluates with the latest
    No interaction) after an             or adb pull), review it,              rules, applies the policy,
    approved fresh reset                 commit it                             writes SARIF
 2. Report → "Export audit data       4. Optional: keep last release's      6. traceveil-ci diff old new
    (JSON)" → Downloads/TraceVeil         bundle for the diff                   flags regressions
```

### 1. Capture on the phone

Audit the build you are about to release, with all three plans (Accept, Reject, No interaction). Approve the fresh reset before the run so every plan starts from cleared app data; without it, consent given earlier cannot be told apart from no consent, and the default policy will not pass the bundle. A Fast Preview (one Accept plan, shorter observation) is a first look, not a gateable audit. For an app used by children, switch on **This app is used by children** in the audit setup so C9 (tracking in a child-facing app) can be tested; otherwise C9 is NOT ESTABLISHED.

### 2. Export the audit data

On the audit's **Report** screen:

- **Export audit data (JSON)** saves `TraceVeil-audit-<number>.json` to `Downloads/TraceVeil` (the same place as the PDF and HTML reports).
- **Share audit data (JSON)** opens the Android share sheet instead.

The file is the audit's full evidence: every recorded lookup, the consent-screen text TraceVeil read, the device model and Android version, the findings and the score. Nothing leaves the phone until you save or share it. Review it before committing it to a shared repository.

A quarantine run's **Behaviour report** has **Export report (JSON)** and **Share report (JSON)**, which write `TraceVeil-quarantine-<number>.json` in the `traceveil.quarantine.v1` format (see below). `traceveil-ci check` gates audit bundles only; it rejects a quarantine report with exit code 2.

### 3. Move it to the repository

Use any phone-to-laptop transfer, for example Office Kit's file transfer, or over USB:

```sh
adb pull /sdcard/Download/TraceVeil/TraceVeil-audit-1791595268053.json privacy/traceveil/release-1.4.0.json
```

A convenient layout is `privacy/traceveil/release-<version>.json`, one bundle per release, so the previous release's bundle is always available for `diff`.

### 4. Build the tool

```sh
./gradlew :cli:installDist     # scripts in cli/build/install/traceveil-ci/bin (traceveil-ci, traceveil-ci.bat)
./gradlew :cli:fatJar          # or one runnable jar: java -jar cli/build/libs/traceveil-ci-0.1.0-all.jar
```

The tool is plain Kotlin/JVM on top of TraceVeil's `core` module (Java 17, no Android). Both forms behave the same.

### 5. Run it in CI

`docs/ci/traceveil-gate.yml` is a GitHub Actions example: it builds the tool, gates the committed bundles with `docs/ci/traceveil-policy.json`, diffs the previous and current release, and uploads SARIF to code scanning. Copy it to `.github/workflows/` and point the `TRACEVEIL_*` paths at your bundles. Uploading SARIF needs `security-events: write`, and code scanning on a private repository needs GitHub Advanced Security; the gate itself (exit code) works without it.

## Commands

```text
traceveil-ci check <bundle.json>... [--policy policy.json] [--format text|json|sarif] [--out file]
traceveil-ci diff <old.json> <new.json> [--format text|json] [--out file]
traceveil-ci --help | --version
```

| Exit code | `check` | `diff` |
| --- | --- | --- |
| 0 | every bundle passes the policy | no regression |
| 1 | at least one policy violation | regression |
| 2 | bad command line, unreadable or wrong-format bundle, unusable policy | same |

Without `--out`, the chosen format goes to stdout. With `--out`, the chosen format is written to the file (UTF-8) and the text report still goes to stdout, so the CI log stays readable.

### `check`: re-evaluate and gate

For each bundle, `check` scores the bundle's sessions again with this build's rules engine and tracker catalogue, so CI always applies the latest rules. It shows the re-evaluated score and, when it differs, the score stored on the phone and which checks appear in only one of them. The policy is applied to the re-evaluated result.

The stored and re-evaluated results can differ when the rules changed since the export, or when the person marked a domain as first-party on the phone: CI uses the default catalogue and does not apply phone-side first-party marks, so it cannot be used to hide a tracker.

Real output for the synthetic leaky sample (exit code 1):

```text
$ traceveil-ci check docs/samples/leakyshop-leaky_sample.json
TraceVeil CI check · rules re-evaluated by TraceVeil CI 0.1.0
Policy: defaults (failOn C2, C9; not-established fails; requireFreshState; requirePlans ACCEPT, REJECT, SILENT; inconclusive fails)

== docs/samples/leakyshop-leaky_sample.json
App:      LeakyShop (sample) · com.leakyshop.demo
Exported: 2026-10-10T00:00:00Z by TraceVeil 1.1.0-samples
Warning:  SYNTHETIC SAMPLE: test data, not a device observation. Generated by ./gradlew :cli:writeSamples from a copy of the device-shaped LeakyShop leaky audit in core DemoScoreRegressionTest (three plans after an approved fresh reset).
Plans:    Plan C · No interaction: consent screen seen, no choice | Plan B · Reject: Reject recorded | Plan A · Accept: Accept recorded
Fresh:    all 3 plans started from an approved fresh reset
Score:    21/100 · below 60 (re-evaluated; same as stored in the bundle)

CHECK  SEVERITY  EVIDENCE  FINDING
C2     Critical  OBSERVED  Tracker hosts looked up after the user refused
                           first evidence: graph.facebook.com (Meta (Facebook SDK)) — catalogue match: advertising — looked up 9.4 s after launch, 1.1 s after the user chose reject [Plan B · Reject]
C1     High      OBSERVED  Tracker hosts looked up before consent
                           first evidence: app-measurement.com (Google Analytics for Firebase) — catalogue match: analytics — looked up 0.6 s after launch, the user had not chosen yet [Plan C · No interaction]
C3     High      OBSERVED  Pre-ticked consent option
                           first evidence: Consent screen shows a pre-ticked option.
C4     High      OBSERVED  No clear way to refuse
                           first evidence: Reject option: hidden.
C10    Medium    INFERRED  Device identifiers likely shared before consent
                           first evidence: app-measurement.com (Google Analytics for Firebase) — catalogue match: analytics — looked up 0.6 s after launch, the user had not chosen yet [Plan C · No interaction]

Policy
  PASS  allowInconclusive  result is conclusive
  FAIL  failOn C2          C2 Tracker hosts looked up after the user refused (Critical, OBSERVED evidence)
  FAIL  failOn C9          C9 NOT ESTABLISHED: the app was not declared child-facing in this capture and no consent screen was reviewed for child-audience signals; a failOn check that could not be tested fails unless allowNotEstablished is true
  OFF   minScore           not set
  OFF   maxHigh            not set
  PASS  requireFreshState  all 3 plans started from an approved fresh reset
  PASS  requirePlans       captured: Plan A · Accept, Plan B · Reject, Plan C · No interaction

Result: FAIL (2 policy violations)

Overall: FAIL (1 of 1 bundle violated the policy, 2 violations; exit 1)
A DNS lookup is an attempt to contact a host, not proof that personal data was sent.
Findings come from DNS lookups and consent-screen review on one phone during one run. This is an automated technical check, not legal advice or a compliance certificate.
```

The same command on the fixed sample has no findings but still fails (exit code 1). C9 could not be tested: the capture did not declare the app child-facing and no consent-screen review answered whether it targets children, and the default policy never passes a check by the absence of evidence it could not collect:

```text
$ traceveil-ci check docs/samples/leakyshop-fixed_sample.json
TraceVeil CI check · rules re-evaluated by TraceVeil CI 0.1.0
Policy: defaults (failOn C2, C9; not-established fails; requireFreshState; requirePlans ACCEPT, REJECT, SILENT; inconclusive fails)

== docs/samples/leakyshop-fixed_sample.json
App:      LeakyShop Fixed (sample) · com.leakyshop.demo.fixed
Exported: 2026-10-10T00:00:00Z by TraceVeil 1.1.0-samples
Warning:  SYNTHETIC SAMPLE: test data, not a device observation. Generated by ./gradlew :cli:writeSamples from the same plans for the LeakyShop fixed build (first-party lookups only, no pre-ticked box, Reject visible), as in core DemoScoreRegressionTest.
Plans:    Plan C · No interaction: consent screen seen, no choice | Plan B · Reject: Reject recorded | Plan A · Accept: Accept recorded
Fresh:    all 3 plans started from an approved fresh reset
Score:    100/100 · 85–100 band (re-evaluated; same as stored in the bundle)

No findings in the captured evidence. That covers only what this run observed.

Policy
  PASS  allowInconclusive  result is conclusive
  PASS  failOn C2          no C2 finding in the captured evidence
  FAIL  failOn C9          C9 NOT ESTABLISHED: the app was not declared child-facing in this capture and no consent screen was reviewed for child-audience signals; a failOn check that could not be tested fails unless allowNotEstablished is true
  OFF   minScore           not set
  OFF   maxHigh            not set
  PASS  requireFreshState  all 3 plans started from an approved fresh reset
  PASS  requirePlans       captured: Plan A · Accept, Plan B · Reject, Plan C · No interaction

Result: FAIL (1 policy violation)

Overall: FAIL (1 of 1 bundle violated the policy, 1 violation; exit 1)
A DNS lookup is an attempt to contact a host, not proof that personal data was sent.
Findings come from DNS lookups and consent-screen review on one phone during one run. This is an automated technical check, not legal advice or a compliance certificate.
```

The example policy (`--policy docs/ci/traceveil-policy.json`) is written for an app that is not directed at children: it gates on C2 only and adds `minScore: 85` and `maxHigh: 0`. With it the fixed sample passes (exit code 0) and the leaky sample has three violations:

```text
  FAIL  failOn C2          C2 Tracker hosts looked up after the user refused (Critical, OBSERVED evidence)
  FAIL  minScore           score 21 is below the minimum 85
  FAIL  maxHigh            4 findings at High or Critical severity (C2, C1, C3, C4); the policy allows 0
```

The kept-data sample shows why unknown is not a pass. Its score is 100 because the rules cannot call kept-data lookups "before consent", but the gate fails: the plan did not start from a fresh reset, Accept and Reject were not captured, and neither C2 nor C9 could be tested at all.

```text
  PASS  allowInconclusive  result is conclusive
  FAIL  failOn C2          C2 NOT ESTABLISHED: no Reject choice was recorded, so behaviour after refusal was not observed; a failOn check that could not be tested fails unless allowNotEstablished is true
  FAIL  failOn C9          C9 NOT ESTABLISHED: the app was not declared child-facing in this capture and no consent screen was reviewed for child-audience signals; a failOn check that could not be tested fails unless allowNotEstablished is true
  OFF   minScore           not set
  OFF   maxHigh            not set
  FAIL  requireFreshState  Plan C · No interaction ran on the app's kept data (no approved fresh reset)
  FAIL  requirePlans       Plan A · Accept was not captured; Plan B · Reject was not captured
```

Rule statuses are `PASS`, `FAIL` (a violation), `NOT ESTABLISHED` and `OFF` (not set in the policy). A `failOn` check the captured plans could not show (C2 without a recorded Reject choice; C9 without a child-facing declaration or a screen review that answered child audience; C1, C10 and NC when every plan ran on kept data without a consent screen; C3 to C8 without a reviewed consent screen) is never a pass by absence: it is reported as `C<n> NOT ESTABLISHED` and fails the gate, unless the policy sets `allowNotEstablished: true`, in which case it is listed with the status `NOT ESTABLISHED` and does not fail.

### `diff`: release-to-release regression check

`diff` re-evaluates both bundles with the same rules, matches findings by check id and lists resolved, new and unchanged findings (with severity, evidence-label and entity changes) and the score change. It exits 1 on a regression: a new finding at Low severity or above, a finding whose severity rose, a lower score, a new result that is inconclusive (even when the old one was too), or an old finding that the new capture could not test. Such a finding (for example C2 when the new capture recorded no Reject choice) is listed under "Not established in the new capture", never as resolved. New Info findings alone (U1, S1) are not a regression. It warns when the two bundles are for different packages.

```text
$ traceveil-ci diff docs/samples/leakyshop-leaky_sample.json docs/samples/leakyshop-fixed_sample.json
TraceVeil CI diff · both bundles re-evaluated by TraceVeil CI 0.1.0
Old: docs/samples/leakyshop-leaky_sample.json · LeakyShop (sample) · com.leakyshop.demo · exported 2026-10-10T00:00:00Z · SYNTHETIC SAMPLE
New: docs/samples/leakyshop-fixed_sample.json · LeakyShop Fixed (sample) · com.leakyshop.demo.fixed · exported 2026-10-10T00:00:00Z · SYNTHETIC SAMPLE
Warning: the bundles are for different packages (com.leakyshop.demo → com.leakyshop.demo.fixed)
Score: 21/100 · below 60 → 100/100 · 85–100 band (+79)

Resolved (5)
  C2     Critical  OBSERVED  Tracker hosts looked up after the user refused
  C1     High      OBSERVED  Tracker hosts looked up before consent
  C3     High      OBSERVED  Pre-ticked consent option
  C4     High      OBSERVED  No clear way to refuse
  C10    Medium    INFERRED  Device identifiers likely shared before consent

Not established in the new capture (0)
  none

New (0)
  none

Unchanged (0)
  none

Result: no regression (no new finding at Low severity or above, no severity rise, the score did not fall and every old finding was testable; exit 0)
A DNS lookup is an attempt to contact a host, not proof that personal data was sent.
A resolved finding means it was not seen in the new capture; that covers only what the new run observed.
```

Swapping the arguments reports `Result: REGRESSION (exit 1)` with the five new findings and `score fell from 100 to 21`.

## Policy reference

`policy.json` is a JSON object. Every key is optional; a missing key uses its default. Unknown keys are an error (exit 2) so a typo cannot silently weaken the gate; keys starting with `_` or `$` are comments.

| Key | Default | Meaning |
| --- | --- | --- |
| `failOn` | `["C2", "C9"]` | Fail when the re-evaluated result contains any of these check ids (C1–C10, NC, V0, U1, S1), or when the captured plans could not test one of them (NOT ESTABLISHED, see `allowNotEstablished`). The default is the two Critical checks: trackers after the user refused, and tracking in a child-facing app. |
| `allowNotEstablished` | `false` | A `failOn` check that the captured plans could not test fails the gate. Set `true` to report it as NOT ESTABLISHED without failing. |
| `minScore` | none | Fail when the score is below this (0–100). Not evaluated when the result is inconclusive. |
| `maxHigh` | none | Fail when more than this many findings are High or Critical. |
| `requireFreshState` | `true` | Every plan must have started from an approved fresh reset. A plan on kept data fails, and so does an audit that did not record its fresh state. |
| `allowInconclusive` | `false` | An inconclusive result (V0: almost no lookups captured) fails unless this is `true`. |
| `requirePlans` | `["ACCEPT", "REJECT", "SILENT"]` | These plans must be captured. An Accept or Reject plan counts only when that choice was recorded: with no consent screen, or a screen but no recorded choice, behaviour after the choice was not observed. |

Example (`docs/ci/traceveil-policy.json`):

```json
{
    "_comment": "TraceVeil CI release gate. Every key is optional; see docs/CI_MODE.md for each key and its default. Keys starting with _ are comments.",
    "_failOn": "This example is for an app that is not directed at children, so it gates on C2 only. For an app used by children, add \"C9\" and audit with 'This app is used by children' switched on: without that declaration (or a consent-screen review that answers child audience) C9 is NOT ESTABLISHED, which fails the gate unless allowNotEstablished is true.",
    "failOn": ["C2"],
    "minScore": 85,
    "maxHigh": 0,
    "requireFreshState": true,
    "allowInconclusive": false,
    "allowNotEstablished": false,
    "requirePlans": ["ACCEPT", "REJECT", "SILENT"]
}
```

## Output formats

- **text** (default): the report shown above.
- **json**: schema `traceveil.ci.check.v1` with `passed`, `exitCode`, the effective `policy`, and per bundle the stored and current results (score, `band` as the engine's enum name and `bandText`, the neutral range shown in the text report), plan coverage (choice and fresh state, `null` when not recorded), findings (check id, severity, evidence label, evidence lines, entities, DPDP references, explanation) and each rule's status. `diff --format json` uses schema `traceveil.ci.diff.v1`, with `resolved`, `notEstablished` (check id, title and reason), `added` and `unchanged`.
- **sarif**: SARIF 2.1.0 for GitHub code scanning. One result per re-evaluated finding; `ruleId` is the check id; `level` is `error` for Critical and High, `warning` for Medium, `note` for Low and Info. The message names the severity, the evidence label (OBSERVED, or INFERRED with what it was inferred from), the first evidence line and the lookup caveat; synthetic samples are prefixed `[synthetic sample]`. Each result points at its bundle file (path relative to the working directory, so run the tool from the repository root) at the line of the stored finding. `partialFingerprints` (`<package>/<check id>`) keeps an alert stable across releases. Policy violations are listed under `invocations[0].properties`; the job's exit code is what gates the release.

Scores are shown with a neutral band ("85–100 band", "60–84 band", "below 60" or "inconclusive"), never as a readiness or compliance label. No output includes penalty figures.

## Bundle formats

Both formats are written by `core/src/main/kotlin/com/dpdpxray/core/export/AuditBundle.kt` and shared with the web console.

`traceveil.audit.v1`:

| Field | Content |
| --- | --- |
| `schema` | `"traceveil.audit.v1"` |
| `exportedAt` | ISO-8601 UTC time of the export, e.g. `2026-10-10T08:15:30Z` |
| `app` | `{"package", "label"}` of the audited app |
| `meta` | The audit's saved metadata (id, target, createdAtMs, score, band, findingCount, plans, sample, timingProfile), verbatim |
| `sessions` | One object per plan, exactly as the phone saves them (lookups with phases, consent events, consent-screen review, capture health, fresh state) |
| `result` | The findings and score the phone computed |
| `networkReadings` | The per-plan upload readings, or `null` when they were not recorded (never "zero upload") |
| `tool` | `{"name": "TraceVeil", "version": <app version>}` |

`traceveil.quarantine.v1`: `{"schema": "traceveil.quarantine.v1", "exportedAt", "report": <the saved quarantine report>}`.

Compatibility rules: readers ignore fields they do not know; a missing optional part (`meta`, `networkReadings`) reads as unknown; a wrong or newer schema is a clear error (exit 2 in the CLI). Fixtures may add a top-level `"note"`.

## Sample bundles

`docs/samples` holds synthetic bundles. Each is generated from existing core test fixtures, says so in its `note`, and has `meta.sample = true`; none is a device observation. Their results are computed by the rules engine, not typed in.

| File | What it shows |
| --- | --- |
| `leakyshop-leaky_sample.json` | LeakyShop leaky, three plans after a fresh reset: 21/100 with C2, C1, C3, C4, C10. Fails the default policy on C2 (and on C9, not established). |
| `leakyshop-fixed_sample.json` | LeakyShop fixed, same plans: 100/100, no findings. Fails the default policy only because C9 is not established; passes the example policy. |
| `instagram-kept-data_sample.json` | The G1 kept-data case: one plan on kept data, no consent screen, only U1 and S1. Scores 100 but fails the default policy (not fresh, plans missing, C2 and C9 not established). |
| `riskdemo-quarantine_sample.json` | A `traceveil.quarantine.v1` report for the TEST risk-demo app (documentation-range address). |

Regenerate them after changing the fixtures or the rules with `./gradlew :cli:writeSamples`; `:cli:test` fails while the committed files are out of date.

## Limits

- The evidence is one phone, one run, one build. A pass covers what that run observed; it is not proof the app never contacts a tracker, and it is not a compliance certificate.
- A DNS lookup is an attempt to contact a host, not proof that personal data was sent. Payloads are never inspected.
- INFERRED findings come from SDK documentation or the consent-screen AI review, not direct observation; they count half in the score.
- CI re-evaluates the recorded lookups and screen review; it cannot recover evidence the phone did not record.
- The bundle contains consent-screen text and device details. Review it before committing it to a repository others can read.
