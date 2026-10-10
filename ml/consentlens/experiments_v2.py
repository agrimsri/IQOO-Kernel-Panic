"""ConsentLens v2 experiments: reproduce ConsentLens-1's evaluation and compare candidate recipes under NPU limits.

Every recipe is scored with exactly the splits and protocol of train_consentlens.py (5 leave-languages-out folds and
5 seen-language folds, both fixed with seed 42); only the model-training seed varies. Recipe choices are made on an
inner validation split inside the TRAINING rows of outer fold 0 ("dev"), never on outer test rows.

    python -I ml/consentlens/experiments_v2.py run  --recipe v1 --seed 42           # outer protocols, saves predictions
    python -I ml/consentlens/experiments_v2.py dev  --recipe deep --seed 1          # inner-validation screening only
    python -I ml/consentlens/experiments_v2.py export --recipe deep --seed 42 --out <file.tflite>
    python -I ml/consentlens/experiments_v2.py aggregate                            # mean +- std tables

Run outputs (predictions, per-fold numbers) go to --work (default: a folder outside Git); summaries that the report
quotes are written to ml/consentlens/reports/v2/.
"""
import argparse
import collections
import glob
import hashlib
import json
import os
import sys
import time
import unicodedata

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")

import numpy as np  # noqa: E402
import tensorflow as tf  # noqa: E402
import keras  # noqa: E402
from keras import layers  # noqa: E402

import train_consentlens as T  # noqa: E402
from consentlens_text import MAX_BYTES, ROLES, VOCAB, encode, normalize  # noqa: E402

REPORT_DIR = os.path.join(HERE, "reports", "v2")
DEFAULT_WORK = os.environ.get("CONSENTLENS_WORK", os.path.join("D:/DevTools/tmp", "consentlens-v2-work"))
SPLIT_SEED = 42  # fold assignment is ConsentLens-1's and never changes
NC = len(ROLES)
OTHER = ROLES.index("OTHER")

# v1 op types (from the shipped file); a candidate may stack more of these, never a new type.
V1_OP_TYPES = {"GATHER", "EXPAND_DIMS", "CONV_2D", "RESHAPE", "REDUCE_MAX", "FULLY_CONNECTED", "SOFTMAX"}

V1 = dict(emb=48, convs=[(128, 3, 1), (128, 5, 1)], dense=[128], dropout=0.1, emb_dropout=0.0, pool_dropout=0.0,
          loss="ce", label_smoothing=0.0, focal_gamma=0.0, class_weight="sqrt", lang_balance=0.0,
          epochs=30, batch=64, lr=2e-3, schedule="const", aug=0.0, logit_tau=0.0)

