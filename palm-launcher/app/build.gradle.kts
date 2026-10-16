plugins {
	id("com.android.application")
	id("org.jetbrains.kotlin.android")
}

android {
	namespace = "com.rearman.palmlauncher"
	compileSdk = 34

	defaultConfig {
		applicationId = "com.rearman.palmlauncher"
		minSdk = 26
		targetSdk = 34
		versionCode = 1
		versionName = "1.0"
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
	kotlinOptions {
		jvmTarget = "17"
	}
}

dependencies {
	testImplementation("junit:junit:4.13.2")
}
