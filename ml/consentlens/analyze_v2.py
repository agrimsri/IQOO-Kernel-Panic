"""Aggregates ConsentLens v2 experiment runs into the numbers quoted by reports/v2_report.md.

    python -I ml/consentlens/analyze_v2.py dev                      # dev (inner validation) screening table
    python -I ml/consentlens/analyze_v2.py outer --base v1 --cand <recipe> [--thr-cand 0.9]
    python -I ml/consentlens/analyze_v2.py latency --tflite a.tflite b.tflite

Everything is computed from the saved out-of-fold predictions of experiments_v2.py; pooled macro-F1 over the 5 folds
of a protocol (as ConsentLens-1's metrics.json reports it), then mean +- sample std over the training seeds.
"""
import argparse
import collections
import glob
import json
import os
import re
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
sys.path.insert(0, HERE)
os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")

import numpy as np  # noqa: E402

from consentlens_text import ROLES, normalize  # noqa: E402

WORK = os.environ.get("CONSENTLENS_WORK", os.path.join("D:/DevTools/tmp", "consentlens-v2-work"))
OUT = os.path.join(HERE, "reports", "v2")
DATA = os.path.join(HERE, "data", "consentlens_dataset.jsonl")
NC = len(ROLES)
OTHER = ROLES.index("OTHER")
LABELS = list(range(NC))


def cm(y, p):
    m = np.zeros((NC, NC), dtype=np.int64)
    np.add.at(m, (y, p), 1)
    return m


def prf(y, p):
    m = cm(y, p)
    tp = np.diag(m).astype(float)
    pred_n, true_n = m.sum(0).astype(float), m.sum(1).astype(float)
    pr = np.divide(tp, pred_n, out=np.zeros(NC), where=pred_n > 0)
    rc = np.divide(tp, true_n, out=np.zeros(NC), where=true_n > 0)
    f = np.divide(2 * pr * rc, pr + rc, out=np.zeros(NC), where=(pr + rc) > 0)
    return pr, rc, f, m


def macro_f1(y, p):
    return float(np.mean(prf(y, p)[2]))


def rows():
    return [json.loads(line) for line in open(DATA, encoding="utf-8")]


# ---------------------------------------------------------------- lexicon layer (port of ConsentLexicon.kt + withdraw cues)
def lexicon_layers():
    lex = open(os.path.join(REPO, "core/src/main/kotlin/com/dpdpxray/core/consent/ConsentLexicon.kt"), encoding="utf-8").read()
    aud = open(os.path.join(REPO, "core/src/main/kotlin/com/dpdpxray/core/screen/ScreenAuditing.kt"), encoding="utf-8").read()

    def phrases(src, name):
        return re.findall(r'"([^"]+)"', re.search(name + r"\s*=\s*listOf\((.*?)\)", src, re.S).group(1))

    reject, settings, accept = (phrases(lex, "private val " + n) for n in ("REJECT", "SETTINGS", "ACCEPT"))
    withdraw = phrases(aud, "private val WITHDRAW_CUES")
    punct = re.compile(r"[!\"#$%&'()*+,./:;<=>?@\[\\\]^_`{|}~]")

    def norm(s):
        s = s.lower().replace("&", " ").replace("'", "").replace("’", "")
        return re.sub(r"[ \t\n\x0b\f\r]+", " ", punct.sub(" ", s)).strip()

    def match(t, ps):
        return any(t == p or t.startswith(p + " ") for p in ps)

    def button(text):
        t = norm(text)
        if t and len(t) <= 40:
            if match(t, reject):
                return "REJECT"
            if match(t, settings):
                return "MANAGE"
            if match(t, accept):
                return "ACCEPT"
        return None

    def cue(text):
        low = text.lower()
        if any(c in low for c in withdraw):
            return "WITHDRAW_INFO"
        if any(p.search(norm(text)) or p.search(low) for p in policy):
            return "PRIVACY_LINK"
        return None

    # ConsentLexicon.POLICY_PATTERNS (mentionsPrivacyPolicy), as raw Kotlin regex source
    src = re.search(r"POLICY_PATTERNS\s*=\s*listOf\((.*?)\n    \)", lex, re.S).group(1)
    policy = [re.compile(m.replace("\\\\", "\\")) for m in re.findall(r'Regex\("((?:[^"\\]|\\.)*)"\)', src)]
    assert len(policy) == 8, len(policy)
    return button, cue


