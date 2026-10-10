# Model card: APK permission risk (v1.0.0)

**What it is.** An L2-regularised logistic regression that turns the **declared permissions** of an Android app or APK into a probability-like score of how much that permission set resembles the *malware* class in public research data. It feeds the APK Guard check. It runs on the phone in pure Kotlin (`com.dpdpxray.core.ml.PermissionRiskModel`): the score is the intercept plus one weight per known declared permission, passed through a logistic function, so every score can be explained permission by permission.

**What it is not.** It is not a malware detector and never gives a verdict. Its output is labelled **INFERRED** and worded as "resembles malware samples in research data (model score N%, inferred) … not a malware verdict". A low score never clears an app.

| | |
| --- | --- |
| Exported file | `core/src/main/resources/ml/apk_permission_risk.json` (8.7 KB): 85 permission weights, intercept, threshold, metrics summary, data sources, limits |
| Input | `PackageInfo.requestedPermissions` (full manifest names). Unknown names are ignored. A doubled `android.permission.android.permission.` prefix is collapsed. |
| Output | probability, logit, `elevated` (probability ≥ threshold 0.539), the app's own permissions that raise / lower the score, how many permissions were recognised |
| Training code | `ml/train_apk_permission_risk.py` (seed 42); shared metrics in `ml/evaluation.py` |
| Parity | 50 golden samples: Kotlin equals the Python reference within 1e-6 (`PermissionRiskModelTest`); scikit-learn vs exported JSON differ by at most 3.3e-16 on the test set |

## Data

Training and held-out test: **NATICUSdroid** (UCI, CC BY 4.0; 29,332 apps released 2010-2019, about 50% malware, 86 permission columns merged to 85). Cross-dataset test: **TUANDROMD** (UCI, Tezpur University, CC BY 4.0; 4,464 apps, 80% malware; 163 of its 214 permission columns map to full manifest names). Sources, licences, citations, hashes and the name normalisation are in [DATA_SOURCES.md](DATA_SOURCES.md).

**Duplication, and why the split is grouped.** 74.9% of NATICUSdroid rows repeat another row's exact permission vector (7,368 distinct vectors; one malware profile appears 1,171 times; 97 vectors carry both labels). A random row split puts copies of the same vector in train and test (it did for 4,691 of 5,867 test rows). Our primary split therefore keeps all copies of a vector on one side: **0 of 5,859 test rows share a vector with training.** All 95% confidence intervals resample whole vector groups (group bootstrap, 1,000 replicates), which makes them wider and more honest than a row bootstrap.

## Method

- Features: 85 binary declared-permission features; nothing the phone cannot read.
- Models: (1) baseline = count of dangerous (runtime) permissions, per AOSP android-14; (2) logistic regression, deployed; (3) LightGBM, comparison only.
- Model selection on the training split only, by stratified group 5-fold CV: C chosen from {0.01 … 10} as the smallest C within 1% of the best out-of-fold log loss (C = 1.0); threshold = the F1-maximising value on out-of-fold predictions (0.539). The held-out test split was scored once.

## Results

**Group 5-fold CV on the training split (mean ± SD):**

| Model | ROC-AUC | PR-AUC | Brier |
| --- | --- | --- | --- |
| Dangerous-permission count | 0.760 ± 0.052 | 0.691 ± 0.071 | n/a |
| Logistic regression (deployed) | 0.985 ± 0.009 | 0.985 ± 0.010 | 0.043 ± 0.018 |
| LightGBM | 0.990 ± 0.005 | 0.990 ± 0.006 | 0.037 ± 0.018 |

**Held-out test (5,859 apps, 50.1% malware, no vector shared with training), 95% group-bootstrap CI:**

| Model | ROC-AUC | PR-AUC | Precision | Recall | F1 | FPR | Brier |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Dangerous count (threshold ≥ 2) | 0.801 [0.472, 0.889] | 0.742 [0.611, 0.837] | 0.743 | 0.829 | 0.784 | 0.288 | n/a |
| **Logistic regression (threshold 0.539)** | **0.978 [0.938, 0.993]** | **0.979 [0.908, 0.998]** | **0.977 [0.963, 0.986]** | **0.915 [0.822, 0.981]** | **0.945 [0.891, 0.981]** | **0.021 [0.008, 0.072]** | **0.039 [0.025, 0.049]** |
| LightGBM (threshold 0.448) | 0.982 [0.941, 0.996] | 0.981 [0.911, 0.999] | 0.981 | 0.945 | 0.963 | 0.018 | 0.039 |

Logistic-regression confusion matrix at 0.539: TN 2,861, FP 62, FN 249, TP 2,687 (accuracy 0.947). Precision at 90% recall: 0.986. LightGBM is not significantly better (overlapping intervals) and cannot be explained weight by weight, so the logistic regression is the deployed model.

