package com.rearman.palmdesktop

import java.awt.Color
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.util.Collections
import javax.swing.UIManager
import javax.swing.plaf.metal.DefaultMetalTheme
import javax.swing.plaf.metal.MetalLookAndFeel

/** The Palm Desktop look: the Windows classic look and feel where it exists, one font and the Palm colors. */
object Theme {
	/** The small Windows dialog font. Tahoma when it is installed, else the logical dialog font. */
	private val family: String =
		if (GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.contains("Tahoma")) "Tahoma" else Font.DIALOG
	val font: Font = Font(family, Font.PLAIN, 11)
	val bold: Font = Font(family, Font.BOLD, 11)
	val dateRed: Color = Color(255, 0, 0)
	val select: Color = Color(0, 120, 215)
	val grid: Color = Color(160, 160, 160)
	val otherMonth: Color = Color(240, 240, 240)
	val panel: Color get() = UIManager.getColor("Panel.background")

	/** Sets the look and feel and the default fonts. Call it once, before any window is built. */
	fun install() {
		val windows = System.getProperty("os.name").startsWith("Windows")
		val classic = windows && runCatching {
			UIManager.setLookAndFeel("com.sun.java.swing.plaf.windows.WindowsClassicLookAndFeel")
		}.isSuccess
		if (!classic) {
			MetalLookAndFeel.setCurrentTheme(DefaultMetalTheme())
			UIManager.setLookAndFeel(MetalLookAndFeel())
		}
		// Copy the keys first: the defaults table is not changed while it is read.
		val defaults = UIManager.getLookAndFeelDefaults()
		for (key in Collections.list(defaults.keys())) {
			if (key is String && key.endsWith(".font")) UIManager.put(key, font)
		}
	}
}
