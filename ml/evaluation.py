"""Shared, honest evaluation helpers for the TraceVeil models.

Every number reported for a model comes from these functions, so the APK model
and the hostname model are judged the same way:
ROC-AUC, PR-AUC (average precision), Brier score, precision/recall/F1/FPR and the
confusion matrix at a fixed threshold, precision at a target recall, precision at
realistic prevalence, and percentile bootstrap 95% confidence intervals that
resample whole groups (duplicate vectors / domain families), not single rows.
"""
from __future__ import annotations

import numpy as np
from sklearn.metrics import average_precision_score, brier_score_loss, precision_recall_curve, roc_auc_score


def threshold_metrics(y: np.ndarray, score: np.ndarray, thr: float) -> dict:
    pred = score >= thr
    y = y.astype(bool)
    tp = int(np.sum(pred & y)); fp = int(np.sum(pred & ~y))
    fn = int(np.sum(~pred & y)); tn = int(np.sum(~pred & ~y))
    precision = tp / (tp + fp) if tp + fp else float("nan")
    recall = tp / (tp + fn) if tp + fn else float("nan")
    f1 = 2 * precision * recall / (precision + recall) if tp else 0.0
    fpr = fp / (fp + tn) if fp + tn else float("nan")
    return {"threshold": float(thr), "tp": tp, "fp": fp, "tn": tn, "fn": fn,
            "precision": precision, "recall": recall, "f1": f1, "fpr": fpr,
            "accuracy": (tp + tn) / len(y)}


def best_f1_threshold(y: np.ndarray, score: np.ndarray) -> float:
    """Threshold that maximises F1 (chosen on training/out-of-fold scores only)."""
    p, r, t = precision_recall_curve(y, score)
    f1 = np.where(p[:-1] + r[:-1] > 0, 2 * p[:-1] * r[:-1] / np.maximum(p[:-1] + r[:-1], 1e-12), 0)
    return float(t[int(np.argmax(f1))])


def precision_at_recall(y: np.ndarray, score: np.ndarray, target: float = 0.90) -> float:
    p, r, _ = precision_recall_curve(y, score)
    ok = r >= target
    return float(p[ok].max()) if ok.any() else float("nan")


def precision_at_prevalence(recall: float, fpr: float, prevalence: float) -> float:
    """Precision the same TPR/FPR would give if only `prevalence` of apps were positive."""
    num = recall * prevalence
    den = num + fpr * (1 - prevalence)
    return num / den if den else float("nan")


def summary(y: np.ndarray, score: np.ndarray, thr: float, probabilistic: bool = True) -> dict:
    out = {"n": int(len(y)), "positives": int(y.sum()), "prevalence": float(y.mean()),
           "roc_auc": float(roc_auc_score(y, score)),
           "pr_auc": float(average_precision_score(y, score)),
           "precision_at_recall_0.90": precision_at_recall(y, score, 0.90)}
    if probabilistic:
        out["brier"] = float(brier_score_loss(y, score))
    out["at_threshold"] = threshold_metrics(y, score, thr)
    at = out["at_threshold"]
    out["precision_if_prevalence"] = {f"{p:g}": precision_at_prevalence(at["recall"], at["fpr"], p)
                                      for p in (0.01, 0.05, 0.10)}
    return out


def group_bootstrap_ci(y: np.ndarray, score: np.ndarray, groups: np.ndarray, thr: float,
                       probabilistic: bool = True, n_boot: int = 1000, seed: int = 7) -> dict:
    """Percentile 95% CIs; each replicate resamples whole groups with replacement."""
    rng = np.random.default_rng(seed)
    uniq, inv = np.unique(groups, return_inverse=True)
    order = np.argsort(inv, kind="stable")
    members = np.split(order, np.cumsum(np.bincount(inv))[:-1])
    keys = ["roc_auc", "pr_auc", "precision", "recall", "f1", "fpr"] + (["brier"] if probabilistic else [])
    draws: dict[str, list[float]] = {k: [] for k in keys}
    for _ in range(n_boot):
        pick = rng.integers(0, len(uniq), len(uniq))
        idx = np.concatenate([members[g] for g in pick])
        yb, sb = y[idx], score[idx]
        if yb.min() == yb.max():
            continue
        draws["roc_auc"].append(roc_auc_score(yb, sb))
        draws["pr_auc"].append(average_precision_score(yb, sb))
        tm = threshold_metrics(yb, sb, thr)
        for k in ("precision", "recall", "f1", "fpr"):
            draws[k].append(tm[k])
        if probabilistic:
            draws["brier"].append(brier_score_loss(yb, sb))
    return {k: [float(np.nanpercentile(v, 2.5)), float(np.nanpercentile(v, 97.5))] for k, v in draws.items()}


def fold_stats(rows: list[dict], keys=("roc_auc", "pr_auc", "brier")) -> dict:
    out = {}
    for k in keys:
        vals = [r[k] for r in rows if k in r]
        if vals:
            out[k] = {"mean": float(np.mean(vals)), "sd": float(np.std(vals, ddof=1)), "folds": [float(v) for v in vals]}
    return out
