# Licences of what ships in TraceVeil, and what to review before selling it

Compiled on 11 October 2026 from `ATTRIBUTIONS.md`, `docs/ATTRIBUTION.md`, the resolved `releaseRuntimeClasspath` of `:app`, the contents of the signed release APK (`TraceVeil-1.1.0-release.apk`, see [RELEASE.md](RELEASE.md)) and the licence texts named below.

Updated on 11 October 2026 (`nitya/sale-ready`): items 1 and 8 are addressed by the in-app "Open-source notices" screen (section 5), item 6 by a build switch that leaves the online voice out of release builds, and items 2 and 3 in part. What each item still needs is in its "Status" cell.

**This is an engineering inventory, not legal advice and not a clearance.** It records what each component's own licence text or publisher page says, where we read it, and what looks like it needs a decision or a fix before the app is sold. Nobody on the team has had it reviewed by a lawyer. Where a cell says "review", the question is open.

## 1. Summary: what needs attention before a commercial release

| # | Item | Why | Suggested next step | Status (11 October 2026, `nitya/sale-ready`) |
| --- | --- | --- | --- | --- |
| 1 | **No in-app open-source notices screen** | The APK ships Apache-2.0 libraries (AndroidX, Kotlin, LiteRT, LiteRT-LM, Vosk, Gson and others), the Archivo font (SIL OFL 1.1), and data under CC BY 4.0, CC BY 3.0 and CC BY-SA 3.0. Their licences ask for the licence text, NOTICE files or attribution to travel with the copy. Only a few AndroidX `LICENSE.txt` files end up in the APK's `META-INF`; no OFL text, no Qualcomm `NOTICE.txt`, no native-library notices | Add a "Licences" screen (or an asset) that lists every component in section 2 with its licence text and notices. Small engineering task | **Addressed by the notices screen** (section 5): More › About TraceVeil › Open-source notices, and the same row at the end of "Why you can trust TraceVeil", list every component in section 2 with its version, licence and use, and show the full texts from `assets/licenses/` in the APK. Still open: notices of the native code inside Vosk (Kaldi, OpenFST, math libraries) are not collected; the AndroidX, Kotlin and other Apache-2.0 artefacts were not checked one by one for NOTICE files beyond those found in the LiteRT and LiteRT-LM AARs |
| 2 | **Qualcomm QNN delegate and runtime (proprietary)** | Distribution is allowed only "in object code format and as incorporated in Your software application"; the licensor "may at any time terminate this Agreement, without cause, upon notice"; it includes an indemnity that covers posting apps "on one (1) or more application download websites or stores", export-control duties, prohibited uses, and California law. The QNN runtime's `NOTICE.txt` (third-party notices, about 2,400 lines) must be complied with but is not in the APK. The acceptance of these terms is recorded in `ATTRIBUTIONS.md` as relayed by a work brief; the owner has not confirmed it there | Owner (or counsel) reads both licences in full and confirms acceptance in the register; ship `NOTICE.txt` with the app; decide whether the business accepts a licence that can be terminated without cause, or ships a build without the QNN libraries that relies on ConsentLens's existing CPU fallback (it already runs on the CPU when the NPU is unavailable) | **In part.** `NOTICE.txt` and both `LICENSE.pdf` files now ship in the APK, copied unchanged from the 2.50.0 AARs, and the notices screen shows them. Still open: **the owner (or counsel) must still read and accept both Qualcomm licences and record that in `ATTRIBUTIONS.md`**, and decide on the termination-without-cause, indemnity and export-control terms, or ship without the QNN libraries |
| 3 | **`tracker_hostname.json` is CC BY-SA 3.0** | It is derived from EasyList/EasyPrivacy (used under CC BY-SA 3.0) and includes Majestic Million data (CC BY 3.0). ShareAlike means this file (and any adaptation of it) stays under CC BY-SA 3.0 with attribution, inside a commercial app | Keep the file's licence and attribution visible (item 1); do not relicense it under the app's terms. Review whether trained weights count as an adaptation (open question already noted in `ATTRIBUTIONS.md`) | **In part.** The notices screen credits The EasyList authors and Majestic, says the file is shared under CC BY-SA 3.0, and ships the CC BY-SA 3.0 and CC BY 3.0 legal codes. Still open: whether trained weights count as an adaptation |
| 4 | **Legal text excerpts** | `law/passages.json` quotes EU legislation (EUR-Lex), EDPB guidelines, ICO guidance (UK), Singapore's PDPA (Singapore Statutes Online) and PDPC guidelines, Brazilian statutes (planalto.gov.br), California statute and regulations, US federal regulations (eCFR); `dpdp/passages.json` quotes the DPDP Act and Rules (Government of India). Reuse terms differ by publisher (for example, ICO material is usually published under the Open Government Licence, which asks for an attribution statement); none of these reuse terms has been read for this inventory | Read each publisher's reuse terms; add the attribution statements they ask for; keep verbatim quotes short and sourced | Open. The notices screen lists the publishers and says each excerpt keeps its source; their reuse terms are still unread |
| 5 | **Map boundary depiction** | The new outline follows Natural Earth's default (de facto) boundaries, with Crimea drawn with Ukraine. It includes India's northern boundary as Natural Earth draws it. Countries regulate how their own borders may be shown on maps sold there, India included | Have the depiction reviewed for each launch market, India first. Natural Earth publishes "point of view" variants at larger scales if a market needs a specific view | Open |
| 6 | **Optional online voice (Sarvam)** | Off by default and only enabled by a pushed `api.env`; Sarvam's terms, retention and training policy were never read and no team sign-off exists (`ATTRIBUTIONS.md`) | Remove it from the sold build, or read the terms and add a data-processing agreement first | **Addressed for release builds.** `BuildConfig.ONLINE_VOICE_AVAILABLE` is false in release builds and true in debug builds (the demo build is unchanged); `-Ptraceveil.onlineVoice=true` or `=false` overrides both (see [RELEASE.md](RELEASE.md)). Without it the app never reads `api.env`, never calls Sarvam, and the trust screen does not list it. The Sarvam code is still compiled into the APK but cannot be reached. Still open: a build made with `-Ptraceveil.onlineVoice=true` for sale needs Sarvam's terms read and a data-processing agreement first |
| 7 | **User-supplied models and data** | Gemma, the Vosk model and GeoLite2 are not in the APK; each user obtains them. Their terms bind the user, but the product's instructions point users at them | Keep them out of the APK; in the product, show each file's licence before the user fetches or uses it (see section 3) | Open |
| 8 | **Archivo font** | SIL OFL 1.1 lets the font be bundled and sold with software, but the licence text must accompany it; it does not today (open item in `ATTRIBUTIONS.md`) | Include the OFL text (item 1) | **Addressed by the notices screen**: the full OFL 1.1 with the font's copyright line ("Copyright 2020 The Archivo Project Authors", as in the font's own name table) ships as `assets/licenses/archivo-ofl-1.1.txt` |

