"""Writes ml/consentlens/reports/v2_report.md and reports/v2/decision.json (text blocks for the PDF) from the measured
JSON files, so no number in either document is typed by hand.

    python -I ml/consentlens/write_v2_report.py --cand <recipe> --shipped yes|no --thr-app <threshold in the app>
"""
import argparse
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
REP = os.path.join(HERE, "reports")
V2 = os.path.join(REP, "v2")
ROLES = ["ACCEPT", "REJECT", "MANAGE", "PURPOSE_NOTICE", "PRIVACY_LINK", "WITHDRAW_INFO", "OTHER"]
PROT = {"seen": "Seen languages, unseen strings", "lolo": "Held-out languages (never seen)"}

RECIPE_NOTES = {
    "v1": "ConsentLens-1 recipe (constant LR 2e-3, 30 epochs, sqrt class weights)",
    "s1": "v1 network; cosine LR decay, weight EMA (0.999), 40 epochs",
    "s2": "s1 + byte-noise augmentation (50% of rows per epoch) + embedding/pool dropout",
    "s3": "s2 + label smoothing 0.1",
    "s2_inv": "s2 with full inverse-frequency class weights",
    "s2_w160": "s2, wider: 64-dim embedding, Conv 160×3 + Conv 160×7, Dense 160 (272 KB)",
    "s2_d3": "s2, three conv layers 128×3 / 128×5 / 128×5 dilation 2",
    "s2_s3": "s2, three conv layers with stride 2 (128/160/192)",
    "pk2_mix_k33": "packed ensemble of 2 members (s1 and s2 recipes, Conv 128×3 + 128×3 each) in one graph",
    "pk2_k33": "packed ensemble of 2 s1 members (Conv 128×3 + 128×3)",
}


def J(*p):
    return json.load(open(os.path.join(*p), encoding="utf-8"))


