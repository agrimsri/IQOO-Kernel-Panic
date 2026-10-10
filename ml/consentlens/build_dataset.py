"""Builds the ConsentLens dataset from openly licensed, real UI strings.

Every example keeps its provenance: source, upstream URL at a pinned revision, licence, string key and language.
Raw downloads are cached outside Git (default D:/DevTools/dl/consentlens-raw, override with --raw).

    python -I ml/consentlens/build_dataset.py --raw <cache dir>

Writes ml/consentlens/data/consentlens_dataset.jsonl (one example per line: text, role, lang, lang_group, source, file,
key) and ml/consentlens/data/sources.json (per-source pinned URL, licence, revision, notice, and build statistics).
Label rules are documented in ml/consentlens/MODEL_CARD.md ("Labels").
"""
import argparse
import base64
import collections
import json
import os
import random
import re
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from consentlens_text import ROLES, normalize  # noqa: E402

import yaml  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
OUT_DIR = os.path.join(HERE, "data")

# ---------------------------------------------------------------- sources (pinned revisions)
AOSP_COMMIT = "26231a7eac744c7d2a8fea4740926620f56b20c6"  # platform/packages/modules/Permission, refs/heads/main on 10 Oct 2026
AOSP_BASE = "https://android.googlesource.com/platform/packages/modules/Permission/+/" + AOSP_COMMIT + "/PermissionController/res"
FW_COMMIT = "1cdfff555f4a21f71ccc978290e2e212e2f8b168"  # platform/frameworks/base, refs/heads/main on 10 Oct 2026
FW_BASE = "https://android.googlesource.com/platform/frameworks/base/+/" + FW_COMMIT + "/core/res/res"
KLARO_COMMIT = "db9f1aca906a392990c58c58475366a091012ea7"  # kiprotect/klaro master, 2025-03-27
KLARO_BASE = "https://raw.githubusercontent.com/kiprotect/klaro/" + KLARO_COMMIT + "/src/translations"
TAC_COMMIT = "07254bb6ff2858e2ab6a9b45c391d655a55a9c54"  # AmauriC/tarteaucitron.js master, 2026-10-08
TAC_BASE = "https://raw.githubusercontent.com/AmauriC/tarteaucitron.js/" + TAC_COMMIT + "/lang"

SOURCES = {
    "aosp-permissioncontroller": {
        "url": "https://android.googlesource.com/platform/packages/modules/Permission/+/" + AOSP_COMMIT + "/PermissionController/res/",
        "licence": "Apache-2.0", "revision": AOSP_COMMIT, "date": "2025-03-24 (commit date)",
        "notice": "Copyright (C) The Android Open Source Project. Licensed under the Apache License, Version 2.0.",
    },
    "aosp-framework": {
        "url": "https://android.googlesource.com/platform/frameworks/base/+/" + FW_COMMIT + "/core/res/res/",
        "licence": "Apache-2.0", "revision": FW_COMMIT, "date": "refs/heads/main on 2026-10-10",
        "notice": "Copyright (C) The Android Open Source Project. Licensed under the Apache License, Version 2.0.",
    },
    "klaro": {
        "url": "https://github.com/kiprotect/klaro/tree/" + KLARO_COMMIT + "/src/translations",
        "licence": "BSD-3-Clause", "revision": KLARO_COMMIT, "date": "2025-03-27",
        "notice": "Copyright (c) 2019-2020, KIProtect GmbH and Klaro contributors. BSD 3-Clause License.",
    },
    "tarteaucitron": {
        "url": "https://github.com/AmauriC/tarteaucitron.js/tree/" + TAC_COMMIT + "/lang",
        "licence": "MIT", "revision": TAC_COMMIT, "date": "2026-10-08",
        "notice": "Copyright (c) Amauri CHAMPEAUX and tarteaucitron.js contributors. MIT License.",
    },
    "traceveil-lexicon": {
        "url": "core/src/main/kotlin/com/dpdpxray/core/consent/ConsentLexicon.kt and core/.../screen/ScreenAuditing.kt",
        "licence": "MIT (this repository)", "revision": "repository HEAD", "date": "2026-10-10",
        "notice": "TraceVeil's own deterministic lexicon (EN/HI/KN).",
    },
    "traceveil-leakyshop": {
        "url": "leakyshop/src/main/java/com/leakyshop/demo/Shop.kt",
        "licence": "MIT (this repository)", "revision": "repository HEAD", "date": "2026-10-10",
        "notice": "Consent dialog strings of TraceVeil's controlled demo app (leaky and fixed flavours).",
    },
}