Already resolved on this branch: the world map outline. The amCharts/GeoMap outline (CC BY-NC 4.0, non-commercial) was replaced by Natural Earth country outlines (public domain) to allow commercial distribution; see `docs/ATTRIBUTION.md`.

## 2. Bundled in the APK

### Libraries (from `:app` `releaseRuntimeClasspath`)

| Component | Version | Licence (as published) | Native code in the APK | Notes |
| --- | --- | --- | --- | --- |
| LiteRT (`com.google.ai.edge.litert:litert`, `litert-api`) | 1.4.2 | Apache-2.0 | `libtensorflowlite_jni.so` | The AAR's `LICENSE` (Apache-2.0 plus the Caffe notice) ships on the notices screen; upstream notices for the native library beyond it are not collected |
| LiteRT-LM (`com.google.ai.edge.litertlm:litertlm-android`) | 0.17.1 | Apache-2.0 | `liblitertlm_jni.so` (21.8 MB) | The AAR's `THIRD_PARTY_NOTICE.txt` (2.1 MB) ships on the notices screen; whether it covers every native dependency was not reviewed |
| Qualcomm QNN LiteRT delegate (`com.qualcomm.qti:qnn-litert-delegate`) | 2.50.0 | Proprietary. POM names "Qualcomm AI Hub Model License"; the AAR's `LICENSE.pdf` is "Terms and Conditions of Use, AI Model Hub License" from Qualcomm Innovation Center, Inc. (text read 11 October 2026 from the AAR) | `libQnnTFLiteDelegate.so`, `libqnn_delegate_jni.so` | Summary item 2. This file was recorded as unread before; it is now read, but its terms have not been reviewed |
| Qualcomm QNN runtime (`com.qualcomm.qti:qnn-runtime`) | 2.50.0 | Proprietary. AAR `LICENSE.pdf`: Qualcomm Technologies, Inc. "AI Stack License"; `NOTICE.txt` third-party notices | `libQnnHtp.so`, `libQnnHtpPrepare.so` (85 MB), `libQnnSystem.so`, HTP V69/V73/V75/V79/V81 stub and skel libraries | Summary item 2 |
| Vosk Android (`com.alphacephei:vosk-android`) | 0.3.75 | Apache-2.0 | `libvosk.so` | Bundles Kaldi, OpenFST and math libraries; their notices are not collected yet (the AAR carries none) |
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
| `assets/consentlens/consentlens_int8.tflite`, `consentlens_meta.json` | TraceVeil's own model; trained on AOSP strings (Apache-2.0), Klaro! translations (BSD-3-Clause), tarteaucitron.js translations (MIT) and our own strings | Keep the upstream notices with the derived dataset; BSD-3 and MIT ask that their notices accompany redistributions | Shown on the notices screen, with the Klaro! (BSD-3-Clause) and tarteaucitron.js (MIT) licences in full |
| `ml/tracker_hostname.json` | Derived from EasyList/EasyPrivacy (CC BY-SA 3.0) and Majestic Million (CC BY 3.0) | Attribution; ShareAlike for this file | Summary item 3 |
| `ml/apk_permission_risk.json` | Trained on NATICUSdroid (UCI, CC BY 4.0); evaluated on TUANDROMD (CC BY 4.0) | Attribution and a note of changes (in the file's metadata) | Shown on the notices screen, with the CC BY 4.0 legal code |
| `law/passages.json`, `law/profiles.json` | Excerpts of EU, UK, Singapore, Brazil, California and US federal texts and regulator guidance, each with its URL | Publisher reuse terms | Summary item 4 |
| `dpdp/passages.json` | DPDP Act 2023 and DPDP Rules 2025 excerpts (Government of India publications) | Keep section and source labels | Summary item 4 |
| `trackers/trackers.json`, `trackers/app_owners.json`, `fixes/fix_templates.json`, `sdk/*.json` | Inherited baseline data plus short cited quotes from SEC filings and company terms pages (A5) | Keep each quote with its URL | Short quotations; review alongside item 4 |
| `report/traceveil-report.css`, `.js` | TraceVeil's own code ("no libraries") | MIT (our code) | OK |
| `res/font/archivo.ttf` | Archivo, SIL Open Font License 1.1 | Ship the OFL text with the font; do not sell the font on its own | OFL text ships (item 8) |
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

- Store policies for the permissions TraceVeil uses (`VpnService`, an accessibility service, `QUERY_ALL_PACKAGES`, `REQUEST_INSTALL_PACKAGES`), and the privacy policy and data-safety declarations a store listing needs.
- **Store review item: `REQUEST_INSTALL_PACKAGES`** (added for APK Guard's "Install anyway", `docs/CHECK_BEFORE_INSTALL.md`). Google Play restricts it to apps whose core purpose includes installing packages and asks for a declaration. If a store does not accept it, build with `-Ptraceveil.installHandoff=false`: the permission is removed from the merged manifest and APK Guard explains how to continue with Android's own installer instead.
- Trademark clearance for the name "TraceVeil".
- Export classification of the shipped Qualcomm libraries (the Qualcomm licences require compliance with US export and sanctions law).

## 5. The in-app "Open-source notices" screen

Added on 11 October 2026 (`nitya/sale-ready`). It only reproduces licence texts and credits; it does not say that any licence's conditions are met.

**Where:** More › About TraceVeil › **Open-source notices** ("Licences and credits for the libraries, data and font inside TraceVeil"), and the same row at the end of "Why you can trust TraceVeil" (both modes). The list has three cards: Libraries (13), Data, models and font (8) and TraceVeil (1). Each row shows the name, "version · licence" and what TraceVeil uses it for; tapping it opens the version, licence and use, then the full text. A component with several texts (for example the QNN runtime's `NOTICE.txt` and `LICENSE.pdf`) shows one chip per text. Text files are shown as published, in a monospaced font; Qualcomm's PDFs are drawn page by page by Android's PdfRenderer, with "Zoom in" and "Open in a PDF viewer".

**Kept in step by `OpenSourceNoticesTest`:** every first cell of the two tables in section 2 must be named by an entry in `OpenSourceNotices`, every entry's files must exist in `app/src/main/assets/licenses/`, no file there may be unused, versions must match `gradle/libs.versions.toml`, and the Qualcomm and LiteRT files must keep the SHA-256 of the copies inside their AARs. Adding a row to section 2 therefore fails the tests until the screen lists it.

**Texts in the APK (`assets/licenses/`) and where they came from.** All were downloaded or extracted on 11 October 2026. The folder is marked `-text` in `.gitattributes`, so Git keeps the bytes.

| File | Source | SHA-256 |
| --- | --- | --- |
| `apache-2.0.txt` | https://www.apache.org/licenses/LICENSE-2.0.txt | `cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30` |
| `cc-by-4.0.txt` | https://creativecommons.org/licenses/by/4.0/legalcode.txt | `9ba9550ad48438d0836ddab3da480b3b69ffa0aac7b7878b5a0039e7ab429411` |
| `cc-by-sa-3.0.txt` | https://creativecommons.org/licenses/by-sa/3.0/legalcode.txt | `3f941b3b89cf7b8370ceb83cc76d2120d471b58735d8ca60238a751a48d7f72f` |
| `cc-by-3.0.txt` | https://creativecommons.org/licenses/by/3.0/legalcode.txt | `e6bc9e9c474700b708f568bac9e5a8a9bcb2b1dad53442f5ba449fcb848b8e76` |
| `archivo-ofl-1.1.txt` | `ofl/archivo/OFL.txt` in google/fonts (last changed in commit `6c70c829f09ea345d3590406693220ea35c6553f`). Its licence body matches https://openfontlicense.org/documents/OFL.txt except for one trailing space, and its copyright line equals the font's own name-table copyright (name ID 0, Archivo 2.001) | `108b4e57c9c796d3d38d0428ca7ee39de47ad93187302718d9b2d8864b9b716b` |
| `shizuku-api-mit.txt` | `LICENSE` of RikkaApps/Shizuku-API at commit `a27f6e4151ba7b39965ca47edb2bf0aeed7102e5` ("Copyright (c) 2021 RikkaW") | `64870037f294b6f2b75c8ebe77cc0702acb3b64f86b4b0e58bf8558525303686` |
| `qualcomm/qnn-runtime-2.50.0-NOTICE.txt` | `NOTICE.txt` inside `qnn-runtime-2.50.0.aar` (Maven Central; AAR SHA-256 `b507656e4031d8aa6a7ab0fecf725b941096737ad7440ad7ea288f7c9cc9d743`), unchanged | `0c5e8aad3506d0ab881cabaf0dae8de64e1784dc1ee6c873caf24a78fbf01924` |
| `qualcomm/qnn-runtime-2.50.0-LICENSE.pdf` | `LICENSE.pdf` inside the same AAR ("AI Stack License"), unchanged | `ec1dccfdcba5c6e64126e84199b8362bf4999107bfa567ebe831dbb4c461692b` |
| `qualcomm/qnn-litert-delegate-2.50.0-LICENSE.pdf` | `LICENSE.pdf` inside `qnn-litert-delegate-2.50.0.aar` (AAR SHA-256 `b8790cdf77bb1393663adae9aa6b028f63db4c4efd3690f9f5f58c3aa0935732`; "AI Model Hub License"), unchanged | `2d20d88391972aa355c2d2ac0d03e7d4805b9a1c2398649d861c7dfd10dd5f83` |
| `google/litert-1.4.2-LICENSE.txt` | `LICENSE` inside `litert-1.4.2.aar` and `litert-api-1.4.2.aar` (identical): Apache-2.0 followed by the Caffe BSD notice, unchanged | `71c6915d04265772a0339bed47276942c678b45cc01534210ebe6984fd1aec65` |
| `google/litertlm-android-0.17.1-THIRD_PARTY_NOTICE.txt` | `THIRD_PARTY_NOTICE.txt` inside `litertlm-android-0.17.1.aar`, unchanged (2,146,747 bytes) | `67d807a83a6e4f9457365ca61ff3fa1db95b135a17feeefadeff8e9f02123243` |
| `traceveil-mit.txt` | This repository's `LICENSE` (LF line endings) | `49d37dbedf66d8b1421afbba11808a00a9aee2c6246dda75924a87552c62681a` |
| `dbip-country-lite.txt`, `natural-earth.txt`, `tracker-hostname-model.txt`, `apk-permission-model.txt`, `consentlens-training-data.txt`, `legal-text-sources.txt`, `catalogue-sources.txt` | Written for TraceVeil from the registers (`ATTRIBUTIONS.md`, `docs/ATTRIBUTION.md`, `ml/DATA_SOURCES.md` and the models' own metadata). The DB-IP and Natural Earth files carry the exact credit lines the map prints ("IP Geolocation by DB-IP (https://db-ip.com)", "Country outlines: Natural Earth (public domain)."). `consentlens-training-data.txt` reproduces, unchanged, the `LICENSE` files of kiprotect/klaro at `db9f1aca906a392990c58c58475366a091012ea7` and AmauriC/tarteaucitron.js at `07254bb6ff2858e2ab6a9b45c391d655a55a9c54` | |

**Qualcomm files:** both AARs were found in the Gradle cache. The runtime AAR contains `NOTICE.txt` and `LICENSE.pdf`; the delegate AAR contains only `LICENSE.pdf`. All three ship unchanged. Shipping them does not accept either licence on anyone's behalf.

**Size:** the folder is about 2.6 MB uncompressed (2.1 MB of it is LiteRT-LM's notice) and is stored compressed in the APK.
