plugins {
    id("com.android.application")
}

// The Date Book watch app for Wear OS 7: the Date Book and To Do tiles (Tiles and ProtoLayout
// Material 3) and the screen they open (plain Android Views). It shares the applicationId and the
// key of datebook/app, which the Wear Data Layer requires, so the phone's WearBridge can send it
// the agenda. This module alone uses AndroidX.
android {
    namespace = "com.rearman.datebook.wear"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.rearman.datebook"
        // Wear OS 7 (API 37), the watch of the user.
        minSdk = 37
        targetSdk = 37
        versionCode = 4
        versionName = "1.3"
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

// Stable releases, pinned (2026-11).
dependencies {
    // WearAgenda (the payload codec the phone shares) and Fmt (the Date Book date and time text).
    implementation(project(":sync-core"))
    implementation("androidx.wear.tiles:tiles:1.6.2")
    implementation("androidx.wear.protolayout:protolayout:1.4.2")
    implementation("androidx.wear.protolayout:protolayout-material3:1.4.2")
    // SuspendToFutureAdapter: a tile request as a suspend function.
    implementation("androidx.concurrent:concurrent-futures-ktx:1.3.0")
    // Dispatchers.Main of the watch screen.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
}
