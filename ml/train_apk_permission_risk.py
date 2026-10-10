"""Train and evaluate the APK permission-risk model, then export it for :core.

Reproduce (from the repository root, after `python -P ml/fetch_data.py apk`):
    python -P ml/train_apk_permission_risk.py

Writes
    ml/reports/apk_permission_risk/metrics.json        every number we report
    ml/reports/apk_permission_risk/*.png               ROC, PR, calibration, confusion, weights
    core/src/main/resources/ml/apk_permission_risk.json            exported model
    core/src/test/resources/ml/apk_permission_risk_golden.json     Kotlin parity samples

Design (see ml/MODEL_CARD_apk_permission_risk.md):
* Features are declared manifest permission names only, i.e. exactly what
  PackageManager returns on the phone.
* 74% of NATICUSdroid rows are exact duplicates of another row, so the primary
  split keeps every copy of a permission vector on one side (group split). The
  naive row split is reported next to it to show how much duplication inflates it.
* Hyper-parameters and the threshold are chosen on the training part only
  (group 5-fold CV); the held-out test split is scored once.
"""
from __future__ import annotations

import datetime as dt
import json
import math
import sys
from pathlib import Path

import numpy as np
import pandas as pd
from lightgbm import LGBMClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import average_precision_score, log_loss, roc_auc_score
from sklearn.model_selection import StratifiedGroupKFold, train_test_split

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import evaluation as ev  # noqa: E402
import plots  # noqa: E402
from permissions import canonical, dangerous_permissions, map_tuandromd_short, platform_permissions  # noqa: E402

SEED = 42
RAW = HERE / "data" / "raw"
REPO = HERE.parent
REPORTS = HERE / "reports" / "apk_permission_risk"
EXPORT = REPO / "core" / "src" / "main" / "resources" / "ml" / "apk_permission_risk.json"
GOLDEN = REPO / "core" / "src" / "test" / "resources" / "ml" / "apk_permission_risk_golden.json"
C_GRID = [0.01, 0.03, 0.1, 0.3, 1.0, 3.0, 10.0]
MODEL_VERSION = "1.0.0"

SOURCES = [
    {"name": "NATICUSdroid (Android Permissions) Dataset", "role": "training + held-out test",
     "url": "https://archive.ics.uci.edu/dataset/722/naticusdroid+android+permissions+dataset",
     "doi": "10.24432/C5FS64", "licence": "CC BY 4.0",
     "citation": "Mathur, A., Podila, L. M., Kulkarni, K., Niyaz, Q., & Javaid, A. Y. (2021). NATICUSdroid (Android Permissions) Dataset. UCI Machine Learning Repository."},
    {"name": "TUANDROMD (Tezpur University Android Malware Dataset)", "role": "cross-dataset test",
     "url": "https://archive.ics.uci.edu/dataset/855/tuandromd+tezpur+university+android+malware+dataset",
     "doi": "10.24432/C5560H", "licence": "CC BY 4.0",
     "citation": "Borah, P., & Bhattacharyya, D. K. (2020). TUANDROMD (Tezpur University Android Malware Dataset). UCI Machine Learning Repository."},
]


# ----------------------------------------------------------------------------- data

def _or_merge(columns: dict[str, np.ndarray]) -> pd.DataFrame:
    return pd.DataFrame(columns).astype(np.int8)


def load_naticusdroid() -> tuple[pd.DataFrame, np.ndarray]:
    df = pd.read_csv(RAW / "naticusdroid" / "data.csv")
    y = df.pop("Result").to_numpy().astype(int)
    cols: dict[str, np.ndarray] = {}
    for c in df.columns:
        name = canonical(c)
        v = df[c].to_numpy().astype(np.int8)
        cols[name] = np.maximum(cols[name], v) if name in cols else v
    return _or_merge(cols), y


