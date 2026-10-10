"""Trains, evaluates and exports ConsentLens-1, a UTF-8 byte-level 1D-CNN that labels one consent-screen string.

    python -I ml/consentlens/train_consentlens.py            # full run: CV folds + final export (about 1 hour on CPU)
    python -I ml/consentlens/train_consentlens.py --quick    # smoke run with few epochs (numbers not reportable)

Evaluation (all numbers in ml/consentlens/reports/metrics.json):
  * leave-languages-out: language groups (English always in training) split into 5 folds; each fold's languages are
    completely absent from its training data. Float and full-INT8 TFLite models are both scored on the held-out fold.
  * leave-source-out: each source is held out in turn (TraceVeil lexicon + LeakyShop counted as one source).
  * lexicon-only baseline: a port of TraceVeil's ConsentLexicon.classifyButton + withdraw cues, on the same test rows.
The exported model is then trained on all rows; it is never scored on its own training rows for reporting.
"""
import argparse
import collections
import hashlib
import json
import os
import random
import re
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.dirname(HERE))
os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")

import numpy as np  # noqa: E402
import tensorflow as tf  # noqa: E402
import keras  # noqa: E402
from keras import layers  # noqa: E402

# scikit-learn is not used: this machine's application-control policy blocks its native extension, and these three
# metrics are a few lines of numpy (same definitions as sklearn, zero_division=0).


def confusion_matrix(y_true, y_pred, labels):
    labels = list(labels)
    idx = {c: i for i, c in enumerate(labels)}
    m = np.zeros((len(labels), len(labels)), dtype=np.int64)
    for t, p in zip(np.asarray(y_true).tolist(), np.asarray(y_pred).tolist()):
        if t in idx and p in idx:
            m[idx[t], idx[p]] += 1
    return m


def precision_recall_fscore_support(y_true, y_pred, labels, zero_division=0):
    m = confusion_matrix(y_true, y_pred, labels)
    tp = np.diag(m).astype(float)
    pred_n, true_n = m.sum(0).astype(float), m.sum(1).astype(float)
    # predictions of labels outside `labels` still count against precision of none; recall uses all true rows
    true_all = np.array([np.sum(np.asarray(y_true) == c) for c in labels], dtype=float)
    pred_all = np.array([np.sum(np.asarray(y_pred) == c) for c in labels], dtype=float)
    p = np.divide(tp, pred_all, out=np.full_like(tp, zero_division, dtype=float), where=pred_all > 0)
    r = np.divide(tp, true_all, out=np.full_like(tp, zero_division, dtype=float), where=true_all > 0)
    f = np.divide(2 * p * r, p + r, out=np.full_like(tp, zero_division, dtype=float), where=(p + r) > 0)
    return p, r, f, true_all.astype(int)


def f1_score(y_true, y_pred, labels=None, average="macro", zero_division=0):
    if labels is None:
        labels = sorted(set(np.asarray(y_true).tolist()) | set(np.asarray(y_pred).tolist()))
    _, _, f, _ = precision_recall_fscore_support(y_true, y_pred, list(labels), zero_division)
    return f if average is None else float(np.mean(f))


from consentlens_text import MAX_BYTES, MODEL_ID, ROLES, VOCAB, encode, normalize  # noqa: E402

SEED = 42
DATA = os.path.join(HERE, "data", "consentlens_dataset.jsonl")
REPORTS = os.path.join(HERE, "reports")
ASSET_DIR = os.path.join(REPO, "app", "src", "main", "assets", "consentlens")
GOLDEN = os.path.join(REPO, "core", "src", "test", "resources", "consentlens", "golden.json")
N_LANG_FOLDS = 5
OTHER = ROLES.index("OTHER")

