plugins {
	id("com.android.library")
}

// The Palm OS look shared by Date Book and the launcher: colors, font, Palm px, forms, menus and
// status bar handling.
android {
	namespace = "com.rearman.palmui"
	compileSdk = 37

	defaultConfig {
		minSdk = 26
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
	testImplementation("junit:junit:4.13.2")
}
