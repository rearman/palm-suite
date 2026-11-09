// AGP 9 builds Kotlin itself (built-in Kotlin), so the Android modules apply no Kotlin plugin. The
// kotlin.jvm entry puts KGP 2.2.21 on the build classpath, which AGP's built-in Kotlin then uses in
// place of its own 2.2.10; the Compose compiler plugin must carry the same version. Glance Wear and
// Remote Compose need a Kotlin standard library of 2.2.20 or later.
plugins {
	id("com.android.application") version "9.2.1" apply false
	id("com.android.library") version "9.2.1" apply false
	id("org.jetbrains.kotlin.jvm") version "2.2.21" apply false
	id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
}
