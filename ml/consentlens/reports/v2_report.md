# ConsentLens v2: experiments, benchmarks and related work

Measured on the team laptop (Intel Core i7-1355U, Python 3.11.9, TensorFlow 2.20 CPU) on 10–11 Oct 2026. Every number below is read from the JSON files in `reports/v2/` written by `experiments_v2.py`, `analyze_v2.py`, `external_benchmark.py` and `decide_v2.py`. Phone NPU facts (delegation, latency) are not measured here.

## Verdict

**ConsentLens-1 stays in the app.** The wider candidate s2_w160 passed every internal ship criterion (macro-F1 +0.060 on seen languages and +0.058 on never-seen languages, mean of 3 seeds, every role up), but it is not shipped: on the only external labelled data (Bouhoula et al., USENIX Security 2024: 2,204 real, mostly English cookie-banner button strings) its Reject F1 fell from 0.513 to 0.415 (precision 0.51 to 0.32), and in the app's layered decision it would emit 134 wrong Reject labels at its calibrated threshold 0.68 (79 at 0.90) against 47 for ConsentLens-1, for example "Cookie preferences" and "Configurar cookies". A wrong Reject on a settings button makes an audit infer a visible reject option and hides the most common consent dark pattern, so v2 is not genuinely better for TraceVeil yet (and a new file would need a fresh NPU check on the phone). The app assets are unchanged; only the experiment code and this report are committed.

The lead's ship criteria, measured on the internal test folds:

| Criterion | Measured | Result |
| --- | --- | --- |
| Macro-F1 gain >= 0.03, seen languages | +0.060 | PASS |
| Macro-F1 gain >= 0.03, held-out languages | +0.058 | PASS |
| No role's F1 drops by more than 0.05 | worst +0.011 (OTHER, seen) | PASS |
| Op types are a subset of v1's | no new op type | PASS |
| INT8 vs float agreement >= 98% | 98.9% lowest seed and protocol (lowest single fold 98.7%) | PASS |
| Size <= 400 KB | 271,720 bytes | PASS |

The external check (section 8) is not one of those criteria; it is the reason the candidate is held back. If the team accepts that risk, v2 can be installed in a few minutes:

```sh
# the exported candidate (all rows, seed 42) is kept outside Git: D:/DevTools/tmp/consentlens-v2-work/consentlens2_candidate_int8.tflite (+ .json)
python -I ml/consentlens/experiments_v2.py ship --recipe s2_w160 --out <that .tflite> --threshold 0.68 \
    --summary ml/consentlens/reports/v2/outer_v1_vs_s2_w160.json --model-id ConsentLens-2   # rewrites the asset, meta and golden decode cases
# then set ConsentLensText.MODEL_ID = "ConsentLens-2", update the ConsentLensEngine KDoc size line, run :core:test :app:testDebugUnitTest :app:assembleDebug,
# and on the phone: AI lab -> ConsentLens -> Load (planNodes=1/11, fullyDelegated=true), Benchmark, Try samples
```

## 1. v1 reproduced exactly

`experiments_v2.py run --recipe v1 --seed 42` calls ConsentLens-1's own `train_consentlens.train` / `to_int8` on the same folds. Every fold matched the published `metrics.json` to 4 decimals:

| Number | v1 published | Reproduced |
| --- | --- | --- |
| Held-out languages, macro-F1 INT8 | 0.5207 | 0.5207 |
| Held-out languages, macro-F1 float | 0.5225 | 0.5225 |
| Held-out languages, INT8/float agreement | 0.9887 | 0.9887 |
| Seen languages, macro-F1 INT8 | 0.6710 | 0.6710 |
| Lexicon baseline held-out / seen | 0.1489 / 0.1782 | 0.1489 / 0.1782 |

v1's facts in the brief were also checked against the files: 145,816-byte INT8 file (SHA-256 `5802e3df…`), 130,311 parameters, 11 ops (GATHER, EXPAND_DIMS, CONV_2D, RESHAPE, EXPAND_DIMS, CONV_2D, RESHAPE, REDUCE_MAX, FULLY_CONNECTED, FULLY_CONNECTED, SOFTMAX), 7 roles, 18,655 strings, 76 language groups, threshold 0.9 in `consentlens_meta.json`. The NPU 11/11 delegation is a phone fact and was not re-checked here.

