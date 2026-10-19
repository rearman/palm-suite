package com.rearman.palmdesktop

import java.io.File
import java.util.Properties

/**
 * Settings in desktop.properties, in [configDir]. The token and the key are saved in a file that
 * only the user can read. The records in desktop-data.json are plain text, as in Palm Desktop.
 *
 * Use it on the Swing event thread only. Each set saves the file at once.
 */
object Settings {
	private const val FILE_NAME = "desktop.properties"
	private const val COMMENT = "The token and the key are saved in a file that only the user can read. " +
		"The records in desktop-data.json are plain text, as in Palm Desktop."

	private val props = Properties()

	init {
		runCatching {
			val file = File(configDir(), FILE_NAME)
			if (file.isFile) file.inputStream().use { props.load(it) }
		}
	}

	/** %APPDATA%\palm-suite, else $XDG_CONFIG_HOME/palm-suite, else ~/.config/palm-suite. */
	fun configDir(): File {
		val appData = System.getenv("APPDATA")
		if (!appData.isNullOrEmpty()) return File(appData, "palm-suite")
		val xdg = System.getenv("XDG_CONFIG_HOME")
		if (!xdg.isNullOrEmpty()) return File(xdg, "palm-suite")
		return File(System.getProperty("user.home"), ".config/palm-suite")
	}

	fun get(key: String, default: String): String = props.getProperty(key, default)

	fun set(key: String, value: String) {
		props.setProperty(key, value)
		save()
	}

	fun remove(key: String) {
		props.remove(key)
		save()
	}

	fun getInt(key: String, default: Int): Int = props.getProperty(key)?.toIntOrNull() ?: default

	fun setInt(key: String, value: Int) = set(key, value.toString())

	fun getBool(key: String, default: Boolean): Boolean =
		props.getProperty(key)?.toBooleanStrictOrNull() ?: default

	fun setBool(key: String, value: Boolean) = set(key, value.toString())

	private fun save() {
		val dir = configDir()
		dir.mkdirs()
		val file = File(dir, FILE_NAME)
		file.outputStream().use { props.store(it, COMMENT) }
		if (!System.getProperty("os.name").startsWith("Windows")) {
			file.setReadable(false, false)
			file.setWritable(false, false)
			file.setReadable(true, true)
			file.setWritable(true, true)
		}
	}
}
