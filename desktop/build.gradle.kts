plugins {
	id("org.jetbrains.kotlin.jvm")
	application
}

// Palm Desktop for Windows and Linux (Swing). CI packages it with jpackage.
kotlin {
	jvmToolchain(17)
}

application {
	mainClass.set("com.rearman.palmdesktop.MainKt")
}

dependencies {
	implementation(project(":sync-core"))
	implementation("org.json:json:20231013")
	testImplementation("junit:junit:4.13.2")
}
