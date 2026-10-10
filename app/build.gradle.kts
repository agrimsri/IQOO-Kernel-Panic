import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Release signing (docs/RELEASE.md). Read from the gitignored `keystore.properties` at the repository root, or else from
 * TRACEVEIL_KEYSTORE, TRACEVEIL_KEYSTORE_TYPE, TRACEVEIL_KEYSTORE_PASSWORD, TRACEVEIL_KEY_ALIAS and
 * TRACEVEIL_KEY_PASSWORD. Without a keystore path, both passwords and an alias, release builds are left unsigned; debug
 * builds always use the Android debug key.
 */
val releaseSigning: Map<String, String?>? = run {
    val file = rootProject.file("keystore.properties")
    val props = Properties().apply { if (file.isFile) file.inputStream().use { load(it) } }
    fun value(key: String, env: String): String? =
        props.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() } ?: providers.environmentVariable(env).orNull?.takeIf { it.isNotBlank() }
    val found = mapOf(
        "storeFile" to value("storeFile", "TRACEVEIL_KEYSTORE"),
        "storeType" to value("storeType", "TRACEVEIL_KEYSTORE_TYPE"),
        "storePassword" to value("storePassword", "TRACEVEIL_KEYSTORE_PASSWORD"),
        "keyAlias" to value("keyAlias", "TRACEVEIL_KEY_ALIAS"),
        "keyPassword" to value("keyPassword", "TRACEVEIL_KEY_PASSWORD"),
    )
    val required = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    when {
        required.all { found[it] != null } -> found
        required.any { found[it] != null } -> {
            logger.warn("Release signing: incomplete settings (missing ${required.filter { found[it] == null }}); release builds stay unsigned.")
            null
        }
        else -> null
    }
}

/**
 * Optional online voice (Sarvam text to speech, docs/RELEASE.md). Built into debug builds, so the demo build is
 * unchanged; left out of release builds, the build that is sold. `-Ptraceveil.onlineVoice=true|false` overrides both.
 * Without it the app never reads `api.env`, never speaks through Sarvam, and its trust screen does not list it.
 */
val onlineVoiceOverride: Boolean? = providers.gradleProperty("traceveil.onlineVoice").orNull?.trim()?.let { value ->
    value.lowercase().toBooleanStrictOrNull() ?: throw GradleException("traceveil.onlineVoice must be true or false, not '$value'")
}

android {
    namespace = "com.dpdpxray.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.dpdpxray.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
        // Release builds target arm64 phones only; this keeps LiteRT-LM's native libs small.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        releaseSigning?.let { s ->
            create("release") {
                storeFile = file(s.getValue("storeFile")!!)
                s["storeType"]?.let { storeType = it }
                storePassword = s.getValue("storePassword")
                keyAlias = s.getValue("keyAlias")
                keyPassword = s.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Debug builds also run on the x86_64 emulator; release stays arm64-only.
            ndk { abiFilters += "x86_64" }
            buildConfigField("boolean", "ONLINE_VOICE_AVAILABLE", (onlineVoiceOverride ?: true).toString())
        }
        release {
            buildConfigField("boolean", "ONLINE_VOICE_AVAILABLE", (onlineVoiceOverride ?: false).toString())
            isMinifyEnabled = false
            // Signed with the TraceVeil release key when one is configured (see releaseSigning above), else unsigned.
            signingConfig = if (releaseSigning != null) signingConfigs.getByName("release") else null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // ConsentLens NPU: the Hexagon skel libraries must exist as files in nativeLibraryDir (the QNN delegate's
        // skel directory), so native libraries are extracted at install time, as Google's LiteRT NPU guide requires.
        jniLibs.useLegacyPackaging = true
        // QNN backends and Hexagon versions ConsentLens never loads (it uses only the HTP backend on the SoCs in
        // NpuBackendPolicy.HTP_ARCH: V69, V73, V75, V79, V81). The delegate opens a backend library only when selected.
        jniLibs.excludes += listOf(
            "**/libQnnDsp.so", "**/libQnnDspV66Skel.so", "**/libQnnDspV66Stub.so", "**/libQnnGpu.so",
            "**/libQnnHtpV68Skel.so", "**/libQnnHtpV68Stub.so",
        )
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.litertlm.android)
    implementation(libs.vosk.android)
    // ConsentLens-1 on the NPU: LiteRT runtime + Qualcomm QNN TFLite delegate and HTP runtime (arm64-v8a only).
    implementation(libs.litert)
    implementation(libs.qnn.litert.delegate)
    implementation(libs.qnn.runtime)

    testImplementation(libs.junit)
    // Test-only certificate chain builder for evidence signing (core/src/testFixtures).
    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.kotlinx.coroutines.test)
}
