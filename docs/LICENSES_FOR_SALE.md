# Licences of what ships in TraceVeil, and what to review before selling it

Compiled on 11 October 2026 from `ATTRIBUTIONS.md`, `docs/ATTRIBUTION.md`, the resolved `releaseRuntimeClasspath` of `:app`, the contents of the signed release APK (`TraceVeil-1.1.0-release.apk`, see [RELEASE.md](RELEASE.md)) and the licence texts named below.

**This is an engineering inventory, not legal advice and not a clearance.** It records what each component's own licence text or publisher page says, where we read it, and what looks like it needs a decision or a fix before the app is sold. Nobody on the team has had it reviewed by a lawyer. Where a cell says "review", the question is open.

## 1. Summary: what needs attention before a commercial release

| # | Item | Why | Suggested next step |
| --- | --- | --- | --- |
| 1 | **No in-app open-source notices screen** | The APK ships Apache-2.0 libraries (AndroidX, Kotlin, LiteRT, LiteRT-LM, Vosk, Gson and others), the Archivo font (SIL OFL 1.1), and data under CC BY 4.0, CC BY 3.0 and CC BY-SA 3.0. Their licences ask for the licence text, NOTICE files or attribution to travel with the copy. Only a few AndroidX `LICENSE.txt` files end up in the APK's `META-INF`; no OFL text, no Qualcomm `NOTICE.txt`, no native-library notices | Add a "Licences" screen (or an asset) that lists every component in section 2 with its licence text and notices. Small engineering task |
| 2 | **Qualcomm QNN delegate and runtime (proprietary)** | Distribution is allowed only "in object code format and as incorporated in Your software application"; the licensor "may at any time terminate this Agreement, without cause, upon notice"; it includes an indemnity that covers posting apps "on one (1) or more application download websites or stores", export-control duties, prohibited uses, and California law. The QNN runtime's `NOTICE.txt` (third-party notices, about 2,400 lines) must be complied with but is not in the APK. The acceptance of these terms is recorded in `ATTRIBUTIONS.md` as relayed by a work brief; the owner has not confirmed it there | Owner (or counsel) reads both licences in full and confirms acceptance in the register; ship `NOTICE.txt` with the app; decide whether the business accepts a licence that can be terminated without cause, or ships a build without the QNN libraries that relies on ConsentLens's existing CPU fallback (it already runs on the CPU when the NPU is unavailable) |
| 3 | **`tracker_hostname.json` is CC BY-SA 3.0** | It is derived from EasyList/EasyPrivacy (used under CC BY-SA 3.0) and includes Majestic Million data (CC BY 3.0). ShareAlike means this file (and any adaptation of it) stays under CC BY-SA 3.0 with attribution, inside a commercial app | Keep the file's licence and attribution visible (item 1); do not relicense it under the app's terms. Review whether trained weights count as an adaptation (open question already noted in `ATTRIBUTIONS.md`) |
| 4 | **Legal text excerpts** | `law/passages.json` quotes EU legislation (EUR-Lex), EDPB guidelines, ICO guidance (UK), Singapore's PDPA (Singapore Statutes Online) and PDPC guidelines, Brazilian statutes (planalto.gov.br), California statute and regulations, US federal regulations (eCFR); `dpdp/passages.json` quotes the DPDP Act and Rules (Government of India). Reuse terms differ by publisher (for example, ICO material is usually published under the Open Government Licence, which asks for an attribution statement); none of these reuse terms has been read for this inventory | Read each publisher's reuse terms; add the attribution statements they ask for; keep verbatim quotes short and sourced |
| 5 | **Map boundary depiction** | The new outline follows Natural Earth's default (de facto) boundaries, with Crimea drawn with Ukraine. It includes India's northern boundary as Natural Earth draws it. Countries regulate how their own borders may be shown on maps sold there, India included | Have the depiction reviewed for each launch market, India first. Natural Earth publishes "point of view" variants at larger scales if a market needs a specific view |
| 6 | **Optional online voice (Sarvam)** | Off by default and only enabled by a pushed `api.env`; Sarvam's terms, retention and training policy were never read and no team sign-off exists (`ATTRIBUTIONS.md`) | Remove it from the sold build, or read the terms and add a data-processing agreement first |
| 7 | **User-supplied models and data** | Gemma, the Vosk model and GeoLite2 are not in the APK; each user obtains them. Their terms bind the user, but the product's instructions point users at them | Keep them out of the APK; in the product, show each file's licence before the user fetches or uses it (see section 3) |
| 8 | **Archivo font** | SIL OFL 1.1 lets the font be bundled and sold with software, but the licence text must accompany it; it does not today (open item in `ATTRIBUTIONS.md`) | Include the OFL text (item 1) |

