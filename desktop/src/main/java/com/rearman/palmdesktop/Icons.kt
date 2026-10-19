package com.rearman.palmdesktop

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import javax.swing.Icon
import javax.swing.ImageIcon

/**
 * The toolbar, launcher and marker icons. Each icon is drawn with Java2D, so no image files are
 * needed. Every icon uses a 32 by 32 grid, scaled down to its size.
 */
object Icons {
	fun date(): Icon = icon(32) {
		shape(rect(5, 7, 22, 21), Color.WHITE)
		shape(rect(5, 7, 22, 6), RED)
		shape(rect(9, 2, 3, 7), INK)
		shape(rect(20, 2, 3, 7), INK)
		for (row in 0..2) for (col in 0..3) shape(rect(9 + col * 4, 16 + row * 4, 2, 2), GRAY)
	}

	fun todo(): Icon = icon(32) {
		shape(roundRect(4, 5, 24, 25, 4), Color.WHITE)
		shape(roundRect(10, 2, 12, 6, 3), Color(150, 150, 150))
		line(poly(9, 12, 12, 15, 17, 8, close = false), GREEN)
		shape(rect(19, 11, 7, 2), GRAY)
		line(poly(9, 22, 12, 25, 17, 18, close = false), GREEN)
		shape(rect(19, 21, 7, 2), GRAY)
	}

	fun memo(): Icon = icon(32) {
		shape(roundRect(6, 5, 20, 24, 3), Color.WHITE)
		shape(rect(9, 2, 3, 5), INK)
		shape(rect(15, 2, 3, 5), INK)
		shape(rect(21, 2, 3, 5), INK)
		line(Line2D.Double(10.0, 13.0, 22.0, 13.0), GRAY)
		line(Line2D.Double(10.0, 17.0, 20.0, 17.0), GRAY)
		line(Line2D.Double(10.0, 21.0, 18.0, 21.0), GRAY)
		shape(poly(13, 27, 16, 21, 26, 9, 30, 12, 20, 24), Color(250, 200, 0))
		shape(poly(13, 27, 16, 21, 20, 24), Color(60, 60, 60))
	}

	fun save(): Icon = icon(16) {
		shape(roundRect(4, 4, 24, 24, 3), Color(40, 80, 160))
		shape(rect(9, 4, 14, 9), Color(220, 220, 220))
		shape(rect(8, 18, 16, 10), Color.WHITE)
	}

	fun print(): Icon = icon(16) {
		shape(rect(9, 3, 14, 8), Color.WHITE)
		shape(roundRect(3, 11, 26, 11, 3), Color(150, 150, 150))
		shape(rect(9, 19, 14, 10), Color.WHITE)
		shape(rect(22, 14, 3, 2), GREEN)
		line(Line2D.Double(11.0, 23.0, 21.0, 23.0), GRAY)
		line(Line2D.Double(11.0, 26.0, 19.0, 26.0), GRAY)
	}

	fun cut(): Icon = icon(16) {
		line(Line2D.Double(10.0, 21.0, 21.0, 6.0), INK)
		line(Line2D.Double(22.0, 21.0, 11.0, 6.0), INK)
		shape(oval(5, 21, 8, 8), null)
		shape(oval(19, 21, 8, 8), null)
	}

	fun copy(): Icon = icon(16) {
		shape(rect(11, 4, 15, 19), Color.WHITE)
		shape(rect(6, 9, 15, 19), Color.WHITE)
		line(Line2D.Double(9.0, 15.0, 18.0, 15.0), GRAY)
		line(Line2D.Double(9.0, 19.0, 18.0, 19.0), GRAY)
		line(Line2D.Double(9.0, 23.0, 16.0, 23.0), GRAY)
	}

	fun paste(): Icon = icon(16) {
		shape(roundRect(6, 6, 20, 24, 3), Color(200, 160, 100))
		shape(rect(9, 10, 14, 18), Color.WHITE)
		shape(roundRect(11, 3, 10, 6, 2), Color(150, 150, 150))
		line(Line2D.Double(11.0, 15.0, 21.0, 15.0), GRAY)
		line(Line2D.Double(11.0, 19.0, 21.0, 19.0), GRAY)
		line(Line2D.Double(11.0, 23.0, 17.0, 23.0), GRAY)
	}

	fun undo(): Icon = icon(16) {
		line(Arc2D.Double(6.0, 8.0, 20.0, 16.0, 0.0, 180.0, Arc2D.OPEN), INK)
		shape(poly(1, 15, 11, 15, 6, 23), INK)
	}

	fun find(): Icon = icon(16) {
		shape(rect(12, 6, 8, 10), Color(120, 120, 120))
		shape(oval(3, 14, 11, 12), Color(90, 90, 90))
		shape(oval(18, 14, 11, 12), Color(90, 90, 90))
		shape(oval(6, 18, 5, 5), Color(170, 210, 255))
		shape(oval(21, 18, 5, 5), Color(170, 210, 255))
	}