def load_tuandromd(platform: set[str]) -> tuple[pd.DataFrame, np.ndarray, dict]:
    df = pd.read_csv(RAW / "tuandromd" / "TUANDROMD.csv")
    df = df.dropna(subset=["Label"])
    y = (df.pop("Label") == "malware").to_numpy().astype(int)
    perm_cols = [c for c in df.columns if "->" not in c]  # API-call columns look like Lpkg/Class;->method
    cols: dict[str, np.ndarray] = {}
    dropped = []
    for c in perm_cols:
        name = map_tuandromd_short(c, platform)
        if name is None:
            dropped.append(c)
            continue
        v = df[c].to_numpy().astype(np.int8)
        cols[name] = np.maximum(cols[name], v) if name in cols else v
    info = {"permission_columns": len(perm_cols), "mapped_features": len(cols), "dropped_columns": dropped,
            "api_columns_ignored": int(sum("->" in c for c in df.columns))}
    return _or_merge(cols), y, info


def vector_groups(X: pd.DataFrame) -> np.ndarray:
    return pd.util.hash_pandas_object(X, index=False).to_numpy()


def duplication_stats(X: pd.DataFrame, y: np.ndarray) -> dict:
    g = vector_groups(X)
    frame = pd.DataFrame({"g": g, "y": y})
    per_group = frame.groupby("g")["y"].nunique()
    return {"rows": int(len(y)), "unique_vectors": int(per_group.size),
            "rows_that_duplicate_another_row": int(len(y) - per_group.size),
            "vectors_with_both_labels": int((per_group > 1).sum())}


# ----------------------------------------------------------------------------- models

def make_lr(c: float) -> LogisticRegression:
    return LogisticRegression(C=c, max_iter=10000)


def make_lgbm() -> LGBMClassifier:
    return LGBMClassifier(n_estimators=400, learning_rate=0.05, num_leaves=31, min_child_samples=20,
                          subsample=0.8, subsample_freq=1, colsample_bytree=0.8, random_state=SEED, verbose=-1)


def oof_predictions(make, X: np.ndarray, y: np.ndarray, groups: np.ndarray, folds) -> tuple[np.ndarray, list[dict]]:
    oof = np.zeros(len(y))
    rows = []
    for tr, va in folds:
        m = make().fit(X[tr], y[tr])
        p = m.predict_proba(X[va])[:, 1]
        oof[va] = p
        rows.append({"roc_auc": roc_auc_score(y[va], p), "pr_auc": average_precision_score(y[va], p),
                     "brier": float(np.mean((p - y[va]) ** 2)), "log_loss": log_loss(y[va], p)})
    return oof, rows


def group_folds(y, groups, k=5, seed=SEED):
    return list(StratifiedGroupKFold(n_splits=k, shuffle=True, random_state=seed).split(np.zeros(len(y)), y, groups))


def evaluate(name, y, score, groups, thr, probabilistic=True) -> dict:
    out = ev.summary(y, score, thr, probabilistic)
    out["ci95"] = ev.group_bootstrap_ci(y, score, groups, thr, probabilistic)
    out["model"] = name
    return out


# ----------------------------------------------------------------------------- export

def export_model(lr: LogisticRegression, features: list[str], thr: float, metrics_summary: dict) -> dict:
    weights = {f: float(w) for f, w in zip(features, lr.coef_[0])}
    return {
        "schema": "traceveil.ml.logistic-regression.v1",
        "model": "apk_permission_risk",
        "version": MODEL_VERSION,
        "created": dt.date.today().isoformat(),
        "evidence": "INFERRED",
        "output": "Probability-like score that the declared-permission profile resembles the malware class of the training data. "
                  "It is a model score, not a malware verdict, and says nothing about what the app actually does.",
        "featureSpace": "Declared manifest permission names (PackageInfo.requestedPermissions), present = 1, absent = 0. "
                        "Names outside 'weights' are ignored. A doubled 'android.permission.android.permission.' prefix is collapsed.",
        "intercept": float(lr.intercept_[0]),
        "threshold": float(thr),
        "thresholdRule": "Maximises F1 on out-of-fold predictions of the training split (group 5-fold CV).",
        "regularisation": {"penalty": "l2", "C": float(lr.C)},
        "weights": dict(sorted(weights.items())),
        "metrics": metrics_summary,
        "trainingData": SOURCES,
        "limits": [
            "Training apps date from about 2010-2019; current malware and current Android permission rules differ (temporal drift).",
            "Labels come from the dataset authors and could not be verified app by app; some identical permission sets carry both labels.",
            "Declared permissions are not behaviour: a benign app can request many permissions and malware can request few.",
            "Easy to evade: an attacker can drop or add permissions to change the score.",
            "Dataset class balance (about 50% malware) is far above real-world prevalence, so precision on a phone will be lower than in testing.",
        ],
    }


