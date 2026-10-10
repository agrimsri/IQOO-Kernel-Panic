"""Train and evaluate TrackerLens, the hostname tracker classifier, then export it for :core.

Reproduce (from the repository root, after `python -P ml/fetch_data.py tracker`):
    python -P ml/train_tracker_hostname.py

Writes
    ml/reports/tracker_hostname/metrics.json        every number we report (incl. worst errors)
    ml/reports/tracker_hostname/*.png               ROC, PR, calibration, confusion
    core/src/main/resources/ml/tracker_hostname.json             exported hashed weights
    core/src/test/resources/ml/tracker_hostname_golden.json      Kotlin parity samples

What is scored
    the *site* of a hostname (hostnames.site(): its registrable-domain approximation), not the full name.
    Reason, measured below and kept in metrics.json: 85% of EasyPrivacy hosts are subdomains while 99.8%
    of popular-domain negatives are bare sites, so a full-hostname model mostly learns "has a subdomain".
    Scoring sites removes that shortcut; first-party subdomain tracking is left to the rules.
Labels
    positive  site blocked outright by an EasyPrivacy `||site^` rule
    negative  Majestic Million top-N site that no EasyPrivacy or EasyList `||d^` rule covers
Split
    by domain family (the registrable domain's first label via the Public Suffix List, so example.com,
    example.co.in and cdn.example.net share a group): no family is in both train and test.
"""
from __future__ import annotations

import base64
import datetime as dt
import json
import math
import sys
from pathlib import Path

import numpy as np
import pandas as pd
import scipy.sparse as sp
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import average_precision_score, log_loss, roc_auc_score
from sklearn.model_selection import StratifiedGroupKFold

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import evaluation as ev  # noqa: E402
import hostnames as hn  # noqa: E402
import plots  # noqa: E402

SEED = 42
RAW = HERE / "data" / "raw"
REPO = HERE.parent
REPORTS = HERE / "reports" / "tracker_hostname"
EXPORT = REPO / "core" / "src" / "main" / "resources" / "ml" / "tracker_hostname.json"
GOLDEN = REPO / "core" / "src" / "test" / "resources" / "ml" / "tracker_hostname_golden.json"
CATALOGUE = REPO / "core" / "src" / "main" / "resources" / "trackers" / "trackers.json"
MAJESTIC_TOP = 200_000
C_GRID = [0.03, 0.1, 0.3]  # a first run found out-of-fold log loss rising from C=0.1 (0.107) to 0.3 (0.110) and 1.0 (0.124)
MODEL_VERSION = "1.0.0"
KEYWORDS = ["analytic", "track", "metric", "pixel", "stat", "telemetry", "beacon", "collect", "log", "tag", "ads", "click"]

SOURCES = [
    {"name": "EasyPrivacy", "role": "positive labels", "url": "https://easylist.to/easylist/easyprivacy.txt",
     "licence": "GPL-3.0-or-later OR CC BY-SA 3.0 (or later); used under CC BY-SA 3.0",
     "attribution": "The EasyList authors (https://easylist.to/)"},
    {"name": "EasyList", "role": "excludes ad hosts from the negatives", "url": "https://easylist.to/easylist/easylist.txt",
     "licence": "GPL-3.0-or-later OR CC BY-SA 3.0 (or later); used under CC BY-SA 3.0",
     "attribution": "The EasyList authors (https://easylist.to/)"},
    {"name": "Majestic Million", "role": "negative labels (popular domains)", "url": "https://majestic.com/reports/majestic-million",
     "licence": "CC BY 3.0", "attribution": "Majestic (https://majestic.com/)"},
    {"name": "Public Suffix List", "role": "registrable-domain grouping (training only, not shipped)",
     "url": "https://publicsuffix.org/list/public_suffix_list.dat", "licence": "MPL-2.0"},
]


# ----------------------------------------------------------------------------- data

