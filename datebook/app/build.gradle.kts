plugins {
    id("com.android.application")
}

android {
    namespace = "com.rearman.datebook"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.rearman.datebook"
        minSdk = 26
        targetSdk = 34
        versionCode = 18
        versionName = "1.16"
    }

    // A fixed key, kept in the repository, so that each new build
    // installs over the old one and keeps your data.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
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

dependencies {
    implementation(project(":palm-ui"))
    implementation(project(":sync-core"))
    // The Wear Data Layer (WearBridge.kt), which sends the agenda to the watch app in datebook/wear.
    // It is the one dependency outside the repository; it brings androidx.core with it.
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    testImplementation("junit:junit:4.13.2")
}
