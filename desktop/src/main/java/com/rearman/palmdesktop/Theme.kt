package com.rearman.palmdesktop

import java.awt.Color
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.Paint
import java.awt.Rectangle
import java.awt.TexturePaint
import java.awt.image.BufferedImage
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
	/** The Day view fill of an event: the Palm Desktop checkerboard of yellow and white pixels, #FFFF88 on average. */
	val eventFill: Paint by lazy {
		val img = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
		img.setRGB(0, 0, 0xFFFF00)
		img.setRGB(1, 1, 0xFFFF00)
		img.setRGB(1, 0, 0xFFFFFF)
		img.setRGB(0, 1, 0xFFFFFF)
		TexturePaint(img, Rectangle(0, 0, 2, 2))
	}
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
		// Windows hides the Alt key underlines until Alt is pressed. Palm Desktop shows them all the time.
		UIManager.put("Button.showMnemonics", true)
	}
}

/**
 * A small arrow button. Tahoma has no arrow characters, so the arrow is drawn. [direction] is a
 * SwingConstants value such as WEST or EAST.
 */
fun arrowButton(direction: Int, action: () -> Unit): javax.swing.JButton =
	javax.swing.plaf.basic.BasicArrowButton(direction).apply { addActionListener { action() } }

/** Draws the Palm untimed-row mark, a filled diamond, centered on (cx, cy). */
fun drawDiamond(g: java.awt.Graphics, cx: Int, cy: Int, r: Int = 3) {
	g.fillPolygon(intArrayOf(cx, cx + r, cx, cx - r), intArrayOf(cy - r, cy, cy + r, cy), 4)
}
