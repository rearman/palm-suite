pluginManagement {
	repositories {
		google()
		mavenCentral()
		gradlePluginPortal()
	}
}

dependencyResolutionManagement {
	repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
	repositories {
		google()
		mavenCentral()
	}
}

rootProject.name = "Palm Suite"
// The shared Palm OS look, and the two apps that use it.
include(":palm-ui", ":datebook:app", ":palm-launcher:app")
// The Date Book watch app (Wear OS 7), which shows the agenda that datebook/app sends it.
include(":datebook:wear")
// The sync code of Date Book (the desktop app in desktop-cpp/ ports it).
include(":sync-core")
