# Model card: TrackerLens hostname model (v1.0.0, experimental)

**What it is.** A logistic regression over hashed character n-grams (2-5) plus four shape features (length, label count, digit ratio, character entropy). Given a hostname the tracker catalogue does not know, it scores how much the hostname's **site** (its registrable-domain approximation, for example `x9.some-site.co.in` → `some-site.co.in`) resembles the tracking/analytics sites that EasyPrivacy blocks outright. It runs in pure Kotlin (`com.dpdpxray.core.ml.HostnameModel`).

**What it is not.** Not a tracker detector you can quote as fact. The output is labelled **INFERRED** ("possible tracking site … model score N%, inferred … a lookup is an attempt to contact a host, not proof that data was sent"). It never creates a C1/C2 finding or any penalty on its own, and a low score does not clear a host.

**Status: experimental.** It is clearly better than the baselines, but at its threshold about two of every three flags are wrong (precision 0.35). Use it only as a "look closer" hint for unknown hosts.

| | |
| --- | --- |
| Exported file | `core/src/main/resources/ml/tracker_hostname.json` (354 KB; 65,536 float32 hashed weights in base64, 4 dense weights, intercept, threshold, feature definition, metrics, sources, licence). Shared under **CC BY-SA 3.0** because it is derived from EasyPrivacy/EasyList. |
| Feature definition | trim, lower-case, strip trailing dots → site (last two labels, or three under a two-letter country code with a common second level such as `co.in`) → distinct n-grams of `^site$` → FNV-1a 32-bit, 16-bit bucket (each bucket counts once) + length/64, labels/8, digit ratio, entropy/5. Python `ml/hostnames.py` and Kotlin `HostnameModel.kt` implement the same steps. |
| Parity | 51 golden samples: Kotlin equals the Python reference within 1e-6 (`HostnameModelTest`). Rounding the weights to float32 changes probabilities by at most 2.2e-8 versus scikit-learn. |

## Data

- **Positives:** 6,807 sites that EasyPrivacy blocks outright with a `||site^` rule (The EasyList authors; GPL-3.0+ or CC BY-SA 3.0+, used under CC BY-SA 3.0).
- **Negatives:** 198,757 sites from the Majestic Million top 200,000 (CC BY 3.0) that no EasyPrivacy or EasyList `||d^` rule covers.
- Prevalence 3.3%. Groups: 186,835 domain families via the Public Suffix List (MPL-2.0, training only). Details in [DATA_SOURCES.md](DATA_SOURCES.md).

**Why sites and not full hostnames (a shortcut we caught).** 85.0% of EasyPrivacy hosts are subdomains (for example `smetrics.<brand>.com`), but only 0.2% of the popular-domain negatives are. A plain "has a subdomain" rule would reach ROC-AUC 0.924 on full hostnames without learning anything about tracking. An exploratory full-hostname model (not kept) scored 0.978, a number that shortcut alone gets most of the way to, and on a phone, where most lookups are subdomains, it would likely have over-flagged ordinary hosts (not measured). Scoring the site removes the shortcut, and the honest number drops to 0.878. The site rule agrees with the full Public Suffix List on 99.8% of the Majestic domains.

## Method

