"""Zero-shot external benchmark: ConsentLens INT8 files on Bouhoula et al.'s labelled cookie-notice button strings.

Source: A. Bouhoula, K. Kubicek, A. Zac, C. Cotrini, D. Basin, "Automated Large-Scale Analysis of Cookie Notice
Compliance", USENIX Security 2024; file data/interactive_elements.csv of https://github.com/bouhoula/alsacnc (GPLv3).
The file is NOT redistributed here: download it yourself into an empty folder outside Git and pass its path.

    curl -L -o <dir>/interactive_elements.csv https://raw.githubusercontent.com/bouhoula/alsacnc/main/data/interactive_elements.csv
    python -I ml/consentlens/external_benchmark.py --csv <dir>/interactive_elements.csv --tflite v1=<a.tflite> v2=<b.tflite>

Label mapping (theirs -> ours): Accept -> ACCEPT, Reject -> REJECT, Cookies settings -> MANAGE, Other -> NOT-A-CHOICE
(our PURPOSE_NOTICE, PRIVACY_LINK, WITHDRAW_INFO and OTHER collapsed, because their "Other" holds links, notice text
and unrelated elements). "Save cookie settings" and "Close/Continue without accepting" have no role of ours; they are
reported separately as prediction distributions, not scored.
"""
import argparse
import collections
import csv
import hashlib
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")

import numpy as np  # noqa: E402

from consentlens_text import MAX_BYTES, ROLES, encode, normalize  # noqa: E402
import analyze_v2 as A  # noqa: E402

MAP = {"Accept": "ACCEPT", "Reject": "REJECT", "Cookies settings": "MANAGE", "Other": "NOT_A_CHOICE"}
CLASSES = ["ACCEPT", "REJECT", "MANAGE", "NOT_A_CHOICE"]
UNSCORED = ["Save cookie settings", "Close/Continue without accepting"]


def collapse(role):
    return role if role in ("ACCEPT", "REJECT", "MANAGE") else "NOT_A_CHOICE"


def scores(y, p):
    yi = np.array([CLASSES.index(v) for v in y]); pi = np.array([CLASSES.index(v) for v in p])
    m = np.zeros((4, 4), int)
    np.add.at(m, (yi, pi), 1)
    tp = np.diag(m).astype(float)
    pr = np.divide(tp, m.sum(0), out=np.zeros(4), where=m.sum(0) > 0)
    rc = np.divide(tp, m.sum(1), out=np.zeros(4), where=m.sum(1) > 0)
    f = np.divide(2 * pr * rc, pr + rc, out=np.zeros(4), where=(pr + rc) > 0)
    return {"n": int(len(y)), "accuracy": round(float(np.mean(yi == pi)), 4), "macro_f1_4": round(float(f.mean()), 4),
            "macro_f1_choices_only": round(float(f[:3].mean()), 4),
            "per_class": {c: {"precision": round(float(pr[i]), 4), "recall": round(float(rc[i]), 4), "f1": round(float(f[i]), 4),
                              "support": int(m[i].sum())} for i, c in enumerate(CLASSES)},
            "confusion_rows_true_cols_pred": m.tolist()}


