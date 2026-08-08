import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Reads TELEGRAM_API_ID / TELEGRAM_API_HASH out of local.properties so they
// never get committed to git. Copy local.properties.example -> local.properties
// and fill in your own values from https://my.telegram.org first.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.example.proxytester"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.proxytester"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.2-mtproto-wip"

        // Only build/package the arm64-v8a native slice, per request —
        // smaller APK, single architecture. Remove this block (or add
        // more ABIs) if you need to run on 32-bit or emulator devices.
        ndk {
            abiFilters += "arm64-v8a"
        }

        buildConfigField(
            "int",
            "TELEGRAM_API_ID",
            localProps.getProperty("TELEGRAM_API_ID", "0")
        )
        buildConfigField(
            "String",
            "TELEGRAM_API_HASH",
            "\"${localProps.getProperty("TELEGRAM_API_HASH", "")}\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Networking helper for downloading proxy list files (added in v0.2)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // TDLib is wired up as a local module (:tdlib) — see tdlib/ and
    // docs/tdlib-integration.md. The module is empty until the CI workflow
    // (.github/workflows/build-tdlib-apk.yml) or a manual build populates
    // tdlib/src/main/java and tdlib/src/main/jniLibs with TDLib's real
    // output.
    implementation(project(":tdlib"))

    debugImplementation("androidx.compose.ui:ui-tooling")
}
