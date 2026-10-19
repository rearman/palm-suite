package com.rearman.palmdesktop

import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.font.FontRenderContext
import java.awt.font.LineBreakMeasurer
import java.awt.font.TextAttribute
import java.awt.print.PageFormat
import java.awt.print.Printable
import java.awt.print.PrinterException
import java.awt.print.PrinterJob
import java.text.AttributedString
import javax.swing.JOptionPane

/** Prints the lines of an [AppView]. */
object Printing {
	/** The paper and margins from File > Page Setup; null uses the printer default. */
	var pageFormat: PageFormat? = null

	fun print(owner: Component, view: AppView) {
		val (title, lines) = view.printLines()
		val job = PrinterJob.getPrinterJob()
		try {
			job.setPrintable(LinesPrintable(title, lines), pageFormat ?: job.defaultPage())
			if (job.printDialog()) job.print()
		} catch (e: PrinterException) {
			JOptionPane.showMessageDialog(owner, "The print job failed.\n${e.message ?: ""}", "Print", JOptionPane.ERROR_MESSAGE)
		}
	}
}

/** One page of text: the bold title, then the lines in 10 pt. Long lines wrap at the page width. */
private class LinesPrintable(private val title: String, private val lines: List<String>) : Printable {
	private val headFont: Font = Theme.bold.deriveFont(12f)
	private val bodyFont: Font = Theme.font.deriveFont(10f)
	private var pages: List<List<Line>>? = null

	private class Line(val text: String, val font: Font, val ascent: Int, val height: Int)

	override fun print(g: Graphics, pageFormat: PageFormat, pageIndex: Int): Int {
		val g2 = g as Graphics2D
		val all = pages ?: paginate(g2, pageFormat).also { pages = it }
		if (pageIndex >= all.size) return Printable.NO_SUCH_PAGE
		var top = 0
		for (line in all[pageIndex]) {
			g2.font = line.font
			g2.drawString(line.text, 0, top + line.ascent)
			top += line.height
		}
		return Printable.PAGE_EXISTS
	}

	/** Splits the title and lines into rows that fit the printable height of each page. */
	private fun paginate(g2: Graphics2D, pageFormat: PageFormat): List<List<Line>> {
		val frc = g2.fontRenderContext
		val width = pageFormat.imageableWidth.toFloat()
		val maxHeight = pageFormat.imageableHeight
		val pages = mutableListOf(mutableListOf<Line>())
		var used = 0
		fun add(text: String, font: Font) {
			val fm = g2.getFontMetrics(font)
			for (part in wrap(text, font, width, frc)) {
				if (used + fm.height > maxHeight && pages.last().isNotEmpty()) {
					pages.add(mutableListOf())
					used = 0
				}
				pages.last().add(Line(part, font, fm.ascent, fm.height))
				used += fm.height
			}
		}
		add(title, headFont)
		for (line in lines) add(line, bodyFont)
		return pages
	}

	/** Word wrap of [text] to [width] pixels. A new line in [text] starts a new row. */
	private fun wrap(text: String, font: Font, width: Float, frc: FontRenderContext): List<String> {
		val out = mutableListOf<String>()
		for (para in text.split('\n')) {
			if (para.isEmpty()) {
				out.add("")
				continue
			}
			val measurer = LineBreakMeasurer(AttributedString(para, mapOf(TextAttribute.FONT to font)).iterator, frc)
			while (measurer.position < para.length) {
				val start = measurer.position
				val end = measurer.nextOffset(width)
				out.add(para.substring(start, end).trimEnd())
				measurer.position = end
			}
		}
		return out
	}
}