# ---------------------------------------------------------------- label maps (see MODEL_CARD.md "Labels")
# AOSP PermissionController keys. Roles are defined for a string that appears on a consent or permission screen.
AOSP_KEYS = {
    # grant choices that give access
    "grant_dialog_button_allow": "ACCEPT",
    "grant_dialog_button_allow_all": "ACCEPT",
    "grant_dialog_button_always_allow_all": "ACCEPT",
    "grant_dialog_button_allow_always": "ACCEPT",
    "grant_dialog_button_allow_foreground": "ACCEPT",
    "grant_dialog_button_allow_one_time": "ACCEPT",
    "grant_dialog_button_allow_limited_access": "ACCEPT",
    "app_permission_button_allow": "ACCEPT",
    "app_permission_button_allow_foreground": "ACCEPT",
    "permission_access_only_foreground": "ACCEPT",
    "incident_report_dialog_allow_label": "ACCEPT",
    "ongoing_usage_dialog_ok": "ACCEPT",  # "Got it" (the lexicon also treats it as accept)
    # refusals
    "grant_dialog_button_deny": "REJECT",
    "grant_dialog_button_deny_and_dont_ask_again": "REJECT",
    "grant_dialog_button_deny_anyway": "REJECT",
    "deny_permission_deny_and_dont_ask_again": "REJECT",
    "app_permission_button_deny": "REJECT",
    "incident_report_dialog_deny_label": "REJECT",
    # settings / more choices
    "settings": "MANAGE",
    "settings_button": "MANAGE",
    "go_to_settings": "MANAGE",
    "manage_permission": "MANAGE",
    "adjust_user_sensitive_title": "MANAGE",
    "location_settings": "MANAGE",
    # data-use / purpose descriptions and purpose names
    "permission_rationale_message_location": "PURPOSE_NOTICE",
    "app_location_permission_rationale_subtitle": "PURPOSE_NOTICE",
    "app_location_permission_rationale_title": "PURPOSE_NOTICE",
    "permission_rationale_location_purpose_title": "PURPOSE_NOTICE",
    "data_sharing_updates_subtitle": "PURPOSE_NOTICE",
    "permission_rationale_data_sharing_device_manufacturer_message": "PURPOSE_NOTICE",
    "permission_rationale_data_sharing_varies_message_without_link": "PURPOSE_NOTICE",
    "permission_rationale_purpose_app_functionality": "PURPOSE_NOTICE",
    "permission_rationale_purpose_analytics": "PURPOSE_NOTICE",
    "permission_rationale_purpose_developer_communications": "PURPOSE_NOTICE",
    "permission_rationale_purpose_advertising": "PURPOSE_NOTICE",
    "permission_rationale_purpose_fraud_prevention_security": "PURPOSE_NOTICE",
    "permission_rationale_purpose_personalization": "PURPOSE_NOTICE",
    "permission_rationale_purpose_account_management": "PURPOSE_NOTICE",
    # links to privacy / data-sharing information
    "learn_about_data_sharing": "PRIVACY_LINK",
    "permission_rationale_data_sharing_source_title": "PRIVACY_LINK",
    # how to change access later
    "permission_rationale_permission_settings_message": "WITHDRAW_INFO",
    "safety_center_issue_card_dismiss_confirmation_message": "WITHDRAW_INFO",
}
# Deliberately excluded as ambiguous on a consent screen (documented): ok, cancel, review_button_continue ("Continue"),
# grant_dialog_button_more_info / *learn_more ("More info", "Learn more": could open settings or the privacy notice),
# grant_dialog_button_dismiss ("Dismiss"), permission_access_never (status label "Don't allow" duplicate).
AOSP_EXCLUDE_KEYS = {"ok", "cancel", "review_button_cancel", "review_button_continue", "grant_dialog_button_more_info",
                     "permission_usage_access_dialog_learn_more", "enhanced_confirmation_dialog_learn_more",
                     "grant_dialog_button_dismiss", "permission_access_never"}
