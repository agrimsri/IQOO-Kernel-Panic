package com.dpdpxray.app.notices

/**
 * One licence or notice file shipped inside the APK under `assets/licenses/`. [source] says where its words come from
 * (a licence steward's page, the component's repository, or the library archive it was copied from unchanged).
 */
data class NoticeText(val asset: String, val title: String, val source: String) {
    val isPdf: Boolean get() = asset.endsWith(".pdf", ignoreCase = true)
}

enum class NoticeGroup(val title: String) {
    LIBRARIES("Libraries"),
    DATA("Data, models and font"),
    TRACEVEIL("TraceVeil"),
}

/**
 * One component inside the APK, as the "Open-source notices" screen lists it: its name, version, licence as the
 * component publishes it, what TraceVeil uses it for, and the licence and notice texts bundled with the app.
 * [inventory] names the rows of docs/LICENSES_FOR_SALE.md (section 2, "Bundled in the APK") this entry covers, so
 * OpenSourceNoticesTest fails when the inventory and this screen drift apart.
 */
data class NoticeComponent(
    val id: String,
    val name: String,
    val version: String,
    val licence: String,
    val usedFor: String,
    val texts: List<NoticeText>,
    val group: NoticeGroup,
    val inventory: List<String>,
)

/**
 * Everything the "Open-source notices" screen shows. It only reproduces licence texts and credits; it states no
 * opinion on whether any licence's conditions are met. Pure, so OpenSourceNoticesTest can check it against the
 * inventory and the asset folder.
 */
object OpenSourceNotices {
    const val ASSET_DIR = "licenses"
    const val TITLE = "Open-source notices"
    const val INTRO = "TraceVeil is built with the libraries, data and font below. Tap one to read its licence or " +
        "notice; every text is stored inside the app, so this page works offline."
    const val NOT_ADVICE = "Each licence is named as its publisher names it, and each text is reproduced as published."

    /** The detail line of the row that opens this screen (More › About TraceVeil, and the trust screen). */
    const val ROW_DETAIL = "Licences and credits for the libraries, data and font inside TraceVeil"

    private val APACHE = NoticeText(
        "$ASSET_DIR/apache-2.0.txt", "Apache License 2.0", "https://www.apache.org/licenses/LICENSE-2.0.txt",
    )
    private val CC_BY_4 = NoticeText(
        "$ASSET_DIR/cc-by-4.0.txt", "Creative Commons Attribution 4.0 International",
        "https://creativecommons.org/licenses/by/4.0/legalcode.txt",
    )
    private val CC_BY_SA_3 = NoticeText(
        "$ASSET_DIR/cc-by-sa-3.0.txt", "Creative Commons Attribution-ShareAlike 3.0 Unported",
        "https://creativecommons.org/licenses/by-sa/3.0/legalcode.txt",
    )
    private val CC_BY_3 = NoticeText(
        "$ASSET_DIR/cc-by-3.0.txt", "Creative Commons Attribution 3.0 Unported",
        "https://creativecommons.org/licenses/by/3.0/legalcode.txt",
    )
    private val TRACEVEIL_MIT = NoticeText("$ASSET_DIR/traceveil-mit.txt", "TraceVeil licence (MIT)", "LICENSE in TraceVeil's source repository")

    private fun library(
        id: String, name: String, version: String, licence: String, usedFor: String, inventory: String,
        texts: List<NoticeText> = listOf(APACHE),
    ) = NoticeComponent(id, name, version, licence, usedFor, texts, NoticeGroup.LIBRARIES, listOf(inventory))