def build_dataset(psl: hn.PublicSuffixList) -> tuple[pd.DataFrame, dict]:
    privacy = hn.blocked_hosts(RAW / "easyprivacy" / "easyprivacy.txt")
    ads = hn.blocked_hosts(RAW / "easylist" / "easylist.txt")
    blocked = privacy | ads
    maj = pd.read_csv(RAW / "majestic" / "majestic_million.csv", usecols=["GlobalRank", "Domain"], nrows=MAJESTIC_TOP)
    neg_all = [d for d in dict.fromkeys(hn.normalise(d) for d in maj["Domain"].astype(str)) if hn.is_hostname(d)]
    neg_clean = [d for d in neg_all if not hn.covered_by(d, blocked)]
    # Shortcut check on full hostnames (why the model scores sites, not full names).
    pos_sub = sum(psl.registrable(h) != h for h in privacy) / len(privacy)
    neg_sub = sum(psl.registrable(d) != d for d in neg_clean) / len(neg_clean)
    shortcut = {"positives_with_subdomain": pos_sub, "negatives_with_subdomain": neg_sub,
                "roc_auc_of_rule_has_subdomain_on_full_hostnames": 0.5 + 0.5 * (pos_sub - neg_sub)}
    pos = sorted(h for h in privacy if hn.site(h) == h)
    neg = [d for d in neg_clean if hn.site(d) == d]
    df = pd.DataFrame({"host": pos + neg, "y": [1] * len(pos) + [0] * len(neg)}).drop_duplicates("host")
    df["group"] = [psl.registrable(h).split(".")[0] for h in df["host"]]
    agree = float(np.mean([psl.registrable(d) == hn.site(d) for d in neg_all]))
    info = {"easyprivacy_hosts": len(privacy), "easylist_hosts": len(ads), "majestic_top": MAJESTIC_TOP,
            "majestic_valid": len(neg_all), "majestic_removed_as_listed": len(neg_all) - len(neg_clean),
            "positives_sites": int(df.y.sum()), "negatives_sites": int((1 - df.y).sum()),
            "prevalence": float(df.y.mean()), "domain_families": int(df.group.nunique()),
            "site_heuristic_agrees_with_public_suffix_list_on_majestic": agree,
            "full_hostname_shortcut_check": shortcut}
    return df.reset_index(drop=True), info


def matrix(hosts: list[str]) -> sp.csr_matrix:
    rows, cols, dense = [], [], []
    for i, h in enumerate(hosts):
        b, d = hn.features(h)
        rows.extend([i] * len(b)); cols.extend(b); dense.append(d)
    hashed = sp.csr_matrix((np.ones(len(rows), dtype=np.float64), (rows, cols)), shape=(len(hosts), 1 << hn.HASH_BITS))
    return sp.hstack([hashed, sp.csr_matrix(np.array(dense))], format="csr")


def catalogue_domains() -> list[str]:
    entries = json.loads(CATALOGUE.read_text(encoding="utf-8"))
    return sorted({d.lower() for e in entries for d in e.get("domains", [])})


def catalogue_score(hosts, domains) -> np.ndarray:
    return np.array([1.0 if any(h == d or h.endswith("." + d) for d in domains) else 0.0 for h in hosts])


def keyword_score(hosts) -> np.ndarray:
    return np.array([float(sum(k in h for k in KEYWORDS)) for h in hosts])


# ----------------------------------------------------------------------------- model

def make_lr(c: float) -> LogisticRegression:
    return LogisticRegression(C=c, max_iter=3000, solver="liblinear")


def group_folds(y, groups, k=5, seed=SEED):
    return list(StratifiedGroupKFold(n_splits=k, shuffle=True, random_state=seed).split(np.zeros(len(y)), y, groups))


def oof(c, X, y, folds):
    out = np.zeros(len(y)); rows = []
    for tr, va in folds:
        p = make_lr(c).fit(X[tr], y[tr]).predict_proba(X[va])[:, 1]
        out[va] = p
        rows.append({"roc_auc": roc_auc_score(y[va], p), "pr_auc": average_precision_score(y[va], p),
                     "brier": float(np.mean((p - y[va]) ** 2)), "log_loss": log_loss(y[va], p)})
    return out, rows