# English-text words that keep a key out of the OTHER (negative) pool, so negatives are not consent-like.
OTHER_BLOCK = re.compile(
    r"allow|deny|denied|don.t|revoke|privacy|private|data|consent|shar|setting|manage|polic|learn|accept|agree|decline|"
    r"reject|track|cookie|^ok$|cancel|got it|continue|dismiss|close|info|purpose|personal|collect|permission|access|"
    r"remov|withdraw|opt|choose|choice|review|control|later|any ?time|\bno\b|\byes\b|always|never|only|keep|select|"
    r"confirm|ask|back|retry|disable|enable|block|change|%|\$|<|\{|_|http", re.I)
OTHER_KEYS_PER_LANGUAGE = 70

# Android framework (core/res) dialog buttons, plus generic UI strings as OTHER negatives.
FW_KEYS = {
    "accept": "ACCEPT",
    "allow": "ACCEPT",
    "sms_control_yes": "ACCEPT",
    "accessibility_dialog_button_allow": "ACCEPT",
    "decline": "REJECT",
    "deny": "REJECT",
    "sms_control_no": "REJECT",
    "accessibility_dialog_button_deny": "REJECT",
    "autofill_save_no": "REJECT",  # "No thanks" (the lexicon treats it as a refusal)
    "autofill_save_notnow": "REJECT",  # "Not now"
}
FW_OTHER_KEYS_PER_LANGUAGE = 120

KLARO_KEYS = {
    "acceptAll": "ACCEPT",
    "acceptSelected": "ACCEPT",  # confirms the user's own selection; an affirmative consent action
    "ok": "ACCEPT",
    "contextualConsent.acceptOnce": "ACCEPT",
    "decline": "REJECT",
    "consentNotice.learnMore": "MANAGE",  # "Let me choose"
    "contextualConsent.modalLinkText": "MANAGE",  # "Consent Manager"
    "privacyPolicy.name": "PRIVACY_LINK",
    "privacyPolicy.text": "PRIVACY_LINK",  # rendered with {privacyPolicy} = privacyPolicy.name, as Klaro does
    "purposes.advertising.title": "PURPOSE_NOTICE",
    "purposes.advertising.description": "PURPOSE_NOTICE",
    "purposes.marketing.title": "PURPOSE_NOTICE",
    "purposes.marketing.description": "PURPOSE_NOTICE",
    "purposes.performance.title": "PURPOSE_NOTICE",
    "purposes.performance.description": "PURPOSE_NOTICE",
    "purposes.functional.title": "PURPOSE_NOTICE",
    "purposes.functional.description": "PURPOSE_NOTICE",
    "poweredBy": "OTHER",
    "consentNotice.testing": "OTHER",
}
# consentNotice.description ("Hi! Could we please enable some additional services for {purposes}? You can always change
# or withdraw your consent later.") is split at sentence ends when it has two or more sentences: the last sentence
# -> WITHDRAW_INFO, the sentences before it (with {purposes} rendered) -> PURPOSE_NOTICE. One-sentence translations
# are skipped. Every split is listed in sources.json for review.
KLARO_SPLIT_KEY = "consentNotice.description"
# Languages whose split was read and confirmed (10 Oct 2026): the last sentence says consent can be changed/withdrawn
# later. cs (closing quote split off) and ro (different text, split at an abbreviation) are excluded.
KLARO_SPLIT_REVIEWED = {"bg", "de", "en", "es", "fr", "gl", "it", "nl", "oc", "pl", "pt", "zh"}