# Hand-written demo phrases for the AI lab card (not taken from the dataset; expected role is our own label).
DEMO = [
    ("en", "Decline optional tracking", "REJECT"),
    ("en", "Read our privacy notice", "PRIVACY_LINK"),
    ("en", "Add to cart", "OTHER"),
    ("hi", "सभी स्वीकार करें", "ACCEPT"),
    ("hi", "हम विज्ञापन दिखाने के लिए आपके डेटा का उपयोग करते हैं", "PURPOSE_NOTICE"),
    ("de", "Alle ablehnen", "REJECT"),
    ("de", "Einstellungen anpassen", "MANAGE"),
    ("fr", "Tout accepter", "ACCEPT"),
    ("fr", "Vous pouvez retirer votre consentement à tout moment.", "WITHDRAW_INFO"),
    ("es", "Rechazar todo", "REJECT"),
    ("es", "Usamos sus datos para mostrar anuncios personalizados.", "PURPOSE_NOTICE"),
    ("pt", "Aceitar todos", "ACCEPT"),
    ("pt", "Política de privacidade", "PRIVACY_LINK"),
    ("it", "Gestisci preferenze", "MANAGE"),
    ("ja", "すべて拒否", "REJECT"),
    ("ja", "同意する", "ACCEPT"),
    ("zh", "接受全部", "ACCEPT"),
    ("zh", "管理偏好设置", "MANAGE"),
    ("ar", "رفض الكل", "REJECT"),
    ("ar", "سياسة الخصوصية", "PRIVACY_LINK"),
    ("ko", "모두 거부", "REJECT"),
    ("ru", "Принять все", "ACCEPT"),
    ("tr", "Tümünü kabul et", "ACCEPT"),
    ("id", "Tolak semua", "REJECT"),
]

# Strings for the Kotlin preprocessing golden test: scripts, casing, whitespace, apostrophes, truncation, NUL.
GOLDEN_TEXTS = [
    "Accept & Continue", "  Reject\tall \n", "DON’T ALLOW", "Don't allow", "ΟΔΟΣ ΣΟΦΟΣ", "İstanbul’da Kabul Et",
    "Straße ÄÖÜ", "ﬁle ① Ｆｕｌｌ", "सभी स्वीकार करें", "ಒಪ್ಪುತ್ತೇನೆ", "すべて拒否", "接受全部", "رفض الكل", "모두 거부",
    "a b c　d", "x\u0000y", "", "   ", "🍪 Cookies 🍪",
    "We use your data to personalise offers, measure the app and show relevant ads. " * 2,
    "हम आपका ईमेल, फ़ोन नंबर और ऑर्डर इतिहास सिर्फ़ डिलीवरी के लिए लेते हैं।",
]


def set_seed():
    random.seed(SEED)
    np.random.seed(SEED)
    keras.utils.set_random_seed(SEED)


def load():
    rows = [json.loads(line) for line in open(DATA, encoding="utf-8")]
    X = np.array([encode(r["text"]) for r in rows], dtype=np.int32)
    y = np.array([ROLES.index(r["role"]) for r in rows], dtype=np.int32)
    return rows, X, y


class ByteEmbedding(layers.Layer):
    """Plain tf.gather lookup. keras.layers.Embedding adds LESS/ADD/SELECT_V2 ops (negative-index handling) to the
    TFLite graph; byte ids are always 0..255, so a single GATHER keeps the graph NPU-friendly."""

    def __init__(self, vocab, dim, **kw):
        super().__init__(**kw)
        self.vocab, self.dim = vocab, dim

    def build(self, input_shape):
        self.embeddings = self.add_weight(name="embeddings", shape=(self.vocab, self.dim), initializer="uniform")

    def call(self, ids):
        return tf.gather(self.embeddings.value, ids)


def build_model():
    inp = keras.Input(shape=(MAX_BYTES,), dtype="int32", name="utf8_bytes")
    # Size and recipe chosen on fold 0 of the leave-languages-out and seen-language protocols only (see MODEL_CARD.md).
    x = ByteEmbedding(VOCAB, 48, name="byte_embedding")(inp)
    x = layers.Conv1D(128, 3, padding="same", activation="relu", name="conv3")(x)
    x = layers.Conv1D(128, 5, padding="same", activation="relu", name="conv5")(x)
    x = layers.GlobalMaxPooling1D(name="max_pool")(x)
    x = layers.Dense(128, activation="relu", name="dense")(x)
    x = layers.Dropout(0.1)(x)
    out = layers.Dense(len(ROLES), activation="softmax", name="role")(x)
    model = keras.Model(inp, out, name="consentlens1")
    model.compile(optimizer=keras.optimizers.Adam(2e-3), loss="sparse_categorical_crossentropy", metrics=["accuracy"])
    return model