def layered(texts, probs, thr):
    """String-level combined decision in TraceVeil's precedence: lexicon button kind (OBSERVED) > withdraw cue or
    privacy-policy wording (OBSERVED) > ConsentLens (INFERRED, only non-OTHER at or above thr) > OTHER (unknown). Returns predictions and
    which layer decided each string."""
    button, cue = lexicon_layers()
    pred, layer = [], []
    for t, p in zip(texts, probs if probs is not None else [None] * len(texts)):
        b = button(t)
        c = None if b else cue(t)
        if b or c:
            pred.append(ROLES.index(b or c)); layer.append("lexicon")
        elif p is not None and int(p.argmax()) != OTHER and float(p.max()) >= thr:
            pred.append(int(p.argmax())); layer.append("consentlens")
        else:
            pred.append(OTHER); layer.append("none")
    return np.array(pred), np.array(layer)


def layered_stats(y, pred, layer):
    consent = y != OTHER
    emitted = pred != OTHER
    return {"macro_f1": round(macro_f1(y, pred), 4),
            "consent_strings": int(consent.sum()),
            "consent_strings_correctly_labelled": int(np.sum(consent & (pred == y))),
            "coverage": round(float(np.sum(consent & (pred == y)) / max(1, consent.sum())), 4),
            "labels_emitted": int(emitted.sum()),
            "precision_of_emitted": round(float(np.mean(pred[emitted] == y[emitted])), 4) if emitted.any() else None,
            "decided_by": dict(collections.Counter(layer[emitted].tolist()))}


# ---------------------------------------------------------------- outer runs
def load_outer(recipe, prot):
    """Out-of-fold predictions per training seed; a seed may be split over partial files (``..._s3_f01.npz``)."""
    parts = collections.defaultdict(list)
    for f in sorted(glob.glob(os.path.join(WORK, f"outer_{prot}_{recipe}_s*.npz"))):
        m = re.search(r"_s(\d+)(?:_f(\d+))?\.npz$", f)
        if m:
            parts[int(m.group(1))].append(f)
    out = {}
    for seed, files in parts.items():
        ds = [dict(np.load(f)) for f in files]
        d = {k: np.concatenate([x[k] for x in ds]) for k in ds[0]}
        folds = sorted((fs for f in files for fs in json.load(open(f.replace(".npz", ".json"), encoding="utf-8"))["folds"]), key=lambda fs: fs["fold"])
        if sorted(fs["fold"] for fs in folds) != list(range(5)):
            print("incomplete seed", recipe, prot, seed, [fs["fold"] for fs in folds])
            continue
        o = np.argsort(d["row"])
        out[seed] = {k: v[o] for k, v in d.items()}
        out[seed]["json"] = {"folds": folds}
    return out


def mean_std(v):
    v = np.asarray(v, float)
    return {"mean": round(float(v.mean()), 4), "std": round(float(v.std(ddof=1)) if len(v) > 1 else 0.0, 4), "n": int(len(v)),
            "values": [round(float(x), 4) for x in v]}


