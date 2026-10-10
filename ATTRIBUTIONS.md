# TraceVeil attribution register

TraceVeil includes an imported baseline and third-party dependencies. Its MIT code licence does not replace their licences or establish hackathon eligibility. Preserve the baseline history, copyright notices and AI-use disclosure in [provenance](PROVENANCE.md). Exact dependency coordinates and versions are pinned in [the version catalogue](gradle/libs.versions.toml); N2 adds no runtime library, model or dataset.

## Baseline code and resources

| Component | Version or location | Source and licence | Required handling |
| --- | --- | --- | --- |
| DPDP X-Ray baseline | Release 1.1.0, original revision `d901807d84bc` | [Original repository](https://github.com/nitya-prakash-pandey-2005/dpdp-xray); MIT, copyright 2026 nitya-prakash-pandey-2005 | Retain [LICENSE](LICENSE). Describe it as pre-event code; organiser reuse approval is pending. |
| Archivo font | `app/src/main/res/font/archivo.ttf` | Copyright 2020 The Archivo Project Authors; [upstream OFL](https://github.com/google/fonts/blob/main/ofl/archivo/OFL.txt), SIL Open Font License 1.1 | Keep the font under OFL, not MIT. Its embedded name records retain copyright and the OFL reference. Include the complete OFL with distributed font copies; release notice packaging remains to be checked. |
| Legal passages | `core/src/main/resources/dpdp/passages.json` | Inherited excerpts labelled DPDP Act 2023 and Rules 2025, attributed to Government of India publications | Keep section/source labels. Recheck the applicable official text and publication terms before release; do not call this corpus legal advice or current-law certification. |
| Tracker catalogue and fix data | `core/src/main/resources/trackers`, `fixes` | Inherited baseline data under its recorded provenance | This is not an imported competitor feed. Source review of new country/tracker claims is required before adding them. No new country dataset is approved here. |

## Existing library inventory

| Component | Pinned version | Licence evidence | Scope |
| --- | --- | --- | --- |
| LiteRT-LM Android | 0.17.1 | [Upstream LICENSE](https://github.com/google-ai-edge/LiteRT-LM/blob/main/LICENSE), Apache License 2.0 | Existing runtime dependency. Retain required licence and any applicable bundled notices; native/transitive notice review is still needed for a release. |
| Shizuku API and provider | 13.1.5 | Both cached published POMs name MIT and link [Shizuku-API LICENSE](https://github.com/RikkaApps/Shizuku-API/blob/master/LICENSE), copyright 2021 RikkaW | Existing runtime dependencies. Preserve MIT notices. This is the API/provider licence, not a blanket statement about the separate Shizuku app. |
| JUnit | 4.13.2 | Published POM names [Eclipse Public License 1.0](https://www.eclipse.org/legal/epl-v10.html) | Test dependency only; not an app feature or bundled model. |
| Gradle, AGP, Kotlin, Kotlin libraries and AndroidX/Compose | Versions in the catalogue and Gradle wrapper | Existing build/runtime inventory; version-specific licence and transitive notice review remains open | Keep pinned versions. Do not treat this register as an exhaustive software bill of materials or release clearance. |

Gemma model weights are optional and not committed or bundled. They have separate [Gemma terms](https://ai.google.dev/gemma/terms), not the LiteRT-LM code licence. Each downloader must meet the applicable model terms. No embedding, NPU, geography or competitor dataset is approved for redistribution by this change.

## CI dependencies added by N2

Both exact action revisions' complete MIT texts were read on 10 October 2026. Their copyright is 2018 GitHub, Inc. and contributors. They run only on the CI runner; their implementation is not copied into TraceVeil or the APK.

| Action | Immutable revision | Licence source |
| --- | --- | --- |
| actions/checkout v7 | `3d3c42e5aac5ba805825da76410c181273ba90b1` | [MIT LICENSE at the pinned revision](https://github.com/actions/checkout/blob/3d3c42e5aac5ba805825da76410c181273ba90b1/LICENSE) |
| actions/setup-java v6 | `de7274f081f381c8f8158605e0321c36c376e2e6` | [MIT LICENSE at the pinned revision](https://github.com/actions/setup-java/blob/de7274f081f381c8f8158605e0321c36c376e2e6/LICENSE) |

CI uses the existing JDK 17 toolchain requirement with Eclipse Temurin. JDK distribution terms are separate from the setup action's MIT licence. Python's standard-library unittest and Git exercise disposable test repositories; no additional Python package is needed to run those tests.

## Gate for any new external material

Before adding a library, dataset, model or service, record here its name, exact version/revision, original source URL, complete licence URL, required notices, redistribution conditions and intended use, and the reviewed decision, before integration. Yashvi maintains this register for the Y1-Y3 material; any teammate may add a row for their own. A link or a project's general licence alone does not clear a third-party asset inside that project.

TrackerControl, PCAPdroid and Exodus references are idea/research inputs only. No competitor source, map asset, GeoLite database or tracker feed is added by N2. The offline geography decision and any opt-in online service remain separate team decisions.

## Y3 integration decision — 10 October 2026

| Material | Source / terms | Integration handling |
| --- | --- | --- |
| Optional GeoLite2 Country data | [MaxMind GeoLite EULA](https://www.maxmind.com/en/geolite/eula) and MaxMind's [display guidance](https://support.maxmind.com/knowledge-base/articles/sell-or-display-data-from-geolite-databases-and-web-services), both read 10 October 2026; details in [Yashvi's attribution notes](docs/ATTRIBUTION.md) | **Not in Git, the APK or any release.** Used only from a copy on the phone (`files/geo/GeoLite2-Country.mmdb`), and preferred when present; with no copy the bundled DB-IP Lite table below places countries. **Attribution is required** and is shown on the card whenever the data is used: "This product includes GeoLite Data created by MaxMind, available from https://www.maxmind.com". MaxMind's guidance also asks for it in documentation that mentions use of GeoLite data, which is why this line appears here. **Permission is conditional, not a blanket ban:** application use and display to outside users is allowed with attribution; copyrightable elements are under CC BY-SA 4.0; giving the database file to a third party beyond what that licence allows needs MaxMind's written consent and the same duties on the recipient (EULA §3, §6); no identifying households, individuals or street addresses (§5). **Update duty:** use the newest release and stop using and destroy versions older than 30 days after an update (§6), so the card shows the build date and warns past 30 days. **Version in use:** built 2026-09-29, 8,423,789 bytes, SHA-256 `ff539785596f72ac2a07048f08506b38134bcf3b568182fd65f4534817bf91d7`, taken from the TrackerControl checkout the team supplied, not downloaded under a project MaxMind account. Each user should obtain their own copy. |
| Existing `geo/world.svg` | [amCharts SVG directory](https://www.amcharts.com/svg-maps/) and [GeoMap](https://github.com/takuseno/GeoMap); [CC BY-NC 4.0 legal code](https://creativecommons.org/licenses/by-nc/4.0/legalcode.en) | amCharts identifies its SVG maps as non-commercial. Geometry stays unchanged; visible credits name both sources, licence and styling change. Not cleared for commercial use or an assumed prize-competition exemption; reassess that scope before broader distribution. |
| MaxMind format specification | [Primary specification](https://maxmind.github.io/MaxMind-DB/) | Yashvi wrote the reader; no added reader library or competitor implementation. |
| Company facts / statutory context | A5's cited `trackers.json`; [official Act](https://www.indiacode.nic.in/bitstream/123456789/22037/2/a2023-22.pdf) and [commencement notification](https://upload.indiacode.nic.in/showfile?actid=AC_CEN_45_0_00003_2023-22_1763464807080&filename=c56ceae6c383460ca69577428d36828b.pdf&type=hindinotification) | Mount with the catalogue's single sourced owner mapping. DPDP context is jurisdiction-specific readiness, with commencement/exception warnings; no new corpus citation ID or applicability verdict. |

This records the restricted prototype integration, not blanket release clearance. No MaxMind account or terms were accepted on anyone else's behalf. Earlier N2 statements above describe that checkpoint, not the subsequently merged Y3 asset.

### Company facts: the nine sources behind A5's `trackers.json`

All read on 10 October 2026 and cited in the catalogue with their quotes; each source says what it says about a place, and the screen labels a headquarters as a headquarters and a stated address as a stated address. No dataset was imported.

| Source | Entities it supports |
| --- | --- |
| [Alphabet 10-K, FY2023](https://www.sec.gov/Archives/edgar/data/1652044/000165204424000022/goog-20231231.htm) | Google Analytics for Firebase, Google Analytics, Firebase Crashlytics, Google Ads / AdMob (location: headquarters) |
| [Google Terms of Service](https://policies.google.com/terms) | The same four (incorporation of Google LLC) |
| [Meta Platforms 10-K, FY2025](https://www.sec.gov/Archives/edgar/data/1326801/000162828026003942/meta-20251231.htm) | Meta (Facebook SDK), Meta Audience Network, Meta (location: headquarters) |
| [Microsoft 10-K, FY2025](https://www.sec.gov/Archives/edgar/data/789019/000095017025100235/msft-20250630.htm) | Microsoft Clarity (headquarters) |
| [Braze 10-K, FY2026](https://www.sec.gov/Archives/edgar/data/1676238/000167623826000013/brze-20260131.htm) | Braze (headquarters) |
| [Unity 10-K, FY2025](https://www.sec.gov/Archives/edgar/data/1810806/000181080626000011/unity-20251231.htm) | Unity Ads (headquarters) |
| [AppLovin filing](https://www.sec.gov/Archives/edgar/data/1751008/000175100826000010/R9.htm) | AppLovin (headquarters) |
| [Amplitude 10-K, FY2025](https://www.sec.gov/Archives/edgar/data/1866692/000119312526057847/ampl-20251231.htm) | Amplitude (stated address: principal executive office) |
| [AppsFlyer Terms of Use](https://www.appsflyer.com/legal/terms-of-use/) | AppsFlyer (stated address; its terms name three contracting entities by customer country) |

CleverTap is deliberately unresolved (three entities in three countries, no headquarters named). 22 catalogue entities were not researched and show "Not established".

## Bundled IP-to-country table (DB-IP Lite) — 10 October 2026

| Item | Detail |
| --- | --- |
| Material | DB-IP [IP to Country Lite](https://db-ip.com/db/download/ip-to-country-lite), October 2026 MMDB edition (`DBIP-Country-Lite`, built 2026-10-01), 8,348,509 bytes, SHA-256 `dbd70ccfa2a13627eaf4913d19920a1a229f1b3c5439ac01c509995b6364202a` |
| Licence | [Creative Commons Attribution 4.0 International](https://creativecommons.org/licenses/by/4.0/), as stated on DB-IP's download page (read 10 October 2026). Redistribution is allowed with attribution |
| Required notice | "IP Geolocation by DB-IP (https://db-ip.com)", printed with the licence on the endpoint map card whenever this table placed the countries, and in this register |
| Location | `app/src/main/assets/geo/dbip-country-lite.mmdb.gz` (gzip -9 -n of the unmodified file); inside the APK as `assets/geo/dbip-country-lite.mmdb`. Details, APK impact and update steps in [docs/ATTRIBUTION.md](docs/ATTRIBUTION.md) |
| Intended use | Offline country placement of IP addresses in DNS answers on the endpoint map, labelled Inferred and approximate. No network access; not used to locate the person |
| Decision | Bundled on the owner's instruction for the finale build, 10 October 2026, because the licence permits redistribution with attribution (unlike GeoLite2, which stays a user-provided option) |

## Optional online voice (Sarvam Bulbul) — 10 October 2026

| Item | Detail |
| --- | --- |
| Service | [Sarvam AI](https://docs.sarvam.ai/) Bulbul v3 text to speech: `POST https://api.sarvam.ai/text-to-speech`, `api-subscription-key` header, base64 WAV reply. Voices `ishita` (English) and `priya` (Hindi). No SDK or library is added: plain `HttpURLConnection`. |
| Status | **Demo prototype, off by default.** It is built only when an `api.env` pushed to the phone (`adb push api.env /sdcard/Android/data/com.dpdpxray.app/files/`) sets `USE_API=true` and holds a key. The repository's `api.env` holds the flag only; the key is never in Git, the APK, logs or the UI. |
| What leaves the phone | Only the text of one answer, at most 2,500 characters. Never microphone audio and never the audit file. The answer text can name the audited app and the trackers found. Speech recognition and answer generation stay on the phone. The screen and Settings say when online voice is on. |
| If it fails | The user is told and the phone's offline voice speaks. The fallback is cloud to phone, never phone to cloud. |
| Cost | ₹30 per 10,000 characters for Bulbul v3, as read on Sarvam's pricing page; the amount of sign-up credit is not stated there. |
| Switching it | `tools/push-api-env.sh online` pushes the flag and the key from the laptop's `.env` to the phone without printing it; `nokey`, `offline` and `remove` return to the offline voice. With no key (or a blank one) the script clears the phone's `api.env`, and the app also falls back on its own: the online voice needs `USE_API=true` (any letter case) **and** a key. |
| Tested 10 October 2026 | Two real calls through the app's own `SarvamTts` code, with the real grounded answers: English (497 characters, request `20261010_73884898-bf84-475b-8666-9c7884686da7`, about 32 s of 22,050 Hz mono 16-bit WAV) and Hindi (101 characters, request `20261010_dc25fd6b-1ef9-452f-a25b-60059e3db971`, about 11 s). About 600 characters in total, roughly ₹1.8 at the listed price. Not yet confirmed on the phone: audible playback of the online voice. |
| Not yet read | Sarvam's terms, data-retention and training policy. **No team sign-off is recorded.** Do not describe the product as "uploads nothing" while this is enabled, and do not enable it for a real-app audit. |

## Optional "Hey TraceVeil" wake word (Vosk) — 10 October 2026

Experimental, off by default; see [docs/HEY_TRACEVEIL.md](docs/HEY_TRACEVEIL.md). Intended use: offline keyword spotting for one phrase while the user has explicitly started listening.

| Component | Pinned version | Source and licence | Handling |
| --- | --- | --- | --- |
| Vosk Android (`com.alphacephei:vosk-android`) | 0.3.75 | [Maven Central](https://repo1.maven.org/maven2/com/alphacephei/vosk-android/0.3.75/), [vosk-api COPYING](https://github.com/alphacep/vosk-api/blob/master/COPYING), Apache License 2.0 (POM and COPYING read 10 October 2026) | Runtime dependency; bundles `libvosk.so` (about 10 MB per ABI). Native/transitive notice review (Kaldi, OpenFST and the math libraries inside the native library) is still needed for a release. |
| JNA (`net.java.dev.jna:jna`, aar) | 5.18.1, pulled by Vosk | [Published POM](https://repo1.maven.org/maven2/net/java/dev/jna/jna/5.18.1/jna-5.18.1.pom) and [LICENSE](https://github.com/java-native-access/jna/blob/master/LICENSE): Apache-2.0 OR LGPL-2.1-or-later | Used under Apache-2.0. Bundles `libjnidispatch.so`. |
| Vosk small US-English model | `vosk-model-small-en-us-0.15` | [alphacephei.com model list](https://alphacephei.com/vosk/models), listed as Apache 2.0 | **Not in Git or the APK.** Downloaded only after the user taps Download, from the official URL, into the app's own storage. Checked against the official list's MD5 (`09ab50cc…`) and a SHA-256 (`30f26242…`) computed from a copy whose size and MD5 matched that list. |

## Trained models: datasets and derived weights — 10 October 2026

Raw data is not in Git or the APK; only the trained weights are bundled. Full details, citations and SHA-256 values are in [ml/DATA_SOURCES.md](ml/DATA_SOURCES.md); licences were read on 10 October 2026.

| Material | Source / terms | Handling |
| --- | --- | --- |
| NATICUSdroid (training) and TUANDROMD (cross-dataset test) | UCI Machine Learning Repository, DOIs 10.24432/C5FS64 and 10.24432/C5560H; both CC BY 4.0 | Credited in `core/src/main/resources/ml/apk_permission_risk.json` and the model card, with the changes we made (name normalisation, dropped columns). |
| EasyPrivacy and EasyList | The EasyList authors (https://easylist.to/); dual GPL-3.0-or-later / CC BY-SA 3.0, used under CC BY-SA 3.0 | Attribution "The EasyList authors (https://easylist.to/)". The derived `core/src/main/resources/ml/tracker_hostname.json` is shared under CC BY-SA 3.0, not MIT. |
| Majestic Million | Majestic (https://majestic.com/); CC BY 3.0 | Attribution to Majestic in the TrackerLens weights file and model card. |
| AOSP platform manifests; Public Suffix List | Apache-2.0; MPL-2.0 | Training only (permission names and levels; registrable-domain grouping). Nothing from them is shipped. |
| DuckDuckGo Tracker Radar | CC BY-NC-SA 4.0 | Considered, **not used** (non-commercial only). |
