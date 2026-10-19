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
// The sync code that Date Book shares with other clients.
include(":sync-core")