def summarize(recipe, prot, y_all, texts):
    runs = load_outer(recipe, prot)
    if not runs:
        return None
    per_seed = {}
    for seed, d in runs.items():
        y = y_all[d["row"]]
        q, f = d["pq"].argmax(1), d["pf"].argmax(1)
        pr, rc, f1, m = prf(y, q)
        per_seed[seed] = {"int8_macro_f1": macro_f1(y, q), "float_macro_f1": macro_f1(y, f),
                          "int8_accuracy": float(np.mean(y == q)), "int8_float_agreement": float(np.mean(q == f)),
                          "per_class_f1": f1.tolist(), "per_class_precision": pr.tolist(), "per_class_recall": rc.tolist(),
                          "confusion": m.tolist(), "fold0_int8_macro_f1": macro_f1(y[d["fold"] == 0], q[d["fold"] == 0]),
                          "folds": d["json"]["folds"]}
    seeds = sorted(per_seed)
    s = {"recipe": recipe, "protocol": prot, "seeds": seeds, "n_rows": int(len(runs[seeds[0]]["row"])),
         "int8_macro_f1": mean_std([per_seed[k]["int8_macro_f1"] for k in seeds]),
         "float_macro_f1": mean_std([per_seed[k]["float_macro_f1"] for k in seeds]),
         "int8_accuracy": mean_std([per_seed[k]["int8_accuracy"] for k in seeds]),
         "int8_float_agreement": mean_std([per_seed[k]["int8_float_agreement"] for k in seeds]),
         "fold0_int8_macro_f1": mean_std([per_seed[k]["fold0_int8_macro_f1"] for k in seeds]),
         "min_fold_int8_float_agreement": min(fs["int8_float_agreement"] for k in seeds for fs in per_seed[k]["folds"]),
         "folds1to4_int8_macro_f1": None,
         "per_class_f1_mean": {ROLES[c]: round(float(np.mean([per_seed[k]["per_class_f1"][c] for k in seeds])), 4) for c in LABELS},
         "per_class_f1_std": {ROLES[c]: round(float(np.std([per_seed[k]["per_class_f1"][c] for k in seeds], ddof=1)) if len(seeds) > 1 else 0.0, 4) for c in LABELS},
         "confusion_sum_over_seeds": np.sum([per_seed[k]["confusion"] for k in seeds], axis=0).tolist(),
         "tflite_bytes": sorted({fs["tflite_bytes"] for k in seeds for fs in per_seed[k]["folds"]}),
         "params": sorted({fs["params"] for k in seeds for fs in per_seed[k]["folds"]}),
         "ops": per_seed[seeds[0]]["folds"][0]["ops"],
         "per_seed": {str(k): {kk: (round(vv, 4) if isinstance(vv, float) else vv) for kk, vv in v.items() if kk not in ("confusion", "folds")}
                      for k, v in per_seed.items()}}
    f14 = []
    for k in seeds:
        d = runs[k]; y = y_all[d["row"]]; q = d["pq"].argmax(1); msk = d["fold"] != 0
        f14.append(macro_f1(y[msk], q[msk]))
    s["folds1to4_int8_macro_f1"] = mean_std(f14)
    return s, runs


def threshold_table(y, probs, grid=(0.5, 0.6, 0.7, 0.8, 0.9, 0.95)):
    pred, conf = probs.argmax(1), probs.max(1)
    out = []
    for t in grid:
        keep = (pred != OTHER) & (conf >= t)
        out.append({"threshold": t, "kept": int(keep.sum()),
                    "precision": round(float(np.mean(pred[keep] == y[keep])), 4) if keep.any() else None,
                    "recall_of_consent_strings": round(float(np.sum(keep & (pred == y)) / max(1, np.sum(y != OTHER))), 4)})
    return out


