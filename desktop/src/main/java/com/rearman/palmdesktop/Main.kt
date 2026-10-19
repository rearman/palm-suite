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
		val timePattern = (DateFormat.getTimeInstance(DateFormat.SHORT) as? SimpleDateFormat)?.toPattern() ?: ""
		Fmt.h24 = timePattern.contains('H')
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
