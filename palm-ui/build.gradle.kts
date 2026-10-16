plugins {
	id("com.android.library")
	id("org.jetbrains.kotlin.android")
}

// The Palm OS look shared by Date Book and the launcher: colors, font, Palm px, forms, menus and
// status bar handling.
android {
	namespace = "com.rearman.palmui"
	compileSdk = 34

	defaultConfig {
		minSdk = 26
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