TAC_KEYS = {
    "acceptAll": "ACCEPT",
    "allow": "ACCEPT",
    "allowAll": "ACCEPT",
    "deny": "REJECT",
    "denyAll": "REJECT",
    "alertSmall": "MANAGE",  # "Manage services"
    "personalize": "MANAGE",
    "alertBigPrivacy": "PURPOSE_NOTICE",
    "disclaimer": "PURPOSE_NOTICE",
    "ads.title": "PURPOSE_NOTICE",
    "ads.details": "PURPOSE_NOTICE",
    "analytic.title": "PURPOSE_NOTICE",
    "analytic.details": "PURPOSE_NOTICE",
    "social.details": "PURPOSE_NOTICE",
    "google.details": "PURPOSE_NOTICE",
    "mandatoryText": "PURPOSE_NOTICE",
    "privacyUrl": "PRIVACY_LINK",
    "credit": "OTHER",
    "reload": "OTHER",
    "source": "OTHER",
    "adblock_call": "OTHER",
    "newWindow": "OTHER",
    "modalWindow": "OTHER",
}
# tarteaucitron file codes that are not ISO 639-1 language codes.
TAC_LANG = {"cn": "zh", "se": "sv"}  # cn.js duplicates zh.js; se.js is Swedish text

# TraceVeil's demo app strings (must exist verbatim in Shop.kt).
LEAKYSHOP = {
    "Accept & Continue": "ACCEPT",
    "Reject all": "REJECT",
    "Manage": "MANAGE",
    "We use your data to personalise offers, measure the app and show relevant ads.": "PURPOSE_NOTICE",
    "Share usage data to improve offers": "PURPOSE_NOTICE",
    "Personalised offers, analytics and advertising partners are enabled to give you the best experience.": "PURPOSE_NOTICE",
    "We collect your email, phone number and order history to deliver orders. The choices below are optional.": "PURPOSE_NOTICE",
    "Analytics to improve the app": "PURPOSE_NOTICE",
    "Personalised offers": "PURPOSE_NOTICE",
    "You can withdraw consent anytime in Settings › Privacy, or complain to the Data Protection Board.": "WITHDRAW_INFO",
    "हम आपका ईमेल, फ़ोन नंबर और ऑर्डर इतिहास सिर्फ़ डिलीवरी के लिए लेते हैं। नीचे दिए विकल्प वैकल्पिक हैं।": "PURPOSE_NOTICE",
    "ऐप सुधार के लिए एनालिटिक्स": "PURPOSE_NOTICE",
    "निजी ऑफ़र": "PURPOSE_NOTICE",
    "आप सेटिंग्स › गोपनीयता में कभी भी सहमति वापस ले सकते हैं।": "WITHDRAW_INFO",
    "We value your privacy": "OTHER",
}


def fetch(url, cache_path, gitiles=False):
    if not os.path.exists(cache_path):
        os.makedirs(os.path.dirname(cache_path), exist_ok=True)
        req = urllib.request.Request(url + ("?format=TEXT" if gitiles else ""), headers={"User-Agent": "consentlens-data"})
        for attempt in range(8):
            try:
                time.sleep(1.5)  # be polite: gitiles rate-limits bursts (HTTP 429)
                with urllib.request.urlopen(req, timeout=60) as r:
                    data = r.read()
                break
            except urllib.error.HTTPError as e:
                if e.code == 404:
                    return None
                if e.code != 429 or attempt == 7:
                    raise
                time.sleep(15 * (attempt + 1))
        if gitiles:
            data = base64.b64decode(data)
        with open(cache_path, "wb") as f:
            f.write(data)
    with open(cache_path, "rb") as f:
        return f.read().decode("utf-8")


def android_unescape(s):
    s = s.strip()
    if len(s) >= 2 and s[0] == '"' and s[-1] == '"':
        s = s[1:-1]
    s = re.sub(r"\\u([0-9a-fA-F]{4})", lambda m: chr(int(m.group(1), 16)), s)
    s = s.replace("\\n", " ").replace("\\t", " ").replace("\\'", "'").replace('\\"', '"').replace("\\@", "@").replace("\\?", "?")
    return s.replace("\\\\", "\\")


