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

/** The Palm Desktop look: the Windows classic look and feel where it exists, the Palm Desktop fonts and the Palm colors. */
object Theme {
	/** The installed font family names, read once. */
	private val installed: Set<String> = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
	/** The first of [names] that is installed, else the logical dialog font. */
	private fun pick(vararg names: String): String = names.firstOrNull { it in installed } ?: Font.DIALOG
	/** The TrueType MS Sans Serif, the Palm Desktop dialog font. */
	private val sans = pick("Microsoft Sans Serif", "Liberation Sans", "Arial")
	/** Arial, the Palm Desktop view and launcher font. */
	private val arial = pick("Arial", "Liberation Sans")
	/** MS Sans Serif 8 pt, the small Windows dialog font. */
	val font: Font = Font(sans, Font.PLAIN, 11)
	/** MS Sans Serif 8 pt, bold. */
	val bold: Font = Font(sans, Font.BOLD, 11)
	/** The launcher button labels: Arial, 16 px cell. */
	val launcher: Font = Font(arial, Font.PLAIN, 14)
	/** The Date view title: Arial Bold 10 pt. */
	val title: Font = Font(arial, Font.BOLD, 13)
	/** The To Do List and Memo Pad captions: Arial Bold, as in Palm Desktop. */
	val caption: Font = Font(arial, Font.BOLD, 14)
	/** The Day and Week view time labels and event text: Arial, 15 px cell. */
	val view: Font = Font(arial, Font.PLAIN, 13)
	val dateRed: Color = Color(255, 0, 0)
	val select: Color = Color(0, 120, 215)
	val grid: Color = Color(160, 160, 160)
	/** Days of other months: the panel gray, as in the Palm Desktop Month view. */
	val otherMonth: Color get() = panel
	/** The 3D shadow gray of a raised cell. */
	val shadow: Color = Color(160, 160, 160)
	/** The plain black To Do check box of Palm Desktop. Set it as the icon of a JCheckBox; it draws the box checked when the button is selected. */
	val plainCheck: javax.swing.Icon = object : javax.swing.Icon {
		override fun getIconWidth(): Int = 13
		override fun getIconHeight(): Int = 13
		override fun paintIcon(c: java.awt.Component?, g: java.awt.Graphics, x: Int, y: Int) {
			g.color = Color.WHITE
			g.fillRect(x + 1, y + 1, 11, 11)
			g.color = Color.BLACK
			g.drawRect(x + 1, y + 1, 10, 10)
			if ((c as? javax.swing.AbstractButton)?.isSelected == true) {
				g.drawLine(x + 3, y + 6, x + 5, y + 8)
				g.drawLine(x + 5, y + 8, x + 9, y + 4)
				g.drawLine(x + 3, y + 7, x + 5, y + 9)
				g.drawLine(x + 5, y + 9, x + 9, y + 5)
			}
		}
	}
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
 * A small arrow button. The UI font has no arrow characters, so the arrow is drawn. [direction] is a
 * SwingConstants value such as WEST or EAST.
 */
fun arrowButton(direction: Int, action: () -> Unit): javax.swing.JButton =
	javax.swing.plaf.basic.BasicArrowButton(direction).apply { addActionListener { action() } }

/** Draws the Palm untimed-row mark, a filled diamond, centered on (cx, cy). */
fun drawDiamond(g: java.awt.Graphics, cx: Int, cy: Int, r: Int = 3) {
	g.fillPolygon(intArrayOf(cx, cx + r, cx, cx - r), intArrayOf(cy - r, cy, cy + r, cy), 4)
}

/** Draws a raised cell as Palm Desktop draws each hour of the time column: panel gray, a white top edge and a shadow bottom edge. */
fun raisedCell(g: java.awt.Graphics, x: Int, y: Int, w: Int, h: Int) {
	g.color = Theme.panel
	g.fillRect(x, y, w, h)
	g.color = java.awt.Color.WHITE
	g.drawLine(x, y, x + w - 1, y)
	g.color = Theme.shadow
	g.drawLine(x, y + h - 1, x + w - 1, y + h - 1)
}