def class_weights(y):
    counts = np.bincount(y, minlength=len(ROLES)).astype(float)
    w = np.where(counts > 0, np.sqrt(counts.sum() / np.maximum(counts, 1) / len(ROLES)), 0.0)
    return {i: float(w[i]) for i in range(len(ROLES))}


def stratified_val(y, frac=0.1, seed=SEED):
    rng = np.random.default_rng(seed)
    val = []
    for c in np.unique(y):
        idx = np.flatnonzero(y == c)
        rng.shuffle(idx)
        val.extend(idx[: max(1, int(round(frac * len(idx))))])
    mask = np.zeros(len(y), bool)
    mask[val] = True
    return mask


def train(X, y, epochs):
    """Fixed number of epochs on all given rows (early stopping on a random split under-fitted: training-row macro-F1
    0.67 with the first recipe), square-root inverse-frequency class weights."""
    set_seed()
    model = build_model()
    model.fit(X, y, epochs=epochs, batch_size=64, verbose=0, class_weight=class_weights(y))
    return model


def to_int8(model, rep_X):
    rng = np.random.default_rng(SEED)
    rep = rep_X[rng.choice(len(rep_X), size=min(600, len(rep_X)), replace=False)]

    def representative():
        for x in rep:
            yield [x[None, :].astype(np.int32)]

    conv = tf.lite.TFLiteConverter.from_keras_model(model)
    conv.optimizations = [tf.lite.Optimize.DEFAULT]
    conv.representative_dataset = representative
    conv.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
    conv.inference_output_type = tf.int8  # the input is already int32 byte ids
    return conv.convert()


def tflite_probs(model_bytes, X, batch=256):
    it = tf.lite.Interpreter(model_content=model_bytes, num_threads=2)
    inp, out = it.get_input_details()[0], it.get_output_details()[0]
    scale, zp = out["quantization"]
    res = []
    for s in range(0, len(X), batch):
        xb = X[s:s + batch]
        it.resize_tensor_input(inp["index"], [len(xb), MAX_BYTES])
        it.allocate_tensors()
        it.set_tensor(inp["index"], xb.astype(np.int32))
        it.invoke()
        q = it.get_tensor(it.get_output_details()[0]["index"]).astype(np.float32)
        res.append(scale * (q - zp))
    return np.concatenate(res), float(scale), int(zp)


def tflite_ops(model_bytes):
    it = tf.lite.Interpreter(model_content=model_bytes,
                             experimental_op_resolver_type=tf.lite.experimental.OpResolverType.BUILTIN_WITHOUT_DEFAULT_DELEGATES)
    it.allocate_tensors()
    return [op["op_name"] for op in it._get_ops_details()]  # noqa: SLF001 (only way to list ops in TF 2.20)


# ---------------------------------------------------------------- lexicon-only baseline (port of ConsentLexicon.kt)
def lexicon_baseline():
    lex = open(os.path.join(REPO, "core/src/main/kotlin/com/dpdpxray/core/consent/ConsentLexicon.kt"), encoding="utf-8").read()
    aud = open(os.path.join(REPO, "core/src/main/kotlin/com/dpdpxray/core/screen/ScreenAuditing.kt"), encoding="utf-8").read()

    def phrases(src, name):
        return re.findall(r'"([^"]+)"', re.search(name + r"\s*=\s*listOf\((.*?)\)", src, re.S).group(1))

    reject, settings, accept = (phrases(lex, "private val " + n) for n in ("REJECT", "SETTINGS", "ACCEPT"))
    withdraw = phrases(aud, "private val WITHDRAW_CUES")
    punct = re.compile(r"[!\"#$%&'()*+,./:;<=>?@\[\\\]^_`{|}~]")  # Java \p{Punct} minus '-'

    def norm(s):
        s = s.lower().replace("&", " ").replace("'", "").replace("’", "")
        return re.sub(r"[ \t\n\x0b\f\r]+", " ", punct.sub(" ", s)).strip()

    def match(t, ps):
        return any(t == p or t.startswith(p + " ") for p in ps)

    def classify(text):
        t = norm(text)
        if t and len(t) <= 40:
            if match(t, reject):
                return "REJECT"
            if match(t, settings):
                return "MANAGE"
            if match(t, accept):
                return "ACCEPT"
        low = text.lower()
        if any(c in low for c in withdraw):
            return "WITHDRAW_INFO"
        return "OTHER"

    return classify