def cmd_outer(a):
    R = rows()
    y_all = np.array([ROLES.index(r["role"]) for r in R])
    texts = [r["text"] for r in R]
    os.makedirs(OUT, exist_ok=True)
    res = {"note": "pooled macro-F1 over the 5 outer folds of each protocol (ConsentLens-1's metric), mean +- sample std over seeds"}
    button, cue = lexicon_layers()
    for prot in ("lolo", "seen"):
        res[prot] = {}
        for rec in [a.base] + a.cand:
            got = summarize(rec, prot, y_all, texts)
            if got is None:
                continue
            s, runs = got
            # thresholds and layered system per seed (INT8 probabilities)
            thr = a.thr_base if rec == a.base else a.thr_cand
            lay, tt = [], []
            for seed, d in runs.items():
                y = y_all[d["row"]]
                tx = [texts[i] for i in d["row"]]
                pred, layer = layered(tx, d["pq"], thr)
                lay.append(layered_stats(y, pred, layer))
                tt.append(threshold_table(y, d["pq"]))
            s["threshold_used_for_layered"] = thr
            s["layered_per_seed"] = lay
            s["layered_mean"] = {k: round(float(np.mean([l[k] for l in lay])), 4) for k in ("macro_f1", "coverage", "precision_of_emitted")}
            s["thresholds_int8_per_seed"] = tt
            res[prot][rec] = s
        # lexicon alone on the same rows
        any_runs = load_outer(a.base, prot)
        d = next(iter(any_runs.values()))
        y = y_all[d["row"]]
        tx = [texts[i] for i in d["row"]]
        pred, layer = layered(tx, None, 1.1)
        res[prot]["lexicon_only"] = layered_stats(y, pred, layer)
        # ConsentLens-1's own baseline port (buttons + withdraw cues, no privacy-policy patterns), for the reproduction
        v1_port = np.array([ROLES.index(button(t) or ("WITHDRAW_INFO" if cue(t) == "WITHDRAW_INFO" else "OTHER")) for t in tx])
        res[prot]["lexicon_v1_port_macro_f1"] = round(macro_f1(y, v1_port), 4)
        res[prot]["lexicon_v1_port_per_class_f1"] = [round(float(v), 4) for v in prf(y, v1_port)[2]]
    path = os.path.join(OUT, f"outer_{a.base}_vs_{'_'.join(a.cand)}.json")
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(res, f, indent=1)
    for prot in ("lolo", "seen"):
        for rec, s in res[prot].items():
            if not isinstance(s, dict):
                print(prot, rec, s)
                continue
            if rec == "lexicon_only":
                print(prot, "lexicon_only", s["macro_f1"], "coverage", s["coverage"], "precision", s["precision_of_emitted"])
                continue
            print(prot, rec, "int8 macroF1 %.4f+-%.4f" % (s["int8_macro_f1"]["mean"], s["int8_macro_f1"]["std"]),
                  "float %.4f" % s["float_macro_f1"]["mean"], "agree %.4f" % s["int8_float_agreement"]["mean"],
                  "fold0 %.4f" % s["fold0_int8_macro_f1"]["mean"], "folds1-4 %.4f" % s["folds1to4_int8_macro_f1"]["mean"],
                  "seeds", s["seeds"], "bytes", s["tflite_bytes"], "\n   per-class", s["per_class_f1_mean"],
                  "\n   layered", s["layered_mean"])
    print("wrote", path)


# ---------------------------------------------------------------- dev screening
def cmd_dev(a):
    R = rows()
    y_all = np.array([ROLES.index(r["role"]) for r in R])
    table = collections.defaultdict(lambda: collections.defaultdict(dict))
    for f in sorted(glob.glob(os.path.join(WORK, "dev_*_s*.json"))):
        d = json.load(open(f, encoding="utf-8"))
        for prot in ("lolo", "seen"):
            if prot in d:
                table[d["recipe"]][prot][d["seed"]] = d[prot]
    print("%-14s %-22s %-22s %s" % ("recipe", "lolo-dev macroF1", "seen-dev macroF1", "curves (lolo | seen)"))
    out = {}
    for rec, t in sorted(table.items(), key=lambda kv: -np.mean([v["val_macro_f1"] for v in kv[1].get("lolo", {}).values()] or [0])):
        lo = [v["val_macro_f1"] for v in t.get("lolo", {}).values()]
        se = [v["val_macro_f1"] for v in t.get("seen", {}).values()]
        cur = lambda p: " ".join("%d:%.3f" % (c["epoch"], c["val_macro_f1"]) for v in t.get(p, {}).values() for c in v["curve"][:12])
        print("%-14s %-22s %-22s %s | %s" % (rec, "%.4f (n=%d)" % (np.mean(lo), len(lo)) if lo else "-",
                                             "%.4f (n=%d)" % (np.mean(se), len(se)) if se else "-", cur("lolo"), cur("seen")))
        out[rec] = {"lolo_dev_macro_f1": lo, "seen_dev_macro_f1": se, "seeds": sorted(t.get("lolo", {}).keys())}
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, "dev_screening.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(out, f, indent=1)