RECIPES = {
    "v1": dict(V1),
    # one change at a time, then combinations (screened on the dev split only)
    "v1_cos": dict(V1, schedule="cosine"),
    "v1_ls": dict(V1, label_smoothing=0.1),
    "v1_focal": dict(V1, loss="focal", focal_gamma=2.0),
    "v1_cwfull": dict(V1, class_weight="inv"),
    "v1_aug": dict(V1, aug=0.5),
    "v1_drop": dict(V1, emb_dropout=0.1, pool_dropout=0.2),
    "v1_lang": dict(V1, lang_balance=0.5),
    "v1_e45": dict(V1, epochs=45, schedule="cosine"),
    "deep3": dict(V1, emb=64, convs=[(160, 3), (160, 5), (160, 5)], dense=[160]),
    "deep3d": dict(V1, emb=64, convs=[(160, 3), (160, 5), (160, 5, 2)], dense=[160]),
    "deep4d": dict(V1, emb=64, convs=[(128, 3), (128, 5), (128, 5, 2), (128, 3, 4)], dense=[128]),
    "deep3s": dict(V1, emb=64, convs=[(128, 3), (160, 5, 1, 2), (192, 5, 1, 2)], dense=[160]),
    "wide": dict(V1, emb=64, convs=[(192, 3, 1), (192, 7, 1)], dense=[192]),
    "dense2": dict(V1, dense=[192, 128]),
    # bundles: stable optimisation, then regularisation, then capacity
    "s1": dict(V1, schedule="cosine", ema=0.999, epochs=40),
    "s2": dict(V1, schedule="cosine", ema=0.999, epochs=40, aug=0.5, emb_dropout=0.1, pool_dropout=0.2),
    "s3": dict(V1, schedule="cosine", ema=0.999, epochs=40, aug=0.5, emb_dropout=0.1, pool_dropout=0.2, label_smoothing=0.1),
    "s2_inv": dict(V1, schedule="cosine", ema=0.999, epochs=40, aug=0.5, emb_dropout=0.1, pool_dropout=0.2, class_weight="inv"),
    "s2_d3": dict(V1, schedule="cosine", ema=0.999, epochs=40, aug=0.5, emb_dropout=0.1, pool_dropout=0.2,
                  convs=[(128, 3), (128, 5), (128, 5, 2)]),
    "s2_s3": dict(V1, schedule="cosine", ema=0.999, epochs=40, aug=0.5, emb_dropout=0.1, pool_dropout=0.2,
                  emb=64, convs=[(128, 3), (160, 5, 1, 2), (192, 5, 1, 2)], dense=[160]),
    "s2_w160": dict(V1, schedule="cosine", ema=0.999, epochs=40, aug=0.5, emb_dropout=0.1, pool_dropout=0.2,
                    emb=64, convs=[(160, 3), (160, 7)], dense=[160]),
    # packed ensembles (members trained independently, packed into one block-diagonal graph)
    "s1_k33": dict(V1, schedule="cosine", ema=0.999, epochs=40, convs=[(128, 3), (128, 3)]),
    "s1_f96": dict(V1, schedule="cosine", ema=0.999, epochs=40, convs=[(96, 3), (96, 5)], dense=[96]),
    "s2_k33": dict(V1, schedule="cosine", ema=0.999, epochs=40, aug=0.5, emb_dropout=0.1, pool_dropout=0.2, convs=[(128, 3), (128, 3)]),
    "pk2_k33": dict(V1, members=["s1_k33", "s1_k33"]),
    "pk2_f96": dict(V1, members=["s1_f96", "s1_f96"]),
    "pk2_mix_k33": dict(V1, members=["s1_k33", "s2_k33"]),
}


# ---------------------------------------------------------------- data and splits (identical to train_consentlens.py)
def outer_splits(rows):
    """Returns {"lolo": [test_mask x5], "seen": [test_mask x5]} exactly as ConsentLens-1's script builds them."""
    T.SEED = SPLIT_SEED
    lg = np.array([r["lang_group"] for r in rows])
    lolo = [np.isin(lg, held) for held in T.lang_folds(rows)]
    import random
    texts = sorted({normalize(r["text"]) for r in rows})
    rng = random.Random(SPLIT_SEED)
    rng.shuffle(texts)
    fold_of = {t: i % T.N_LANG_FOLDS for i, t in enumerate(texts)}
    folds = np.array([fold_of[normalize(r["text"])] for r in rows])
    seen = [folds == k for k in range(T.N_LANG_FOLDS)]
    return {"lolo": lolo, "seen": seen}


