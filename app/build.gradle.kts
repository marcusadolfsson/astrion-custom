import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Optional local settings live in a gitignored secrets.properties (see
// secrets.properties.example): the release signing keystore, and — only as a
// legacy fallback — HA credentials, which the app normally gets at runtime from
// its setup server. Falls back to blanks so a fresh clone still compiles.
val secrets = Properties().apply {
    val f = rootProject.file("secrets.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(key: String, default: String) = (secrets.getProperty(key) ?: default)

android {
    namespace = "com.custom.astrion"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.custom.astrion"
        // Astrion HA100 runs Android 8.1 (API 27). minSdk 26 keeps a little
        // headroom while covering the device; targetSdk stays modern.
        minSdk = 26
        targetSdk = 34
        versionCode = 46
        versionName = "0.26.4"

        buildConfigField("String", "HA_URL", "\"${secret("haUrl", "http://YOUR_HA_IP:8123")}\"")
        buildConfigField("String", "HA_TOKEN", "\"${secret("haToken", "YOUR_LONG_LIVED_ACCESS_TOKEN")}\"")
    }

    // Release signing. The keystore lives OUTSIDE the repo at a path given by
    // secrets.properties (releaseKeystore/...); it must be the SAME key for
    // every build or Android refuses to update the installed app in place.
    signingConfigs {
        create("release") {
            val ks = secret("releaseKeystore", "")
            if (ks.isNotEmpty() && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = secret("releaseKeystorePassword", "")
                keyAlias = secret("releaseKeyAlias", "astrion")
                keyPassword = secret("releaseKeyPassword", "")
            }
        }
    }

    buildTypes {
        release {
            // R8: strip unused code/resources and optimise. The app ships to a
            // 1 GB MT6580 on Android 8.1, where UI-thread CPU work is the main
            // source of scroll jank — and debug builds add real per-frame cost
            // (debuggable ART, Compose live literals + source information).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
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
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    // Extended icon set (FastRewind, PowerSettingsNew, etc.) used by cards.
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // OkHttp provides the WebSocket transport.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