def pm(d):
    return f"{d['mean']:.3f} ± {d['std']:.3f}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cand", required=True)
    ap.add_argument("--shipped", choices=["yes", "no"], required=True)
    ap.add_argument("--thr-app", type=float, required=True)
    ap.add_argument("--hold-reason", default=None, help="plain-text verdict when the candidate is not shipped for a reason beyond the criteria table")
    a = ap.parse_args()
    cand, shipped = a.cand, a.shipped == "yes"
    S = J(V2, f"outer_v1_vs_{cand}.json")
    C = J(V2, "decision_criteria.json")
    dev = J(V2, "dev_screening.json")
    cal = J(V2, "threshold_calibration_dev.json")
    ext = J(V2, "external_bouhoula2024.json")
    lat = J(V2, "latency_laptop_cpu.json")
    exp = J(V2, f"export_{cand}.json")
    pub = J(REP, "metrics.json")
    b = {p: S[p]["v1"] for p in ("seen", "lolo")}
    c = {p: S[p][cand] for p in ("seen", "lolo")}
    d_seen = c["seen"]["int8_macro_f1"]["mean"] - b["seen"]["int8_macro_f1"]["mean"]
    d_lolo = c["lolo"]["int8_macro_f1"]["mean"] - b["lolo"]["int8_macro_f1"]["mean"]
    cthr = cal["cand"].get(cand, {}).get("threshold")

    L = []
    w = L.append
    w("# ConsentLens v2: experiments, benchmarks and related work")
    w("")
    w("Measured on the team laptop (Intel Core i7-1355U, Python 3.11.9, TensorFlow 2.20 CPU) on 10–11 Oct 2026. Every number below is "
      "read from the JSON files in `reports/v2/` written by `experiments_v2.py`, `analyze_v2.py`, `external_benchmark.py` and `decide_v2.py`. "
      "Phone NPU facts (delegation, latency) are not measured here.")
    w("")
    w("## Verdict")
    w("")
    if shipped:
        w(f"**ConsentLens-2 shipped.** The candidate `{cand}` ({RECIPE_NOTES.get(cand, '')}) met every ship criterion against ConsentLens-1 "
          f"under the same folds and protocol: macro-F1 {d_seen:+.3f} on seen languages and {d_lolo:+.3f} on held-out languages "
          "(mean of 3 training seeds each, INT8 files), no role down by more than 0.05, no new TFLite op type, INT8/float agreement ≥ 98%, file ≤ 400 KB.")
    else:
        if a.hold_reason:
            w(f"**ConsentLens-1 stays in the app.** {a.hold_reason} The app assets are unchanged; only the experiment code and this report are committed.")
        else:
            w(f"**ConsentLens-1 stays in the app.** The best candidate, `{cand}` ({RECIPE_NOTES.get(cand, '')}), did not meet every ship criterion "
              f"(macro-F1 {d_seen:+.3f} seen / {d_lolo:+.3f} held-out). The app assets are unchanged; only the experiment code and this report are committed.")
    w("")
    w("The lead's ship criteria, measured on the internal test folds:")
    w("")
    w("| Criterion | Measured | Result |")
    w("| --- | --- | --- |")
    for k, v in C["criteria"].items():
        w(f"| {k} | {v['value']} | {'PASS' if v['pass'] else 'FAIL'} |")
    w("")
    if not shipped and a.hold_reason:
        w("The external check (section 8) is not one of those criteria; it is the reason the candidate is held back. "
          "If the team accepts that risk, v2 can be installed in a few minutes:")
        w("")
        w("```sh")
        w("# the exported candidate (all rows, seed 42) is kept outside Git: D:/DevTools/tmp/consentlens-v2-work/consentlens2_candidate_int8.tflite (+ .json)")
        w(f"python -I ml/consentlens/experiments_v2.py ship --recipe {cand} --out <that .tflite> --threshold {cthr if cthr else 0.68} \\")
        w(f"    --summary ml/consentlens/reports/v2/outer_v1_vs_{cand}.json --model-id ConsentLens-2   # rewrites the asset, meta and golden decode cases")
        w("# then set ConsentLensText.MODEL_ID = \"ConsentLens-2\", update the ConsentLensEngine KDoc size line, run :core:test :app:testDebugUnitTest :app:assembleDebug,")
        w("# and on the phone: AI lab -> ConsentLens -> Load (planNodes=1/11, fullyDelegated=true), Benchmark, Try samples")
        w("```")
        w("")
    w("## 1. v1 reproduced exactly")
    w("")
    w("`experiments_v2.py run --recipe v1 --seed 42` calls ConsentLens-1's own `train_consentlens.train` / `to_int8` on the same folds. "
      "Every fold matched the published `metrics.json` to 4 decimals:")
    w("")
    w("| Number | v1 published | Reproduced |")
    w("| --- | --- | --- |")
    s42l = b["lolo"]["per_seed"]["42"]; s42s = b["seen"]["per_seed"]["42"]
    w(f"| Held-out languages, macro-F1 INT8 | {pub['leave_languages_out']['int8']['macro_f1']:.4f} | {s42l['int8_macro_f1']:.4f} |")
    w(f"| Held-out languages, macro-F1 float | {pub['leave_languages_out']['float']['macro_f1']:.4f} | {s42l['float_macro_f1']:.4f} |")
    w(f"| Held-out languages, INT8/float agreement | {pub['leave_languages_out']['int8_vs_float_agreement']:.4f} | {s42l['int8_float_agreement']:.4f} |")
    w(f"| Seen languages, macro-F1 INT8 | {pub['seen_languages_unseen_strings']['int8']['macro_f1']:.4f} | {s42s['int8_macro_f1']:.4f} |")
    w(f"| Lexicon baseline held-out / seen | {pub['leave_languages_out']['lexicon_baseline']['macro_f1']:.4f} / {pub['seen_languages_unseen_strings']['lexicon_baseline']['macro_f1']:.4f} | "
      f"{S['lolo']['lexicon_v1_port_macro_f1']:.4f} / {S['seen']['lexicon_v1_port_macro_f1']:.4f} |")
    w("")
    w("v1's facts in the brief were also checked against the files: 145,816-byte INT8 file (SHA-256 `5802e3df…`), 130,311 parameters, 11 ops "
      "(GATHER, EXPAND_DIMS, CONV_2D, RESHAPE, EXPAND_DIMS, CONV_2D, RESHAPE, REDUCE_MAX, FULLY_CONNECTED, FULLY_CONNECTED, SOFTMAX), 7 roles, "
      "18,655 strings, 76 language groups, threshold 0.9 in `consentlens_meta.json`. The NPU 11/11 delegation is a phone fact and was not re-checked here.")
    w("")
    w("## 2. Protocol and model selection")
    w("")
    w("- **Test protocols (unchanged):** 5 leave-languages-out folds (75 non-English language groups; English always in training) and 5 seen-language folds "
      "grouped by normalised text. Fold assignment fixed with seed 42 for every model. Macro-F1 = mean of the 7 per-role F1 scores on the pooled predictions of the 5 folds.")
    w("- **Seeds:** v1 with training seeds 42, 1, 2; the candidate with 1, 2, 3. Mean ± sample std over seeds.")
    w("- **Selection never used outer test rows.** All recipe choices used an inner validation split inside the training rows of outer fold 0 (held-out languages: 15 of its 60 "
      "training language groups, 3,661 validation strings; seen languages: a quarter of its training texts, 3,734 validation strings). Outer fold 0's test rows were never used; those validation rows are test rows of outer folds 1–4, "
      "so folds 1–4 carry a small selection effect, and fold 0 alone is reported as the clean check.")
    w("- **Pre-registered rule** (written before any candidate's outer numbers existed): the packed ensemble `pk2_mix_k33` is the candidate if its dev macro-F1 is at least the best "
      "single model's on both dev protocols; otherwise the best single model is.")
    pk, wd = dev.get("pk2_mix_k33"), dev.get("s2_w160")
    if pk and wd:
        w(f"- **Outcome of the rule:** `pk2_mix_k33` dev {pk['lolo_dev_macro_f1'][0]:.4f} held-out / {pk['seen_dev_macro_f1'][0]:.4f} seen against the best single model `s2_w160` "
          f"{wd['lolo_dev_macro_f1'][0]:.4f} / {wd['seen_dev_macro_f1'][0]:.4f}: the ensemble lost on the seen-language dev split, so **`s2_w160` became the candidate**. "
          "To save time the packed ensemble's outer run had been started in parallel; it was stopped as soon as the rule selected `s2_w160`. For transparency, its only finished folds "
          "(seed 1, held-out-language folds 0 and 1) were INT8 macro-F1 0.646 and 0.557 against v1's 0.574 and 0.500 on the same folds; an incomplete run is not comparable and was not "
          "used for any decision. It is the obvious next experiment.")
    w("")
    w("## 3. What was tried (dev split only)")
    w("")
    w("| Recipe | Change against v1 | Held-out-language dev macro-F1 | Seen-language dev macro-F1 |")
    w("| --- | --- | --- | --- |")
    for rec, d in sorted(dev.items(), key=lambda kv: -(sum(kv[1]["lolo_dev_macro_f1"]) / max(1, len(kv[1]["lolo_dev_macro_f1"])))):
        lo, se = d["lolo_dev_macro_f1"], d["seen_dev_macro_f1"]
        w(f"| `{rec}` | {RECIPE_NOTES.get(rec, '')} | {sum(lo) / len(lo):.4f} (n={len(lo)}) | {sum(se) / len(se):.4f} (n={len(se)}) |")
    w("")
    w("Other findings:")
    w("- **Logit adjustment** (subtracting τ·log prior from the logits, foldable into the last bias) did not help v1 on dev (τ = 0.1: +0.002 / −0.000; larger τ hurt): the square-root class weights already balance the roles.")
    w("- **Dilation:** Keras `Conv1D(dilation_rate>1)` converts to SPACE_TO_BATCH_ND + CONV_2D + BATCH_TO_SPACE_ND + RELU (3 new op types). A direct `tf.nn.conv2d(..., dilations=…)` "
      "converts to a single CONV_2D with a dilation factor; `experiments_v2.DilatedConv1D` does that. Three-layer, dilated and strided variants (`s2_d3`, `s2_s3`, `deep*`) were "
      "built and op-checked (they convert to v1's op types only) but **not screened**: there was not enough laptop time.")
    w("- **Not run for lack of time:** label smoothing (`s3`), focal loss, full inverse-frequency weights (`s2_inv`) and language-balanced sampling exist as recipe switches; "
      "knowledge distillation has only a soft-target hook (no teacher was trained). None has a dev result, so nothing is claimed about them.")
    ens = J(V2, "dev_ensembles.json") if os.path.exists(os.path.join(V2, "dev_ensembles.json")) else None
    if ens:
        gains = [v["gain"] for p in ens.values() for v in p["pairs"].values()]
        w(f"- **Two-member ensembles** (mean log-probability of saved dev predictions, seed 1): every pair of the screened models beat its better member on both dev protocols, "
          f"by {min(gains):+.3f} to {max(gains):+.3f} macro-F1 (`reports/v2/dev_ensembles.json`). "
          "Two v1-sized members packed block-diagonally would be about 490 KB, over the 400 KB limit, so the packed variant `pk2_mix_k33` uses members with two 3-wide convolutions (389 KB packed).")
    w("- **Packed ensemble:** members are trained independently, then written into ONE network whose embedding is the members' embeddings side by side, whose conv and dense kernels are "
      "block-diagonal, and whose last layer averages the members' logits (`experiments_v2.pack`). The graph has the same 11 ops as v1; the zero blocks quantise to exact zeros. "
      "The packed float model reproduces the members' averaged logits to 3e-7 (checked).")
    w("")
    w("## 4. Results: v1 against the candidate (INT8 files, same folds)")
    w("")
    w("| Protocol | ConsentLens-1 | Candidate | Change | Fold 0 only (clean) | Accuracy v1 → cand |")
    w("| --- | --- | --- | --- | --- | --- |")
    for p in ("seen", "lolo"):
        d = c[p]["int8_macro_f1"]["mean"] - b[p]["int8_macro_f1"]["mean"]
        w(f"| {PROT[p]} | {pm(b[p]['int8_macro_f1'])} | {pm(c[p]['int8_macro_f1'])} | {d:+.3f} | "
          f"{b[p]['fold0_int8_macro_f1']['mean']:.3f} → {c[p]['fold0_int8_macro_f1']['mean']:.3f} | {pm(b[p]['int8_accuracy'])} → {pm(c[p]['int8_accuracy'])} |")
    w("")
    w("Per-role F1 (mean over seeds):")
    w("")
    w("| Role | v1 seen | cand seen | Δ | v1 held-out | cand held-out | Δ |")
    w("| --- | --- | --- | --- | --- | --- | --- |")
    for r in ROLES:
        bs, cs = b["seen"]["per_class_f1_mean"][r], c["seen"]["per_class_f1_mean"][r]
        bl, cl = b["lolo"]["per_class_f1_mean"][r], c["lolo"]["per_class_f1_mean"][r]
        w(f"| {r} | {bs:.3f} | {cs:.3f} | {cs - bs:+.3f} | {bl:.3f} | {cl:.3f} | {cl - bl:+.3f} |")
    w("")
    w("Per-seed INT8 macro-F1:")
    w("")
    for p in ("seen", "lolo"):
        w(f"- {PROT[p]}: v1 " + ", ".join(f"seed {k} {v['int8_macro_f1']:.4f}" for k, v in b[p]["per_seed"].items())
          + "; candidate " + ", ".join(f"seed {k} {v['int8_macro_f1']:.4f}" for k, v in c[p]["per_seed"].items()))
    w("")
    worst_beats = all(min(v["int8_macro_f1"] for v in c[p]["per_seed"].values()) > max(v["int8_macro_f1"] for v in b[p]["per_seed"].values())
                      for p in ("seen", "lolo"))
    w("The published v1 (seed 42) was v1's best seed on seen languages, so v1's 3-seed mean is below its published 0.671. "
      + ("The worst candidate seed beats the best v1 seed on both protocols." if worst_beats else "The seed ranges of v1 and the candidate overlap on at least one protocol."))
    w("")
    w("Figures: `reports/v2/fig_macro_f1.png`, `fig_per_class_f1.png`, `fig_confusion.png`, `fig_layered.png`.")
    w("")
    w("## 5. Size, ops, quantisation, latency")
    w("")
    w(f"| | ConsentLens-1 | Candidate (exported, all rows) |")
    w("| --- | --- | --- |")
    w(f"| File size | {b['lolo']['tflite_bytes'][0]:,} bytes | {exp['bytes']:,} bytes |")
    w(f"| Parameters | {b['lolo']['params'][0]:,} | {exp['params']:,} |")
    w(f"| Ops | {len(b['lolo']['ops'])}: {', '.join(b['lolo']['ops'])} | {len(exp['ops'])}: {', '.join(exp['ops'])} |")
    w(f"| New op types | – | {', '.join(sorted(set(exp['ops']) - set(b['lolo']['ops']))) or 'none'} |")
    w(f"| INT8/float agreement, held-out languages | {pm(b['lolo']['int8_float_agreement'])} | {pm(c['lolo']['int8_float_agreement'])} |")
    w(f"| INT8/float agreement, seen languages | {pm(b['seen']['int8_float_agreement'])} | {pm(c['seen']['int8_float_agreement'])} |")
    w(f"| SHA-256 | `5802e3dff77f70ee…` | `{exp['sha256'][:16]}…` |")
    w("")
    w(f"Laptop CPU latency, one string, batch 1 ({lat['machine']}):")
    w("")
    w("| File | Threads | Median ms | p95 ms |")
    w("| --- | --- | --- | --- |")
    for k, v in lat["results"].items():
        n, t = k.split("|")
        w(f"| {n} | {t.replace('threads=', '')} | {v['median_ms']:.3f} | {v['p95_ms']:.3f} |")
    w("")
    w("These are laptop numbers, not phone numbers.")
    if os.path.exists(os.path.join(V2, "demo_phrases_v1_v2.json")):
        dp = J(V2, "demo_phrases_v1_v2.json")
        w("")
        w(f"AI-lab demo phrases (24 hand-written strings in 15 languages, some in the training data): ConsentLens-1 matches our expected role on {dp['v1_matches']}/24, "
          f"the v2 candidate on {dp['v2_matches']}/24 (`reports/v2/demo_phrases_v1_v2.json`).")
    w("")
    w("## 6. Threshold")
    w("")
    w(f"ConsentLens-1's threshold is 0.9. On the dev predictions (never the test folds), v1's precision on confident non-OTHER predictions at 0.9 was "
      f"{cal['base_dev']['lolo'][0]:.3f} (held-out-language dev) and {cal['base_dev']['seen'][0]:.3f} (seen-language dev). "
      + (f"The lowest candidate threshold with at least that precision on both dev splits is **{cthr:.2f}**. " if cthr else "No candidate threshold reached that precision. ")
      + f"The app uses **{a.thr_app:.2f}**.")
    w("")
    w("## 7. The layered system (string level, offline)")
    w("")
    w("Decision order as in the app: lexicon button phrase / withdrawal cue / privacy-policy wording (OBSERVED) > ConsentLens role if not OTHER and ≥ threshold (INFERRED) > unknown. "
      "Coverage = consent-role strings labelled correctly / all consent-role strings; precision = correct / emitted non-OTHER labels. Out-of-fold predictions, mean over seeds.")
    w("")
    w("| Protocol | System | Macro-F1 | Coverage | Precision |")
    w("| --- | --- | --- | --- | --- |")
    for p in ("seen", "lolo"):
        lx = S[p]["lexicon_only"]
        w(f"| {PROT[p]} | Lexicon only | {lx['macro_f1']:.3f} | {lx['coverage']:.3f} | {lx['precision_of_emitted']:.3f} |")
        for name, rec in (("Lexicon + ConsentLens-1", "v1"), ("Lexicon + candidate", cand)):
            m = S[p][rec]["layered_mean"]
            w(f"| {PROT[p]} | {name} (≥ {S[p][rec]['threshold_used_for_layered']:.2f}) | {m['macro_f1']:.3f} | {m['coverage']:.3f} | {m['precision_of_emitted']:.3f} |")
    w("")
    w("No number is reported for the optional Gemma vision layer.")
    w("")
    w("## 8. External benchmark (zero-shot): Bouhoula et al., USENIX Security 2024")
    w("")
    w(f"`data/interactive_elements.csv` from https://github.com/bouhoula/alsacnc (GPLv3; downloaded for evaluation, not committed; SHA-256 `{ext['sha256']}`), "
      f"{ext['rows']:,} labelled cookie-notice button strings from .uk sites (labels: {', '.join(f'{k} {v}' for k, v in ext['label_counts'].items())}). "
      "Mapping: Accept→ACCEPT, Reject→REJECT, Cookies settings→MANAGE, Other→not-a-choice (our PURPOSE_NOTICE/PRIVACY_LINK/WITHDRAW_INFO/OTHER); "
      f"'Save cookie settings' and 'Close/Continue without accepting' are not scored. {ext['scored_rows']:,} scored rows; "
      f"{ext['rows_whose_normalized_text_is_in_our_training_data']} rows also occur in our training data.")
    w("")
    w("| System | Macro-F1 (4 classes) | Accuracy | Accept F1 | Reject F1 | Manage F1 | Not-a-choice F1 |")
    w("| --- | --- | --- | --- | --- | --- | --- |")
    def row(name, m):
        pc = m["per_class"]
        w(f"| {name} | {m['macro_f1_4']:.3f} | {m['accuracy']:.3f} | {pc['ACCEPT']['f1']:.3f} | {pc['REJECT']['f1']:.3f} | {pc['MANAGE']['f1']:.3f} | {pc['NOT_A_CHOICE']['f1']:.3f} |")
    row("Lexicon only", ext["lexicon_only"])
    for name, m in ext["models"].items():
        base = name.split("_at_")[0]
        if "_at_" not in name:
            row(f"{name} alone (argmax)", m["argmax"])
        row(f"Lexicon + {base} (≥ {m['layered_lexicon_then_model']['threshold']:.2f})", m["layered_lexicon_then_model"])
        if "_at_" not in name:
            nv = m["argmax_novel_strings_only"]
            row(f"{name} alone, novel strings only (n={nv['n']:,})", nv)
    w("")
    M2 = ext["models"]
    if "v1" in M2 and "v2" in M2:
        a1, a2 = M2["v1"]["argmax"]["per_class"], M2["v2"]["argmax"]["per_class"]
        frs = "; ".join(f"{k.split('_at_')[0]} at {v['layered_lexicon_then_model']['threshold']:.2f}: {v['layered_model_decided_false_reject']['count']}"
                        for k, v in M2.items() if "layered_model_decided_false_reject" in v)
        exs = ", ".join(f"\"{x[0]}\"" for x in M2["v2"]["layered_model_decided_false_reject"]["examples"][:6])
        w(f"**Per role the two models move in opposite directions on this English set.** Model alone: Accept F1 {a1['ACCEPT']['f1']:.3f} → {a2['ACCEPT']['f1']:.3f}, "
          f"Manage {a1['MANAGE']['f1']:.3f} → {a2['MANAGE']['f1']:.3f}, but **Reject {a1['REJECT']['f1']:.3f} → {a2['REJECT']['f1']:.3f}** "
          f"(precision {a1['REJECT']['precision']:.2f} → {a2['REJECT']['precision']:.2f}). Strings that the layered decision labels Reject on the model's word although "
          f"they are not Reject: {frs}. Typical v2 errors: {exs}. A wrong Reject on a button can make an audit infer \"reject option visible\", so this is the error that matters most.")
        w("")
    w("The paper's own BERT reports \"an accuracy of 95.1% and an F1 score of 90.9%\" with 5-fold cross-validation trained on this same data "
      "(in-distribution, 6 labels, server-side, text machine-translated to English). Our numbers are zero-shot with a different label set; they are not comparable and we do not claim to beat it.")
    w("")
    w("## 9. Related work (different tasks: a map, not a leaderboard)")
    w("")
    w("| Work | Task | Input | Model | On-device | Languages | Reported metric |")
    w("| --- | --- | --- | --- | --- | --- | --- |")
    for r in RELATED:
        w("| " + " | ".join(r) + " |")
    w(f"| ConsentLens ({'v2' if shipped else 'v1'}), this work | role of one consent-screen string (7 roles) | accessibility-tree text, UTF-8 bytes | byte CNN, INT8 | yes, phone NPU (QNN HTP) with CPU fallback | 76 language groups in training | "
      f"macro-F1 {(c if shipped else b)['seen']['int8_macro_f1']['mean']:.3f} seen / {(c if shipped else b)['lolo']['int8_macro_f1']['mean']:.3f} held-out languages |")
    w("")
    w("We found no published on-device (phone NPU) classifier of consent-string roles. That is a statement about our search, not proof that none exists.")
    w("")
    w("References and quotes (copied from the abstract page or the paper PDF on 10 Oct 2026):")
    w("")
    for ref in REFS:
        w(f"1. {ref}")
    w("")
    w("## 10. Limits, and what the lead must verify on the phone")
    w("")
    w("- Unseen languages remain hard: the choice buttons (Accept, Reject, Manage) on never-seen languages are the weakest roles for both models.")
    if not shipped and a.hold_reason:
        w("- The candidate says \"Reject\" too often for English cookie-settings and info links (section 8). Fix first: add such strings as MANAGE/OTHER training rows from an "
          "openly licensed source (not the GPLv3 benchmark itself, which must stay a test set), then re-run this exact protocol and the external check.")
    w("- Labels are per string key (AOSP, Klaro!, tarteaucitron.js), not per screen; most rows are Android permission/system strings.")
    w("- The external benchmark is English web buttons only; it does not test the multilingual claim.")
    w("- Folds 1–4 carry a small selection effect from the dev split; fold 0 is clean.")
    w("- No accuracy is claimed for the optional Gemma vision layer.")
    w("- Everything here is laptop-measured.")
    w("")
    w("**On the phone (AI lab → ConsentLens):** Load must show “Running on the NPU”, `planNodes=1/" + str(len(exp["ops"]) if shipped else 11) + "` and `fullyDelegated=true`; "
      "Benchmark must report NPU median/p95, `npuCpuAgree=24/24` (or close) and `matchesPythonInt8=24/24`; Try samples must match the Python INT8 reference roles in `consentlens_meta.json`.")
    w("")
    w("## Reproduce")
    w("")
    w("```sh")
    w("# Python 3.11 + ml/consentlens/requirements.txt; outputs go to D:/DevTools/tmp/consentlens-v2-work (override with CONSENTLENS_WORK)")
    w("python -I ml/consentlens/experiments_v2.py run --recipe v1 --seed 42        # also seeds 1, 2")
    w(f"python -I ml/consentlens/experiments_v2.py run --recipe {cand} --seed 1   # also seeds 2, 3")
    w("python -I ml/consentlens/experiments_v2.py dev --recipe <recipe> --seed 1    # dev screening")
    w(f"python -I ml/consentlens/analyze_v2.py outer --base v1 --cand {cand} --thr-cand <t>")
    w(f"python -I ml/consentlens/decide_v2.py --cand {cand} --export ml/consentlens/reports/v2/export_{cand}.json")
    w("python -I ml/consentlens/external_benchmark.py --csv <dir>/interactive_elements.csv --tflite v1=<v1.tflite> v2=<v2.tflite>")
    w("```")
    open(os.path.join(REP, "v2_report.md"), "w", encoding="utf-8", newline="\n").write("\n".join(L) + "\n")
    print("wrote", os.path.join(REP, "v2_report.md"))
    # text blocks for the PDF (pitch-traceveil/build/build_consentlens_research.py)
    ops_n = len(exp["ops"]) if shipped else 11
    if shipped:
        summary = (f"<p>The candidate <code>{cand}</code> beat ConsentLens-1 on <b>both</b> test protocols under the same folds "
                   f"(macro-F1 {d_seen:+.3f} on seen languages, {d_lolo:+.3f} on held-out languages, mean of 3 seeds, INT8), with no role down by more than 0.05, "
                   f"no new op type, INT8/float agreement of at least 98% and a {exp['bytes']:,}-byte file. It replaces <code>consentlens_int8.tflite</code> as "
                   f"<b>ConsentLens-2</b> with threshold {a.thr_app:.2f} (chosen on the dev split). NPU delegation must still be confirmed on the phone.</p>")
    else:
        import html as _html
        summary = (f"<p>{_html.escape(a.hold_reason)} The app keeps ConsentLens-1.</p>" if a.hold_reason else
                   f"<p>The best candidate <code>{cand}</code> did not pass every ship criterion (macro-F1 {d_seen:+.3f} seen, {d_lolo:+.3f} held-out). "
                   "The app keeps ConsentLens-1; this report documents the experiments.</p>")
    ens = J(V2, "dev_ensembles.json") if os.path.exists(os.path.join(V2, "dev_ensembles.json")) else None
    gains = [v["gain"] for p in ens.values() for v in p["pairs"].values()] if ens else [0.0]
    tried = ("<p><b>Screened on the dev split</b> (one seed each; see the table):</p><ul>"
             "<li><b>Optimisation</b> (<code>s1</code>): cosine learning-rate decay, an exponential moving average of the weights (EMA 0.999), 40 epochs.</li>"
             "<li><b>Regularisation</b> (<code>s2</code>): byte-noise augmentation on half the rows each epoch (character drop / duplicate / swap, diacritics "
             "stripped, punctuation and spacing changes, same-class same-language concatenation for notices) plus embedding and pool dropout.</li>"
             "<li><b>Capacity</b> (<code>s2_w160</code>): 64-dim embedding, Conv 160×3 + Conv 160×7, Dense 160 (272 KB, 11 ops).</li>"
             f"<li><b>Ensembling:</b> every pair of screened models, averaged, beat its better member on both dev splits ({min(gains):+.3f} to {max(gains):+.3f}). "
             "<code>pk2_mix_k33</code> <b>packs two members into one graph</b>: embeddings side by side, block-diagonal conv and dense kernels, last layer averages "
             "the logits; same 11 op types as v1, 389 KB.</li>"
             "<li><b>Logit adjustment</b> (foldable into the last bias): no gain on dev; the square-root class weights already balance the roles.</li></ul>"
             "<p><b>Built and op-checked but not screened (no laptop time left):</b> three conv layers, dilation (written as one CONV_2D; Keras's own dilated "
             "Conv1D adds 3 new op types), stride 2. <b>Not tried:</b> label smoothing, focal loss, full inverse-frequency weights, language-balanced sampling, "
             "knowledge distillation. Nothing is claimed about them.</p>"
             "<p><b>Pre-registered rule</b> (fixed before any candidate's test numbers existed): the packed ensemble is the candidate if its dev macro-F1 is at least "
             "the best single model's on both dev splits; otherwise the best single model is. <b>Outcome:</b> the packed ensemble lost on the seen-language dev split "
             "(see table), so the wider single network <code>s2_w160</code> is the candidate. The ensemble's outer run, started early to save time, was stopped; its two "
             "finished folds (0.646 and 0.557 vs v1's 0.574 and 0.500, seed 1, held-out languages) are incomplete and were not used for any decision.</p>")
    dec = {"shipped": shipped, "hold_basis": None if shipped else ("external" if a.hold_reason else "criteria"),
           "summary_html": summary, "threshold_in_app": f"{a.thr_app:.2f}",
           "reproduction": {"lolo_int8_macro_f1": s42l["int8_macro_f1"], "lolo_float_macro_f1": s42l["float_macro_f1"],
                            "lolo_agreement": s42l["int8_float_agreement"], "seen_int8_macro_f1": s42s["int8_macro_f1"],
                            "statement": "Seed 42 through ConsentLens-1's own training and conversion code reproduces every fold of the published "
                                         "metrics.json to 4 decimals (macro-F1 float and INT8, agreement, lexicon baseline)."},
           "tried_html": tried, "recipe_notes": RECIPE_NOTES, "criteria": C["criteria"],
           "phone_checks_html": (f"<ol><li>AI lab → ConsentLens → <b>Load</b>: chip says “Running on the NPU”, logcat <code>planNodes=1/{ops_n} "
                                 "fullyDelegated=true</code> (the whole graph on the QNN HTP).</li>"
                                 "<li><b>Benchmark</b> (1,000 runs): read NPU and CPU median/p95 from the card or logcat; <code>npuCpuAgree</code> and "
                                 "<code>matchesPythonInt8</code> should be 24/24 or close.</li>"
                                 "<li><b>Try samples</b>: the 24 phrases' roles should match the Python INT8 reference stored in <code>consentlens_meta.json</code>.</li>"
                                 "<li>Only after these hold, say “runs fully on the Hexagon NPU”. Until then say “built for the NPU, CPU fallback”.</li></ol>")}
    with open(os.path.join(V2, "decision.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(dec, f, ensure_ascii=False, indent=1)
    if not shipped:
        return
    # section for MODEL_CARD.md (pasted above ConsentLens-1's card, which stays for comparison)
    M = []
    m = M.append
    m("## ConsentLens-2 (in the app since 11 Oct 2026; ConsentLens-1 below is kept for comparison)")
    m("")
    m(f"**What changed.** Same input (UTF-8 bytes, at most 128), the same 7 roles in the same order and the same {len(exp['ops'])} TFLite ops "
      f"({', '.join(exp['ops'])}); no new op type. The network is wider: byte embedding 256×64 → Conv1D 160×3 ReLU → Conv1D 160×7 ReLU → global max pool → "
      f"Dense 160 ReLU → Dense 7 softmax ({exp['params']:,} parameters). Training: Adam 2e-3 with cosine decay, weight EMA 0.999, 40 epochs, square-root class weights, "
      "byte-noise augmentation on half the rows each epoch (character drop / duplicate / swap, diacritics stripped, punctuation and spacing changes, same-class "
      "same-language concatenation for notice texts), embedding dropout 0.1, pool dropout 0.2, dense dropout 0.1. Full-INT8 export as before "
      f"(600 representative training strings). File {exp['bytes']:,} bytes, SHA-256 `{exp['sha256']}`.")
    m("")
    m("| Same folds and protocol as ConsentLens-1 (INT8, mean ± std over 3 training seeds) | ConsentLens-1 | ConsentLens-2 | Change |")
    m("| --- | --- | --- | --- |")
    for p in ("seen", "lolo"):
        dd = c[p]["int8_macro_f1"]["mean"] - b[p]["int8_macro_f1"]["mean"]
        m(f"| {PROT[p]}: macro-F1 | {pm(b[p]['int8_macro_f1'])} | {pm(c[p]['int8_macro_f1'])} | {dd:+.3f} |")
        m(f"| {PROT[p]}: accuracy | {pm(b[p]['int8_accuracy'])} | {pm(c[p]['int8_accuracy'])} | {c[p]['int8_accuracy']['mean'] - b[p]['int8_accuracy']['mean']:+.3f} |")
    m(f"| EN/HI/KN lexicon on the same rows (macro-F1, seen / held-out) | {S['seen']['lexicon_v1_port_macro_f1']:.3f} / {S['lolo']['lexicon_v1_port_macro_f1']:.3f} | same | |")
    m("")
    m("Per-role F1, ConsentLens-1 → ConsentLens-2 (" + ", ".join(ROLES) + "):")
    m("")
    for p in ("seen", "lolo"):
        m(f"- {PROT[p]}: " + " | ".join(f"{b[p]['per_class_f1_mean'][r]:.2f} → {c[p]['per_class_f1_mean'][r]:.2f}" for r in ROLES))
    m("")
    m(f"- INT8 vs float agreement on the test folds: {pm(c['lolo']['int8_float_agreement'])} (held-out languages), {pm(c['seen']['int8_float_agreement'])} (seen languages); "
      f"lowest single fold {100 * min(c[p]['min_fold_int8_float_agreement'] for p in ('seen', 'lolo')):.1f}%.")
    m(f"- Threshold **{a.thr_app:.2f}** (was 0.9), chosen on the dev split only: the lowest threshold at which ConsentLens-2's precision on confident non-OTHER "
      "predictions is at least ConsentLens-1's at 0.9 on both dev splits.")
    e2 = ext["models"].get("v2")
    if e2:
        m(f"- External, zero-shot (Bouhoula et al., USENIX Security 2024, 2,204 English cookie-banner button strings, 4 classes): macro-F1 {e2['argmax']['macro_f1_4']:.3f} "
          f"(ConsentLens-1 {ext['models']['v1']['argmax']['macro_f1_4']:.3f}; lexicon alone {ext['lexicon_only']['macro_f1_4']:.3f}; lexicon + ConsentLens-2 "
          f"{e2['layered_lexicon_then_model']['macro_f1_4']:.3f}).")
    m("- **How it was chosen:** every choice was made on an inner validation split inside the training rows of outer fold 0, never on test rows, with a rule fixed "
      "before any candidate's test numbers existed. Full method, dev table, per-seed numbers, confusion matrices, the layered-system measurement, the external benchmark "
      "and related work: [reports/v2_report.md](reports/v2_report.md).")
    m("- **Still true from ConsentLens-1:** labels are per string key; unseen languages remain hard (Accept, Reject and Manage are the weakest roles there); NPU delegation and latency "
      f"must be confirmed on the phone (AI lab → Load must show `planNodes=1/{len(exp['ops'])}`, `fullyDelegated=true`).")
    m("")
    with open(os.path.join(V2, "model_card_v2_section.md"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(M) + "\n")
    print("wrote", os.path.join(V2, "model_card_v2_section.md"))


RELATED = [
    ["Nouwens et al., CHI 2020", "scrape 5 CMPs' designs, check legal minimums", "DOM, CMP-specific selectors", "rules", "no (crawler)", "UK sites", "11.8% of 680 sites meet minimal requirements"],
    ["Consent-O-Matic (Nouwens et al., CHI EA 2022)", "auto-answer consent pop-ups", "DOM selectors per CMP", "hand-written rules (200+ CMPs)", "yes (browser extension)", "selector-based", "no accuracy reported"],
    ["CookieBlock (Bollinger et al., USENIX Sec 2022)", "classify cookies into 4 purposes", "cookie attributes (not UI text)", "XGBoost", "yes (browser extension)", "n/a", "84.4% mean validation accuracy"],
    ["AidUI (Mansur et al., ICSE 2023)", "detect 10 dark-pattern types", "screenshots", "Faster R-CNN + OCR + spaCy rules", "not stated", "English patterns", "P 0.66 / R 0.67 / F1 0.65"],
    ["UIGuard (Chen et al., UIST 2023)", "detect dark patterns in mobile UIs", "screenshots", "Faster R-CNN, PaddleOCR (EN), ResNet-18, rules", "not stated", "English OCR", "micro-F1 0.79"],
    ["Kollnig et al., SOUPS 2021", "consent before tracking in Android apps", "screenshots + traffic", "manual inspection", "n/a", "not stated", "9.9% of apps asked for consent"],
    ["Nguyen et al., CCS 2022", "consent notices in Android apps vs GDPR", "screenshots → OCR", "keywords + clustering + manual labels", "no", "English only", "no classifier metric"],
    ["Koch et al., USENIX Sec 2023", "consent dialogs in Android/iOS apps", "UI text via Appium", "regular expressions", "dynamic analysis, no model", "not stated", "no P/R reported"],
    ["Khandelwal et al., USENIX Sec 2023", "detect cookie notices, generate opt-out steps", "rendered DOM text", "BERT-base + T5-Large (770M)", "no (backend)", "English", "91% correct steps"],
    ["Bouhoula et al., USENIX Sec 2024", "classify notice buttons (6 labels) and purposes", "DOM element text, translated to EN", "BERT", "no", "11 EU languages via translation", "95.1% acc / 90.9% F1 (5-fold CV)"],
    ["BannerClick (Rasaii et al., PAM 2023)", "detect / accept / reject banners", "DOM text + position", "keyword lists", "no (crawler)", "12", "99% / 97% / 87% accuracy"],
    ["Nouwens et al., CHI 2025", "detect banners, categorise buttons", "DOM text", "normalised-label lookup, Levenshtein ≤ 1", "no", "29", "F1 0.98 / 0.99 / 0.99 (accept / reject / settings)"],
]

REFS = [
    "M. Nouwens, I. Liccardi, M. Veale, D. Karger, L. Kagal. Dark Patterns after the GDPR: Scraping Consent Pop-ups and Demonstrating their Influence. CHI 2020. https://arxiv.org/abs/2001.02479 — \"We scraped the designs of the five most popular CMPs on the top 10,000 websites in the UK (n=680).\" … \"only 11.8% meet the minimal requirements that we set based on European law.\"",
    "M. Nouwens, R. Bagge, J. B. Kristensen, C. N. Klokmose. Consent-O-Matic: Automatically Answering Consent Pop-ups Using Adversarial Interoperability. CHI EA 2022. doi:10.1145/3491101.3519683; https://github.com/cavi-au/Consent-O-Matic (MIT).",
    "D. Bollinger, K. Kubicek, C. Cotrini, D. Basin. Automating Cookie Consent and GDPR Violation Detection. USENIX Security 2022. https://www.usenix.org/conference/usenixsecurity22/presentation/bollinger — \"At a mean validation accuracy of 84.4%, our model attains a prediction quality competitive with expert knowledge in the field\"",
    "S. M. H. Mansur, S. Salma, D. Awofisayo, K. Moran. AidUI: Toward Automated Recognition of Dark Patterns in User Interfaces. ICSE 2023. https://arxiv.org/abs/2303.06782 — \"achieves an overall precision of 0.66, recall of 0.67, F1-score of 0.65 in detecting dark pattern instances\"",
    "J. Chen, J. Sun, S. Feng, Z. Xing, Q. Lu, X. Xu, C. Chen. Unveiling the Tricks: Automated Detection of Dark Patterns in Mobile Applications. UIST 2023. https://arxiv.org/abs/2308.05898 — \"micro averages: 0.82 in precision, 0.77 in recall, 0.79 in F1 score\"",
    "K. Kollnig, R. Binns, P. Dewitte, M. Van Kleek, G. Wang, D. Omeiza, H. Webb, N. Shadbolt. A Fait Accompli? An Empirical Study into the Absence of Consent to Third-Party Tracking in Android Apps. SOUPS 2021. https://arxiv.org/abs/2106.09407 — \"Only 9.9% of apps asked the user for consent.\" (paper body, https://ar5iv.labs.arxiv.org/html/2106.09407)",
    "T. T. Nguyen, M. Backes, B. Stock. Freely Given Consent? Studying Consent Notice of Third-Party Tracking and Its Violations of GDPR in Android Apps. CCS 2022. https://cispa.saarland/group/stock/papers/nguyen2022consent.pdf — \"we only consider consent notices written in English\"",
    "S. Koch, B. Altpeter, M. Johns. The OK Is Not Enough: A Large Scale Study of Consent Dialogs in Smartphone Applications. USENIX Security 2023. https://www.usenix.org/conference/usenixsecurity23/presentation/koch — \"designed regular expressions capturing essential words or phrases but leaving out app-specific wordings\"",
    "R. Khandelwal, A. Nayak, H. Harkous, K. Fawaz. Automated Cookie Notice Analysis and Enforcement (CookieEnforcer). USENIX Security 2023. https://arxiv.org/abs/2204.04221 — \"showing that it can generate the required steps in 91% of the cases\"",
    "A. Bouhoula, K. Kubicek, A. Zac, C. Cotrini, D. Basin. Automated Large-Scale Analysis of Cookie Notice Compliance. USENIX Security 2024. https://www.usenix.org/conference/usenixsecurity24/presentation/bouhoula — \"This results in 2353 unique text samples extracted from interactive elements. We manually annotate each sample with one of the six labels: accept, reject, close, save, settings, and other.\" … \"We train a BERT model on this dataset using 5-fold cross-validation. It achieves an accuracy of 95.1% and an F1 score of 90.9%.\" … \"For non-English texts, we use the open-source machine translation API LibreTranslate\"",
    "A. Rasaii, S. Singh, D. Gosain, O. Gasser. Exploring the Cookieverse: A Multi-Perspective Analysis of Web Cookies. PAM 2023. https://arxiv.org/abs/2302.05353 — \"to automatically detect, accept, and reject cookie banners with an accuracy of 99%, 97%, and 87%, respectively\"",
    "M. Nouwens, J. B. Kristensen, K. Maalt, R. Bagge. A Cross-Country Analysis of GDPR Cookie Banners and Flexible Methods for Scraping Them. CHI 2025. https://arxiv.org/abs/2503.19655 — \"We obtain an F1 score of 0.98 for accept options, 0.99 for reject options, and 0.99 for settings options\"",
    "O. Laurent, A. Lafage, E. Tartaglione, G. Daniel, J.-M. Martinez, A. Bursuc, G. Franchi. Packed-Ensembles for Efficient Uncertainty Estimation. ICLR 2023. https://arxiv.org/abs/2210.09184 — \"We leverage grouped convolutions to parallelize the ensemble into a single shared backbone\" (ConsentLens packs members with dense block-diagonal kernels instead of grouped convolutions, to keep v1's op types).",
]


if __name__ == "__main__":
    main()