Already resolved on this branch: the world map outline. The amCharts/GeoMap outline (CC BY-NC 4.0, non-commercial) was replaced by Natural Earth country outlines (public domain) to allow commercial distribution; see `docs/ATTRIBUTION.md`.

## 2. Bundled in the APK

### Libraries (from `:app` `releaseRuntimeClasspath`)

| Component | Version | Licence (as published) | Native code in the APK | Notes |
| --- | --- | --- | --- | --- |
| LiteRT (`com.google.ai.edge.litert:litert`, `litert-api`) | 1.4.2 | Apache-2.0 | `libtensorflowlite_jni.so` | Upstream third-party notices for the native library not yet collected |
| LiteRT-LM (`com.google.ai.edge.litertlm:litertlm-android`) | 0.17.1 | Apache-2.0 | `liblitertlm_jni.so` (21.8 MB) | Native/transitive notice review still open (`ATTRIBUTIONS.md`) |
| Qualcomm QNN LiteRT delegate (`com.qualcomm.qti:qnn-litert-delegate`) | 2.50.0 | Proprietary. POM names "Qualcomm AI Hub Model License"; the AAR's `LICENSE.pdf` is "Terms and Conditions of Use, AI Model Hub License" from Qualcomm Innovation Center, Inc. (text read 11 October 2026 from the AAR) | `libQnnTFLiteDelegate.so`, `libqnn_delegate_jni.so` | Summary item 2. This file was recorded as unread before; it is now read, but its terms have not been reviewed |
| Qualcomm QNN runtime (`com.qualcomm.qti:qnn-runtime`) | 2.50.0 | Proprietary. AAR `LICENSE.pdf`: Qualcomm Technologies, Inc. "AI Stack License"; `NOTICE.txt` third-party notices | `libQnnHtp.so`, `libQnnHtpPrepare.so` (85 MB), `libQnnSystem.so`, HTP V69/V73/V75/V79/V81 stub and skel libraries | Summary item 2 |
| Vosk Android (`com.alphacephei:vosk-android`) | 0.3.75 | Apache-2.0 | `libvosk.so` | Bundles Kaldi, OpenFST and math libraries; their notices are not collected yet |
| JNA (`net.java.dev.jna:jna`) | 5.18.1 | Apache-2.0 OR LGPL-2.1-or-later; used under Apache-2.0 | `libjnidispatch.so` | |
| Shizuku API, provider, aidl, shared (`dev.rikka.shizuku`) | 13.1.5 | MIT (published POMs) | none | The Shizuku app itself is separate and installed by the user |
| AndroidX (core, activity, lifecycle, navigation, savedstate, compose ui/foundation/animation/runtime/material3/material-icons-core, window, emoji2, profileinstaller, startup, tracing and others) | per `gradle/libs.versions.toml` and the Compose BOM 2026.09.00 | Apache-2.0 | `libandroidx.graphics.path.so` | |
| Kotlin standard library and `kotlin-reflect` | 2.4.20 / 2.4.0 | Apache-2.0 | none | |
| kotlinx.coroutines | 1.11.0 | Apache-2.0 | none | |
| kotlinx.serialization | 1.11.0 | Apache-2.0 | none | |
| Gson (`com.google.code.gson`) | 2.14.0 | Apache-2.0 | none | Pulled in transitively |
| Guava `listenablefuture`, Error Prone annotations, JetBrains annotations, JSpecify | 1.0 / 2.48.0 / 13.0 / 1.0.0 | Apache-2.0 | none | Annotation-only or tiny transitive artefacts |

Test-only (`junit` 4.13.2, EPL-1.0; `kotlinx-coroutines-test`) and build tools (Gradle, AGP, Kotlin compiler) are not shipped in the APK.

### Data, models and assets

