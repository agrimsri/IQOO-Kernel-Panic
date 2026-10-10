plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
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

    buildTypes {
        debug {
            // Debug builds also run on the x86_64 emulator; release stays arm64-only.
            ndk { abiFilters += "x86_64" }
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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
    testImplementation(libs.kotlinx.coroutines.test)
}
