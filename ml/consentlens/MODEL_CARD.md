# Model card: ConsentLens-1 (consent-screen string roles, on-device NPU)

**What it is.** A small UTF-8 byte-level 1D-CNN that reads **one** string from a consent screen (a button label or a short text block from the accessibility tree) and labels its role: `ACCEPT`, `REJECT`, `MANAGE` (settings / customise / more choices), `PURPOSE_NOTICE` (describes data use or purposes, including purpose names), `PRIVACY_LINK` (privacy policy or data-sharing information link), `WITHDRAW_INFO` (how to change or withdraw later) or `OTHER`. It extends TraceVeil's consent-screen reading beyond the English/Hindi/Kannada lexicon. It ships inside the APK as a full-INT8 TFLite file and runs on the Qualcomm Hexagon NPU through the QNN HTP delegate, with a CPU (XNNPACK) fallback.

**What it is not.** Not a verdict and not a compliance check. Its output is **AI-inferred** evidence labelled with model id `ConsentLens-1`. It never overrides an OBSERVED lexicon or tree fact, it never turns the *absence* of a string into a "missing" notice element, and the rules engine still decides every finding. A role is a guess about one string; it says nothing about what the app does with data.

## Results (measured on the laptop; INT8 = the file that ships)

Dataset: 18,655 real UI strings, 76 language groups, 4 openly licensed sources plus TraceVeil's own strings.

| Protocol | Strings tested | Accuracy | Macro-F1 |
| --- | --- | --- | --- |
| Seen languages, unseen strings: ConsentLens INT8 | 18,655 | 0.866 | 0.671 |
| Seen languages, unseen strings: EN/HI/KN lexicon (today) | 18,655 | 0.794 | 0.178 |
| Held-out languages (5 folds, never seen): ConsentLens INT8 | 18,328 | 0.791 | 0.521 |
| Held-out languages: EN/HI/KN lexicon (today) | 18,328 | 0.794 | 0.149 |

Per-role F1, held-out languages, INT8 (ACCEPT / REJECT / MANAGE / PURPOSE_NOTICE / PRIVACY_LINK / WITHDRAW_INFO / OTHER): 0.35 | 0.35 | 0.36 | 0.54 | 0.49 | 0.67 | 0.88.

Per-role F1, seen languages, INT8: 0.60 | 0.54 | 0.61 | 0.71 | 0.61 | 0.72 | 0.93.

- INT8 vs float agreement: 98.9% of held-out predictions; the quantised file costs about 0.002 macro-F1.
- Leave-source-out (train without one source, test on it) is much weaker (macro-F1 aosp-framework 0.36, aosp-permissioncontroller 0.21, klaro 0.40, tarteaucitron 0.58, traceveil 0.32): wording from an unseen product is the hardest case. That is why every ConsentLens output is labelled AI-inferred and counted at half weight, and only confident, non-OTHER predictions are used.
- Exported file: 145,816 bytes, 130,311 parameters, SHA-256 `5802e3dff77f70ee10778384cb548f7c832389ffdd52f8754c308d91a03fa6c2`, ops GATHER, EXPAND_DIMS, CONV_2D, RESHAPE, REDUCE_MAX, FULLY_CONNECTED, SOFTMAX.
- Plots: `reports/per_role_f1.png`, `reports/per_language_accuracy.png`, `reports/confusion_leave_languages_out_int8.png`; all numbers in `reports/metrics.json`.

**Honest reading:** ConsentLens is a weak-to-moderate multilingual reader (macro-F1 0.67 on known languages, 0.52 on unseen ones), but the EN/HI/KN lexicon it supplements scores 0.18 and 0.15 on the same strings because it simply does not know most languages. It widens coverage; it never decides a finding.


## Intended use

