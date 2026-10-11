"""Figures for reports/v2_report.md from the JSON written by analyze_v2.py.

    python -I ml/consentlens/plot_v2.py --summary ml/consentlens/reports/v2/outer_v1_vs_<cand>.json --cand <cand>
"""
import argparse
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import numpy as np  # noqa: E402
import matplotlib  # noqa: E402

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

from consentlens_text import ROLES  # noqa: E402

OUT = os.path.join(HERE, "reports", "v2")
# Validated categorical order (slots 1-3) and the blue sequential ramp; text stays in ink tokens.
SURFACE, INK, INK2, MUTED, GRID, AXIS = "#fcfcfb", "#0b0b0b", "#52514e", "#898781", "#e1e0d9", "#c3c2b7"
C_V1, C_V2, C_LEX = "#2a78d6", "#eb6834", "#1baf7a"
SEQ = ["#f4f8fd", "#cde2fb", "#9ec5f4", "#6da7ec", "#3987e5", "#256abf", "#184f95", "#0d366b"]
PROT = {"seen": "Seen languages, unseen strings", "lolo": "Held-out languages (never seen)"}
SHORT = [r.replace("_", "\n") for r in ROLES]


def style(ax):
    ax.set_facecolor(SURFACE)
    for s in ("top", "right"):
        ax.spines[s].set_visible(False)
    for s in ("left", "bottom"):
        ax.spines[s].set_color(AXIS)
    ax.tick_params(colors=MUTED, labelcolor=INK2, labelsize=8.5)
    ax.grid(True, axis="y", color=GRID, linewidth=0.6)
    ax.set_axisbelow(True)


def fig(w, h, n=1):
    f, axs = plt.subplots(1, n, figsize=(w, h), dpi=200)
    f.patch.set_facecolor(SURFACE)
    return f, (axs if n > 1 else [axs])


def bar(ax, x, h, color, label, err=None):
    ax.bar(x, h, width=0.26, color=color, label=label, edgecolor=SURFACE, linewidth=1.0, zorder=2)
    if err is not None:
        ax.errorbar(x, h, yerr=err, fmt="none", ecolor=INK, elinewidth=0.9, capsize=2.5, zorder=3)


def macro(summary, cand, base):
    f, (ax,) = fig(7.2, 3.6)
    style(ax)
    xs = np.arange(2)
    for j, prot in enumerate(("seen", "lolo")):
        lex = summary[prot]["lexicon_only"]
        b, c = summary[prot][base], summary[prot][cand]
        bar(ax, [xs[j] - 0.28], [summary_lex(summary, prot)], C_LEX, "EN/HI/KN lexicon (ConsentLens-1 baseline port)" if j == 0 else None)
        bar(ax, [xs[j]], [b["int8_macro_f1"]["mean"]], C_V1, "ConsentLens-1 INT8" if j == 0 else None, [b["int8_macro_f1"]["std"]])
        bar(ax, [xs[j] + 0.28], [c["int8_macro_f1"]["mean"]], C_V2, "v2 candidate INT8" if j == 0 else None, [c["int8_macro_f1"]["std"]])
        for dx, v in ((-0.28, summary_lex(summary, prot)), (0, b["int8_macro_f1"]["mean"]), (0.28, c["int8_macro_f1"]["mean"])):
            ax.text(xs[j] + dx, v + 0.03, f"{v:.3f}", ha="center", va="bottom", fontsize=8, color=INK)
    ax.set_xticks(xs, [PROT["seen"], PROT["lolo"]], fontsize=9, color=INK2)
    ax.set_ylim(0, 1)
    ax.set_ylabel("Macro-F1 (7 roles)", color=INK2, fontsize=9)
    leg = ax.legend(loc="upper right", frameon=False, fontsize=8)
    f.suptitle("Macro-F1, mean ± std over 3 training seeds (same folds as ConsentLens-1)", x=0.01, ha="left", fontsize=10.5, color=INK)
    f.tight_layout()
    f.savefig(os.path.join(OUT, "fig_macro_f1.png"), facecolor=SURFACE)
    plt.close(f)


def summary_lex(summary, prot):
    return summary[prot].get("lexicon_v1_port_macro_f1", summary[prot]["lexicon_only"]["macro_f1"])


def per_class(summary, cand, base):
    f, axs = plt.subplots(2, 1, figsize=(7.4, 5.8), dpi=200)
    f.patch.set_facecolor(SURFACE)
    xs = np.arange(len(ROLES))
    for ax, prot in zip(axs, ("seen", "lolo")):
        style(ax)
        b, c = summary[prot][base], summary[prot][cand]
        bar(ax, xs - 0.14, [b["per_class_f1_mean"][r] for r in ROLES], C_V1, "ConsentLens-1", [b["per_class_f1_std"][r] for r in ROLES])
        bar(ax, xs + 0.14, [c["per_class_f1_mean"][r] for r in ROLES], C_V2, "v2 candidate", [c["per_class_f1_std"][r] for r in ROLES])
        for i, r in enumerate(ROLES):
            d = c["per_class_f1_mean"][r] - b["per_class_f1_mean"][r]
            ax.text(i, 1.02, f"{d:+.2f}", ha="center", va="bottom", fontsize=7.5, color=INK2)
        ax.set_xticks(xs, SHORT, fontsize=7.2, color=INK2)
        ax.set_ylim(0, 1.12)
        ax.set_yticks([0, 0.25, 0.5, 0.75, 1.0])
        ax.set_title(PROT[prot] + " (numbers: v2 minus v1)", loc="left", fontsize=9.5, color=INK)
        ax.grid(False, axis="x")
    h, l = axs[0].get_legend_handles_labels()
    f.legend(h, l, loc="upper right", frameon=False, fontsize=8, ncol=2)
    f.suptitle("Per-role F1, INT8, mean ± std over 3 seeds", x=0.01, ha="left", fontsize=10.5, color=INK)
    f.tight_layout(rect=(0, 0, 1, 0.97))
    f.savefig(os.path.join(OUT, "fig_per_class_f1.png"), facecolor=SURFACE)
    plt.close(f)