def logit_from_export(model: dict, perms: set[str]) -> float:
    w = model["weights"]
    return model["intercept"] + sum(w[p] for p in {canonical(x) for x in perms} if p in w)


def sigmoid(z: float) -> float:
    return 1.0 / (1.0 + math.exp(-z)) if z >= 0 else math.exp(z) / (1.0 + math.exp(z))


def golden_samples(model: dict, X_test: pd.DataFrame, y_test: np.ndarray, rng: np.random.Generator) -> list[dict]:
    cols = list(X_test.columns)
    pos = rng.choice(np.flatnonzero(y_test == 1), 22, replace=False)
    neg = rng.choice(np.flatnonzero(y_test == 0), 22, replace=False)
    sets = [{"source": f"held-out test row {int(i)}", "permissions": [cols[j] for j in np.flatnonzero(X_test.iloc[i].to_numpy())]}
            for i in np.concatenate([pos, neg])]
    # Edge cases the phone will see.
    sets += [
        {"source": "empty", "permissions": []},
        {"source": "only unknown names (ignored)", "permissions": ["com.example.app.permission.C2D_MESSAGE", "android.permission.NOT_A_REAL_ONE"]},
        {"source": "doubled prefix alias", "permissions": ["android.permission.android.permission.READ_PHONE_STATE", "android.permission.INTERNET"]},
        {"source": "duplicates and unknowns mixed in", "permissions": ["android.permission.SEND_SMS", "android.permission.SEND_SMS",
                                                                        "android.permission.RECEIVE_SMS", "com.example.UNKNOWN"]},
        {"source": "every vocabulary permission", "permissions": sorted(model["weights"].keys())},
        {"source": "common benign-looking set", "permissions": ["android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE",
                                                                 "android.permission.WAKE_LOCK", "com.google.android.c2dm.permission.RECEIVE"]},
    ]
    out = []
    for s in sets:
        z = logit_from_export(model, set(s["permissions"]))
        out.append({**s, "logit": z, "probability": sigmoid(z)})
    return out


# ----------------------------------------------------------------------------- main