- **In audits** (`ConsentLensAugmenter`, after the tree auditor and the optional screen-review model): only strings the lexicon does not recognise are classified; predictions below the threshold, and `OTHER`, are ignored. A confident `REJECT`/`MANAGE`/`ACCEPT` button can fill an unknown reject-option field; `WITHDRAW_INFO`/`PURPOSE_NOTICE` text can set withdrawal info / purpose stated to *true*; two or more short purpose labels give an inferred purpose count; a choice button plus notice text can mark a screen the lexicon missed as an (inferred) consent screen. Every field it fills is listed in `ScreenAudit.consentLens.inferredFields` with the supporting strings, and findings built on it say "AI-inferred by ConsentLens-1 (on-device model) from: …". Disagreements with OBSERVED facts are kept in `ScreenAudit.conflicts`.
- **In AI lab**: the "ConsentLens (NPU)" card loads it, shows which processor actually runs it, benchmarks it and runs fixed sample phrases.
- **Backend in audits**: an audit uses the NPU only after the NPU has started successfully once on that phone for this exact model file (AI lab → Load records the model's SHA-256); until then audits run ConsentLens on the CPU and say why. A start that never finished (for example a native crash) keeps the CPU until "Retry NPU" is tapped.
- **Not for**: deciding consent validity, scoring apps, telling users an app is safe or unsafe, or any automatic action.

## Limitations (read before quoting a number)

- **Unseen languages are hard.** A byte model cannot know that a word it never saw means "reject". On languages it never saw, ACCEPT vs REJECT is often confused (see the per-role table). The shipped model was trained on all 76 language groups below; on other languages treat its labels as weak hints.
- **Domain gap.** Most rows are Android permission/system dialog strings (AOSP); web cookie-banner text comes from two libraries (Klaro!, tarteaucitron.js) in about 30-40 languages. Real app consent screens use other wording; leave-source-out results show how much accuracy drops on an unseen source.
- **Short generic words are ambiguous.** "OK", "Continue", "Yes", "Settings" mean different things on different screens. Generic `OK`/`Cancel`/`Continue`/`Learn more` keys were excluded from training (see Labels); the model may still label such words with low confidence.
- **Truncation.** Text is cut to 128 UTF-8 bytes (about 42 Devanagari or CJK characters), so only the start of a long notice is read.
- **Labels are ours.** Roles were assigned per string key by the rules below, not by annotators reading each screen; translations are assumed to keep the role of the English source string.
- **Not phone-verified by its authors.** NPU execution, delegation and latency must be confirmed on a phone (see "How to verify on the phone").
- **Dataset licences.** The committed dataset is derived from Apache-2.0, BSD-3-Clause and MIT sources; keep their notices (ATTRIBUTIONS.md).

## Data

Every example is a real UI string from an openly licensed project, with its source, upstream file, string key and language in `data/consentlens_dataset.jsonl`; pinned revisions, licences and notices are in `data/sources.json`. `build_dataset.py` downloads the raw files (cached outside Git) and rebuilds the set deterministically.

| Source (revision) | Licence | What it contributes |
| --- | --- | --- |
| AOSP PermissionController strings, `platform/packages/modules/Permission` @ `26231a7e` | Apache-2.0 | Allow / Don't allow / Only this time / While using the app, settings links, data-sharing rationale and purpose names, "change access in settings", plus 70 unrelated keys as OTHER; 81 locales (74 language groups) |
| AOSP framework strings, `platform/frameworks/base` `core/res` @ `1cdfff55` | Apache-2.0 | Accept / Decline / Allow / Deny / No thanks / Not now dialog buttons, plus 120 unrelated keys as OTHER; 81 locales (74 language groups) |
| Klaro! consent manager, `kiprotect/klaro` `src/translations` @ `db9f1aca` | BSD-3-Clause | Accept all / Accept selected / I decline / Let me choose / privacy policy / purpose titles and descriptions / "you can change or withdraw later"; 25 locales (24 language groups) that parse |
| tarteaucitron.js, `AmauriC/tarteaucitron.js` `lang/` @ `07254bb6` | MIT | Allow / Deny (all cookies), Personalize / Manage services, purpose texts, Privacy policy; 33 languages that parse |
| TraceVeil lexicon (`ConsentLexicon.kt`, withdraw cues) and LeakyShop dialog strings | MIT (this repository) | EN/HI/KN phrases and the demo app's real dialog text |

Not used: GPL consent plugins (licence), Firefox/Mozilla strings (MPL file-level copyleft would complicate the derived dataset), and any scraped app screens (no licence).

**Cleaning.** Android string escapes and quotes undone; pseudo-locales (`en-XA`, `en-XC`, `ar-XB`) and plural/placeholder strings skipped for OTHER; whitespace collapsed. Within a language group, identical normalized text keeps one row, and a text that appears with two different roles is dropped entirely (listed in `sources.json`, e.g. Polish "Personalizacja" is both a purpose name and a "Personalize" button). Upstream files that do not parse (Klaro `hu.yml`, tarteaucitron `tr.js`) are skipped, not hand-repaired.

### Labels

Roles are assigned per string key, for a string **as it would appear on a consent or permission screen**:

- `ACCEPT`: any choice that grants access or consent: Allow, Allow all, While using the app, Only this time, Accept, Accept all, Accept selected, "That's ok", Got it (the lexicon also treats it as accept), Yes in Klaro's contextual consent.
- `REJECT`: Don't allow (and "& don't ask again"), Deny, Decline, I decline, Deny all cookies, No thanks, Not now.
- `MANAGE`: buttons that open more choices: Settings, Go to settings, Manage permission, Advanced settings, Personalize, Manage services, Let me choose, Consent Manager. **"Settings" alone is MANAGE** because on a consent screen it leads to the choices; on other screens it is not a consent element, which is why the role is defined conditional on a consent screen. Klaro's "learn more" key is labelled by what the button does (opens the choices), even where a translation reads "Read more".
- `PURPOSE_NOTICE`: text that says what data is used for, and purpose names (Analytics, Advertising or marketing, Personalization, Marketing, Audience measurement, …), because purpose labels are what C5 counts.
- `PRIVACY_LINK`: Privacy policy, "To learn more, please read our privacy policy", Learn about data sharing, Where data sharing info comes from.
- `WITHDRAW_INFO`: "Change this app's access in …", "Review your security and privacy settings any time", Klaro's "You can always change or withdraw your consent later" (split from the consent request; only splits that were read and confirmed are used), TraceVeil's withdraw cues, LeakyShop's withdrawal sentence.
- `OTHER`: unrelated UI strings from the same projects (keys sampled with a fixed seed after removing any English text with consent-like words), plus Klaro/tarteaucitron credits and window labels.
- **Excluded as ambiguous**: OK, Cancel, Continue, Dismiss, Close, Save, More info / Learn more (could open settings or the privacy notice), service-category names such as "Videos" or "Comments", and Klaro's general modal description.

## Model and export

- Input: NFKC, lower case, curly apostrophes to `'`, NUL removed, whitespace runs collapsed (an explicit character set, because Python and Java disagree on `\s`), then UTF-8 bytes, truncated to 128 and zero padded. `consentlens_text.py` and `ConsentLensText.kt` implement the same steps; 21 golden strings (Greek final sigma, Turkish dotted I, full-width forms, ligatures, CJK, Arabic, Devanagari, Kannada, emoji, NBSP/ideographic spaces, NUL, truncation) are replayed by `ConsentLensTextTest`.
- Network: byte embedding (256×48, a plain `tf.gather` so the TFLite graph has a single GATHER) → Conv1D 128×3 ReLU → Conv1D 128×5 ReLU → global max pool → Dense 128 ReLU → dropout 0.1 → Dense 7 softmax (130,311 parameters, about 12.8 M multiply-accumulates per string). Trained with Adam (2e-3), batch 64, 30 epochs, square-root inverse-frequency class weights, seed 42, TensorFlow 2.20 on CPU.
- **How the size and recipe were chosen (disclosed because it touches evaluation folds):** the first recipe (32-dim embedding, 64 filters, dropout 0.3, early stopping on a random 10% split) under-fitted badly: macro-F1 0.67 on its own training rows, 0.44 held-out-language macro-F1. Two alternatives were then compared on **fold 0 only** of the leave-languages-out and seen-language protocols: the same small network trained a fixed 30 epochs (0.573 / 0.569 macro-F1) and the wider network above (0.579 / 0.666). The wider one was kept. Folds 1-4 were not used for any choice, so their numbers are the cleanest estimate; fold 0 is slightly optimistic.
- Why this model: byte input needs no tokenizer or vocabulary (any script works), the graph is 11 ops that the Hexagon HTP supports natively in INT8 (GATHER, EXPAND_DIMS, CONV_2D, RESHAPE, REDUCE_MAX, FULLY_CONNECTED, SOFTMAX), it fits in about 140 KB, and it trains in a few minutes within the laptop's 2 GB of free RAM. A multilingual pretrained encoder (for example a small BERT) would generalise better to unseen languages but is 10-100× larger, needs a tokenizer port and did not fit the RAM and time budget; that is the obvious next step.
- Export: full-integer INT8 post-training quantization with 600 representative training strings (`TFLITE_BUILTINS_INT8`; int32 byte-id input, int8 softmax output with scale 1/256 and zero point −128). The engine dequantizes in Kotlin (`ConsentLensDecoder`).

## How we evaluated

1. **Leave-languages-out (main number).** The 75 non-English language groups (regional variants grouped: `pt-BR`+`pt-PT`, `zh-CN`/`HK`/`TW`, `sr`+`sr-Latn`, `nb`→`no`) are split into 5 folds with a fixed seed; English is always in training. Each fold's model never saw its test languages. Float Keras and the full-INT8 TFLite model are both scored on the held-out languages; the threshold table is computed from the same pooled INT8 predictions.
2. **Novel strings only.** The same predictions restricted to test strings whose normalized text never occurs (in any language) in that fold's training rows, to rule out credit for strings shared across languages ("OK", brand names).
3. **Leave-source-out.** Each source is held out in turn (TraceVeil lexicon + LeakyShop as one source); macro-F1 is over the roles present in the held-out source.
4. **Seen languages, unseen strings.** 5 random folds grouped by normalized text: every language is in training, no test string is. This is closest to how the shipped model is used on its 76 languages.
5. **Lexicon-only baseline** on exactly the same test rows: a Python port of `ConsentLexicon.classifyButton` (REJECT → REJECT, SETTINGS → MANAGE, ACCEPT → ACCEPT) plus the tree auditor's withdraw cues (→ WITHDRAW_INFO), everything else OTHER. It cannot produce PURPOSE_NOTICE or PRIVACY_LINK; that is part of what the model adds.

The shipped model is then retrained on all rows; it is never scored on its own training rows for any reported accuracy (its training-row INT8/float agreement is reported only as a quantization check).

## How to verify on the phone (NPU, not CPU)

1. Install the debug APK on the SM8850 phone. Open **More → AI lab** and find **ConsentLens (NPU)**.
2. Start `adb logcat -s TraceVeilNpu:I tflite:I` on the laptop (only the integrator runs adb); a plain `adb logcat | grep -i -E "TraceVeilNpu|qnn|TfLiteQnnDelegate"` also shows the QNN delegate's own warnings.
3. Tap **Load**. The card's chip must say **"Running on the NPU"**, label `NPU (QNN HTP V81)`, and "All 11 ops delegated to QNN HTP (plan is 1 delegate node)". Logcat shows one line like
   `ConsentLens-1 load backend=NPU label="NPU (QNN HTP V81)" soc=SM8850 htp=V81 qnn=… planNodes=1/11 fullyDelegated=true …`.
   LiteRT normally also prints an INFO line of the form `Replacing 11 out of 11 node(s) with delegate (…) node, yielding 1 partitions` (exact wording depends on the LiteRT build; our own plan count above does not rely on it).
4. Tap **Benchmark** (1,000 runs each). Logcat: `ConsentLens-1 bench runs=1000 backend=NPU fullyDelegated=true planNodes=1/11 npu.medianMs=… npu.p95Ms=… cpu.medianMs=… cpu.p95Ms=… npuCpuAgree=x/24 matchesPythonInt8=y/24`.
5. Tap **Try samples**: 24 fixed phrases in 15 languages with role, confidence, our expected label and the Python INT8 reference.

**What proves it ran on the HTP:** the NPU interpreter is built with XNNPACK switched off and only the QNN delegate attached, so an execution plan of exactly **1 node out of 11** means the QNN delegate took the entire graph; any op left on the CPU would show as extra plan nodes (`fullyDelegated=false`), and a failed delegate shows `backend=CPU` with the reason. The HTP backend (`libQnnHtp.so` + the V81 skel on the DSP via FastRPC) is the only backend the delegate is created with (DSP/GPU backends are not even in the APK). Latency is supporting evidence only: for a 50 KB model the NPU's fixed call overhead can make it no faster than the CPU, and we report whatever the phone measures.

## Reproduce

Python 3.11 (TensorFlow has no wheels for newer Python) with `ml/consentlens/requirements.txt`, about 1.5 GB of RAM and 60 MB of raw downloads. Run from the repository root; `-I` keeps Python from importing anything from the current directory or the download cache.

```sh
python -I ml/consentlens/build_dataset.py --raw <cache folder outside Git>   # pinned revisions; about 10 min (gitiles rate limits)
python -I ml/consentlens/train_consentlens.py                                  # all protocols + export; about 1 hour on a laptop CPU
./gradlew :core:test --tests "com.dpdpxray.core.consentlens.*"                 # Kotlin preprocessing/decoding parity, merge rules
```

The training script writes `reports/metrics.json` (every number above), the three plots, the app asset `consentlens_int8.tflite` with `consentlens_meta.json` (ops, quantization, demo phrases with Python INT8 reference predictions, headline metrics shown in AI lab) and the Kotlin golden vectors `core/src/test/resources/consentlens/golden.json`. Results are deterministic for a given machine and TensorFlow build (seed 42); another CPU or TensorFlow version can differ slightly.