def parse_strings_xml(text):
    root = ET.fromstring(text)
    out = {}
    for el in root.iter("string"):
        name = el.get("name")
        if el.get("translatable") == "false":
            continue
        out[name] = android_unescape("".join(el.itertext()))
    return out


def aosp_locale(dirname):
    q = dirname[len("values-"):]
    if q.startswith("b+"):
        return "-".join(q[2:].split("+"))
    return q.replace("-r", "-")


AOSP_SKIP = {"en-XC", "en-XA", "ar-XB"}  # pseudo-locales


def build_android(raw, source, base, cache, keymap, exclude, n_other):
    listing = fetch(base + "?format=JSON", os.path.join(raw, cache, "listing.json"))
    entries = json.loads(listing[listing.index("{"):])["entries"]
    dirs = ["values"] + sorted(
        e["name"] for e in entries
        if e["name"].startswith("values-") and re.fullmatch(r"values-(b\+[A-Za-z+]+|[a-z]{2,3}(-r[A-Z]{2})?)", e["name"])
    )
    per_lang = {}
    for d in dirs:
        loc = "en" if d == "values" else aosp_locale(d)
        if loc in AOSP_SKIP:
            continue
        txt = fetch(f"{base}/{d}/strings.xml", os.path.join(raw, cache, d, "strings.xml"), gitiles=True)
        if txt is None:
            continue
        per_lang[loc] = parse_strings_xml(txt)
    en = per_lang["en"]
    eligible = sorted(
        k for k, v in en.items()
        if k not in keymap and k not in exclude and 3 <= len(v) <= 120 and not OTHER_BLOCK.search(v)
        and not re.search(r"(_one|_two|_few|_many|_other|_zero)$", k)
    )
    rng = random.Random(42)
    other_keys = set(rng.sample(eligible, min(n_other, len(eligible))))
    rows = []
    for loc, strings in per_lang.items():
        for key, text in strings.items():
            role = keymap.get(key) or ("OTHER" if key in other_keys else None)
            if role is None or not text.strip():
                continue
            rows.append(row(source, f"{base}/{dir_for(loc)}/strings.xml", key, loc, text, role))
    return rows, sorted(other_keys)


def build_aosp(raw):
    return build_android(raw, "aosp-permissioncontroller", AOSP_BASE, "aosp", AOSP_KEYS, AOSP_EXCLUDE_KEYS, OTHER_KEYS_PER_LANGUAGE)


def build_framework(raw):
    return build_android(raw, "aosp-framework", FW_BASE, "framework", FW_KEYS, set(), FW_OTHER_KEYS_PER_LANGUAGE)


def dir_for(loc):
    if loc == "en":
        return "values"
    if loc.count("-") == 1 and len(loc.split("-")[1]) == 2 and loc.split("-")[1].isupper():
        a, b = loc.split("-")
        return f"values-{a}-r{b}"
    if "-" in loc:
        return "values-b+" + loc.replace("-", "+")
    return "values-" + loc


def flatten(d, prefix=""):
    out = {}
    for k, v in d.items():
        key = f"{prefix}{k}"
        if isinstance(v, dict):
            out.update(flatten(v, key + "."))
        elif isinstance(v, str):
            out[key] = " ".join(v.split())
    return out


SENTENCE = re.compile(r"(?<=[.?!。？！])\s*")


