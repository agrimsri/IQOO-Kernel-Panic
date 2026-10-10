plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.leakyshop.demo"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.leakyshop.demo"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    flavorDimensions += "mode"
    productFlavors {
        // Behaviour flags (Shop.kt): COMPLIANT picks the fixed consent dialog; the others switch one leaky behaviour each.
        //   TRACKERS_AT_LAUNCH     tracker lookups in Application.onCreate, before any choice (check C1)
        //   TRACKERS_AFTER_REJECT  tracker lookups after "Reject all" (check C2)
        //   PRE_TICKED             the "Share usage data" box starts ticked (check C3)
        //   REJECT_ON_FIRST_SCREEN a Reject button on the first dialog instead of only behind "Manage" (check C4)
        //   NAG_AFTER_REJECT       5 s after "Reject all", a second popup with a countdown, a big "Allow & save" button
        //                          and a grey "No, I prefer paying more" text link (dark-pattern demo: nagging, false
        //                          urgency, confirm shaming, interface interference). It contacts no host and the first
        //                          popup is unchanged, so the score stays the same. leaky only.
        create("leaky") {
            dimension = "mode"
            buildConfigField("boolean", "COMPLIANT", "false")
            buildConfigField("boolean", "TRACKERS_AT_LAUNCH", "true")
            buildConfigField("boolean", "TRACKERS_AFTER_REJECT", "true")
            buildConfigField("boolean", "PRE_TICKED", "true")
            buildConfigField("boolean", "REJECT_ON_FIRST_SCREEN", "false")
            buildConfigField("boolean", "NAG_AFTER_REJECT", "true")
            resValue("string", "app_name", "LeakyShop")
        }
        create("fixed") {
            dimension = "mode"
            applicationIdSuffix = ".fixed"
            buildConfigField("boolean", "COMPLIANT", "true")
            buildConfigField("boolean", "TRACKERS_AT_LAUNCH", "false")
            buildConfigField("boolean", "TRACKERS_AFTER_REJECT", "false")
            buildConfigField("boolean", "PRE_TICKED", "false")
            buildConfigField("boolean", "REJECT_ON_FIRST_SCREEN", "true")
            buildConfigField("boolean", "NAG_AFTER_REJECT", "false")
            resValue("string", "app_name", "LeakyShop Fixed")
        }
        // The demo UPDATE of the leaky build: same applicationId and debug signing, a higher versionCode, so
        // `adb install -r` installs it over 1.0. A partial fix: Reject on the first screen and no tracker lookups after
        // Reject, but trackers are still looked up at launch before any choice and the box is still pre-ticked.
        create("leakyV11") {
            dimension = "mode"
            versionCode = 2
            versionName = "1.1"
            buildConfigField("boolean", "COMPLIANT", "false")
            buildConfigField("boolean", "TRACKERS_AT_LAUNCH", "true")
            buildConfigField("boolean", "TRACKERS_AFTER_REJECT", "false")
            buildConfigField("boolean", "PRE_TICKED", "true")
            buildConfigField("boolean", "REJECT_ON_FIRST_SCREEN", "true")
            buildConfigField("boolean", "NAG_AFTER_REJECT", "false")
            resValue("string", "app_name", "LeakyShop")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
}