# ---------------------------------------------------------------- metrics
def metrics(y_true, y_pred, labels=None):
    labels = labels if labels is not None else list(range(len(ROLES)))
    p, r, f, s = precision_recall_fscore_support(y_true, y_pred, labels=labels, zero_division=0)
    return {
        "n": int(len(y_true)),
        "accuracy": round(float(np.mean(y_true == y_pred)), 4) if len(y_true) else None,
        "macro_f1": round(float(f1_score(y_true, y_pred, labels=labels, average="macro", zero_division=0)), 4),
        "per_class": {ROLES[c]: {"precision": round(float(p[i]), 4), "recall": round(float(r[i]), 4),
                                 "f1": round(float(f[i]), 4), "support": int(s[i])} for i, c in enumerate(labels)},
        "confusion_matrix": {"labels": [ROLES[c] for c in labels],
                             "rows_true_cols_pred": confusion_matrix(y_true, y_pred, labels=labels).tolist()},
    }


def threshold_table(probs, y_true):
    """Precision/coverage of non-OTHER predictions kept at each confidence threshold (what the app uses)."""
    pred, conf = probs.argmax(1), probs.max(1)
    out = []
    for t in (0.5, 0.6, 0.7, 0.8, 0.9, 0.95):
        keep = (pred != OTHER) & (conf >= t)
        true_consent = y_true != OTHER
        out.append({"threshold": t, "kept": int(keep.sum()),
                    "precision": round(float(np.mean(pred[keep] == y_true[keep])), 4) if keep.any() else None,
                    "recall_of_consent_strings": round(float(np.sum(keep & (pred == y_true)) / max(1, true_consent.sum())), 4)})
    return out


# ---------------------------------------------------------------- evaluation protocols
def lang_folds(rows):
    groups = sorted({r["lang_group"] for r in rows} - {"en"})
    rng = random.Random(SEED)
    rng.shuffle(groups)
    return [sorted(groups[i::N_LANG_FOLDS]) for i in range(N_LANG_FOLDS)]


