package com.rearman.palmdesktop

import java.awt.Color
import java.awt.Font
import java.util.Collections
import javax.swing.UIManager
import javax.swing.plaf.metal.DefaultMetalTheme
import javax.swing.plaf.metal.MetalLookAndFeel

/** The Palm Desktop look: the Windows classic look and feel where it exists, one font and the Palm colors. */
object Theme {
	val font: Font = Font(Font.DIALOG, Font.PLAIN, 12)
	val bold: Font = Font(Font.DIALOG, Font.BOLD, 12)
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
		for (key in Collections.list(UIManager.getDefaults().keys())) {
			if (UIManager.get(key) is Font) UIManager.put(key, font)
		}
	}
}