def build_klaro(raw):
    names = ["bg.yaml", "ca.yml", "cs.yml", "da.yml", "de.yml", "el.yml", "en.yml", "es.yml", "fi.yml", "fr.yml", "gl.yml",
             "hr.yml", "hu.yml", "it.yml", "nl.yml", "no.yml", "oc.yml", "pl.yml", "pt.yml", "ro.yml", "ru.yml", "sr.yml",
             "sr_cyrl.yml", "sv.yml", "tr.yml", "zh.yml"]
    rows, skipped = [], []
    for name in names:
        url = f"{KLARO_BASE}/{name}"
        txt = fetch(url, os.path.join(raw, "klaro", name))
        lang = name.split(".")[0].replace("_cyrl", "-Cyrl")
        try:
            flat = flatten(yaml.safe_load(txt))
        except yaml.YAMLError as e:  # upstream file that strict YAML cannot parse: skipped, not hand-repaired
            skipped.append((lang, "*", f"YAML parse error: {str(e).splitlines()[0]}"))
            continue
        for key, role in KLARO_KEYS.items():
            text = flat.get(key)
            if not text:
                continue
            if "{privacyPolicy}" in text:
                text = text.replace("{privacyPolicy}", flat.get("privacyPolicy.name", ""))
            if "{" in text:
                skipped.append((lang, key, "placeholder"))
                continue
            rows.append(row("klaro", url, key, lang, text, role))
        desc = flat.get(KLARO_SPLIT_KEY, "")
        parts = [p for p in SENTENCE.split(desc) if p.strip()]
        if len(parts) >= 2 and lang in KLARO_SPLIT_REVIEWED:
            purposes = ", ".join(t for t in (flat.get("purposes.marketing.title"), flat.get("purposes.advertising.title")) if t)
            first = " ".join(parts[:-1]).replace("{purposes}", purposes)
            parts = [first, parts[-1]]
            rows.append(row("klaro", url, KLARO_SPLIT_KEY + "#1", lang, first, "PURPOSE_NOTICE"))
            rows.append(row("klaro", url, KLARO_SPLIT_KEY + "#2", lang, parts[1], "WITHDRAW_INFO"))
        else:
            skipped.append((lang, KLARO_SPLIT_KEY, f"{len(parts)} sentences, split not reviewed"))
    return rows, skipped


def parse_tac(js):
    body = js[js.index("{"): js.rindex("}") + 1]
    body = re.sub(r"/\*.*?\*/", "", body, flags=re.S)
    body = re.sub(r'(?m)^\s*//.*$', "", body)
    body = re.sub(r",(\s*[}\]])", r"\1", body)
    return json.loads(body)


def build_tac(raw):
    codes = ["ar", "bg", "ca", "cn", "cs", "da", "de", "el", "en", "es", "et", "fi", "fr", "hr", "hu", "it", "ja", "ko", "lb",
             "lt", "lv", "nl", "no", "oc", "pl", "pt", "ro", "ru", "se", "sk", "sq", "sv", "tr", "uk", "vi", "zh"]
    rows, skipped = [], []
    for code in codes:
        url = f"{TAC_BASE}/tarteaucitron.{code}.js"
        txt = fetch(url, os.path.join(raw, "tarteaucitron", f"tarteaucitron.{code}.js"))
        try:
            flat = flatten(parse_tac(txt))
        except Exception as e:  # noqa: BLE001
            skipped.append((code, "*", f"parse error {e}"))
            continue
        lang = TAC_LANG.get(code, code)
        for key, role in TAC_KEYS.items():
            text = flat.get(key)
            if text:
                rows.append(row("tarteaucitron", url, key, lang, text, role))
    return rows, skipped


def build_lexicon():
    lex = open(os.path.join(REPO, "core/src/main/kotlin/com/dpdpxray/core/consent/ConsentLexicon.kt"), encoding="utf-8").read()
    aud = open(os.path.join(REPO, "core/src/main/kotlin/com/dpdpxray/core/screen/ScreenAuditing.kt"), encoding="utf-8").read()
    rows = []

    def phrases(src, name):
        m = re.search(name + r"\s*=\s*listOf\((.*?)\)", src, re.S)
        return re.findall(r'"([^"]+)"', m.group(1))

    def lang_of(t):
        if re.search(r"[\u0900-\u097f]", t):
            return "hi"
        if re.search(r"[\u0c80-\u0cff]", t):
            return "kn"
        return "en"

    url = SOURCES["traceveil-lexicon"]["url"]
    for name, role in (("REJECT", "REJECT"), ("SETTINGS", "MANAGE"), ("ACCEPT", "ACCEPT")):
        for p in phrases(lex, "private val " + name):
            if p in ("ok", "okay", "continue", "yes", "नहीं", "settings", "preferences"):
                continue  # single generic words: ambiguous outside a consent screen; kept out (see MODEL_CARD.md)
            rows.append(row("traceveil-lexicon", url, f"{name}:{p}", lang_of(p), p, role))
    for p in phrases(aud, "private val WITHDRAW_CUES"):
        rows.append(row("traceveil-lexicon", url, f"WITHDRAW_CUES:{p}", lang_of(p), p, "WITHDRAW_INFO"))
    return rows