    /** Every entry, in screen order. [appVersion] is this build's version name, shown on TraceVeil's own entry. */
    fun all(appVersion: String): List<NoticeComponent> = listOf(
        // ---- libraries -------------------------------------------------------------------------------------------
        library(
            "androidx", "AndroidX and Jetpack Compose",
            "Compose BOM 2026.09.00; core 1.19.1, activity 1.13.0, lifecycle 2.11.0, navigation 2.10.2",
            "Apache-2.0", "The app's screens, navigation and background lifecycle",
            "AndroidX (core, activity, lifecycle, navigation, savedstate, compose ui/foundation/animation/runtime/material3/material-icons-core, window, emoji2, profileinstaller, startup, tracing and others)",
        ),
        library(
            "kotlin", "Kotlin standard library and kotlin-reflect", "2.4.20 / 2.4.0", "Apache-2.0",
            "The language runtime TraceVeil is written in", "Kotlin standard library and kotlin-reflect",
        ),
        library("coroutines", "kotlinx.coroutines", "1.11.0", "Apache-2.0", "Work that runs in the background", "kotlinx.coroutines"),
        library(
            "serialization", "kotlinx.serialization", "1.11.0", "Apache-2.0",
            "Saving checks and writing reports and exports", "kotlinx.serialization",
        ),
        library(
            "litert", "LiteRT", "1.4.2", "Apache-2.0 (with the Caffe notice in its licence file)",
            "Runs ConsentLens-1, TraceVeil's consent-screen model, on this phone",
            "LiteRT (com.google.ai.edge.litert:litert, litert-api)",
            texts = listOf(
                NoticeText(
                    "$ASSET_DIR/google/litert-1.4.2-LICENSE.txt", "LiteRT licence",
                    "LICENSE inside litert-1.4.2.aar and litert-api-1.4.2.aar (Google Maven), copied unchanged",
                ),
            ),
        ),
        library(
            "litertlm", "LiteRT-LM", "0.17.1", "Apache-2.0; third-party notices supplied with the library",
            "Runs the optional on-device AI model that you copy to the phone",
            "LiteRT-LM (com.google.ai.edge.litertlm:litertlm-android)",
            texts = listOf(
                APACHE,
                NoticeText(
                    "$ASSET_DIR/google/litertlm-android-0.17.1-THIRD_PARTY_NOTICE.txt", "LiteRT-LM third-party notices",
                    "THIRD_PARTY_NOTICE.txt inside litertlm-android-0.17.1.aar (Google Maven), copied unchanged",
                ),
            ),
        ),
        library(
            "qnn-delegate", "Qualcomm AI Engine Direct (QNN) delegate for LiteRT", "2.50.0",
            "Proprietary: Qualcomm Innovation Center, Inc. \"AI Model Hub License\" terms",
            "Runs ConsentLens-1 on the phone's NPU, where the phone has one",
            "Qualcomm QNN LiteRT delegate (com.qualcomm.qti:qnn-litert-delegate)",
            texts = listOf(
                NoticeText(
                    "$ASSET_DIR/qualcomm/qnn-litert-delegate-2.50.0-LICENSE.pdf", "Qualcomm delegate licence (LICENSE.pdf)",
                    "LICENSE.pdf inside qnn-litert-delegate-2.50.0.aar (Maven Central), copied unchanged",
                ),
            ),
        ),
        library(
            "qnn-runtime", "Qualcomm AI Engine Direct (QNN) runtime", "2.50.0",
            "Proprietary: Qualcomm Technologies, Inc. \"AI Stack License\" terms; third-party notices",
            "The NPU runtime the delegate uses for ConsentLens-1",
            "Qualcomm QNN runtime (com.qualcomm.qti:qnn-runtime)",
            texts = listOf(
                NoticeText(
                    "$ASSET_DIR/qualcomm/qnn-runtime-2.50.0-NOTICE.txt", "Qualcomm third-party notices (NOTICE.txt)",
                    "NOTICE.txt inside qnn-runtime-2.50.0.aar (Maven Central), copied unchanged",
                ),
                NoticeText(
                    "$ASSET_DIR/qualcomm/qnn-runtime-2.50.0-LICENSE.pdf", "Qualcomm runtime licence (LICENSE.pdf)",
                    "LICENSE.pdf inside qnn-runtime-2.50.0.aar (Maven Central), copied unchanged",
                ),
            ),
        ),
        library(
            "vosk", "Vosk Android", "0.3.75", "Apache-2.0", "The optional offline \"Hey TraceVeil\" wake word",
            "Vosk Android (com.alphacephei:vosk-android)",
        ),
        library(
            "jna", "JNA (Java Native Access)", "5.18.1", "Apache-2.0 or LGPL-2.1-or-later; used under Apache-2.0",
            "The native bridge Vosk uses", "JNA (net.java.dev.jna:jna)",
        ),
        library(
            "shizuku", "Shizuku API and provider", "13.1.5", "MIT",
            "Optional: opens and resets an app you test, through the separate Shizuku app",
            "Shizuku API, provider, aidl, shared (dev.rikka.shizuku)",
            texts = listOf(
                NoticeText(
                    "$ASSET_DIR/shizuku-api-mit.txt", "MIT License (Shizuku API)",
                    "https://github.com/RikkaApps/Shizuku-API/blob/a27f6e4151ba7b39965ca47edb2bf0aeed7102e5/LICENSE",
                ),
            ),
        ),
        library("gson", "Gson", "2.14.0", "Apache-2.0", "Used inside another library; TraceVeil does not call it", "Gson (com.google.code.gson)"),
        library(
            "annotations", "Guava listenablefuture, Error Prone annotations, JetBrains annotations, JSpecify",
            "1.0 / 2.48.0 / 13.0 / 1.0.0", "Apache-2.0", "Small helpers and code annotations that other libraries use",
            "Guava listenablefuture, Error Prone annotations, JetBrains annotations, JSpecify",
        ),

        // ---- data, models and font -------------------------------------------------------------------------------
        NoticeComponent(
            "archivo", "Archivo font", "2.001", "SIL Open Font License 1.1", "The app's typeface",
            listOf(
                NoticeText(
                    "$ASSET_DIR/archivo-ofl-1.1.txt", "SIL Open Font License 1.1 (Archivo)",
                    "https://github.com/google/fonts/blob/6c70c829f09ea345d3590406693220ea35c6553f/ofl/archivo/OFL.txt; " +
                        "licence wording as at https://openfontlicense.org/documents/OFL.txt",
                ),
            ),
            NoticeGroup.DATA, listOf("res/font/archivo.ttf"),
        ),
        NoticeComponent(
            "dbip", "DB-IP IP to Country Lite", "October 2026 (built 2026-10-01)", "CC BY 4.0",
            "Places the IP addresses in DNS answers in countries on the endpoint map",
            listOf(NoticeText("$ASSET_DIR/dbip-country-lite.txt", "DB-IP credit", "https://db-ip.com/db/download/ip-to-country-lite"), CC_BY_4),
            NoticeGroup.DATA, listOf("assets/geo/dbip-country-lite.mmdb"),
        ),
        NoticeComponent(
            "natural-earth", "Natural Earth country outlines", "1:110m Admin 0, v5.1.2", "Public domain",
            "Country outlines on the endpoint map",
            listOf(NoticeText("$ASSET_DIR/natural-earth.txt", "Natural Earth credit", "https://www.naturalearthdata.com/about/terms-of-use/")),
            NoticeGroup.DATA, listOf("geo/world.svg (country outlines)"),
        ),
        NoticeComponent(
            "tracker-model", "TrackerLens hostname model", "1.0.0",
            "CC BY-SA 3.0 (weights derived from EasyList/EasyPrivacy, CC BY-SA 3.0, and Majestic Million, CC BY 3.0)",
            "Scores how much a contacted hostname looks like a tracker",
            listOf(
                NoticeText("$ASSET_DIR/tracker-hostname-model.txt", "Credits for the TrackerLens data", "ml/tracker_hostname.json and ml/DATA_SOURCES.md"),
                CC_BY_SA_3, CC_BY_3,
            ),
            NoticeGroup.DATA, listOf("ml/tracker_hostname.json"),
        ),
        NoticeComponent(
            "permission-model", "APK permission-risk model", "1.0.0", "Trained on NATICUSdroid and TUANDROMD (CC BY 4.0)",
            "APK Guard's score for an app's declared permissions",
            listOf(
                NoticeText("$ASSET_DIR/apk-permission-model.txt", "Credits for the permission-model data", "ml/apk_permission_risk.json and ml/DATA_SOURCES.md"),
                CC_BY_4,
            ),
            NoticeGroup.DATA, listOf("ml/apk_permission_risk.json"),
        ),
        NoticeComponent(
            "consentlens", "ConsentLens-1 model", "ConsentLens-1",
            "TraceVeil's own model (MIT), trained on Apache-2.0, BSD-3-Clause and MIT licensed strings",
            "Recognises consent-screen buttons and notices on this phone",
            listOf(
                NoticeText(
                    "$ASSET_DIR/consentlens-training-data.txt", "Notices for the training strings",
                    "LICENSE files of kiprotect/klaro and AmauriC/tarteaucitron.js at the pinned revisions (ml/consentlens/data/sources.json)",
                ),
                APACHE,
            ),
            NoticeGroup.DATA, listOf("assets/consentlens/consentlens_int8.tflite, consentlens_meta.json"),
        ),
        NoticeComponent(
            "legal-texts", "Legal text excerpts", "Read 10 October 2026",
            "Quoted from official publications and regulator guidance; each excerpt names its source",
            "Context for findings in the legal lens, never a verdict",
            listOf(NoticeText("$ASSET_DIR/legal-text-sources.txt", "Sources of the legal excerpts", "law/passages.json and dpdp/passages.json")),
            NoticeGroup.DATA, listOf("law/passages.json, law/profiles.json", "dpdp/passages.json"),
        ),
        NoticeComponent(
            "catalogue", "Tracker catalogue and company facts", "Read 10 October 2026",
            "TraceVeil's own data (MIT), with short quotations that each name their source",
            "Naming tracker companies and suggesting fixes",
            listOf(
                NoticeText("$ASSET_DIR/catalogue-sources.txt", "Sources of the company facts", "trackers/trackers.json and ATTRIBUTIONS.md"),
                TRACEVEIL_MIT,
            ),
            NoticeGroup.DATA, listOf("trackers/trackers.json, trackers/app_owners.json, fixes/fix_templates.json, sdk/*.json"),
        ),

        // ---- TraceVeil itself ------------------------------------------------------------------------------------
        NoticeComponent(
            "traceveil", "TraceVeil", appVersion, "MIT", "This app's own code, report styles and scripts",
            listOf(TRACEVEIL_MIT), NoticeGroup.TRACEVEIL,
            listOf("TraceVeil code (:app, :core)", "report/traceveil-report.css, .js"),
        ),
    )

    fun byId(id: String, appVersion: String): NoticeComponent? = all(appVersion).firstOrNull { it.id == id }
}

/**
 * Splits a long licence text into chunks of lines for a lazy list, so a 2 MB notice scrolls smoothly. Chunks rejoin
 * with "\n" to exactly the text (Windows line endings shown as plain line breaks), so nothing is ever dropped.
 */
object NoticeChunks {
    /** A chunk ends at the first blank line after this many lines... */
    const val SOFT_LINES = 40

    /** ...and never holds more than this many. */
    const val HARD_LINES = 120

    fun split(text: String): List<String> {
        val lines = text.replace("\r\n", "\n").split('\n')
        val chunks = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < lines.size) {
            val size = i - start + 1
            val atBreak = size >= SOFT_LINES && lines[i].isBlank()
            if (atBreak || size >= HARD_LINES) {
                if (i + 1 < lines.size) {
                    chunks += lines.subList(start, i + 1).joinToString("\n")
                    start = i + 1
                }
            }
            i++
        }
        chunks += lines.subList(start, lines.size).joinToString("\n")
        return chunks
    }
}