def dev_probs(recipe, prot):
    res = {}
    for f in sorted(glob.glob(os.path.join(WORK, f"dev_{prot}_{recipe}_s*.npz"))):
        seed = int(re.search(r"_s(\d+)\.npz$", f).group(1))
        res[seed] = dict(np.load(f))
    return res


def cmd_logit(a):
    """Offline check of logit adjustment on dev predictions: argmax(log p - tau * log prior)."""
    R = rows()
    y_all = np.array([ROLES.index(r["role"]) for r in R])
    prior = np.bincount(y_all, minlength=NC) / len(y_all)
    for rec in a.cand:
        for prot in ("lolo", "seen"):
            for seed, d in dev_probs(rec, prot).items():
                y = y_all[d["row"]]
                lp = np.log(np.maximum(d["pf"], 1e-9))
                line = []
                for tau in (0.0, 0.1, 0.2, 0.3, 0.5, 0.75, 1.0):
                    line.append("%.2f:%.4f" % (tau, macro_f1(y, (lp - tau * np.log(prior)).argmax(1))))
                print(rec, prot, seed, " ".join(line))


def confident_precision(y, probs, t):
    pred, conf = probs.argmax(1), probs.max(1)
    keep = (pred != OTHER) & (conf >= t)
    return (float(np.mean(pred[keep] == y[keep])) if keep.any() else float("nan")), int(keep.sum()),         float(np.sum(keep & (pred == y)) / max(1, np.sum(y != OTHER)))


def cmd_calibrate(a):
    """Threshold for the candidate, chosen on the dev (inner validation) predictions only: the lowest threshold at
    which the candidate's precision on confident non-OTHER predictions is at least ConsentLens-1's at 0.9, on both
    dev protocols (all dev seeds pooled)."""
    R = rows()
    y_all = np.array([ROLES.index(r["role"]) for r in R])
    ref = {}
    for prot in ("lolo", "seen"):
        d = dev_probs(a.base, prot)
        y = np.concatenate([y_all[v["row"]] for v in d.values()]); p = np.concatenate([v["pf"] for v in d.values()])
        ref[prot] = confident_precision(y, p, a.thr_base)
        print("base", a.base, prot, "seeds", sorted(d), "precision@%.2f" % a.thr_base, "%.4f kept %d recall %.4f" % ref[prot])
    out = {"base": a.base, "base_threshold": a.thr_base, "base_dev": {k: list(v) for k, v in ref.items()}, "cand": {}}
    for rec in a.cand:
        grid = {}
        for prot in ("lolo", "seen"):
            d = dev_probs(rec, prot)
            y = np.concatenate([y_all[v["row"]] for v in d.values()]); p = np.concatenate([v["pf"] for v in d.values()])
            grid[prot] = {round(t, 2): confident_precision(y, p, t) for t in np.arange(0.50, 0.995, 0.01)}
        ok = [t for t in sorted(grid["lolo"]) if all(grid[pr][t][0] >= ref[pr][0] for pr in ("lolo", "seen"))]
        thr = ok[0] if ok else None
        out["cand"][rec] = {"threshold": thr, "dev_at_threshold": {pr: list(grid[pr][thr]) for pr in grid} if thr else None,
                            "dev_at_base_threshold": {pr: list(grid[pr][round(a.thr_base, 2)]) for pr in grid}}
        print(rec, "threshold", thr, out["cand"][rec])
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, "threshold_calibration_dev.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(out, f, indent=1)