def build_leakyshop():
    src = open(os.path.join(REPO, "leakyshop/src/main/java/com/leakyshop/demo/Shop.kt"), encoding="utf-8").read()
    rows = []
    for text, role in LEAKYSHOP.items():
        assert text in src, f"LeakyShop string not found verbatim: {text}"
        lang = "hi" if re.search(r"[\u0900-\u097f]", text) else "en"
        rows.append(row("traceveil-leakyshop", SOURCES["traceveil-leakyshop"]["url"], "Shop.kt", lang, text, role))
    return rows


def row(source, url, key, lang, text, role):
    assert role in ROLES, role
    group = lang.split("-")[0].lower()
    group = {"nb": "no", "in": "id", "iw": "he"}.get(group, group)
    return {"text": " ".join(text.split()), "role": role, "lang": lang, "lang_group": group,
            "source": source, "file": _relative(url), "key": key}


def _relative(url):
    """Upstream file relative to its source's pinned base (full URL, licence and revision are in sources.json)."""
    for base in (AOSP_BASE + "/", FW_BASE + "/", KLARO_BASE + "/", TAC_BASE + "/"):
        if url.startswith(base):
            return url[len(base):]
    return url


def dedupe(rows):
    """Within a language group, identical normalized text keeps one row; conflicting roles drop the text entirely."""
    by = collections.defaultdict(list)
    for r in rows:
        by[(r["lang_group"], normalize(r["text"]))].append(r)
    kept, conflicts = [], []
    for (lg, norm), rs in by.items():
        roles = {r["role"] for r in rs}
        if not norm:
            continue
        if len(roles) > 1:
            conflicts.append({"lang_group": lg, "text": rs[0]["text"], "roles": sorted(roles),
                              "keys": sorted({f"{r['source']}:{r['key']}" for r in rs})})
            continue
        kept.append(rs[0])
    return kept, conflicts


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--raw", default="D:/DevTools/dl/consentlens-raw")
    a = ap.parse_args()
    aosp, other_keys = build_aosp(a.raw)
    framework, fw_other_keys = build_framework(a.raw)
    klaro, klaro_skipped = build_klaro(a.raw)
    tac, tac_skipped = build_tac(a.raw)
    rows = aosp + framework + klaro + tac + build_lexicon() + build_leakyshop()
    kept, conflicts = dedupe(rows)
    kept.sort(key=lambda r: (r["source"], r["lang"], r["key"]))
    os.makedirs(OUT_DIR, exist_ok=True)
    with open(os.path.join(OUT_DIR, "consentlens_dataset.jsonl"), "w", encoding="utf-8", newline="\n") as f:
        for r in kept:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    stats = {
        "rows_before_dedupe": len(rows), "rows": len(kept), "dropped_conflicting_texts": conflicts,
        "aosp_other_keys": other_keys, "framework_other_keys": fw_other_keys, "skipped": [list(s) for s in klaro_skipped + tac_skipped],
        "by_source": collections.Counter(r["source"] for r in kept),
        "by_role": collections.Counter(r["role"] for r in kept),
        "by_lang_group": collections.Counter(r["lang_group"] for r in kept),
        "by_source_role": {s: collections.Counter(r["role"] for r in kept if r["source"] == s) for s in SOURCES},
    }
    with open(os.path.join(OUT_DIR, "sources.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump({"sources": SOURCES, "stats": stats}, f, ensure_ascii=False, indent=1, sort_keys=False)
    print(json.dumps({k: stats[k] for k in ("rows_before_dedupe", "rows", "by_source", "by_role")}, indent=1))
    print("languages:", len(stats["by_lang_group"]), "conflicts dropped:", len(conflicts))


if __name__ == "__main__":
    main()
