package com.rearman.palmdesktop

import com.rearman.datebook.Fmt
import com.rearman.datebook.Occ
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.swing.JPanel
import javax.swing.SwingUtilities

private const val NAME_H = 18
private const val NUM_PAD = 2
private const val WEEKS = 6

private val MONTH_TITLE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM, yyyy", Locale.US)

private fun smallFont(): Font = Theme.font.deriveFont(10f)

/** Shortens [text] with "..." so it fits in [w] pixels. */
private fun fitText(text: String, fm: FontMetrics, w: Int): String {
	if (fm.stringWidth(text) <= w) return text
	var s = text
	while (s.isNotEmpty() && fm.stringWidth("$s...") > w) s = s.dropLast(1)
	return "$s..."
}

/** Month view: a grid of six weeks, with the events of each day in its cell. */
class MonthView : JPanel(BorderLayout()) {
	private val nav = DateNav("month") { dir -> App.day = Fmt.ld(App.day).plusMonths(dir.toLong()).toEpochDay(); App.refresh() }
	private var gridStart = 0L
	private var days: List<List<Occ>> = emptyList()
	private val grid = MonthGrid()

	init {
		add(nav, BorderLayout.NORTH)
		add(grid, BorderLayout.CENTER)
		App.onRefresh { rebuild() }
		rebuild()
	}

	private fun rebuild() {
		val first = Fmt.ld(App.day).withDayOfMonth(1)
		gridStart = first.toEpochDay() - first.dayOfWeek.value % 7
		days = (0 until 7 * WEEKS).map { visibleEvents(gridStart + it) }
		nav.setTitle(MONTH_TITLE.format(first))
		grid.repaint()
	}

	private fun cellClicked(e: MouseEvent) {
		val i = grid.cellAt(e.x, e.y)
		if (i < 0) return
		App.day = gridStart + i
		App.refresh()
		if (e.clickCount < 2) return
		val r = grid.cellRect(i)
		val inLines = e.y >= r.y + grid.numberH()
		val occ = if (inLines) grid.cellLines(i, r).getOrNull((e.y - r.y - grid.numberH()) / grid.lineH()) else null
		if (occ != null) {
			EventDialog.edit(this, occ.event, occ.day)
		} else {
			(SwingUtilities.getAncestorOfClass(DatePanel::class.java, this) as? DatePanel)?.showMode("day")
		}
	}

	private inner class MonthGrid : JPanel() {
		init {
			addMouseListener(object : MouseAdapter() {
				override fun mouseClicked(e: MouseEvent) = cellClicked(e)
			})
		}

		fun cellW(): Int = width / 7

		fun cellH(): Int = (height - NAME_H) / WEEKS

		fun cellRect(i: Int): Rectangle = Rectangle((i % 7) * cellW(), NAME_H + (i / 7) * cellH(), cellW(), cellH())

		/** Index of the cell at (x, y), or -1 on the name row or before the first layout. */
		fun cellAt(x: Int, y: Int): Int {
			if (y < NAME_H || cellW() == 0 || cellH() == 0) return -1
			val row = ((y - NAME_H) / cellH()).coerceAtMost(WEEKS - 1)
			val col = (x / cellW()).coerceAtMost(6)
			return row * 7 + col
		}

		/** Height of the date number area at the top of a cell. */
		fun numberH(): Int = getFontMetrics(Theme.font).height + NUM_PAD

		fun lineH(): Int = getFontMetrics(smallFont()).height

		/** Lines of cell [i]. A null entry is the "..." line for the events that do not fit. */
		fun cellLines(i: Int, r: Rectangle): List<Occ?> {
			val room = (r.height - numberH()) / lineH()
			if (room <= 0) return emptyList()
			val occ = days[i]
			if (occ.size <= room) return occ
			return occ.take(room - 1) + listOf<Occ?>(null)
		}

		override fun paintComponent(g: Graphics) {
			super.paintComponent(g)
			val shown = Fmt.ld(App.day).monthValue
			val today = todayEpoch()
			g.color = Color.WHITE
			g.fillRect(0, 0, width, height)

			// Day name row.
			g.font = Theme.font
			val fm = g.fontMetrics
			g.color = Theme.panel
			g.fillRect(0, 0, width, NAME_H)
			for (c in 0 until 7) {
				val x = c * cellW()
				val name = Fmt.dowLong[c]
				g.color = Color.BLACK
				g.drawString(name, x + (cellW() - fm.stringWidth(name)) / 2, (NAME_H + fm.ascent - fm.descent) / 2)
				g.color = Theme.grid
				g.drawLine(x, 0, x, NAME_H)
			}

			for (i in 0 until 7 * WEEKS) {
				val r = cellRect(i)
				val date = Fmt.ld(gridStart + i)
				val other = date.monthValue != shown
				val cg = g.create() as Graphics2D
				cg.clip(r)
				cg.color = if (other) Theme.otherMonth else Color.WHITE
				cg.fillRect(r.x, r.y, r.width, r.height)
				cg.color = Theme.grid
				cg.drawRect(r.x, r.y, r.width - 1, r.height - 1)

				// Date number at the top right. Today is red on the select color.
				cg.font = Theme.font
				val nfm = cg.fontMetrics
				val num = date.dayOfMonth.toString()
				val nw = nfm.stringWidth(num)
				val nx = r.x + r.width - nw - 3
				if (gridStart + i == today) {
					cg.color = Theme.select
					cg.fillRect(nx - 2, r.y + 1, nw + 4, nfm.height)
					cg.color = Theme.dateRed
				} else {
					cg.color = if (other) Color.GRAY else Color.BLACK
				}
				cg.drawString(num, nx, r.y + nfm.ascent + 1)

				// Event lines.
				cg.font = smallFont()
				val sfm = cg.fontMetrics
				cg.color = Color.BLACK
				var ly = r.y + numberH()
				for (o in cellLines(i, r)) {
					var x = r.x + 3
					if (o == null) {
						cg.drawString("...", x, ly + sfm.ascent)
					} else {
						if (o.event.isPrivate) {
							Icons.privateRecord().paintIcon(this, cg, x, ly)
							x += 13
						}
						cg.drawString(fitText(occLabel(o), sfm, r.x + r.width - x - 2), x, ly + sfm.ascent)
					}
					ly += sfm.height
				}
				cg.dispose()
			}
		}
	}
}
