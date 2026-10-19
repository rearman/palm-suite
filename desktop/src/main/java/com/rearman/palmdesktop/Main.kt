package com.rearman.palmdesktop

import com.rearman.datebook.Fmt
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import javax.swing.SwingUtilities

/**
 * Starts Palm Desktop on the Swing event thread. The store is opened before the window, so every
 * view reads the saved records. Sync starts last, so its callbacks reach a ready window.
 */
fun main() {
	SwingUtilities.invokeLater {
		Theme.install()
		Fmt.h24 = systemUses24Hours()
		App.store = DesktopStore.open(File(Settings.configDir(), "desktop-data.json"))
		App.sync = DesktopSync(App.store)
		App.frame = MainFrame()
		App.frame.extendedState = java.awt.Frame.MAXIMIZED_BOTH
		App.frame.isVisible = true
		App.sync.onApplied = { App.refresh() }
		App.sync.onStatus = { App.status(it) }
		App.sync.start()
	}
}

/**
 * True when the system shows the time in 24-hour form. Java reads only the locale, so on Windows
 * the short time format comes from the Regional settings in the registry.
 */
private fun systemUses24Hours(): Boolean {
	if (System.getProperty("os.name").startsWith("Windows")) {
		val format = runCatching {
			val p = ProcessBuilder("reg", "query", """HKCU\Control Panel\International""", "/v", "sShortTime")
				.redirectErrorStream(true).start()
			val out = p.inputStream.bufferedReader().readText()
			p.waitFor()
			Regex("""sShortTime\s+REG_SZ\s+(\S+)""").find(out)?.groupValues?.get(1)
		}.getOrNull()
		if (format != null) return format.contains('H')
	}
	val pattern = (DateFormat.getTimeInstance(DateFormat.SHORT) as? SimpleDateFormat)?.toPattern() ?: ""
	return pattern.contains('H')
}