	fun note(): Icon = icon(12) {
		shape(poly(7, 4, 20, 4, 25, 9, 25, 28, 7, 28), Color.WHITE)
		line(Line2D.Double(20.0, 4.0, 20.0, 9.0), INK)
		line(Line2D.Double(20.0, 9.0, 25.0, 9.0), INK)
		line(Line2D.Double(10.0, 14.0, 22.0, 14.0), GRAY)
		line(Line2D.Double(10.0, 18.0, 22.0, 18.0), GRAY)
		line(Line2D.Double(10.0, 22.0, 22.0, 22.0), GRAY)
	}

	fun repeat(): Icon = icon(12) {
		line(Arc2D.Double(5.0, 5.0, 22.0, 22.0, 30.0, 150.0, Arc2D.OPEN), INK)
		line(Arc2D.Double(5.0, 5.0, 22.0, 22.0, 210.0, 150.0, Arc2D.OPEN), INK)
		shape(poly(1, 14, 9, 14, 5, 20), INK)
		shape(poly(23, 18, 31, 18, 27, 12), INK)
	}

	fun alarm(): Icon = icon(12) {
		shape(Arc2D.Double(7.0, 6.0, 18.0, 18.0, 0.0, 180.0, Arc2D.CHORD), GOLD)
		shape(poly(7, 15, 5, 24, 27, 24, 25, 15), GOLD)
		shape(oval(13, 24, 6, 4), INK)
		shape(oval(14, 3, 4, 4), INK)
	}

	fun privateRecord(): Icon = icon(12) {
		shape(rect(13, 14, 15, 3), GOLD)
		shape(oval(3, 9, 11, 11), GOLD)
		shape(oval(6, 12, 5, 5), Color.WHITE)
		line(Line2D.Double(23.0, 17.0, 23.0, 21.0), GOLD)
		line(Line2D.Double(27.0, 17.0, 27.0, 20.0), GOLD)
	}

	fun calendar(): Icon = icon(12) {
		shape(rect(4, 6, 24, 22), Color.WHITE)
		shape(rect(4, 6, 24, 6), RED)
		line(Line2D.Double(10.0, 12.0, 10.0, 28.0), GRAY)
		line(Line2D.Double(16.0, 12.0, 16.0, 28.0), GRAY)
		line(Line2D.Double(22.0, 12.0, 22.0, 28.0), GRAY)
		line(Line2D.Double(4.0, 19.0, 28.0, 19.0), GRAY)
		line(Line2D.Double(4.0, 23.0, 28.0, 23.0), GRAY)
	}

	fun clock(): Icon = icon(12) {
		shape(oval(3, 3, 26, 26), Color.WHITE)
		line(Line2D.Double(16.0, 16.0, 16.0, 8.0), INK)
		line(Line2D.Double(16.0, 16.0, 22.0, 16.0), INK)
	}
}

private val INK = Color(40, 40, 40)
private val GRAY = Color(120, 120, 120)
private val RED = Color(200, 30, 30)
private val GREEN = Color(0, 140, 0)
private val GOLD = Color(240, 190, 40)

/** Draws one icon of [size] pixels. The [paint] block uses the 32 by 32 grid. */
private fun icon(size: Int, paint: Graphics2D.() -> Unit): Icon {
	val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
	val g = image.createGraphics()
	g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
	g.scale(size / 32.0, size / 32.0)
	// A thin pen keeps the small icons at about one pixel.
	g.stroke = BasicStroke(if (size <= 16) 2.6f else 2f)
	g.paint()
	g.dispose()
	return ImageIcon(image)
}

/** Fills [s] with [fill] when given, then draws its outline in ink. */
private fun Graphics2D.shape(s: Shape, inside: Color?) {
	if (inside != null) {
		color = inside
		fill(s)
	}
	color = INK
	draw(s)
}

/** Draws a line or an open path in [c]. */
private fun Graphics2D.line(s: Shape, c: Color) {
	color = c
	draw(s)
}

private fun rect(x: Int, y: Int, w: Int, h: Int): Shape =
	Rectangle2D.Double(x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble())

private fun roundRect(x: Int, y: Int, w: Int, h: Int, arc: Int): Shape =
	RoundRectangle2D.Double(x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble(), arc.toDouble(), arc.toDouble())

private fun oval(x: Int, y: Int, w: Int, h: Int): Shape =
	Ellipse2D.Double(x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble())

/** A polygon from the x, y pairs in [p]. */
private fun poly(vararg p: Int, close: Boolean = true): Shape {
	val path = Path2D.Double()
	path.moveTo(p[0].toDouble(), p[1].toDouble())
	for (i in 2 until p.size step 2) path.lineTo(p[i].toDouble(), p[i + 1].toDouble())
	if (close) path.closePath()
	return path
}
