plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// RiskDemo is a harmless TEST app. It only *declares* the permissions and service that fake e-Challan APKs ask for,
// and makes network attempts to reserved names/addresses that can never reach a real server. See riskdemo/README.md.
android {
    namespace = "com.traceveil.riskdemo"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.traceveil.riskdemo"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0-test"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)

    testImplementation(libs.junit)
}