def evaluate(name, y, score, groups, thr, probabilistic=True) -> dict:
    out = ev.summary(y, score, thr, probabilistic)
    out["ci95"] = ev.group_bootstrap_ci(y, score, groups, thr, probabilistic, n_boot=500)
    out["model"] = name
    return out


# ----------------------------------------------------------------------------- export

def export(lr: LogisticRegression, thr: float, summary: dict) -> dict:
    w = lr.coef_[0]
    hashed = w[: 1 << hn.HASH_BITS].astype("<f4")
    return {
        "schema": "traceveil.ml.hashed-logistic-regression.v1",
        "model": "tracker_hostname",
        "version": MODEL_VERSION,
        "created": dt.date.today().isoformat(),
        "evidence": "INFERRED",
        "output": "Probability-like score that the site of a hostname (its registrable-domain approximation) looks like a tracking/analytics site from public blocklists. "
                  "A DNS lookup is an attempt to contact a host, not proof that data was sent; the score never creates a finding on its own.",
        "featureSpace": {
            "normalise": "trim, lower-case, strip trailing dots",
            "site": "last two labels, or last three when the second-to-last label is one of secondLevel and the last label has two letters",
            "secondLevel": sorted(hn.SECOND_LEVEL),
            "ngrams": f"distinct character n-grams of length {hn.NGRAM_MIN}-{hn.NGRAM_MAX} of '^' + host + '$'",
            "hash": "FNV-1a 32-bit over the UTF-8 bytes of each n-gram, bucket = hash & (2^hashBits - 1); a bucket counts once",
            "hashBits": hn.HASH_BITS,
            "dense": {"length": "len/64", "labels": "(number of dots + 1)/8", "digitRatio": "ASCII digits/len",
                      "entropy": "Shannon entropy of the characters in bits / 5"},
        },
        "intercept": float(lr.intercept_[0]),
        "dense": {name: float(v) for name, v in zip(hn.DENSE, w[1 << hn.HASH_BITS:])},
        "hashedWeightsFloat32LeBase64": base64.b64encode(hashed.tobytes()).decode("ascii"),
        "threshold": float(thr),
        "thresholdRule": "Maximises F1 on out-of-fold predictions of the training split (5-fold CV grouped by registrable domain).",
        "regularisation": {"penalty": "l2", "C": float(lr.C)},
        "metrics": summary,
        "trainingData": SOURCES,
        "licence": "Weights are derived from EasyPrivacy/EasyList (CC BY-SA 3.0) and Majestic Million (CC BY 3.0); "
                   "this file is shared under CC BY-SA 3.0 with attribution to The EasyList authors and Majestic.",
        "limits": [
            "Labels mirror blocklist editors' choices; a list host can be multi-purpose and a missing host is not proof it is clean.",
            "Only the site is scored; first-party tracking subdomains (e.g. metrics.<shop>.com) are out of scope for this model.",
            "Negatives are popular sites; obscure legitimate sites are under-represented, so expect more false positives on them than the test FPR shows.",
            "Lists change daily; new tracker domains and first-party CNAME cloaking can look like ordinary hosts.",
            "Hashed n-grams collide, so the per-n-gram explanation is approximate.",
        ],
    }


def reference_logit(model: dict, host: str) -> tuple[float, list[float]]:
    w = np.frombuffer(base64.b64decode(model["hashedWeightsFloat32LeBase64"]), dtype="<f4").astype(np.float64)
    b, d = hn.features(hn.site(hn.normalise(host)))
    z = model["intercept"] + float(sum(w[i] for i in b)) + sum(model["dense"][k] * v for k, v in zip(hn.DENSE, d))
    return z, w


def sigmoid(z: float) -> float:
    return 1.0 / (1.0 + math.exp(-z)) if z >= 0 else math.exp(z) / (1.0 + math.exp(z))


# ----------------------------------------------------------------------------- main