def probs_of(path, X):
    import tensorflow as tf
    it = tf.lite.Interpreter(model_path=path, num_threads=2)
    inp, out = it.get_input_details()[0], it.get_output_details()[0]
    scale, zp = out["quantization"]
    it.resize_tensor_input(inp["index"], [len(X), MAX_BYTES])
    it.allocate_tensors()
    it.set_tensor(inp["index"], X)
    it.invoke()
    return scale * (it.get_tensor(out["index"]).astype(np.float32) - zp)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--csv", required=True)
    ap.add_argument("--tflite", nargs="+", required=True, help="name=path")
    ap.add_argument("--thr", nargs="*", default=[], help="name=threshold for the layered decision (default 0.9)")
    a = ap.parse_args()
    raw = open(a.csv, "rb").read()
    rows = list(csv.DictReader(open(a.csv, encoding="utf-8")))
    train = {normalize(json.loads(l)["text"]) for l in open(os.path.join(HERE, "data", "consentlens_dataset.jsonl"), encoding="utf-8")}
    texts = [r["text"] for r in rows]
    X = np.array([encode(t) for t in texts], dtype=np.int32)
    scored = np.array([r["label"] in MAP for r in rows])
    y = [MAP[r["label"]] for r in rows if r["label"] in MAP]
    overlap = np.array([normalize(t) in train for t in texts])
    thr = {k: float(v) for k, v in (s.split("=") for s in a.thr)}
    res = {"source": "Bouhoula et al., USENIX Security 2024, github.com/bouhoula/alsacnc data/interactive_elements.csv (GPLv3)",
           "sha256": hashlib.sha256(raw).hexdigest(), "rows": len(rows), "label_counts": dict(collections.Counter(r["label"] for r in rows)),
           "scored_rows": int(scored.sum()), "rows_whose_normalized_text_is_in_our_training_data": int(overlap.sum()),
           "mapping": MAP, "models": {}}
    # lexicon alone (buttons, withdraw cues, privacy-policy wording) on the scored rows
    lp, _ = A.layered([t for t, s in zip(texts, scored) if s], None, 1.1)
    res["lexicon_only"] = scores(y, [collapse(ROLES[i]) for i in lp])
    for spec in a.tflite:
        name, path = spec.split("=", 1)
        P = probs_of(path, X)
        arg = [collapse(ROLES[i]) for i in P.argmax(1)]
        t = thr.get(name, 0.9)
        stexts = [tx for tx, s in zip(texts, scored) if s]
        lay, layer = A.layered(stexts, P[scored], t)
        false_rej = [(tx, lab, round(float(p.max()), 2)) for tx, lab, li, la, p in zip(stexts, y, lay, layer, P[scored])
                     if ROLES[li] == "REJECT" and lab != "REJECT" and la == "consentlens"]
        unscored = {lab: dict(collections.Counter(ROLES[i] for i, r in zip(P.argmax(1), rows) if r["label"] == lab)) for lab in UNSCORED}
        novel = scored & ~overlap
        res["models"][name] = {
            "file_sha256": hashlib.sha256(open(path, "rb").read()).hexdigest(),
            "argmax": scores(y, [p for p, s in zip(arg, scored) if s]),
            "argmax_novel_strings_only": scores([MAP[r["label"]] for r, n in zip(rows, novel) if n], [p for p, n in zip(arg, novel) if n]),
            "layered_lexicon_then_model": {"threshold": t, **scores(y, [collapse(ROLES[i]) for i in lay])},
            "unscored_label_predictions": unscored,
            "layered_model_decided_false_reject": {"count": len(false_rej),
                                                   "by_true_label": dict(collections.Counter(l for _, l, _ in false_rej)),
                                                   "examples": false_rej[:12]},
        }
        print(name, "argmax macroF1(4) %.4f acc %.4f | novel-only %.4f | layered@%.2f %.4f" % (
            res["models"][name]["argmax"]["macro_f1_4"], res["models"][name]["argmax"]["accuracy"],
            res["models"][name]["argmax_novel_strings_only"]["macro_f1_4"], t, res["models"][name]["layered_lexicon_then_model"]["macro_f1_4"]))
    print("lexicon only macroF1(4) %.4f acc %.4f" % (res["lexicon_only"]["macro_f1_4"], res["lexicon_only"]["accuracy"]),
          "| overlap with training", res["rows_whose_normalized_text_is_in_our_training_data"])
    os.makedirs(A.OUT, exist_ok=True)
    with open(os.path.join(A.OUT, "external_bouhoula2024.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(res, f, indent=1)


if __name__ == "__main__":
    main()