## 2. Protocol and model selection

- **Test protocols (unchanged):** 5 leave-languages-out folds (75 non-English language groups; English always in training) and 5 seen-language folds grouped by normalised text. Fold assignment fixed with seed 42 for every model. Macro-F1 = mean of the 7 per-role F1 scores on the pooled predictions of the 5 folds.
- **Seeds:** v1 with training seeds 42, 1, 2; the candidate with 1, 2, 3. Mean ± sample std over seeds.
- **Selection never used outer test rows.** All recipe choices used an inner validation split inside the training rows of outer fold 0 (held-out languages: 15 of its 60 training language groups, 3,661 validation strings; seen languages: a quarter of its training texts, 3,734 validation strings). Outer fold 0's test rows were never used; those validation rows are test rows of outer folds 1–4, so folds 1–4 carry a small selection effect, and fold 0 alone is reported as the clean check.
- **Pre-registered rule** (written before any candidate's outer numbers existed): the packed ensemble `pk2_mix_k33` is the candidate if its dev macro-F1 is at least the best single model's on both dev protocols; otherwise the best single model is.
- **Outcome of the rule:** `pk2_mix_k33` dev 0.4956 held-out / 0.6786 seen against the best single model `s2_w160` 0.4918 / 0.6843: the ensemble lost on the seen-language dev split, so **`s2_w160` became the candidate**. To save time the packed ensemble's outer run had been started in parallel; it was stopped as soon as the rule selected `s2_w160`. For transparency, its only finished folds (seed 1, held-out-language folds 0 and 1) were INT8 macro-F1 0.646 and 0.557 against v1's 0.574 and 0.500 on the same folds; an incomplete run is not comparable and was not used for any decision. It is the obvious next experiment.

## 3. What was tried (dev split only)

| Recipe | Change against v1 | Held-out-language dev macro-F1 | Seen-language dev macro-F1 |
| --- | --- | --- | --- |
| `pk2_mix_k33` | packed ensemble of 2 members (s1 and s2 recipes, Conv 128×3 + 128×3 each) in one graph | 0.4956 (n=1) | 0.6786 (n=1) |
| `s2_w160` | s2, wider: 64-dim embedding, Conv 160×3 + Conv 160×7, Dense 160 (272 KB) | 0.4918 (n=1) | 0.6843 (n=1) |
| `s1` | v1 network; cosine LR decay, weight EMA (0.999), 40 epochs | 0.4893 (n=1) | 0.6473 (n=1) |
| `s2` | s1 + byte-noise augmentation (50% of rows per epoch) + embedding/pool dropout | 0.4813 (n=1) | 0.6741 (n=1) |
| `v1` | ConsentLens-1 recipe (constant LR 2e-3, 30 epochs, sqrt class weights) | 0.4513 (n=1) | 0.6486 (n=1) |

Other findings:
- **Logit adjustment** (subtracting τ·log prior from the logits, foldable into the last bias) did not help v1 on dev (τ = 0.1: +0.002 / −0.000; larger τ hurt): the square-root class weights already balance the roles.
- **Dilation:** Keras `Conv1D(dilation_rate>1)` converts to SPACE_TO_BATCH_ND + CONV_2D + BATCH_TO_SPACE_ND + RELU (3 new op types). A direct `tf.nn.conv2d(..., dilations=…)` converts to a single CONV_2D with a dilation factor; `experiments_v2.DilatedConv1D` does that. Three-layer, dilated and strided variants (`s2_d3`, `s2_s3`, `deep*`) were built and op-checked (they convert to v1's op types only) but **not screened**: there was not enough laptop time.
- **Not run for lack of time:** label smoothing (`s3`), focal loss, full inverse-frequency weights (`s2_inv`) and language-balanced sampling exist as recipe switches; knowledge distillation has only a soft-target hook (no teacher was trained). None has a dev result, so nothing is claimed about them.
- **Two-member ensembles** (mean log-probability of saved dev predictions, seed 1): every pair of the screened models beat its better member on both dev protocols, by +0.012 to +0.056 macro-F1 (`reports/v2/dev_ensembles.json`). Two v1-sized members packed block-diagonally would be about 490 KB, over the 400 KB limit, so the packed variant `pk2_mix_k33` uses members with two 3-wide convolutions (389 KB packed).
- **Packed ensemble:** members are trained independently, then written into ONE network whose embedding is the members' embeddings side by side, whose conv and dense kernels are block-diagonal, and whose last layer averages the members' logits (`experiments_v2.pack`). The graph has the same 11 ops as v1; the zero blocks quantise to exact zeros. The packed float model reproduces the members' averaged logits to 3e-7 (checked).

## 4. Results: v1 against the candidate (INT8 files, same folds)

| Protocol | ConsentLens-1 | Candidate | Change | Fold 0 only (clean) | Accuracy v1 → cand |
| --- | --- | --- | --- | --- | --- |
| Seen languages, unseen strings | 0.659 ± 0.014 | 0.719 ± 0.009 | +0.060 | 0.655 → 0.717 | 0.860 ± 0.008 → 0.880 ± 0.002 |
| Held-out languages (never seen) | 0.520 ± 0.007 | 0.579 ± 0.002 | +0.058 | 0.575 → 0.637 | 0.806 ± 0.019 → 0.831 ± 0.004 |

Per-role F1 (mean over seeds):

| Role | v1 seen | cand seen | Δ | v1 held-out | cand held-out | Δ |
| --- | --- | --- | --- | --- | --- | --- |
| ACCEPT | 0.585 | 0.644 | +0.058 | 0.363 | 0.415 | +0.052 |
| REJECT | 0.538 | 0.590 | +0.052 | 0.346 | 0.416 | +0.070 |
| MANAGE | 0.588 | 0.643 | +0.054 | 0.359 | 0.430 | +0.071 |
| PURPOSE_NOTICE | 0.689 | 0.739 | +0.050 | 0.555 | 0.611 | +0.056 |
| PRIVACY_LINK | 0.601 | 0.705 | +0.103 | 0.461 | 0.530 | +0.070 |
| WITHDRAW_INFO | 0.690 | 0.778 | +0.088 | 0.669 | 0.745 | +0.076 |
| OTHER | 0.923 | 0.935 | +0.011 | 0.891 | 0.905 | +0.014 |

Per-seed INT8 macro-F1:

- Seen languages, unseen strings: v1 seed 1 0.6631, seed 2 0.6438, seed 42 0.6710; candidate seed 1 0.7178, seed 2 0.7107, seed 3 0.7283
- Held-out languages (never seen): v1 seed 1 0.5273, seed 2 0.5136, seed 42 0.5207; candidate seed 1 0.5776, seed 2 0.5776, seed 3 0.5814

The published v1 (seed 42) was v1's best seed on seen languages, so v1's 3-seed mean is below its published 0.671. The worst candidate seed beats the best v1 seed on both protocols.

Figures: `reports/v2/fig_macro_f1.png`, `fig_per_class_f1.png`, `fig_confusion.png`, `fig_layered.png`.

## 5. Size, ops, quantisation, latency

| | ConsentLens-1 | Candidate (exported, all rows) |
| --- | --- | --- |
| File size | 145,816 bytes | 271,720 bytes |
| Parameters | 130,311 | 253,511 |
| Ops | 11: GATHER, EXPAND_DIMS, CONV_2D, RESHAPE, EXPAND_DIMS, CONV_2D, RESHAPE, REDUCE_MAX, FULLY_CONNECTED, FULLY_CONNECTED, SOFTMAX | 11: GATHER, EXPAND_DIMS, CONV_2D, RESHAPE, EXPAND_DIMS, CONV_2D, RESHAPE, REDUCE_MAX, FULLY_CONNECTED, FULLY_CONNECTED, SOFTMAX |
| New op types | – | none |
| INT8/float agreement, held-out languages | 0.990 ± 0.002 | 0.990 ± 0.001 |
| INT8/float agreement, seen languages | 0.990 ± 0.001 | 0.991 ± 0.001 |
| SHA-256 | `5802e3dff77f70ee…` | `ce033aa749a106ce…` |

Laptop CPU latency, one string, batch 1 (laptop CPU (Intel Core i7-1355U), TFLite interpreter from TensorFlow 2.20, batch 1, XNNPACK default):

| File | Threads | Median ms | p95 ms |
| --- | --- | --- | --- |
| consentlens_int8.tflite | 1 | 0.073 | 0.094 |
| consentlens_int8.tflite | 2 | 0.048 | 0.094 |
| consentlens2_candidate_int8.tflite | 1 | 0.147 | 0.209 |
| consentlens2_candidate_int8.tflite | 2 | 0.088 | 0.176 |

These are laptop numbers, not phone numbers.

AI-lab demo phrases (24 hand-written strings in 15 languages, some in the training data): ConsentLens-1 matches our expected role on 19/24, the v2 candidate on 19/24 (`reports/v2/demo_phrases_v1_v2.json`).

## 6. Threshold

ConsentLens-1's threshold is 0.9. On the dev predictions (never the test folds), v1's precision on confident non-OTHER predictions at 0.9 was 0.595 (held-out-language dev) and 0.746 (seen-language dev). The lowest candidate threshold with at least that precision on both dev splits is **0.68**. The app uses **0.90**.

## 7. The layered system (string level, offline)

Decision order as in the app: lexicon button phrase / withdrawal cue / privacy-policy wording (OBSERVED) > ConsentLens role if not OTHER and ≥ threshold (INFERRED) > unknown. Coverage = consent-role strings labelled correctly / all consent-role strings; precision = correct / emitted non-OTHER labels. Out-of-fold predictions, mean over seeds.

| Protocol | System | Macro-F1 | Coverage | Precision |
| --- | --- | --- | --- | --- |
| Seen languages, unseen strings | Lexicon only | 0.184 | 0.031 | 0.968 |
| Seen languages, unseen strings | Lexicon + ConsentLens-1 (≥ 0.90) | 0.678 | 0.551 | 0.768 |
| Seen languages, unseen strings | Lexicon + candidate (≥ 0.68) | 0.745 | 0.647 | 0.789 |
| Held-out languages (never seen) | Lexicon only | 0.152 | 0.015 | 0.967 |
| Held-out languages (never seen) | Lexicon + ConsentLens-1 (≥ 0.90) | 0.530 | 0.361 | 0.650 |
| Held-out languages (never seen) | Lexicon + candidate (≥ 0.68) | 0.596 | 0.423 | 0.720 |

No number is reported for the optional Gemma vision layer.

## 8. External benchmark (zero-shot): Bouhoula et al., USENIX Security 2024

`data/interactive_elements.csv` from https://github.com/bouhoula/alsacnc (GPLv3; downloaded for evaluation, not committed; SHA-256 `ce1152b5963ecd2b65dfc9e13bf26c4f950e1758059ae7732cfc67d2d3f96f42`), 2,356 labelled cookie-notice button strings from .uk sites (labels: Other 1529, Close/Continue without accepting 51, Accept 232, Save cookie settings 101, Reject 153, Cookies settings 290). Mapping: Accept→ACCEPT, Reject→REJECT, Cookies settings→MANAGE, Other→not-a-choice (our PURPOSE_NOTICE/PRIVACY_LINK/WITHDRAW_INFO/OTHER); 'Save cookie settings' and 'Close/Continue without accepting' are not scored. 2,204 scored rows; 73 rows also occur in our training data.

| System | Macro-F1 (4 classes) | Accuracy | Accept F1 | Reject F1 | Manage F1 | Not-a-choice F1 |
| --- | --- | --- | --- | --- | --- | --- |
| Lexicon only | 0.632 | 0.817 | 0.775 | 0.545 | 0.319 | 0.889 |
| v1 alone (argmax) | 0.558 | 0.746 | 0.467 | 0.513 | 0.400 | 0.850 |
| Lexicon + v1 (≥ 0.90) | 0.669 | 0.815 | 0.716 | 0.626 | 0.444 | 0.890 |
| v1 alone, novel strings only (n=2,134) | 0.528 | 0.741 | 0.417 | 0.474 | 0.369 | 0.851 |
| v2 alone (argmax) | 0.563 | 0.736 | 0.580 | 0.415 | 0.410 | 0.848 |
| Lexicon + v2 (≥ 0.68) | 0.652 | 0.795 | 0.769 | 0.503 | 0.461 | 0.878 |
| v2 alone, novel strings only (n=2,134) | 0.535 | 0.730 | 0.537 | 0.373 | 0.382 | 0.847 |
| Lexicon + v2 (≥ 0.90) | 0.671 | 0.814 | 0.801 | 0.542 | 0.456 | 0.885 |

**Per role the two models move in opposite directions on this English set.** Model alone: Accept F1 0.467 → 0.580, Manage 0.400 → 0.410, but **Reject 0.513 → 0.415** (precision 0.51 → 0.32). Strings that the layered decision labels Reject on the model's word although they are not Reject: v1 at 0.90: 47; v2 at 0.68: 134; v2 at 0.90: 79. Typical v2 errors: "about cookie control", "about cookies", "about this tool", "about uclan cookies", "about uclan's cookies", "additional cookies". A wrong Reject on a button can make an audit infer "reject option visible", so this is the error that matters most.

The paper's own BERT reports "an accuracy of 95.1% and an F1 score of 90.9%" with 5-fold cross-validation trained on this same data (in-distribution, 6 labels, server-side, text machine-translated to English). Our numbers are zero-shot with a different label set; they are not comparable and we do not claim to beat it.

## 9. Related work (different tasks: a map, not a leaderboard)

| Work | Task | Input | Model | On-device | Languages | Reported metric |
| --- | --- | --- | --- | --- | --- | --- |
| Nouwens et al., CHI 2020 | scrape 5 CMPs' designs, check legal minimums | DOM, CMP-specific selectors | rules | no (crawler) | UK sites | 11.8% of 680 sites meet minimal requirements |
| Consent-O-Matic (Nouwens et al., CHI EA 2022) | auto-answer consent pop-ups | DOM selectors per CMP | hand-written rules (200+ CMPs) | yes (browser extension) | selector-based | no accuracy reported |
| CookieBlock (Bollinger et al., USENIX Sec 2022) | classify cookies into 4 purposes | cookie attributes (not UI text) | XGBoost | yes (browser extension) | n/a | 84.4% mean validation accuracy |
| AidUI (Mansur et al., ICSE 2023) | detect 10 dark-pattern types | screenshots | Faster R-CNN + OCR + spaCy rules | not stated | English patterns | P 0.66 / R 0.67 / F1 0.65 |
| UIGuard (Chen et al., UIST 2023) | detect dark patterns in mobile UIs | screenshots | Faster R-CNN, PaddleOCR (EN), ResNet-18, rules | not stated | English OCR | micro-F1 0.79 |
| Kollnig et al., SOUPS 2021 | consent before tracking in Android apps | screenshots + traffic | manual inspection | n/a | not stated | 9.9% of apps asked for consent |
| Nguyen et al., CCS 2022 | consent notices in Android apps vs GDPR | screenshots → OCR | keywords + clustering + manual labels | no | English only | no classifier metric |
| Koch et al., USENIX Sec 2023 | consent dialogs in Android/iOS apps | UI text via Appium | regular expressions | dynamic analysis, no model | not stated | no P/R reported |
| Khandelwal et al., USENIX Sec 2023 | detect cookie notices, generate opt-out steps | rendered DOM text | BERT-base + T5-Large (770M) | no (backend) | English | 91% correct steps |
| Bouhoula et al., USENIX Sec 2024 | classify notice buttons (6 labels) and purposes | DOM element text, translated to EN | BERT | no | 11 EU languages via translation | 95.1% acc / 90.9% F1 (5-fold CV) |
| BannerClick (Rasaii et al., PAM 2023) | detect / accept / reject banners | DOM text + position | keyword lists | no (crawler) | 12 | 99% / 97% / 87% accuracy |
| Nouwens et al., CHI 2025 | detect banners, categorise buttons | DOM text | normalised-label lookup, Levenshtein ≤ 1 | no | 29 | F1 0.98 / 0.99 / 0.99 (accept / reject / settings) |
| ConsentLens (v1), this work | role of one consent-screen string (7 roles) | accessibility-tree text, UTF-8 bytes | byte CNN, INT8 | yes, phone NPU (QNN HTP) with CPU fallback | 76 language groups in training | macro-F1 0.659 seen / 0.520 held-out languages |

We found no published on-device (phone NPU) classifier of consent-string roles. That is a statement about our search, not proof that none exists.

References and quotes (copied from the abstract page or the paper PDF on 10 Oct 2026):

1. M. Nouwens, I. Liccardi, M. Veale, D. Karger, L. Kagal. Dark Patterns after the GDPR: Scraping Consent Pop-ups and Demonstrating their Influence. CHI 2020. https://arxiv.org/abs/2001.02479 — "We scraped the designs of the five most popular CMPs on the top 10,000 websites in the UK (n=680)." … "only 11.8% meet the minimal requirements that we set based on European law."
1. M. Nouwens, R. Bagge, J. B. Kristensen, C. N. Klokmose. Consent-O-Matic: Automatically Answering Consent Pop-ups Using Adversarial Interoperability. CHI EA 2022. doi:10.1145/3491101.3519683; https://github.com/cavi-au/Consent-O-Matic (MIT).
1. D. Bollinger, K. Kubicek, C. Cotrini, D. Basin. Automating Cookie Consent and GDPR Violation Detection. USENIX Security 2022. https://www.usenix.org/conference/usenixsecurity22/presentation/bollinger — "At a mean validation accuracy of 84.4%, our model attains a prediction quality competitive with expert knowledge in the field"
1. S. M. H. Mansur, S. Salma, D. Awofisayo, K. Moran. AidUI: Toward Automated Recognition of Dark Patterns in User Interfaces. ICSE 2023. https://arxiv.org/abs/2303.06782 — "achieves an overall precision of 0.66, recall of 0.67, F1-score of 0.65 in detecting dark pattern instances"
1. J. Chen, J. Sun, S. Feng, Z. Xing, Q. Lu, X. Xu, C. Chen. Unveiling the Tricks: Automated Detection of Dark Patterns in Mobile Applications. UIST 2023. https://arxiv.org/abs/2308.05898 — "micro averages: 0.82 in precision, 0.77 in recall, 0.79 in F1 score"
1. K. Kollnig, R. Binns, P. Dewitte, M. Van Kleek, G. Wang, D. Omeiza, H. Webb, N. Shadbolt. A Fait Accompli? An Empirical Study into the Absence of Consent to Third-Party Tracking in Android Apps. SOUPS 2021. https://arxiv.org/abs/2106.09407 — "Only 9.9% of apps asked the user for consent." (paper body, https://ar5iv.labs.arxiv.org/html/2106.09407)
1. T. T. Nguyen, M. Backes, B. Stock. Freely Given Consent? Studying Consent Notice of Third-Party Tracking and Its Violations of GDPR in Android Apps. CCS 2022. https://cispa.saarland/group/stock/papers/nguyen2022consent.pdf — "we only consider consent notices written in English"
1. S. Koch, B. Altpeter, M. Johns. The OK Is Not Enough: A Large Scale Study of Consent Dialogs in Smartphone Applications. USENIX Security 2023. https://www.usenix.org/conference/usenixsecurity23/presentation/koch — "designed regular expressions capturing essential words or phrases but leaving out app-specific wordings"
1. R. Khandelwal, A. Nayak, H. Harkous, K. Fawaz. Automated Cookie Notice Analysis and Enforcement (CookieEnforcer). USENIX Security 2023. https://arxiv.org/abs/2204.04221 — "showing that it can generate the required steps in 91% of the cases"
1. A. Bouhoula, K. Kubicek, A. Zac, C. Cotrini, D. Basin. Automated Large-Scale Analysis of Cookie Notice Compliance. USENIX Security 2024. https://www.usenix.org/conference/usenixsecurity24/presentation/bouhoula — "This results in 2353 unique text samples extracted from interactive elements. We manually annotate each sample with one of the six labels: accept, reject, close, save, settings, and other." … "We train a BERT model on this dataset using 5-fold cross-validation. It achieves an accuracy of 95.1% and an F1 score of 90.9%." … "For non-English texts, we use the open-source machine translation API LibreTranslate"
1. A. Rasaii, S. Singh, D. Gosain, O. Gasser. Exploring the Cookieverse: A Multi-Perspective Analysis of Web Cookies. PAM 2023. https://arxiv.org/abs/2302.05353 — "to automatically detect, accept, and reject cookie banners with an accuracy of 99%, 97%, and 87%, respectively"
1. M. Nouwens, J. B. Kristensen, K. Maalt, R. Bagge. A Cross-Country Analysis of GDPR Cookie Banners and Flexible Methods for Scraping Them. CHI 2025. https://arxiv.org/abs/2503.19655 — "We obtain an F1 score of 0.98 for accept options, 0.99 for reject options, and 0.99 for settings options"
1. O. Laurent, A. Lafage, E. Tartaglione, G. Daniel, J.-M. Martinez, A. Bursuc, G. Franchi. Packed-Ensembles for Efficient Uncertainty Estimation. ICLR 2023. https://arxiv.org/abs/2210.09184 — "We leverage grouped convolutions to parallelize the ensemble into a single shared backbone" (ConsentLens packs members with dense block-diagonal kernels instead of grouped convolutions, to keep v1's op types).

## 10. Limits, and what the lead must verify on the phone

- Unseen languages remain hard: the choice buttons (Accept, Reject, Manage) on never-seen languages are the weakest roles for both models.
- The candidate says "Reject" too often for English cookie-settings and info links (section 8). Fix first: add such strings as MANAGE/OTHER training rows from an openly licensed source (not the GPLv3 benchmark itself, which must stay a test set), then re-run this exact protocol and the external check.
- Labels are per string key (AOSP, Klaro!, tarteaucitron.js), not per screen; most rows are Android permission/system strings.
- The external benchmark is English web buttons only; it does not test the multilingual claim.
- Folds 1–4 carry a small selection effect from the dev split; fold 0 is clean.
- No accuracy is claimed for the optional Gemma vision layer.
- Everything here is laptop-measured.

**On the phone (AI lab → ConsentLens):** Load must show “Running on the NPU”, `planNodes=1/11` and `fullyDelegated=true`; Benchmark must report NPU median/p95, `npuCpuAgree=24/24` (or close) and `matchesPythonInt8=24/24`; Try samples must match the Python INT8 reference roles in `consentlens_meta.json`.

## Reproduce

```sh
# Python 3.11 + ml/consentlens/requirements.txt; outputs go to D:/DevTools/tmp/consentlens-v2-work (override with CONSENTLENS_WORK)
python -I ml/consentlens/experiments_v2.py run --recipe v1 --seed 42        # also seeds 1, 2
python -I ml/consentlens/experiments_v2.py run --recipe s2_w160 --seed 1   # also seeds 2, 3
python -I ml/consentlens/experiments_v2.py dev --recipe <recipe> --seed 1    # dev screening
python -I ml/consentlens/analyze_v2.py outer --base v1 --cand s2_w160 --thr-cand <t>
python -I ml/consentlens/decide_v2.py --cand s2_w160 --export ml/consentlens/reports/v2/export_s2_w160.json
python -I ml/consentlens/external_benchmark.py --csv <dir>/interactive_elements.csv --tflite v1=<v1.tflite> v2=<v2.tflite>
```
