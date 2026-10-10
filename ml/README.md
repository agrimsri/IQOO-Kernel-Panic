# TraceVeil's own models

Two small models that TraceVeil trained itself on public data and runs **on the phone in pure Kotlin** (no ML runtime, no network). Both outputs are **INFERRED scores** shown next to OBSERVED facts; neither ever creates a finding, a verdict or an automatic action by itself.

| Model | Question it answers | Scorer in `:core` | Exported weights | Model card |
| --- | --- | --- | --- | --- |
| APK permission risk | Does this app's declared-permission set resemble the malware class of research datasets? | `com.dpdpxray.core.ml.PermissionRiskModel` | `core/src/main/resources/ml/apk_permission_risk.json` (8.7 KB) | [MODEL_CARD_apk_permission_risk.md](MODEL_CARD_apk_permission_risk.md) |
| TrackerLens (experimental) | Does the site of an unknown hostname resemble tracking/analytics sites on public blocklists? | `com.dpdpxray.core.ml.HostnameModel` | `core/src/main/resources/ml/tracker_hostname.json` (354 KB, CC BY-SA 3.0) | [MODEL_CARD_tracker_hostname.md](MODEL_CARD_tracker_hostname.md) |

Data sources, licences, citations and file hashes: [DATA_SOURCES.md](DATA_SOURCES.md). Metrics and slide-ready plots: `ml/reports/<model>/metrics.json` and `*.png`.

A third model, **ConsentLens-1**, labels consent-screen strings in many languages and runs through LiteRT on the phone's NPU (Qualcomm QNN HTP delegate) with a CPU fallback. It lives in its own folder with its dataset, training script, metrics and card: [consentlens/MODEL_CARD.md](consentlens/MODEL_CARD.md) (Python 3.11 + TensorFlow, `consentlens/requirements.txt`).

## Using them from the app

```kotlin
val apkModel = PermissionRiskModel.loadDefault()               // load once and reuse
val risk = apkModel.score(packageInfo.requestedPermissions?.toSet().orEmpty())
risk.probability; risk.elevated; risk.raising.take(3); risk.summary()   // evidence = INFERRED

val trackerLens = HostnameModel.loadDefault()
val host = trackerLens.score("x9.some-unknown-site.com")
host.site; host.probability; host.elevated; host.topNgrams; host.summary()
```

Show the score with its INFERRED label and its reasons, next to the OBSERVED facts (the permission list, the DNS lookup). Use TrackerLens only for hosts the tracker catalogue does not already know, and never turn either score into a finding, a penalty or an automatic action.

## Reproduce end to end

Requirements: Python 3.14 with the pinned packages in [requirements.txt](requirements.txt) (`pip install -r ml/requirements.txt`), about 100 MB of disk for the raw data and a few GB of free RAM. Run everything from the repository root. `-P` keeps Python from importing modules from the current directory.

```sh
python -P ml/fetch_data.py                    # downloads into ml/data/raw/ (git-ignored) and prints SHA-256 per file
python -P ml/train_apk_permission_risk.py     # about 4 minutes on our laptop
python -P ml/train_tracker_hostname.py        # about 6 minutes on our laptop
```

Each training script writes, deterministically (seed 42):

- `ml/reports/<model>/metrics.json`: every reported number: data statistics, CV folds, held-out test metrics with 95% group-bootstrap intervals, baselines, cross-dataset or worst-error analysis, and the export parity check;
- `ml/reports/<model>/*.png`: ROC, precision-recall, calibration and confusion-matrix plots (plus permission weights and the cross-dataset ROC for the APK model);
- the exported model JSON in `core/src/main/resources/ml/` and about 50 golden samples in `core/src/test/resources/ml/`.

Then check that the Kotlin scorers reproduce Python exactly (within 1e-6):

```sh
./gradlew :core:test --tests "com.dpdpxray.core.ml.*"
```

The upstream lists (EasyPrivacy, EasyList, Majestic Million, Public Suffix List) change daily. `fetch_data.py` reports when a file differs from the one we trained on, and a fresh run then gives slightly different numbers; the UCI datasets and AOSP files are fixed.

## How we evaluate (same rules for both models)

1. **Split by group, not by row**, so near-copies cannot leak into the test set: by identical permission vector for the APK model (75% of NATICUSdroid rows are duplicates), by domain family for TrackerLens.
2. **Choose everything on the training split** (group 5-fold CV for the regularisation strength and the threshold), then score the held-out 20% once.
3. **Report** ROC-AUC, PR-AUC (average precision), precision, recall, F1 and FPR at the chosen threshold, the confusion matrix, Brier score and a calibration curve, precision at 90% recall, precision at realistic prevalence, and 95% confidence intervals from a bootstrap that resamples whole groups.
4. **Always compare with a baseline** (dangerous-permission count; keyword rule and TraceVeil's 52-domain catalogue).
5. **Test generalisation**: the APK model is also tested on a second, independent dataset (TUANDROMD).

Shared code: `evaluation.py` (metrics), `plots.py` (figures), `permissions.py` (manifest-name normalisation), `hostnames.py` (hostname features that `HostnameModel.kt` mirrors exactly).
