"""Slide-ready PNG plots (ROC, PR, calibration, confusion matrix, weights).

Colours follow one validated categorical order (blue, orange, aqua), recessive grid
and axes, 2px lines, and text in ink colours rather than series colours.
"""
from __future__ import annotations

import textwrap

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402
from sklearn.calibration import calibration_curve  # noqa: E402
from sklearn.metrics import precision_recall_curve, roc_curve  # noqa: E402

SERIES = ["#2a78d6", "#eb6834", "#1baf7a", "#4a3aa7"]
SURFACE, INK, INK2, MUTED, GRID, AXIS = "#fcfcfb", "#0b0b0b", "#52514e", "#898781", "#e1e0d9", "#c3c2b7"
DIVERGING_POS, DIVERGING_NEG = "#e34948", "#2a78d6"
SEQ_BLUE = ["#cde2fb", "#9ec5f4", "#6da7ec", "#3987e5", "#256abf", "#184f95", "#0d366b"]


def _axes(title: str, xlabel: str, ylabel: str, figsize=(6.4, 5.2)):
    fig, ax = plt.subplots(figsize=figsize, dpi=200)
    fig.patch.set_facecolor(SURFACE)
    ax.set_facecolor(SURFACE)
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)
    for side in ("left", "bottom"):
        ax.spines[side].set_color(AXIS)
    ax.tick_params(colors=MUTED, labelcolor=INK2, labelsize=9)
    ax.grid(True, color=GRID, linewidth=0.6)
    ax.set_axisbelow(True)
    fig.suptitle(title, x=0.015, ha="left", color=INK, fontsize=11)
    ax.set_xlabel(xlabel, color=INK2, fontsize=9.5)
    ax.set_ylabel(ylabel, color=INK2, fontsize=9.5)
    return fig, ax


def _note(fig, note: str | None) -> None:
    """Footnote wrapped to the figure width, then a layout that leaves room for it."""
    if not note:
        fig.tight_layout()
        return
    lines = textwrap.wrap(note, width=int(fig.get_figwidth() * 17))
    fig.text(0.01, 0.01, "\n".join(lines), color=MUTED, fontsize=7.2, ha="left", va="bottom")
    fig.tight_layout(rect=(0, 0.025 + 0.022 * len(lines) * 5.2 / fig.get_figheight(), 1, 1))


def _finish(fig, ax, path, note: str | None = None, legend_loc="lower right"):
    leg = ax.legend(loc=legend_loc, frameon=False, fontsize=8.5)
    for t in leg.get_texts():
        t.set_color(INK)
    _note(fig, note)
    fig.savefig(path, facecolor=SURFACE)
    plt.close(fig)


def roc_plot(curves, path, title, note=None):
    """curves: list of (label, y, score)."""
    fig, ax = _axes(title, "False positive rate", "True positive rate (recall)")
    ax.plot([0, 1], [0, 1], color=MUTED, linewidth=1, linestyle="--", label="Chance (AUC 0.50)")
    for i, (label, y, s) in enumerate(curves):
        fpr, tpr, _ = roc_curve(y, s)
        ax.plot(fpr, tpr, color=SERIES[i], linewidth=2, label=label)
    ax.set_xlim(0, 1); ax.set_ylim(0, 1.01)
    _finish(fig, ax, path, note)


def pr_plot(curves, path, title, note=None):
    fig, ax = _axes(title, "Recall", "Precision")
    prevalence = None
    for i, (label, y, s) in enumerate(curves):
        p, r, _ = precision_recall_curve(y, s)
        ax.step(r, p, where="post", color=SERIES[i], linewidth=2, label=label)
        prevalence = float(np.mean(y))
    if prevalence is not None:
        ax.axhline(prevalence, color=MUTED, linewidth=1, linestyle="--", label=f"Chance (prevalence {prevalence:.2f})")
    ax.set_xlim(0, 1); ax.set_ylim(0, 1.01)
    _finish(fig, ax, path, note, legend_loc="lower left")


def calibration_plot(curves, path, title, note=None, n_bins=10):
    fig, ax = _axes(title, "Mean predicted probability", "Observed fraction positive")
    ax.plot([0, 1], [0, 1], color=MUTED, linewidth=1, linestyle="--", label="Perfect calibration")
    for i, (label, y, p) in enumerate(curves):
        frac, mean = calibration_curve(y, p, n_bins=n_bins, strategy="quantile")
        ax.plot(mean, frac, color=SERIES[i], linewidth=2, marker="o", markersize=5,
                markeredgecolor=SURFACE, markeredgewidth=1, label=label)
    ax.set_xlim(0, 1); ax.set_ylim(0, 1.01)
    _finish(fig, ax, path, note, legend_loc="upper left")


def confusion_plot(tm: dict, path, title, labels=("Negative", "Positive"), note=None):
    fig, ax = plt.subplots(figsize=(5.2, 4.6), dpi=200)
    fig.patch.set_facecolor(SURFACE)
    m = np.array([[tm["tn"], tm["fp"]], [tm["fn"], tm["tp"]]])
    cmap = matplotlib.colors.LinearSegmentedColormap.from_list("seq", SEQ_BLUE)
    ax.imshow(m, cmap=cmap, vmin=0, vmax=m.max())
    total = m.sum()
    names = [["True negative", "False positive"], ["False negative", "True positive"]]
    for i in range(2):
        for j in range(2):
            dark = m[i, j] > 0.55 * m.max()
            ax.text(j, i, f"{m[i, j]:,}\n{100 * m[i, j] / total:.1f}%\n{names[i][j]}", ha="center", va="center",
                    fontsize=9, color="#ffffff" if dark else INK)
    ax.set_xticks([0, 1], [f"Predicted {labels[0].lower()}", f"Predicted {labels[1].lower()}"], color=INK2, fontsize=9)
    ax.set_yticks([0, 1], [f"Actual {labels[0].lower()}", f"Actual {labels[1].lower()}"], color=INK2, fontsize=9)
    ax.tick_params(length=0)
    for s in ax.spines.values():
        s.set_visible(False)
    ax.set_title(title, color=INK, fontsize=11, loc="left", pad=10)
    _note(fig, note)
    fig.savefig(path, facecolor=SURFACE)
    plt.close(fig)


def weights_plot(items, path, title, note=None):
    """items: list of (name, weight), already selected (e.g. top 10 positive and negative)."""
    items = sorted(items, key=lambda kv: kv[1])
    fig, ax = _axes(title, "Logistic-regression weight (log-odds)", "", figsize=(7.6, 0.32 * len(items) + 1.4))
    ys = np.arange(len(items))
    colors = [DIVERGING_POS if w > 0 else DIVERGING_NEG for _, w in items]
    ax.barh(ys, [w for _, w in items], color=colors, height=0.72, edgecolor=SURFACE, linewidth=1)
    ax.set_yticks(ys, [n for n, _ in items], fontsize=7.5, color=INK2)
    ax.axvline(0, color=AXIS, linewidth=1)
    ax.grid(True, axis="x", color=GRID, linewidth=0.6)
    ax.grid(False, axis="y")
    ax.plot([], [], color=DIVERGING_POS, linewidth=6, label="Raises the score")
    ax.plot([], [], color=DIVERGING_NEG, linewidth=6, label="Lowers the score")
    _finish(fig, ax, path, note)
