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

Before adding a library, dataset, model or service, send Nitya its name, exact version/revision, original source URL, complete licence URL, required notices, redistribution conditions and intended use. Nitya records the reviewed decision here before integration. A link or a project's general licence alone does not clear a third-party asset inside that project.

TrackerControl, PCAPdroid and Exodus references are idea/research inputs only. No competitor source, map asset, GeoLite database or tracker feed is added by N2. The offline geography decision and any opt-in online service remain separate team decisions.
