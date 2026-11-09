plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The Date Book watch app for Wear OS 7: the agenda widget (Glance Wear and Remote Compose). It
// shares the applicationId and the key of datebook/app, which the Wear Data Layer requires, so the
// phone's WearBridge can send it the agenda. This module alone uses AndroidX and Compose.
android {
    namespace = "com.rearman.datebook.wear"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.rearman.datebook"
        // Wear OS 7 (API 37): Wear Widgets exist only there.
        minSdk = 37
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    // The key of datebook/app, by path: the Data Layer pairs only apps with the same key.
    signingConfigs {
        getByName("debug") {
            storeFile = file("../app/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

// Alpha releases, pinned: the versions of the Wear Widgets get-started guide (2026-11).
dependencies {
    // WearAgenda (the payload codec the phone shares) and Fmt (the Date Book date and time text).
    implementation(project(":sync-core"))
    implementation("androidx.glance.wear:wear:1.0.0-alpha22")
    implementation("androidx.glance.wear:wear-core:1.0.0-alpha22")
    implementation("androidx.compose.remote:remote-creation-compose:1.0.0-alpha23")
    implementation("androidx.compose.remote:remote-core:1.0.0-alpha23")
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
}