def confusions(summary, cand, base):
    cmap = matplotlib.colors.LinearSegmentedColormap.from_list("seq", SEQ)
    f, axs = plt.subplots(2, 2, figsize=(7.6, 7.4), dpi=200)
    f.patch.set_facecolor(SURFACE)
    for i, prot in enumerate(("seen", "lolo")):
        for j, (rec, name) in enumerate(((base, "ConsentLens-1"), (cand, "v2 candidate"))):
            ax = axs[i][j]
            cm = np.array(summary[prot][rec]["confusion_sum_over_seeds"], float)
            cmn = cm / np.maximum(cm.sum(1, keepdims=True), 1)
            ax.imshow(cmn, cmap=cmap, vmin=0, vmax=1)
            for a in range(len(ROLES)):
                for b in range(len(ROLES)):
                    if cmn[a, b] >= 0.005:
                        ax.text(b, a, f"{100 * cmn[a, b]:.0f}", ha="center", va="center", fontsize=6.6,
                                color="#ffffff" if cmn[a, b] > 0.5 else INK)
            ab = [r[:6] if r != "PRIVACY_LINK" else "PRIV" for r in ROLES]
            ab = ["ACC", "REJ", "MAN", "PURP", "PRIV", "WDRW", "OTH"]
            ax.set_xticks(range(len(ROLES)), ab, fontsize=6.8, color=INK2)
            ax.set_yticks(range(len(ROLES)), ab, fontsize=6.8, color=INK2)
            ax.tick_params(length=0)
            for s in ax.spines.values():
                s.set_visible(False)
            ax.set_title(f"{name}: {PROT[prot].split(',')[0].split(' (')[0].lower()}", fontsize=8.8, color=INK, loc="left")
            if j == 0:
                ax.set_ylabel("Labelled role", fontsize=8, color=INK2)
            if i == 1:
                ax.set_xlabel("Predicted role", fontsize=8, color=INK2)
    f.suptitle("Confusion matrices, INT8, rows sum to 100% (three seeds pooled)", x=0.01, ha="left", fontsize=10.5, color=INK)
    f.tight_layout()
    f.savefig(os.path.join(OUT, "fig_confusion.png"), facecolor=SURFACE)
    plt.close(f)


def layered_fig(summary, cand, base):
    f, axs = plt.subplots(1, 2, figsize=(7.4, 3.3), dpi=200)
    f.patch.set_facecolor(SURFACE)
    for ax, key, title in zip(axs, ("coverage", "precision_of_emitted"),
                              ("Consent strings correctly labelled (coverage)", "Precision of the labels it emits")):
        style(ax)
        xs = np.arange(2)
        for j, prot in enumerate(("seen", "lolo")):
            vals = [summary[prot]["lexicon_only"][key], summary[prot][base]["layered_mean"][key], summary[prot][cand]["layered_mean"][key]]
            for dx, v, col, lab in zip((-0.28, 0, 0.28), vals, (C_LEX, C_V1, C_V2),
                                       ("Lexicon only", "Lexicon + ConsentLens-1", "Lexicon + v2")):
                ax.bar(xs[j] + dx, v, width=0.26, color=col, edgecolor=SURFACE, label=lab if j == 0 else None, zorder=2)
                ax.text(xs[j] + dx, v + 0.02, f"{v:.2f}", ha="center", va="bottom", fontsize=7.4, color=INK)
        ax.set_xticks(xs, ["Seen languages", "Held-out languages"], fontsize=8.4, color=INK2)
        ax.set_ylim(0, 1.1)
        ax.set_title(title, fontsize=9, color=INK, loc="left")
        ax.grid(False, axis="x")
    axs[0].legend(loc="upper left", frameon=False, fontsize=7.4)
    f.tight_layout()
    f.savefig(os.path.join(OUT, "fig_layered.png"), facecolor=SURFACE)
    plt.close(f)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--summary", required=True)
    ap.add_argument("--cand", required=True)
    ap.add_argument("--base", default="v1")
    a = ap.parse_args()
    s = json.load(open(a.summary, encoding="utf-8"))
    os.makedirs(OUT, exist_ok=True)
    macro(s, a.cand, a.base)
    per_class(s, a.cand, a.base)
    confusions(s, a.cand, a.base)
    layered_fig(s, a.cand, a.base)
    print("figures in", OUT)


if __name__ == "__main__":
    main()
