plugins {
	id("org.jetbrains.kotlin.jvm")
}

// The sync code of Date Book: key derivation, record sealing and the palmsync HTTP client. Android
// has org.json built in, and the tests add it. The desktop apps port it (desktop-rs/, desktop-cpp/).
kotlin {
	jvmToolchain(17)
}

dependencies {
	compileOnly("org.json:json:20231013")
	testImplementation("junit:junit:4.13.2")
	testImplementation("org.json:json:20231013")
}