def main() -> None:
    REPORTS.mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(SEED)
    psl = hn.PublicSuffixList(RAW / "psl" / "public_suffix_list.dat")
    df, info = build_dataset(psl)
    hosts, y, groups = df["host"].tolist(), df["y"].to_numpy(), df["group"].to_numpy()
    print(f"data: {info['positives_sites']} positives, {info['negatives_sites']} negatives", flush=True)
    X = matrix(hosts)
    metrics: dict = {"generated": dt.datetime.now().isoformat(timespec="seconds"), "seed": SEED, "data": info}

    tr, te = group_folds(y, groups)[0]
    Xa, ya, ga, Xb, yb, gb = X[tr], y[tr], groups[tr], X[te], y[te], groups[te]
    metrics["split"] = {"type": "stratified group split by domain family (first label of the registrable domain)", "train_rows": int(len(tr)),
                        "test_rows": int(len(te)), "test_groups_also_in_train": int(np.isin(np.unique(gb), ga).sum())}
    folds = group_folds(ya, ga, seed=SEED + 1)

    c_scores, c_oof = {}, {}
    for c in C_GRID:
        c_oof[c], rows = oof(c, Xa, ya, folds)
        c_scores[c] = (float(np.mean([r["log_loss"] for r in rows])), rows)
        print(f"C={c}: log loss {c_scores[c][0]:.4f}", flush=True)
    best_loss = min(v[0] for v in c_scores.values())
    best_c = min(c for c, v in c_scores.items() if v[0] <= best_loss * 1.01)
    thr = ev.best_f1_threshold(ya, c_oof[best_c])
    hosts_a = [hosts[i] for i in tr]; hosts_b = [hosts[i] for i in te]
    kw_a, kw_b = keyword_score(hosts_a), keyword_score(hosts_b)
    cat = catalogue_domains()
    cat_b = catalogue_score(hosts_b, cat)
    metrics["cv5_group_on_train"] = {
        "lr_c_grid_mean_log_loss": {str(c): v[0] for c, v in c_scores.items()}, "lr_best_c": best_c,
        "lr_c_rule": "smallest C whose mean out-of-fold log loss is within 1% of the best C in the grid",
        "logistic_regression": ev.fold_stats(c_scores[best_c][1]),
        "keyword_rule": ev.fold_stats([{"roc_auc": roc_auc_score(ya[va], kw_a[va]), "pr_auc": average_precision_score(ya[va], kw_a[va])} for _, va in folds]),
        "thresholds": {"logistic_regression": thr, "keyword_rule": ev.best_f1_threshold(ya, kw_a)}}

    print(f"best C={best_c}, threshold={thr:.4f}; fitting final model", flush=True)
    lr = make_lr(best_c).fit(Xa, ya)
    p = lr.predict_proba(Xb)[:, 1]
    test = {"logistic_regression": evaluate("hashed n-gram logistic regression", yb, p, gb, thr),
            "keyword_rule": evaluate("keyword rule", yb, kw_b, gb, metrics["cv5_group_on_train"]["thresholds"]["keyword_rule"], False),
            "catalogue_only": evaluate(f"TraceVeil {len(cat)}-domain catalogue", yb, cat_b, gb, 0.5, False)}
    metrics["held_out_test"] = test
    print("test evaluated", flush=True)
    order = np.argsort(p)
    fp = [(hosts_b[i], float(p[i])) for i in order[::-1] if yb[i] == 0][:10]
    fn = [(hosts_b[i], float(p[i])) for i in order if yb[i] == 1][:10]
    metrics["worst_errors"] = {"false_positives_highest_score": fp, "false_negatives_lowest_score": fn}

    s = test["logistic_regression"]
    summary = {"evaluation": "Held-out 20% split by domain family (no family in both train and test); sites, not full hostnames",
               "roc_auc": s["roc_auc"], "roc_auc_ci95": s["ci95"]["roc_auc"], "pr_auc": s["pr_auc"], "pr_auc_ci95": s["ci95"]["pr_auc"],
               "precision": s["at_threshold"]["precision"], "recall": s["at_threshold"]["recall"], "f1": s["at_threshold"]["f1"],
               "fpr": s["at_threshold"]["fpr"], "brier": s["brier"], "prevalence": s["prevalence"]}
    model = export(lr, thr, summary)
    EXPORT.write_text(json.dumps(model, indent=1) + "\n", encoding="utf-8")
    reloaded = json.loads(EXPORT.read_text(encoding="utf-8"))
    ref = np.array([sigmoid(reference_logit(reloaded, h)[0]) for h in hosts_b[:5000]])
    metrics["export"] = {"path": str(EXPORT.relative_to(REPO)).replace("\\", "/"), "bytes": EXPORT.stat().st_size,
                         "max_abs_diff_sklearn_vs_exported_float32_on_5000_test_hosts": float(np.max(np.abs(ref - p[:5000])))}

    # Golden samples: 44 test hosts (22 per class) + edge cases.
    pick = list(rng.choice(np.flatnonzero(yb == 1), 22, replace=False)) + list(rng.choice(np.flatnonzero(yb == 0), 22, replace=False))
    samples = [{"source": f"held-out test ({'positive' if yb[i] else 'negative'})", "host": hosts_b[i]} for i in pick]
    samples += [{"source": "upper case, trailing dot, subdomain (scored on its site)", "host": "  Region1.App-Measurement.COM. "},
                {"source": "single label", "host": "localhost"},
                {"source": "empty", "host": ""},
                {"source": "digits", "host": "203-0-113-7.static.example.net"},
                {"source": "long random label", "host": "x9f2k1q8z7w3v6b5n4m0.cloudfront.net"},
                {"source": "country second-level site", "host": "smetrics.example.co.in"},
                {"source": "two-letter TLD without second level", "host": "tracker.example.io"}]
    for smp in samples:
        z, _ = reference_logit(reloaded, smp["host"])
        smp["logit"], smp["probability"] = z, sigmoid(z)
    GOLDEN.parent.mkdir(parents=True, exist_ok=True)
    GOLDEN.write_text(json.dumps({"model": "tracker_hostname", "version": MODEL_VERSION, "tolerance": 1e-6, "samples": samples}, indent=1) + "\n",
                      encoding="utf-8")
    metrics["export"]["golden_samples"] = len(samples)

    t = test
    note = ("Held-out test split by domain family. Positives: EasyPrivacy-listed sites; negatives: popular Majestic sites not on "
            "EasyPrivacy/EasyList. INFERRED scores; a lookup is not proof that data was sent.")
    plots.roc_plot([(f"Hashed n-gram logistic regression (AUC {t['logistic_regression']['roc_auc']:.3f})", yb, p),
                    (f"Keyword rule baseline (AUC {t['keyword_rule']['roc_auc']:.3f})", yb, kw_b),
                    (f"TraceVeil catalogue only (AUC {t['catalogue_only']['roc_auc']:.3f})", yb, cat_b)],
                   REPORTS / "roc_test.png", "TrackerLens: ROC on held-out test", note)
    plots.pr_plot([(f"Hashed n-gram logistic regression (AP {t['logistic_regression']['pr_auc']:.3f})", yb, p),
                   (f"Keyword rule baseline (AP {t['keyword_rule']['pr_auc']:.3f})", yb, kw_b),
                   (f"TraceVeil catalogue only (AP {t['catalogue_only']['pr_auc']:.3f})", yb, cat_b)],
                  REPORTS / "pr_test.png", "TrackerLens: precision-recall on held-out test", note)
    plots.calibration_plot([(f"Logistic regression (Brier {t['logistic_regression']['brier']:.3f})", yb, p)],
                           REPORTS / "calibration_test.png", "TrackerLens: calibration on held-out test", note)
    plots.confusion_plot(t["logistic_regression"]["at_threshold"], REPORTS / "confusion_lr_test.png",
                         f"TrackerLens at threshold {thr:.2f}", ("Not listed", "Tracker"), note)

    (REPORTS / "metrics.json").write_text(json.dumps(metrics, indent=1, default=float) + "\n", encoding="utf-8")
    print(json.dumps({"best_c": best_c, "thr": thr, "data": info, "split": metrics["split"],
                      "test": {k: {kk: v.get(kk) for kk in ("roc_auc", "pr_auc", "brier")} for k, v in test.items()},
                      "export": metrics["export"]}, indent=1, default=float))


if __name__ == "__main__":
    main()