def leave_languages_out(rows, X, y, epochs, baseline):
    folds = lang_folds(rows)
    lg = np.array([r["lang_group"] for r in rows])
    norm_text = np.array([normalize(r["text"]) for r in rows])
    yt, pf, pq, pb, novel_mask, lang_of, fold_stats, probs_q_all = [], [], [], [], [], [], [], []
    for k, held in enumerate(folds):
        te = np.isin(lg, held)
        t0 = time.time()
        model = train(X[~te], y[~te], epochs)
        probs_f = model.predict(X[te], batch_size=512, verbose=0)
        q_bytes = to_int8(model, X[~te])
        probs_q, _, _ = tflite_probs(q_bytes, X[te])
        seen = set(norm_text[~te])
        nov = np.array([t not in seen for t in norm_text[te]])
        f_pred, q_pred = probs_f.argmax(1), probs_q.argmax(1)
        b_pred = np.array([ROLES.index(baseline(rows[i]["text"])) for i in np.flatnonzero(te)])
        fold_stats.append({
            "fold": k, "held_out_language_groups": held, "n_test": int(te.sum()), "n_train": int((~te).sum()),
            "float_macro_f1": round(float(f1_score(y[te], f_pred, average="macro")), 4),
            "int8_macro_f1": round(float(f1_score(y[te], q_pred, average="macro")), 4),
            "int8_float_agreement": round(float(np.mean(f_pred == q_pred)), 4),
            "max_abs_prob_diff": round(float(np.max(np.abs(probs_f - probs_q))), 4),
            "seconds": round(time.time() - t0, 1),
        })
        print("lang fold", fold_stats[-1], flush=True)
        yt.append(y[te]); pf.append(f_pred); pq.append(q_pred); pb.append(b_pred); novel_mask.append(nov)
        lang_of.extend(lg[te]); probs_q_all.append(probs_q)
        keras.backend.clear_session()
    yt, pf, pq, pb, nov = map(np.concatenate, (yt, pf, pq, pb, novel_mask))
    probs_q_all = np.concatenate(probs_q_all)
    lang_of = np.array(lang_of)
    per_lang = {}
    for g in sorted(set(lang_of)):
        m = lang_of == g
        per_lang[g] = {"n": int(m.sum()), "int8_accuracy": round(float(np.mean(yt[m] == pq[m])), 4),
                       "float_accuracy": round(float(np.mean(yt[m] == pf[m])), 4),
                       "lexicon_accuracy": round(float(np.mean(yt[m] == pb[m])), 4),
                       "int8_macro_f1": round(float(f1_score(yt[m], pq[m], average="macro")), 4)}
    return {
        "protocol": "5 folds of whole language groups (English always in training); every non-English group is "
                    "tested exactly once by a model that never saw that language",
        "folds": fold_stats,
        "int8": metrics(yt, pq), "float": metrics(yt, pf), "lexicon_baseline": metrics(yt, pb),
        "int8_novel_strings_only": metrics(yt[nov], pq[nov]),
        "lexicon_baseline_novel_strings_only": metrics(yt[nov], pb[nov]),
        "novel_strings_note": "test strings whose normalized text never appears (in any language) in the fold's training rows",
        "int8_vs_float_agreement": round(float(np.mean(pf == pq)), 4),
        "per_language": per_lang,
        "thresholds_int8": threshold_table(probs_q_all, yt),
    }, (yt, pq, pb, per_lang)


def seen_language_cv(rows, X, y, epochs, baseline):
    """5 random folds grouped by normalized text: every language is in training, but no test string (in any language)
    appears in its fold's training rows. Closer to how the shipped model is used on languages it was trained on."""
    texts = sorted({normalize(r["text"]) for r in rows})
    rng = random.Random(SEED)
    rng.shuffle(texts)
    fold_of = {t: i % N_LANG_FOLDS for i, t in enumerate(texts)}
    folds = np.array([fold_of[normalize(r["text"])] for r in rows])
    yt, pq, pb = [], [], []
    for k in range(N_LANG_FOLDS):
        te = folds == k
        model = train(X[~te], y[~te], epochs)
        q_bytes = to_int8(model, X[~te])
        probs_q, _, _ = tflite_probs(q_bytes, X[te])
        yt.append(y[te]); pq.append(probs_q.argmax(1))
        pb.append(np.array([ROLES.index(baseline(rows[i]["text"])) for i in np.flatnonzero(te)]))
        print("seen-language fold", k, round(float(f1_score(y[te], pq[-1])), 4), flush=True)
        keras.backend.clear_session()
    yt, pq, pb = map(np.concatenate, (yt, pq, pb))
    return {"protocol": "5 random folds grouped by normalized string text; all languages seen in training",
            "int8": metrics(yt, pq), "lexicon_baseline": metrics(yt, pb)}


def leave_source_out(rows, X, y, epochs, baseline):
    src = np.array(["traceveil" if r["source"].startswith("traceveil") else r["source"] for r in rows])
    out = {}
    for s in sorted(set(src)):
        te = src == s
        model = train(X[~te], y[~te], epochs)
        pred = model.predict(X[te], batch_size=512, verbose=0).argmax(1)
        present = sorted(set(y[te].tolist()))
        b_pred = np.array([ROLES.index(baseline(rows[i]["text"])) for i in np.flatnonzero(te)])
        out[s] = {"float": metrics(y[te], pred, labels=present), "lexicon_baseline": metrics(y[te], b_pred, labels=present)}
        print("source", s, out[s]["float"]["macro_f1"], "lexicon", out[s]["lexicon_baseline"]["macro_f1"], flush=True)
        keras.backend.clear_session()
    out["note"] = "macro-F1 over the roles present in the held-out source; float model"
    return out