- Split: stratified **group** split by domain family (the registrable domain's first label, so `example.com`, `example.co.in` and `cdn.example.net` stay together); 80% train (164,452), 20% held-out test (41,112); **0 test families appear in training**.
- Model selection on the training split only, by group 5-fold CV: C from {0.03, 0.1, 0.3} by out-of-fold log loss (0.110, **0.107**, 0.110 → C = 0.1); threshold = F1-maximising value on out-of-fold predictions (**0.158**, low because positives are rare).
- Baselines: a keyword rule (count of words such as `analytic`, `track`, `metric`, `pixel`, `stat`, `telemetry`, `beacon`, `collect`, `log`, `tag`, `ads`, `click`) and TraceVeil's existing 52-domain tracker catalogue.
- Confidence intervals: 500 group-bootstrap replicates (resampling domain families).

## Results

**Group 5-fold CV on the training split:** ROC-AUC 0.868 ± 0.004, PR-AUC 0.312 ± 0.009, Brier 0.027 (keyword rule: ROC-AUC 0.553 ± 0.007, PR-AUC 0.050 ± 0.004).

**Held-out test (41,112 sites, 1,361 positives = 3.3%), 95% group-bootstrap CI:**

| Model | ROC-AUC | PR-AUC (chance = 0.033) | Precision | Recall | F1 | FPR |
| --- | --- | --- | --- | --- | --- | --- |
| **TrackerLens (threshold 0.158)** | **0.878 [0.870, 0.887]** | **0.324 [0.298, 0.351]** | **0.353 [0.328, 0.376]** | **0.357 [0.333, 0.382]** | **0.355 [0.333, 0.376]** | **0.022 [0.021, 0.024]** |
| Keyword rule (≥ 1 keyword) | 0.547 [0.537, 0.556] | 0.046 [0.042, 0.052] | 0.136 | 0.119 | 0.127 | 0.026 |
| TraceVeil catalogue only | 0.502 [0.500, 0.503] | 0.035 [0.032, 0.038] | 0.556 (9 flags) | 0.004 | 0.007 | 0.000 |

Confusion matrix at 0.158: TN 38,862, FP 889, FN 875, TP 486. Brier 0.026 [0.025, 0.028]; the calibration curve follows the diagonal over the range the model uses (0-0.2). Precision at 90% recall is only 0.081. If 10% of unknown sites were trackers, precision at this threshold would be about 0.64; at 1%, about 0.14.

The catalogue baseline is near chance here by design: these sites are, by construction, mostly unknown to it. That gap is exactly what TrackerLens is for, and also why its flags must stay hints.

**Worst errors (held-out test).** Highest-scored negatives: `miningpoolstats.stream`, `numberanalytics.com`, `sitemetricstool.com`, `f-counter.com`, `myshiptracking.com`, `statsbomb.com`, `counter.com`, `soccerstats.com`, `agencyanalytics.com`, `analyticsvidhya.com`: the model reacts to words like *stats*, *metrics*, *analytics*, *counter* and *tracking*, even on ordinary websites (and a few of these may simply be unlisted analytics services). Lowest-scored positives: `e-kuzbass.ru`, `modernus.is`, `humanpresence.app`, `naanalle.pl`, `ntlab.org`, `hub.com.pl`, `apkonline.ru`, `boomerang.com.au`, `nodethisweek.com`, `openguid.org`: tracking sites with ordinary-looking names cannot be found from the name alone.

Plots (`ml/reports/tracker_hostname/`): `roc_test.png`, `pr_test.png`, `calibration_test.png`, `confusion_lr_test.png`. Every number is in `metrics.json`.

## Intended use

A secondary hint for hosts that the tracker catalogue does not recognise: "possible tracking site (model score N%, inferred)", shown with the name parts that raised the score, next to the OBSERVED lookup. **Out of scope:** findings, scores or penalties, blocking, naming a company, and any claim that data was sent.

## Limits (say these out loud)

1. **Modest accuracy.** At the threshold, about 2 in 3 flags are false and about 2 in 3 listed tracking sites are missed; the name alone carries limited signal.
2. **Labels are list decisions.** Positives are what EasyPrivacy editors chose to block; unlisted analytics services sit among the negatives (label noise), and lists change daily.
3. **Popular-site negatives.** Negatives come from the top 200,000 popular sites; obscure legitimate sites are under-represented, so expect more false alarms on them than the test FPR (2.2%) shows.
4. **Sites only.** First-party tracking subdomains (`metrics.<shop>.com`) and CNAME cloaking are out of scope for this model; the existing catalogue and CNAME-alias rules handle what they can.
5. **Approximate explanations.** Hashed n-grams share buckets (65,536 buckets), so the "name parts that raised it" are approximate.
6. **ASCII hostnames.** Internationalised names should be passed in their `xn--` (punycode) form, as DNS uses them.
