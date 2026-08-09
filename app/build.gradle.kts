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

        // Auto-increments on every CI build (GITHUB_RUN_NUMBER is set
        // automatically by Actions for every job). This is what lets a
        // freshly-built APK be installed as an UPDATE over whatever's
        // already on the phone instead of Android rejecting it as a
        // same-or-lower version — a plain "1" here forever was the other
        // half of why every build needed a clean reinstall (the other
        // half being the signing key changing every run — see
        // signingConfigs below).
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "0.3-" + (System.getenv("GITHUB_RUN_NUMBER") ?: "dev")

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

    // A stable signing identity, reused across every CI build (see
    // docs/apk-updates-and-signing.md for how the keystore gets there).
    // Without this, Gradle's default debug keystore is regenerated fresh
    // on every CI run, so every APK has a different signature and Android
    // refuses to treat it as an "update" — it demands an uninstall first,
    // which wipes the app's data (including your logged-in Telegram
    // session). Only applied when the keystore is actually present, so
    // local builds without it still fall back to the normal ephemeral
    // debug key.
    val hasCiKeystore = localProps.getProperty("KEYSTORE_PATH") != null
    signingConfigs {
        if (hasCiKeystore) {
            create("ci") {
                storeFile = file(localProps.getProperty("KEYSTORE_PATH"))
                storePassword = localProps.getProperty("KEYSTORE_PASSWORD")
                keyAlias = localProps.getProperty("KEY_ALIAS")
                keyPassword = localProps.getProperty("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            if (hasCiKeystore) {
                signingConfig = signingConfigs.getByName("ci")
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasCiKeystore) {
                signingConfig = signingConfigs.getByName("ci")
            }
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
