plugins {
	id("org.jetbrains.kotlin.jvm")
}

// Sync code that runs on Android and on the desktop: key derivation, record sealing and the
// palmsync HTTP client. Android has org.json built in; the desktop app adds it.
kotlin {
	jvmToolchain(17)
}

dependencies {
	compileOnly("org.json:json:20231013")
	testImplementation("junit:junit:4.13.2")
	testImplementation("org.json:json:20231013")
}