| File in the APK | Source and licence | Required handling | Status |
| --- | --- | --- | --- |
| `geo/world.svg` (country outlines) | Natural Earth 1:110m Admin 0, v5.1.2. Public domain: "No permission is needed to use Natural Earth. Crediting the authors is unnecessary." (terms page read 11 October 2026) | None; the map credits it anyway | OK. Boundary depiction: summary item 5 |
| `assets/geo/dbip-country-lite.mmdb` | DB-IP IP to Country Lite, October 2026, CC BY 4.0 | Attribution "IP Geolocation by DB-IP (https://db-ip.com)" where results are shown; the map card prints it | OK as long as the credit stays on screen. Monthly updates are a product decision |
| `assets/consentlens/consentlens_int8.tflite`, `consentlens_meta.json` | TraceVeil's own model; trained on AOSP strings (Apache-2.0), Klaro! translations (BSD-3-Clause), tarteaucitron.js translations (MIT) and our own strings | Keep the upstream notices with the derived dataset; BSD-3 and MIT ask that their notices accompany redistributions | Put those notices in the notices screen (item 1) |
| `ml/tracker_hostname.json` | Derived from EasyList/EasyPrivacy (CC BY-SA 3.0) and Majestic Million (CC BY 3.0) | Attribution; ShareAlike for this file | Summary item 3 |
| `ml/apk_permission_risk.json` | Trained on NATICUSdroid (UCI, CC BY 4.0); evaluated on TUANDROMD (CC BY 4.0) | Attribution and a note of changes (in the file's metadata) | Make the attribution user-visible (item 1) |
| `law/passages.json`, `law/profiles.json` | Excerpts of EU, UK, Singapore, Brazil, California and US federal texts and regulator guidance, each with its URL | Publisher reuse terms | Summary item 4 |
| `dpdp/passages.json` | DPDP Act 2023 and DPDP Rules 2025 excerpts (Government of India publications) | Keep section and source labels | Summary item 4 |
| `trackers/trackers.json`, `trackers/app_owners.json`, `fixes/fix_templates.json`, `sdk/*.json` | Inherited baseline data plus short cited quotes from SEC filings and company terms pages (A5) | Keep each quote with its URL | Short quotations; review alongside item 4 |
| `report/traceveil-report.css`, `.js` | TraceVeil's own code ("no libraries") | MIT (our code) | OK |
| `res/font/archivo.ttf` | Archivo, SIL Open Font License 1.1 | Ship the OFL text with the font; do not sell the font on its own | Summary item 8 |
| TraceVeil code (`:app`, `:core`) | MIT, copyright the team (baseline: DPDP X-Ray, MIT) | Keep `LICENSE` | OK. The licence of the code we wrote does not cover any item above |

## 3. Not bundled: obtained by the user

| Component | How it reaches the phone | Licence | Note for a commercial product |
| --- | --- | --- | --- |
| Gemma model files (`.litertlm`) | The user copies one to the phone (`adb push`); TraceVeil never downloads it | Depends on the file. The `google/gemma-4-E2B-it` model card on Hugging Face shows "License: Apache 2.0" (read 11 October 2026). Gemma 3 and Gemma 3n files, which the app also accepts, are under Google's [Gemma Terms of Use](https://ai.google.dev/gemma/terms) and its prohibited use policy | Do not bundle a model without re-checking its licence. If a Gemma 3/3n file is used, the user is bound by the Gemma terms; the product should say which model it expects and link its licence |
| Vosk small English model (`vosk-model-small-en-us-0.15`, about 40 MB) | Downloaded from alphacephei.com only after the user taps Download | Listed as Apache 2.0 on the official model list | OK as user-initiated download; keep the source and checksum checks |
| MaxMind GeoLite2 Country | Optional; the user places their own file on the phone | GeoLite EULA (attribution; CC BY-SA 4.0 for copyrightable elements; limited licence; 30-day update/destroy duty; no redistribution without conditions) | Keep out of the APK. Each user needs their own MaxMind account |
| Sarvam Bulbul text to speech | Online API, only when a pushed `api.env` enables it | Sarvam's terms not read | Summary item 6 |

## 4. Outside licensing, not assessed here

These came up while compiling the inventory and are listed so they are not forgotten; none was assessed:

- Store policies for the permissions TraceVeil uses (`VpnService`, an accessibility service, `QUERY_ALL_PACKAGES`), and the privacy policy and data-safety declarations a store listing needs.
- Trademark clearance for the name "TraceVeil".
- Export classification of the shipped Qualcomm libraries (the Qualcomm licences require compliance with US export and sanctions law).
