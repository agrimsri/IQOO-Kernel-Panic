"""Applies the v2 ship criterion to the measured numbers and writes reports/v2/decision.json.

    python -I ml/consentlens/decide_v2.py --cand <recipe> [--export reports/v2/export_<recipe>.json]

Ship only if ALL hold (same folds and protocol as ConsentLens-1, INT8 files, mean over 3 training seeds):
  * macro-F1 improves by >= 0.03 absolute on BOTH the seen-language and the held-out-language protocol;
  * no role's F1 drops by more than 0.05 (either protocol);
  * the candidate's TFLite op types are a subset of v1's;
  * INT8 vs float agreement >= 98% on the test folds (every fold, every seed);
  * file size <= 400 KB.
"""
import argparse
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
V2 = os.path.join(HERE, "reports", "v2")
ROLES = ["ACCEPT", "REJECT", "MANAGE", "PURPOSE_NOTICE", "PRIVACY_LINK", "WITHDRAW_INFO", "OTHER"]
V1_OP_TYPES = {"GATHER", "EXPAND_DIMS", "CONV_2D", "RESHAPE", "REDUCE_MAX", "FULLY_CONNECTED", "SOFTMAX"}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cand", required=True)
    ap.add_argument("--export", default=None)
    a = ap.parse_args()
    S = json.load(open(os.path.join(V2, f"outer_v1_vs_{a.cand}.json"), encoding="utf-8"))
    exp = json.load(open(a.export, encoding="utf-8")) if a.export else None
    crit = {}
    for p, name in (("seen", "seen languages"), ("lolo", "held-out languages")):
        d = S[p][a.cand]["int8_macro_f1"]["mean"] - S[p]["v1"]["int8_macro_f1"]["mean"]
        crit[f"Macro-F1 gain >= 0.03, {name}"] = {"value": f"{d:+.3f}", "pass": d >= 0.03, "delta": round(d, 4)}
    worst = min((S[p][a.cand]["per_class_f1_mean"][r] - S[p]["v1"]["per_class_f1_mean"][r], p, r) for p in ("seen", "lolo") for r in ROLES)
    crit["No role's F1 drops by more than 0.05"] = {"value": f"worst {worst[0]:+.3f} ({worst[2]}, {'seen' if worst[1] == 'seen' else 'held-out'})",
                                                    "pass": worst[0] >= -0.05}
    ops = set(S["lolo"][a.cand]["ops"]) | set(S["seen"][a.cand]["ops"]) | (set(exp["ops"]) if exp else set())
    new = sorted(ops - V1_OP_TYPES)
    crit["Op types are a subset of v1's"] = {"value": "no new op type" if not new else "new: " + ", ".join(new), "pass": not new}
    lo = min(sd["int8_float_agreement"] for p in ("seen", "lolo") for sd in S[p][a.cand]["per_seed"].values())
    fmin = min(S[p][a.cand]["min_fold_int8_float_agreement"] for p in ("seen", "lolo"))
    crit["INT8 vs float agreement >= 98%"] = {"value": f"{100 * lo:.1f}% lowest seed and protocol (lowest single fold {100 * fmin:.1f}%)",
                                              "pass": lo >= 0.98}
    size = max(S["lolo"][a.cand]["tflite_bytes"] + ([exp["bytes"]] if exp else []))
    crit["Size <= 400 KB"] = {"value": f"{size:,} bytes", "pass": size <= 400 * 1024}
    shipped = all(v["pass"] for v in crit.values())
    out = {"cand": a.cand, "shipped": shipped, "criteria": crit, "worst_role_drop": worst[0]}
    with open(os.path.join(V2, "decision_criteria.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(out, f, indent=1)
    for k, v in crit.items():
        print(("PASS " if v["pass"] else "FAIL ") + k + ": " + v["value"])
    print("SHIP" if shipped else "KEEP v1")


if __name__ == "__main__":
    main()