def dev_split(rows, protocol, outer_test):
    """Inner validation inside outer fold 0's TRAINING rows only. LOLO: 15 of its non-English language groups;
    seen: a quarter of its normalized texts. Returns (train_mask, val_mask) over all rows."""
    import random
    train = ~outer_test
    rng = random.Random(7)
    if protocol == "lolo":
        groups = sorted({rows[i]["lang_group"] for i in np.flatnonzero(train)} - {"en"})
        rng.shuffle(groups)
        val_groups = set(groups[:15])
        val = train & np.array([r["lang_group"] in val_groups for r in rows])
    else:
        texts = sorted({normalize(rows[i]["text"]) for i in np.flatnonzero(train)})
        rng.shuffle(texts)
        val_texts = set(texts[: len(texts) // 4])
        val = train & np.array([normalize(r["text"]) in val_texts for r in rows])
    return train & ~val, val


# ---------------------------------------------------------------- model
class DilatedConv1D(layers.Layer):
    """Conv1D with dilation written as one tf.nn.conv2d call, so the converter emits a single CONV_2D with a
    dilation factor (keras Conv1D(dilation_rate>1) adds SPACE_TO_BATCH_ND / BATCH_TO_SPACE_ND / RELU ops instead)."""

    def __init__(self, filters, kernel, dilation, **kw):
        super().__init__(**kw)
        self.filters, self.kernel, self.dilation = filters, kernel, dilation

    def build(self, input_shape):
        self.w = self.add_weight(name="kernel", shape=(1, self.kernel, input_shape[-1], self.filters), initializer="glorot_uniform")
        self.b = self.add_weight(name="bias", shape=(self.filters,), initializer="zeros")

    def call(self, x):
        y = tf.nn.conv2d(tf.expand_dims(x, 1), self.w, strides=1, padding="SAME", dilations=[1, 1, self.dilation, 1])
        return tf.nn.relu(tf.squeeze(y, 1) + self.b)


def build(r):
    inp = keras.Input(shape=(MAX_BYTES,), dtype="int32", name="utf8_bytes")
    x = T.ByteEmbedding(VOCAB, r["emb"], name="byte_embedding")(inp)
    if r["emb_dropout"]:
        x = layers.SpatialDropout1D(r["emb_dropout"])(x)  # training only, no inference op
    for i, c in enumerate(r["convs"]):
        f, k, d, s = (tuple(c) + (1, 1))[:4]
        if d > 1:
            x = DilatedConv1D(f, k, d, name=f"conv{i}_k{k}_d{d}")(x)
        else:
            x = layers.Conv1D(f, k, strides=s, padding="same", activation="relu", name=f"conv{i}_k{k}_s{s}")(x)
    x = layers.GlobalMaxPooling1D(name="max_pool")(x)
    if r["pool_dropout"]:
        x = layers.Dropout(r["pool_dropout"])(x)
    for j, u in enumerate(r["dense"]):
        x = layers.Dense(u, activation="relu", name=f"dense{j}")(x)
        x = layers.Dropout(r["dropout"])(x)
    out = layers.Dense(NC, activation="softmax", name="role")(x)
    return keras.Model(inp, out, name="consentlens")


def sample_weights(y, lang, r):
    counts = np.bincount(y, minlength=NC).astype(float)
    if r["class_weight"] == "sqrt":  # ConsentLens-1
        w = np.where(counts > 0, np.sqrt(counts.sum() / np.maximum(counts, 1) / NC), 0.0)
    elif r["class_weight"] == "inv":
        w = np.where(counts > 0, counts.sum() / np.maximum(counts, 1) / NC, 0.0)
    else:
        w = np.ones(NC)
    sw = w[y]
    if r["lang_balance"]:
        lc = collections.Counter(lang.tolist())
        lw = np.array([(np.mean(list(lc.values())) / lc[g]) ** r["lang_balance"] for g in lang])
        sw = sw * lw / lw.mean()
    return sw.astype(np.float32)


# ---------------------------------------------------------------- augmentation (on normalized text, then re-encoded)
PUNCT_TAIL = [".", "!", ":", "…", " >", " ›", "?", " »"]


def strip_marks(s):
    d = unicodedata.normalize("NFD", s)
    return unicodedata.normalize("NFC", "".join(c for c in d if unicodedata.category(c) != "Mn"))


def augment_text(t, rng, same_class_pool):
    """One or two light, label-preserving edits: typo-like char edits, diacritics stripped, punctuation and spacing
    changes, or a same-class same-language text appended (notices and OTHER only, never buttons)."""
    ops = rng.integers(1, 3)
    for _ in range(ops):
        k = rng.integers(0, 8)
        n = len(t)
        if k == 0 and n > 3:  # delete a character
            i = rng.integers(0, n); t = t[:i] + t[i + 1:]
        elif k == 1 and n > 1:  # duplicate a character
            i = rng.integers(0, n); t = t[:i] + t[i] + t[i:]
        elif k == 2 and n > 3:  # swap neighbours
            i = rng.integers(0, n - 1); t = t[:i] + t[i + 1] + t[i] + t[i + 2:]
        elif k == 3:
            t = strip_marks(t)
        elif k == 4:  # trailing punctuation on / off
            t = t.rstrip(".!:?…»›> ") if t and t[-1] in ".!:?…»›>" else t + PUNCT_TAIL[rng.integers(0, len(PUNCT_TAIL))]
        elif k == 5 and " " in t:  # remove one space
            idx = [i for i, c in enumerate(t) if c == " "]; i = idx[rng.integers(0, len(idx))]; t = t[:i] + t[i + 1:]
        elif k == 6 and n > 2:  # insert a space
            i = rng.integers(1, n); t = t[:i] + " " + t[i:]
        elif k == 7 and same_class_pool:  # concatenate with another same-class string of the same language
            t = t + " " + same_class_pool[rng.integers(0, len(same_class_pool))]
    return t


class Augmenter:
    def __init__(self, rows, idx, y, rate, seed):
        self.texts = [normalize(rows[i]["text"]) for i in idx]
        self.y = y
        self.rate = rate
        self.rng = np.random.default_rng(seed + 1000)
        pools = collections.defaultdict(list)
        for t, c, i in zip(self.texts, y, idx):
            if ROLES[c] in ("PURPOSE_NOTICE", "WITHDRAW_INFO", "OTHER"):
                pools[(c, rows[i]["lang_group"])].append(t)
        self.pool_of = [pools.get((c, rows[i]["lang_group"]), []) if ROLES[c] in ("PURPOSE_NOTICE", "WITHDRAW_INFO", "OTHER") else []
                        for c, i in zip(y, idx)]
        self.base = np.array([encode(t) for t in self.texts], dtype=np.int32)

    def epoch(self):
        X = self.base.copy()
        pick = np.flatnonzero(self.rng.random(len(self.texts)) < self.rate)
        for j in pick:
            X[j] = encode(augment_text(self.texts[j], self.rng, self.pool_of[j]))
        return X


# ---------------------------------------------------------------- training
class Batches(keras.utils.PyDataset):
    """Shuffled mini-batches with per-row sample weights; the inputs are re-drawn (augmented) after every epoch."""

    def __init__(self, draw, Y, sw, batch, seed):
        super().__init__()
        self.draw, self.Y, self.sw, self.batch = draw, Y, sw, batch
        self.rng = np.random.default_rng(seed + 77)
        self.on_epoch_end()

    def on_epoch_end(self):
        self.X = self.draw()
        self.order = self.rng.permutation(len(self.Y))

    def __len__(self):
        return int(np.ceil(len(self.Y) / self.batch))

    def __getitem__(self, i):
        j = self.order[i * self.batch:(i + 1) * self.batch]
        return self.X[j], self.Y[j], self.sw[j]


class Curve(keras.callbacks.Callback):
    def __init__(self, Xv, yv, every, epochs):
        super().__init__()
        self.Xv, self.yv, self.every, self.epochs, self.points = Xv, yv, every, epochs, []

    def on_epoch_end(self, epoch, logs=None):
        if (epoch + 1) % self.every == 0 or epoch + 1 == self.epochs:
            p = self.model.predict(self.Xv, batch_size=512, verbose=0).argmax(1)
            self.points.append({"epoch": epoch + 1, "val_macro_f1": round(float(T.f1_score(self.yv, p, labels=list(range(NC)))), 4)})


def train_recipe(name, r, rows, idx, X, y, seed, val=None, curve_every=0, soft=None):
    """Trains on rows `idx`. For the exact ConsentLens-1 recipe, calls train_consentlens.train (same code path).
    `soft` (optional, rows x 7) are teacher probabilities for distillation, mixed with the labels by r["kd_alpha"]."""
    T.SEED = seed
    if name == "v1" and not curve_every:
        return T.train(X[idx], y[idx], r["epochs"]), []
    T.set_seed()
    model = build(r)
    steps = int(np.ceil(len(idx) / r["batch"])) * r["epochs"]
    lr = keras.optimizers.schedules.CosineDecay(r["lr"], steps, alpha=0.05) if r["schedule"] == "cosine" else r["lr"]
    if r["loss"] == "focal":
        loss = keras.losses.CategoricalFocalCrossentropy(gamma=r["focal_gamma"], alpha=1.0, label_smoothing=r["label_smoothing"])
    else:
        loss = keras.losses.CategoricalCrossentropy(label_smoothing=r["label_smoothing"])
    opt = keras.optimizers.Adam(lr, use_ema=bool(r.get("ema")), ema_momentum=r.get("ema") or 0.99)
    model.compile(optimizer=opt, loss=loss)
    lang = np.array([rows[i]["lang_group"] for i in idx])
    yt = y[idx]
    Y = np.eye(NC, dtype=np.float32)[yt]
    if soft is not None:
        Y = (r["kd_alpha"] * Y + (1 - r["kd_alpha"]) * soft).astype(np.float32)
    sw = sample_weights(yt, lang, r)
    aug = Augmenter(rows, idx, yt, r["aug"], seed) if r["aug"] else None
    draw = aug.epoch if aug else (lambda: X[idx])
    cbs = [Curve(X[val], y[val], curve_every, r["epochs"])] if (curve_every and val is not None) else []
    model.fit(Batches(draw, Y, sw, r["batch"], seed), epochs=r["epochs"], verbose=0, callbacks=cbs)
    if r["logit_tau"]:
        adjust_bias(model, yt, r["logit_tau"])
    return model, (cbs[0].points if cbs else [])


def block_diag(ws):
    """Block-diagonal stack over the last two axes (input channels, output channels)."""
    lead = ws[0].shape[:-2]
    i, o = ws[0].shape[-2], ws[0].shape[-1]
    out = np.zeros(lead + (i * len(ws), o * len(ws)), dtype=ws[0].dtype)
    for m, w in enumerate(ws):
        out[..., m * i:(m + 1) * i, m * o:(m + 1) * o] = w
    return out


def pack(models, r):
    """Packs M independently trained members of one architecture into ONE wider network with block-diagonal weights
    (a packed ensemble): embeddings concatenated, each conv / dense layer block-diagonal, final layer averages the
    members' logits. Same op types as a single member; the zero blocks stay exact zeros after INT8 quantisation."""
    M = len(models)
    rp = dict(r, emb=r["emb"] * M, convs=[(c[0] * M,) + tuple(c[1:]) for c in r["convs"]], dense=[u * M for u in r["dense"]],
              emb_dropout=0.0, pool_dropout=0.0)
    big = build(rp)
    for layer in big.layers:
        w = layer.get_weights()
        if not w:
            continue
        mw = [m.get_layer(layer.name).get_weights() for m in models]
        if layer.name == "byte_embedding":
            layer.set_weights([np.concatenate([x[0] for x in mw], axis=-1)])
        elif layer.name == "role":
            layer.set_weights([np.concatenate([x[0] for x in mw], axis=0) / M, np.mean([x[1] for x in mw], axis=0)])
        else:
            layer.set_weights([block_diag([x[0] for x in mw]), np.concatenate([x[1] for x in mw])])
    return big


def train_any(name, r, rows, idx, X, y, seed, **kw):
    """A recipe, or a packed ensemble: r["members"] lists one recipe per member (same architecture; member m is trained
    with seed seed*1000+m)."""
    if not r.get("members"):
        return train_recipe(name, r, rows, idx, X, y, seed, **kw)
    members = [train_recipe(mn, RECIPES[mn], rows, idx, X, y, seed * 1000 + m)[0] for m, mn in enumerate(r["members"])]
    return pack(members, RECIPES[r["members"][0]]), []


def adjust_bias(model, y_train, tau):
    """Logit adjustment folded into the last layer's bias (no new op): b_c -= tau * log(prior_c)."""
    prior = np.bincount(y_train, minlength=NC).astype(float) / len(y_train)
    layer = model.get_layer("role")
    k, b = layer.get_weights()
    layer.set_weights([k, b - tau * np.log(np.maximum(prior, 1e-6)).astype(np.float32)])


# ---------------------------------------------------------------- runs
def run_outer(name, seed, work, protocols, folds_only=None):
    r = RECIPES[name]
    rows, X, y = T.load()
    splits = outer_splits(rows)
    os.makedirs(work, exist_ok=True)
    for prot in protocols:
        part = "" if folds_only is None else "_f" + "".join(str(k) for k in sorted(folds_only))
        out_npz = os.path.join(work, f"outer_{prot}_{name}_s{seed}{part}.npz")
        if os.path.exists(out_npz):
            print("exists, skipping", out_npz, flush=True)
            continue
        recs = collections.defaultdict(list)
        fold_stats = []
        for k, te in enumerate(splits[prot]):
            if folds_only is not None and k not in folds_only:
                continue
            t0 = time.time()
            tr_idx = np.flatnonzero(~te)
            model, _ = train_any(name, r, rows, tr_idx, X, y, seed)
            probs_f = model.predict(X[te], batch_size=512, verbose=0)
            T.SEED = seed
            q = T.to_int8(model, X[~te])
            probs_q, _, _ = T.tflite_probs(q, X[te])
            ops = T.tflite_ops(q)
            fp, qp = probs_f.argmax(1), probs_q.argmax(1)
            fold_stats.append({"fold": k, "n_test": int(te.sum()),
                               "float_macro_f1": round(float(T.f1_score(y[te], fp, labels=list(range(NC)))), 4),
                               "int8_macro_f1": round(float(T.f1_score(y[te], qp, labels=list(range(NC)))), 4),
                               "int8_float_agreement": round(float(np.mean(fp == qp)), 4),
                               "tflite_bytes": len(q), "params": int(model.count_params()), "ops": ops,
                               "seconds": round(time.time() - t0, 1)})
            print(prot, name, "seed", seed, fold_stats[-1], flush=True)
            recs["row"].append(np.flatnonzero(te)); recs["fold"].append(np.full(int(te.sum()), k))
            recs["pf"].append(probs_f.astype(np.float32)); recs["pq"].append(probs_q.astype(np.float32))
            keras.backend.clear_session()
        np.savez_compressed(out_npz, **{k: np.concatenate(v) for k, v in recs.items()})
        with open(out_npz.replace(".npz", ".json"), "w", encoding="utf-8") as f:
            json.dump({"recipe": name, "config": r, "seed": seed, "protocol": prot, "folds": fold_stats}, f, indent=1)


def run_dev(name, seed, work, curve_every=5):
    r = RECIPES[name]
    rows, X, y = T.load()
    splits = outer_splits(rows)
    os.makedirs(work, exist_ok=True)
    res = {"recipe": name, "config": r, "seed": seed}
    for prot in ("lolo", "seen"):
        tr, va = dev_split(rows, prot, splits[prot][0])
        assert not (va & splits[prot][0]).any()
        t0 = time.time()
        model, curve = train_any(name, r, rows, np.flatnonzero(tr), X, y, seed, val=va, curve_every=curve_every)
        probs = model.predict(X[va], batch_size=512, verbose=0)
        p = probs.argmax(1)
        res[prot] = {"n_train": int(tr.sum()), "n_val": int(va.sum()),
                     "val_macro_f1": round(float(T.f1_score(y[va], p, labels=list(range(NC)))), 4),
                     "val_per_class_f1": [round(float(v), 4) for v in T.f1_score(y[va], p, labels=list(range(NC)), average=None)],
                     "curve": curve, "params": int(model.count_params()), "seconds": round(time.time() - t0, 1)}
        np.savez_compressed(os.path.join(work, f"dev_{prot}_{name}_s{seed}.npz"), row=np.flatnonzero(va), pf=probs.astype(np.float32))
        print("dev", prot, name, seed, res[prot]["val_macro_f1"], res[prot]["curve"], flush=True)
        keras.backend.clear_session()
    with open(os.path.join(work, f"dev_{name}_s{seed}.json"), "w", encoding="utf-8") as f:
        json.dump(res, f, indent=1)


def export(name, seed, out):
    """Trains the recipe on all rows and writes the full-INT8 file plus a JSON with ops, size and agreement."""
    r = RECIPES[name]
    rows, X, y = T.load()
    model, _ = train_any(name, r, rows, np.arange(len(rows)), X, y, seed)
    T.SEED = seed
    q = T.to_int8(model, X)
    with open(out, "wb") as f:
        f.write(q)
    model.save(out.replace(".tflite", ".keras"))
    probs_q, scale, zp = T.tflite_probs(q, X)
    probs_f = model.predict(X, batch_size=512, verbose=0)
    info = {"recipe": name, "seed": seed, "bytes": len(q), "sha256": hashlib.sha256(q).hexdigest(),
            "params": int(model.count_params()), "ops": T.tflite_ops(q), "output_quantization": [scale, zp],
            "int8_vs_float_agreement_on_training_rows": round(float(np.mean(probs_q.argmax(1) == probs_f.argmax(1))), 4)}
    print(json.dumps(info), flush=True)
    with open(out.replace(".tflite", ".json"), "w", encoding="utf-8") as f:
        json.dump(info, f, indent=1)


def ship(tflite_path, model_id, threshold, summary_path, cand):
    """Installs an exported file as the app asset and rewrites consentlens_meta.json and the Kotlin golden vectors
    (encode cases are unchanged; decode cases come from the new file), like train_consentlens.export does for v1."""
    rows, X, y = T.load()
    q = open(tflite_path, "rb").read()
    S = json.load(open(summary_path, encoding="utf-8"))
    probs_q, scale, zp = T.tflite_probs(q, X[:8])
    ops = T.tflite_ops(q)
    info = json.load(open(tflite_path.replace(".tflite", ".json"), encoding="utf-8"))
    os.makedirs(T.ASSET_DIR, exist_ok=True)
    with open(os.path.join(T.ASSET_DIR, "consentlens_int8.tflite"), "wb") as f:
        f.write(q)
    seen = {normalize(r["text"]) for r in rows}
    dx = np.array([encode(t) for _, t, _ in T.DEMO], dtype=np.int32)
    dprobs, _, _ = T.tflite_probs(q, dx)
    demo = [{"lang": l, "text": t, "expected": e, "in_training_data": normalize(t) in seen,
             "reference_role": ROLES[int(p.argmax())], "reference_confidence": round(float(p.max()), 4)}
            for (l, t, e), p in zip(T.DEMO, dprobs)]
    meta = {
        "model_id": model_id, "file": "consentlens_int8.tflite", "sha256": hashlib.sha256(q).hexdigest(), "bytes": len(q),
        "roles": ROLES, "max_bytes": MAX_BYTES, "input": "int32[1,128] UTF-8 byte ids (0 = padding)",
        "output": {"type": "int8[1,7] softmax", "scale": scale, "zero_point": zp},
        "tflite_ops": ops, "op_count": len(ops), "params": info["params"],
        "train_rows": int(len(rows)), "languages": len({r["lang_group"] for r in rows}),
        "default_threshold": threshold,
        "held_out_language_macro_f1_int8": S["lolo"][cand]["int8_macro_f1"]["mean"],
        "held_out_language_macro_f1_lexicon": S["lolo"]["lexicon_v1_port_macro_f1"],
        "demo": demo,
    }
    with open(os.path.join(T.ASSET_DIR, "consentlens_meta.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(meta, f, ensure_ascii=False, indent=1)
    golden = json.load(open(T.GOLDEN, encoding="utf-8"))
    golden["decode"] = {"scale": scale, "zero_point": zp, "roles": ROLES,
                        "cases": [{"int8": [int(round(v / scale + zp)) for v in p], "role": ROLES[int(p.argmax())]} for p in dprobs[:6]]}
    with open(T.GOLDEN, "w", encoding="utf-8", newline="\n") as f:
        json.dump(golden, f, ensure_ascii=False, indent=1)
    print(json.dumps({k: v for k, v in meta.items() if k != "demo"}, ensure_ascii=False))
    print("demo matches expected:", sum(d["reference_role"] == d["expected"] for d in demo), "/", len(demo))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["run", "dev", "export", "ship"])
    ap.add_argument("--model-id", default="ConsentLens-2")
    ap.add_argument("--threshold", type=float, default=None)
    ap.add_argument("--summary", default=None)
    ap.add_argument("--recipe", default="v1")
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--protocols", default="lolo,seen")
    ap.add_argument("--folds", default=None)
    ap.add_argument("--work", default=DEFAULT_WORK)
    ap.add_argument("--out", default=None)
    ap.add_argument("--threads", type=int, default=0)
    a = ap.parse_args()
    if a.threads:
        tf.config.threading.set_intra_op_parallelism_threads(a.threads)
        tf.config.threading.set_inter_op_parallelism_threads(2)
    if a.cmd == "run":
        run_outer(a.recipe, a.seed, a.work, a.protocols.split(","), None if a.folds is None else [int(v) for v in a.folds.split(",")])
    elif a.cmd == "dev":
        run_dev(a.recipe, a.seed, a.work)
    elif a.cmd == "export":
        export(a.recipe, a.seed, a.out)
    else:
        ship(a.out, a.model_id, a.threshold, a.summary, a.recipe)


if __name__ == "__main__":
    main()