def cmd_latency(a):
    import tensorflow as tf
    from consentlens_text import encode
    texts = ["Accept all", "Alle ablehnen", "Gestisci preferenze", "We use your data to personalise offers and ads.",
             "सभी स्वीकार करें", "すべて拒否", "Política de privacidade", "Add to cart"]
    X = np.array([encode(t) for t in texts], dtype=np.int32)
    res = {}
    for path in a.tflite:
        for threads in (1, 2):
            it = tf.lite.Interpreter(model_path=path, num_threads=threads)
            it.allocate_tensors()
            i, o = it.get_input_details()[0]["index"], it.get_output_details()[0]["index"]
            for k in range(50):
                it.set_tensor(i, X[k % len(X)][None]); it.invoke()
            ts = []
            for k in range(a.runs):
                it.set_tensor(i, X[k % len(X)][None])
                t0 = time.perf_counter(); it.invoke(); ts.append((time.perf_counter() - t0) * 1000)
            ts = np.sort(ts)
            res[f"{os.path.basename(path)}|threads={threads}"] = {"median_ms": round(float(np.median(ts)), 4),
                                                                  "p95_ms": round(float(ts[int(0.95 * (len(ts) - 1))]), 4), "runs": a.runs}
            print(path, threads, res[f"{os.path.basename(path)}|threads={threads}"])
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, "latency_laptop_cpu.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump({"machine": "laptop CPU (Intel Core i7-1355U), TFLite interpreter from TensorFlow 2.20, batch 1, XNNPACK default",
                   "results": res}, f, indent=1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["dev", "outer", "logit", "latency", "calibrate", "ensembles"])
    ap.add_argument("--base", default="v1")
    ap.add_argument("--cand", nargs="*", default=[])
    ap.add_argument("--thr-base", type=float, default=0.9)
    ap.add_argument("--thr-cand", type=float, default=0.9)
    ap.add_argument("--tflite", nargs="*", default=[])
    ap.add_argument("--runs", type=int, default=2000)
    a = ap.parse_args()
    {"dev": cmd_dev, "outer": cmd_outer, "logit": cmd_logit, "latency": cmd_latency, "calibrate": cmd_calibrate,
     "ensembles": lambda _: dev_ensembles()}[a.cmd](a)



def dev_ensembles(recipes=("v1", "s1", "s2", "s2_w160"), seed=1):
    """Two-member ensembles (mean log-probability) of saved dev predictions: estimate of what ensembling buys."""
    import itertools
    R = rows()
    y_all = np.array([ROLES.index(r["role"]) for r in R])
    out = {}
    for prot in ("lolo", "seen"):
        P = {rec: dev_probs(rec, prot)[seed] for rec in recipes}
        row = P[recipes[0]]["row"]
        single = {rec: round(macro_f1(y_all[row], P[rec]["pf"].argmax(1)), 4) for rec in recipes}
        pairs = {}
        for a, b in itertools.combinations(recipes, 2):
            lp = np.mean([np.log(np.maximum(P[c]["pf"], 1e-9)) for c in (a, b)], 0)
            e = round(macro_f1(y_all[row], lp.argmax(1)), 4)
            pairs[f"{a}+{b}"] = {"ensemble": e, "better_member": max(single[a], single[b]), "gain": round(e - max(single[a], single[b]), 4)}
        out[prot] = {"single": single, "pairs": pairs}
    with open(os.path.join(OUT, "dev_ensembles.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(out, f, indent=1)
    print(json.dumps(out, indent=1))


if __name__ == "__main__":
    main()