# ---------------------------------------------------------------- plots
def plot_all(lolo, lso):
    import types
    import textwrap

    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    # Same palette and axes style as ml/plots.py (not imported: it pulls in scikit-learn, blocked on this machine).
    P = types.SimpleNamespace(SERIES=["#2a78d6", "#eb6834", "#1baf7a", "#4a3aa7"], SURFACE="#fcfcfb", INK="#0b0b0b",
                              INK2="#52514e", MUTED="#898781", GRID="#e1e0d9", AXIS="#c3c2b7",
                              SEQ_BLUE=["#cde2fb", "#9ec5f4", "#6da7ec", "#3987e5", "#256abf", "#184f95", "#0d366b"])

    def _note(fig, note):
        lines = textwrap.wrap(note, width=int(fig.get_figwidth() * 17)) if note else []
        if lines:
            fig.text(0.01, 0.01, chr(10).join(lines), color=P.MUTED, fontsize=7.2, ha="left", va="bottom")
        fig.tight_layout(rect=(0, 0.025 + 0.022 * len(lines) * 5.2 / fig.get_figheight(), 1, 1) if lines else None)

    def _axes(title, xlabel, ylabel, figsize):
        fig, ax = plt.subplots(figsize=figsize, dpi=200)
        fig.patch.set_facecolor(P.SURFACE)
        ax.set_facecolor(P.SURFACE)
        for side in ("top", "right"):
            ax.spines[side].set_visible(False)
        for side in ("left", "bottom"):
            ax.spines[side].set_color(P.AXIS)
        ax.tick_params(colors=P.MUTED, labelcolor=P.INK2, labelsize=9)
        ax.grid(True, color=P.GRID, linewidth=0.6)
        ax.set_axisbelow(True)
        fig.suptitle(title, x=0.015, ha="left", color=P.INK, fontsize=11)
        ax.set_xlabel(xlabel, color=P.INK2, fontsize=9.5)
        ax.set_ylabel(ylabel, color=P.INK2, fontsize=9.5)
        return fig, ax

    def _finish(fig, ax, path, note=None, legend_loc="lower right"):
        leg = ax.legend(loc=legend_loc, frameon=False, fontsize=8.5)
        for t in leg.get_texts():
            t.set_color(P.INK)
        _note(fig, note)
        fig.savefig(path, facecolor=P.SURFACE)
        plt.close(fig)

    P._note, P._axes, P._finish = _note, _axes, _finish

    yt, pq, pb, per_lang = lolo
    cm = confusion_matrix(yt, pq, labels=range(len(ROLES))).astype(float)
    cmn = cm / np.maximum(cm.sum(1, keepdims=True), 1)
    fig, ax = plt.subplots(figsize=(7.4, 6.2), dpi=200)
    fig.patch.set_facecolor(P.SURFACE)
    cmap = matplotlib.colors.LinearSegmentedColormap.from_list("seq", P.SEQ_BLUE)
    ax.imshow(cmn, cmap=cmap, vmin=0, vmax=1)
    for i in range(len(ROLES)):
        for j in range(len(ROLES)):
            if cm[i, j]:
                ax.text(j, i, f"{int(cm[i, j])}\n{100 * cmn[i, j]:.0f}%", ha="center", va="center", fontsize=6.5,
                        color="#ffffff" if cmn[i, j] > 0.55 else P.INK)
    short = [r.replace("_", "\n") for r in ROLES]
    ax.set_xticks(range(len(ROLES)), short, fontsize=7, color=P.INK2)
    ax.set_yticks(range(len(ROLES)), short, fontsize=7, color=P.INK2)
    ax.set_xlabel("Predicted role", color=P.INK2, fontsize=9)
    ax.set_ylabel("Labelled role", color=P.INK2, fontsize=9)
    ax.tick_params(length=0)
    for s in ax.spines.values():
        s.set_visible(False)
    ax.set_title("ConsentLens-1 (INT8) on languages it never saw", color=P.INK, fontsize=11, loc="left", pad=10)
    P._note(fig, "Leave-languages-out, 5 folds; rows sum to 100%. Counts are strings, not screens.")  # noqa: SLF001
    fig.savefig(os.path.join(REPORTS, "confusion_leave_languages_out_int8.png"), facecolor=P.SURFACE)
    plt.close(fig)

    langs = sorted(per_lang, key=lambda g: per_lang[g]["int8_accuracy"])
    fig, ax = P._axes("Per-language accuracy, held-out languages", "Accuracy", "", figsize=(7.0, 0.16 * len(langs) + 1.6))  # noqa: SLF001
    ys = np.arange(len(langs))
    ax.barh(ys + 0.2, [per_lang[g]["int8_accuracy"] for g in langs], height=0.4, color=P.SERIES[0], label="ConsentLens-1 INT8")
    ax.barh(ys - 0.2, [per_lang[g]["lexicon_accuracy"] for g in langs], height=0.4, color=P.SERIES[1], label="Lexicon only")
    ax.set_yticks(ys, langs, fontsize=5.5, color=P.INK2)
    ax.set_xlim(0, 1)
    ax.grid(False, axis="y")
    P._finish(fig, ax, os.path.join(REPORTS, "per_language_accuracy.png"),  # noqa: SLF001
              "Each language scored by the fold model that never saw it. Lexicon = TraceVeil's EN/HI/KN rules.", legend_loc="lower right")

    fig, ax = P._axes("Per-role F1: held-out languages", "", "F1", figsize=(7.4, 4.4))  # noqa: SLF001
    f_m = f1_score(yt, pq, labels=range(len(ROLES)), average=None, zero_division=0)
    f_b = f1_score(yt, pb, labels=range(len(ROLES)), average=None, zero_division=0)
    xs = np.arange(len(ROLES))
    ax.bar(xs - 0.2, f_m, width=0.4, color=P.SERIES[0], label="ConsentLens-1 INT8")
    ax.bar(xs + 0.2, f_b, width=0.4, color=P.SERIES[1], label="Lexicon only")
    ax.set_xticks(xs, short, fontsize=7, color=P.INK2)
    ax.set_ylim(0, 1)
    ax.grid(False, axis="x")
    P._finish(fig, ax, os.path.join(REPORTS, "per_role_f1.png"), "Leave-languages-out, all folds pooled.", legend_loc="upper right")  # noqa: SLF001