**Naive row split (for comparison only):** logistic regression ROC-AUC 0.988, LightGBM 0.994. Duplicated vectors inflate these, which is why we do not quote them as results.

**Precision at real-world prevalence.** The test set is half malware; real phones are not. With the same recall (0.915) and FPR (0.021), precision would be **0.30 if 1% of checked apps were malware-labelled, 0.69 at 5% and 0.83 at 10%**. On an ordinary phone, most "elevated" results will be false alarms, which is why the output is a hint to look closer, not a verdict.

**Calibration.** Brier 0.039; the reliability curve (`calibration_test.png`) is close to the diagonal and slightly under-confident between 0.6 and 0.8. Probabilities are calibrated to a 50% malware share, not to real-world prevalence.

**Cross-dataset generalisation (the honest number).** Retraining the same logistic regression on the 60 permissions both datasets record:

| Direction | ROC-AUC [95% CI] | PR-AUC [95% CI] | At the source-chosen threshold |
| --- | --- | --- | --- |
| Train NATICUSdroid → test TUANDROMD (80% malware) | 0.851 [0.757, 0.915] | 0.952 [0.862, 0.980] | precision 0.932, recall 0.774, FPR 0.225 |
| Train TUANDROMD → test NATICUSdroid (50% malware) | 0.781 [0.729, 0.828] | 0.804 [0.716, 0.872] | precision 0.891, recall 0.370, FPR 0.045 |
| The exported 85-permission model on TUANDROMD (25 of its permissions unrecorded there, treated as absent) | 0.842 [0.778, 0.893] | 0.933 [0.871, 0.964] | precision 0.929, recall 0.818, **FPR 0.247** |

Performance drops from about 0.98 to 0.78-0.85 ROC-AUC on another dataset, and the deployed threshold flagged about a quarter of TUANDROMD's goodware. The dangerous-count baseline does not transfer at all (ROC-AUC 0.446 on TUANDROMD, worse than chance). TUANDROMD's own within-dataset group CV gives 0.966 ± 0.031, so the drop comes from the dataset shift, not from TUANDROMD being unlearnable.

Plots (`ml/reports/apk_permission_risk/`): `roc_test.png`, `pr_test.png`, `calibration_test.png`, `confusion_lr_test.png`, `roc_cross_dataset.png`, `weights_lr.png`. Every number above is in `metrics.json`.

## What drives the score

Largest raising weights (log-odds): `READ_PHONE_STATE` +4.48, launcher `INSTALL_SHORTCUT` +3.04, `CHANGE_CONFIGURATION` +2.26, `SEND_SMS` +2.14, `RESTART_PACKAGES` +1.65, `ACCESS_FINE_LOCATION` +1.52, `READ_LOGS` +1.34. Largest lowering weights: `com.google.android.c2dm.permission.RECEIVE` (push messaging) −4.50, `REQUEST_INSTALL_PACKAGES` −3.45, `RECEIVE_USER_PRESENT` −3.17, Play install-referrer −2.76, Play services `ACTIVITY_RECOGNITION` −2.59, `USE_FINGERPRINT` −2.49. These are **associations in 2010-2019 data, not causes**. `REQUEST_INSTALL_PACKAGES` lowering the score is an artefact of the data's era (it arrived with Android 8 in 2017, mostly in newer benign apps); on a phone today it deserves attention, not a discount.

## Intended use

- A secondary, explainable hint in APK Guard ("check before you install"), shown next to OBSERVED facts such as the permission list itself, always with the INFERRED label and the reasons.
- Prioritising which apps a user may want to inspect more closely.

**Out of scope:** blocking or uninstalling apps, labelling an app or developer as malicious, any automatic action, legal or compliance conclusions, and any claim about what an app actually does.

## Limits (say these out loud)

1. **Temporal drift.** Training apps are from 2010-2019. Permissions, Android rules (runtime permissions, install-source restrictions) and malware have changed since; the weights show it.
2. **Label noise.** Labels come from the dataset authors and we could not verify how each app was labelled; 97 identical permission vectors carry both labels.
3. **Permissions are not behaviour.** A benign app may request many permissions; malware can request few and use exploits, accessibility abuse or a downloaded payload.
4. **Easy to evade.** An attacker can add a low-weight permission (for example push messaging) or remove high-weight ones to lower the score.
5. **Prevalence.** Test precision (0.977) holds only at a 50% malware share; at realistic shares precision is far lower (0.30 at 1%).
6. **Feature coverage.** Only 85 permissions are known to the model; anything else is ignored, and the model says how many of the app's permissions it recognised.
7. **Pre-selected features.** NATICUSdroid's 86 columns were pre-selected by its authors (the UCI page does not say how). If they were chosen using the full data, that can flatter any model trained on it.