def main() -> None:
    REPORTS.mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(SEED)
    plat_levels = platform_permissions()
    platform = set(plat_levels)
    dangerous = dangerous_permissions(plat_levels)

    X, y = load_naticusdroid()
    features = list(X.columns)
    groups = vector_groups(X)
    Xt, yt, t_info = load_tuandromd(platform)
    t_groups = vector_groups(Xt)
    metrics: dict = {"generated": dt.datetime.now().isoformat(timespec="seconds"), "seed": SEED,
                     "data": {"naticusdroid": {"features": len(features), **duplication_stats(X, y), "malware_share": float(y.mean())},
                              "tuandromd": {**t_info, **duplication_stats(Xt, yt), "malware_share": float(yt.mean())}},
                     "dangerous_reference": "protectionLevel=dangerous in AOSP android-14.0.0_r1"}

    # Primary split: group (unique permission vector) 80/20, stratified.
    tr_idx, te_idx = group_folds(y, groups, k=5)[0]
    Xa, ya, ga = X.to_numpy()[tr_idx], y[tr_idx], groups[tr_idx]
    Xb, yb, gte = X.to_numpy()[te_idx], y[te_idx], groups[te_idx]
    metrics["split"] = {"type": "stratified group split by identical permission vector", "train_rows": int(len(ya)),
                        "test_rows": int(len(yb)), "test_rows_whose_vector_is_in_train": int(np.isin(gte, ga).sum())}
    folds = group_folds(ya, ga, k=5, seed=SEED + 1)

    # Choose C by mean out-of-fold log loss (training split only).
    c_scores = {}
    for c in C_GRID:
        _, rows = oof_predictions(lambda c=c: make_lr(c), Xa, ya, ga, folds)
        c_scores[c] = float(np.mean([r["log_loss"] for r in rows]))
    # Prefer the strongest regularisation whose loss is within 1% of the best (stabler, more readable weights).
    best_loss = min(c_scores.values())
    best_c = min(c for c, v in c_scores.items() if v <= best_loss * 1.01)

    oof_lr, cv_lr = oof_predictions(lambda: make_lr(best_c), Xa, ya, ga, folds)
    oof_gb, cv_gb = oof_predictions(make_lgbm, Xa, ya, ga, folds)
    dcols = [i for i, f in enumerate(features) if f in dangerous]
    base_a, base_b = Xa[:, dcols].sum(1).astype(float), Xb[:, dcols].sum(1).astype(float)
    cv_base = [{"roc_auc": roc_auc_score(ya[va], base_a[va]), "pr_auc": average_precision_score(ya[va], base_a[va])} for _, va in folds]

    thr_lr, thr_gb, thr_base = ev.best_f1_threshold(ya, oof_lr), ev.best_f1_threshold(ya, oof_gb), ev.best_f1_threshold(ya, base_a)
    metrics["cv5_group_on_train"] = {
        "lr_c_grid_mean_log_loss": {str(k): v for k, v in c_scores.items()}, "lr_best_c": best_c,
        "lr_c_rule": "smallest C whose mean out-of-fold log loss is within 1% of the best C in the grid",
        "baseline_dangerous_count": ev.fold_stats(cv_base), "logistic_regression": ev.fold_stats(cv_lr),
        "lightgbm": ev.fold_stats(cv_gb),
        "thresholds": {"baseline_dangerous_count": thr_base, "logistic_regression": thr_lr, "lightgbm": thr_gb}}

    lr = make_lr(best_c).fit(Xa, ya)
    lgbm = make_lgbm().fit(Xa, ya)
    p_lr, p_gb = lr.predict_proba(Xb)[:, 1], lgbm.predict_proba(Xb)[:, 1]
    metrics["held_out_test"] = {
        "baseline_dangerous_count": evaluate("dangerous-permission count", yb, base_b, gte, thr_base, probabilistic=False),
        "logistic_regression": evaluate("L2 logistic regression", yb, p_lr, gte, thr_lr),
        "lightgbm": evaluate("LightGBM", yb, p_gb, gte, thr_gb)}

    # Naive row split, reported only to show the duplication effect.
    r_tr, r_te = train_test_split(np.arange(len(y)), test_size=0.2, stratify=y, random_state=SEED)
    lr_n = make_lr(best_c).fit(X.to_numpy()[r_tr], y[r_tr]); gb_n = make_lgbm().fit(X.to_numpy()[r_tr], y[r_tr])
    metrics["naive_row_split_for_comparison"] = {
        "test_rows_whose_vector_is_in_train": int(np.isin(groups[r_te], groups[r_tr]).sum()), "test_rows": int(len(r_te)),
        "logistic_regression": {"roc_auc": roc_auc_score(y[r_te], lr_n.predict_proba(X.to_numpy()[r_te])[:, 1]),
                                "pr_auc": average_precision_score(y[r_te], lr_n.predict_proba(X.to_numpy()[r_te])[:, 1])},
        "lightgbm": {"roc_auc": roc_auc_score(y[r_te], gb_n.predict_proba(X.to_numpy()[r_te])[:, 1]),
                     "pr_auc": average_precision_score(y[r_te], gb_n.predict_proba(X.to_numpy()[r_te])[:, 1])}}

    # TUANDROMD within-dataset (context), same C, group 5-fold CV on its mapped permissions.
    t_folds = group_folds(yt, t_groups, k=5)
    _, t_rows = oof_predictions(lambda: make_lr(best_c), Xt.to_numpy(), yt, t_groups, t_folds)
    metrics["tuandromd_within_dataset_cv5_group"] = {"logistic_regression": ev.fold_stats(t_rows)}

    # Cross-dataset: shared permissions only, both directions.
    shared = sorted(set(features) & set(Xt.columns))
    s_dang = [i for i, f in enumerate(shared) if f in dangerous]
    XA, XB = X[shared].to_numpy(), Xt[shared].to_numpy()
    cross = {"shared_features": len(shared), "shared_feature_names": shared}
    curves_cross = []
    for name, (Xs, ys, gs, Xd, yd, gd) in {
        "naticusdroid_to_tuandromd": (XA, y, groups, XB, yt, t_groups),
        "tuandromd_to_naticusdroid": (XB, yt, t_groups, XA, y, groups),
    }.items():
        s_folds = group_folds(ys, gs, k=5)
        oof_s, _ = oof_predictions(lambda: make_lr(best_c), Xs, ys, gs, s_folds)
        thr_s = ev.best_f1_threshold(ys, oof_s)
        m_s = make_lr(best_c).fit(Xs, ys)
        p_d = m_s.predict_proba(Xd)[:, 1]
        g_s = make_lgbm().fit(Xs, ys).predict_proba(Xd)[:, 1]
        b_s, b_d = Xs[:, s_dang].sum(1).astype(float), Xd[:, s_dang].sum(1).astype(float)
        cross[name] = {"logistic_regression": evaluate("LR (shared permissions)", yd, p_d, gd, thr_s),
                       "lightgbm": {"roc_auc": roc_auc_score(yd, g_s), "pr_auc": average_precision_score(yd, g_s)},
                       "baseline_dangerous_count": evaluate("dangerous count", yd, b_d, gd, ev.best_f1_threshold(ys, b_s), probabilistic=False)}
        curves_cross.append((name, yd, p_d))
    # The exported model itself on TUANDROMD: its permissions TUANDROMD never recorded are treated as absent.
    Xt_full = np.column_stack([Xt[f].to_numpy() if f in Xt.columns else np.zeros(len(yt), dtype=np.int8) for f in features])
    p_exp_t = lr.predict_proba(Xt_full)[:, 1]
    cross["exported_model_on_tuandromd"] = {
        "note": f"{len(features) - len(shared)} of the model's {len(features)} permissions are not recorded by TUANDROMD and are treated as absent.",
        **evaluate("exported LR", yt, p_exp_t, t_groups, thr_lr)}
    metrics["cross_dataset"] = cross

    # Export + parity check.
    s = metrics["held_out_test"]["logistic_regression"]
    summary = {
        "evaluation": "NATICUSdroid held-out test (20%, group split by identical permission vector; no test vector seen in training)",
        "roc_auc": s["roc_auc"], "roc_auc_ci95": s["ci95"]["roc_auc"], "pr_auc": s["pr_auc"], "pr_auc_ci95": s["ci95"]["pr_auc"],
        "precision": s["at_threshold"]["precision"], "recall": s["at_threshold"]["recall"], "f1": s["at_threshold"]["f1"],
        "fpr": s["at_threshold"]["fpr"], "brier": s["brier"],
        "cross_dataset_roc_auc_naticusdroid_to_tuandromd": cross["naticusdroid_to_tuandromd"]["logistic_regression"]["roc_auc"],
        "cross_dataset_roc_auc_tuandromd_to_naticusdroid": cross["tuandromd_to_naticusdroid"]["logistic_regression"]["roc_auc"],
    }
    model = export_model(lr, features, thr_lr, summary)
    EXPORT.parent.mkdir(parents=True, exist_ok=True)
    EXPORT.write_text(json.dumps(model, indent=1) + "\n", encoding="utf-8")
    reloaded = json.loads(EXPORT.read_text(encoding="utf-8"))
    p_json = np.array([sigmoid(logit_from_export(reloaded, {features[j] for j in np.flatnonzero(row)})) for row in Xb])
    metrics["export"] = {"path": str(EXPORT.relative_to(REPO)).replace("\\", "/"), "bytes": EXPORT.stat().st_size,
                         "max_abs_diff_sklearn_vs_exported_on_test": float(np.max(np.abs(p_json - p_lr)))}
    gold = golden_samples(reloaded, X.iloc[te_idx].reset_index(drop=True), yb, rng)
    GOLDEN.parent.mkdir(parents=True, exist_ok=True)
    GOLDEN.write_text(json.dumps({"model": "apk_permission_risk", "version": MODEL_VERSION, "tolerance": 1e-6, "samples": gold}, indent=1) + "\n",
                      encoding="utf-8")
    metrics["export"]["golden_samples"] = len(gold)

    # Plots.
    t = metrics["held_out_test"]
    note = "NATICUSdroid held-out test, group split (no permission vector in both train and test). INFERRED model scores, not malware verdicts."
    plots.roc_plot([(f"Logistic regression, deployed (AUC {t['logistic_regression']['roc_auc']:.3f})", yb, p_lr),
                    (f"LightGBM, comparison (AUC {t['lightgbm']['roc_auc']:.3f})", yb, p_gb),
                    (f"Dangerous-permission count baseline (AUC {t['baseline_dangerous_count']['roc_auc']:.3f})", yb, base_b)],
                   REPORTS / "roc_test.png", "APK permission risk: ROC on held-out test", note)
    plots.pr_plot([(f"Logistic regression, deployed (AP {t['logistic_regression']['pr_auc']:.3f})", yb, p_lr),
                   (f"LightGBM, comparison (AP {t['lightgbm']['pr_auc']:.3f})", yb, p_gb),
                   (f"Dangerous-permission count baseline (AP {t['baseline_dangerous_count']['pr_auc']:.3f})", yb, base_b)],
                  REPORTS / "pr_test.png", "APK permission risk: precision-recall on held-out test", note)
    plots.calibration_plot([(f"Logistic regression (Brier {t['logistic_regression']['brier']:.3f})", yb, p_lr),
                            (f"LightGBM (Brier {t['lightgbm']['brier']:.3f})", yb, p_gb)],
                           REPORTS / "calibration_test.png", "APK permission risk: calibration on held-out test", note)
    plots.confusion_plot(t["logistic_regression"]["at_threshold"], REPORTS / "confusion_lr_test.png",
                         f"Logistic regression at threshold {thr_lr:.2f}", ("Benign", "Malware"), note)
    lab = {"naticusdroid_to_tuandromd": "Train NATICUSdroid, test TUANDROMD", "tuandromd_to_naticusdroid": "Train TUANDROMD, test NATICUSdroid"}
    plots.roc_plot([(f"Within NATICUSdroid, held-out (AUC {t['logistic_regression']['roc_auc']:.3f})", yb, p_lr)] +
                   [(f"{lab[n]} (AUC {cross[n]['logistic_regression']['roc_auc']:.3f})", yy, pp) for n, yy, pp in curves_cross],
                   REPORTS / "roc_cross_dataset.png", "APK permission risk: does it generalise to another dataset?",
                   f"Cross-dataset curves: logistic regression retrained on the {len(shared)} permissions both datasets record. "
                   f"Within-dataset curve: the deployed {len(features)}-permission model. A drop across datasets is expected and is the honest number.")
    order = np.argsort(lr.coef_[0])
    top = [(features[i].replace("android.permission.", ""), float(lr.coef_[0][i])) for i in list(order[:8]) + list(order[-12:])]
    plots.weights_plot(top, REPORTS / "weights_lr.png", "What moves the score (top 12 up, top 8 down)",
                       "Weights of the deployed logistic regression; 'android.permission.' prefix removed. Association in 2010-2019 data, not cause.")

    (REPORTS / "metrics.json").write_text(json.dumps(metrics, indent=1, default=float) + "\n", encoding="utf-8")
    print(json.dumps({"best_c": best_c, "threshold": thr_lr, "test": {k: {"roc_auc": v["roc_auc"], "pr_auc": v["pr_auc"]} for k, v in t.items()},
                      "naive": metrics["naive_row_split_for_comparison"], "cross": {k: v["logistic_regression"]["roc_auc"] for k, v in cross.items() if isinstance(v, dict) and "logistic_regression" in v},
                      "export": metrics["export"], "data": metrics["data"]}, indent=1, default=float))


if __name__ == "__main__":
    main()