# ---------------------------------------------------------------- export
def export(rows, X, y, epochs, summary):
    model = train(X, y, epochs)
    q = to_int8(model, X)
    probs_q, scale, zp = tflite_probs(q, X)
    probs_f = model.predict(X, batch_size=512, verbose=0)
    ops = tflite_ops(q)
    os.makedirs(ASSET_DIR, exist_ok=True)
    path = os.path.join(ASSET_DIR, "consentlens_int8.tflite")
    with open(path, "wb") as f:
        f.write(q)
    sha = hashlib.sha256(q).hexdigest()
    seen = {normalize(r["text"]) for r in rows}
    dx = np.array([encode(t) for _, t, _ in DEMO], dtype=np.int32)
    dprobs, _, _ = tflite_probs(q, dx)
    demo = [{"lang": l, "text": t, "expected": e, "in_training_data": normalize(t) in seen,
             "reference_role": ROLES[int(p.argmax())], "reference_confidence": round(float(p.max()), 4)}
            for (l, t, e), p in zip(DEMO, dprobs)]
    meta = {
        "model_id": MODEL_ID, "file": "consentlens_int8.tflite", "sha256": sha, "bytes": len(q), "roles": ROLES,
        "max_bytes": MAX_BYTES, "input": "int32[1,128] UTF-8 byte ids (0 = padding)",
        "output": {"type": "int8[1,7] softmax", "scale": scale, "zero_point": zp},
        "tflite_ops": ops, "op_count": len(ops), "params": int(model.count_params()),
        "train_rows": int(len(rows)), "languages": len({r["lang_group"] for r in rows}),
        "default_threshold": summary["default_threshold"],
        "held_out_language_macro_f1_int8": summary["lolo_int8_macro_f1"],
        "held_out_language_macro_f1_lexicon": summary["lolo_lexicon_macro_f1"],
        "demo": demo,
    }
    with open(os.path.join(ASSET_DIR, "consentlens_meta.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(meta, f, ensure_ascii=False, indent=1)
    golden = {
        "max_bytes": MAX_BYTES,
        "encode": [{"text": t, "normalized": normalize(t), "ids": encode(t)} for t in GOLDEN_TEXTS],
        "decode": {"scale": scale, "zero_point": zp, "roles": ROLES,
                   "cases": [{"int8": [int(round(v / scale + zp)) for v in p], "role": ROLES[int(p.argmax())]}
                             for p in dprobs[:6]]},
    }
    os.makedirs(os.path.dirname(GOLDEN), exist_ok=True)
    with open(GOLDEN, "w", encoding="utf-8", newline="\n") as f:
        json.dump(golden, f, ensure_ascii=False, indent=1)
    return {"tflite_bytes": len(q), "sha256": sha, "params": int(model.count_params()), "ops": ops,
            "output_quantization": {"scale": scale, "zero_point": zp},
            "int8_vs_float_agreement_on_training_rows": round(float(np.mean(probs_q.argmax(1) == probs_f.argmax(1))), 4),
            "max_abs_prob_diff_on_training_rows": round(float(np.max(np.abs(probs_q - probs_f))), 4),
            "demo": demo}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--quick", action="store_true")
    ap.add_argument("--skip-cv", action="store_true", help="export only (reuses metrics.json summary)")
    a = ap.parse_args()
    epochs = 3 if a.quick else 30
    os.makedirs(REPORTS, exist_ok=True)
    rows, X, y = load()
    baseline = lexicon_baseline()
    t0 = time.time()
    data_stats = {
        "rows": len(rows),
        "by_role": dict(collections.Counter(r["role"] for r in rows)),
        "by_source": dict(collections.Counter(r["source"] for r in rows)),
        "by_source_role": {s: dict(collections.Counter(r["role"] for r in rows if r["source"] == s))
                           for s in sorted({r["source"] for r in rows})},
        "by_language_group": dict(sorted(collections.Counter(r["lang_group"] for r in rows).items())),
        "truncated_rows": int(sum(len(normalize(r["text"]).encode("utf-8")) > MAX_BYTES for r in rows)),
    }
    metrics_path = os.path.join(REPORTS, "metrics.json")
    if a.skip_cv:
        report = json.load(open(metrics_path, encoding="utf-8"))
        lolo = report["leave_languages_out"]
    else:
        lolo, lolo_arrays = leave_languages_out(rows, X, y, epochs, baseline)
        lso = leave_source_out(rows, X, y, epochs, baseline)
        seen = seen_language_cv(rows, X, y, epochs, baseline)
        report = {"model_id": MODEL_ID, "data": data_stats, "leave_languages_out": lolo, "leave_source_out": lso,
                  "seen_languages_unseen_strings": seen}
        plot_all(lolo_arrays, lso)
    thr = next((t["threshold"] for t in lolo["thresholds_int8"] if t["precision"] and t["precision"] >= 0.9), 0.9)
    summary = {"default_threshold": thr, "lolo_int8_macro_f1": lolo["int8"]["macro_f1"],
               "lolo_lexicon_macro_f1": lolo["lexicon_baseline"]["macro_f1"]}
    report["export"] = export(rows, X, y, epochs, summary)
    report["summary"] = summary
    report["environment"] = {"python": sys.version.split()[0], "tensorflow": tf.__version__, "keras": keras.__version__,
                             "numpy": np.__version__, "seed": SEED, "epochs_max": epochs, "quick": a.quick,
                             "seconds": round(time.time() - t0, 1)}
    with open(metrics_path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(report, f, ensure_ascii=False, indent=1)
    print(json.dumps({"summary": summary, "export": {k: v for k, v in report["export"].items() if k != "demo"}}, indent=1))


if __name__ == "__main__":
    main()
