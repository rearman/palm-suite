package com.rearman.palmdesktop

import com.rearman.datebook.Fmt
import com.rearman.datebook.Occ
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

private const val HOUR_H = 38
private const val TIME_W = 44
private const val HEAD_H = 20
private const val UNTIMED_LINE = 16
private const val MIN_UNTIMED_H = 20
private const val MIN_OF_DAY = 1440

private val LONG_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US)

/** Occurrences of [day]. Private records are hidden unless App.showPrivate. */
internal fun visibleEvents(day: Long): List<Occ> =
	App.store.dayEvents(day).filter { App.showPrivate || !it.event.isPrivate }

/** Label of an occurrence: "9:00 Staff" for a timed record, the title for an untimed one. */
internal fun occLabel(o: Occ): String =
	if (o.event.untimed) o.event.title else "${Fmt.timeShort(o.event.start)} ${o.event.title}"

internal fun todayEpoch(): Long = LocalDate.now().toEpochDay()

private fun weekStart(day: Long): Long = day - Fmt.ld(day).dayOfWeek.value % 7

/** Event box in the week grid, with the occurrence it shows. */
private class Hit(val rect: Rectangle, val occ: Occ)

/** Panel that takes the width of its scroll viewport, so the header and the grid line up. */
private abstract class ViewportWidthPanel : JPanel(), Scrollable {
	override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
	override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = HOUR_H
	override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = visibleRect.height
	override fun getScrollableTracksViewportWidth(): Boolean = true
	override fun getScrollableTracksViewportHeight(): Boolean = false
}

/** Shared header: the step buttons, the Go to button, the title and the Today button. [mode] is the DatePicker mode. */
internal class DateNav(private val mode: String, private val step: (Int) -> Unit) : JPanel(BorderLayout()) {
	private val title = JLabel("", SwingConstants.CENTER)

	init {
		val left = JPanel(FlowLayout(FlowLayout.LEFT, 2, 2))
		left.add(arrowButton(SwingConstants.WEST) { step(-1) })
		left.add(JButton("Go to").apply { mnemonic = KeyEvent.VK_G; addActionListener { goTo() } })
		left.add(arrowButton(SwingConstants.EAST) { step(1) })
		val right = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 2))
		right.add(JButton("Today").apply {
			mnemonic = KeyEvent.VK_T
			addActionListener { App.day = todayEpoch(); App.refresh() }
		})
		title.font = Theme.bold
		title.isOpaque = true
		title.background = Color.WHITE
		title.border = BorderFactory.createLineBorder(Theme.grid)
		add(left, BorderLayout.WEST)
		add(title, BorderLayout.CENTER)
		add(right, BorderLayout.EAST)
	}

	fun setTitle(text: String) {
		title.text = text
	}

	private fun goTo() {
		DatePicker.pick(this, mode, App.day)?.let {
			App.day = it
			App.refresh()
		}
	}
}

/** Week view: seven day columns, a time grid and an untimed row. */
class WeekView : JPanel(BorderLayout()) {
	private val nav = DateNav("week") { dir -> App.day += 7L * dir; App.refresh() }
	private var start = 0L
	private var days: List<List<Occ>> = emptyList()
	private var untimedH = MIN_UNTIMED_H
	private val header = WeekHeader()
	private val grid = WeekGrid()
	private val scroll = JScrollPane(grid)

	init {
		scroll.setColumnHeaderView(header)
		add(nav, BorderLayout.NORTH)
		add(scroll, BorderLayout.CENTER)
		App.onRefresh { rebuild() }
		rebuild()
		// Start the time grid at 8:00.
		SwingUtilities.invokeLater { scroll.viewport.viewPosition = Point(0, hourY(8)) }
	}

	private fun rebuild() {
		start = weekStart(App.day)
		days = (0 until 7).map { visibleEvents(start + it) }
		val mostUntimed = days.maxOf { d -> d.count { it.event.untimed } }
		untimedH = maxOf(MIN_UNTIMED_H, mostUntimed * UNTIMED_LINE + 4)
		val first = Fmt.ld(start)
		val week = first.get(WeekFields.SUNDAY_START.weekOfYear())
		nav.setTitle("${LONG_DATE.format(first)} - ${LONG_DATE.format(first.plusDays(6))} Week $week")
		grid.revalidate()
		grid.repaint()
		header.repaint()
	}

	/** Y of the top of [hour] in the grid, below the untimed row. */
	private fun hourY(hour: Int): Int = untimedH + hour * HOUR_H

	private fun colW(w: Int): Int = ((w - TIME_W) / 7).coerceAtLeast(1)

	/** Event boxes at grid width [w]. A later box in the list is drawn on top. */
	private fun hits(w: Int): List<Hit> {
		val cw = colW(w)
		val out = ArrayList<Hit>()
		for (i in 0 until 7) {
			val x = TIME_W + i * cw + 1
			var untimedIndex = 0
			for (o in days[i]) {
				val e = o.event
				if (e.untimed) {
					out.add(Hit(Rectangle(x, 2 + untimedIndex * UNTIMED_LINE, cw - 2, UNTIMED_LINE - 2), o))
					untimedIndex++
				} else {
					// Start and end in minutes. A record without an end gets 30 minutes.
					val s = e.start.coerceIn(0, MIN_OF_DAY - 1)
					val end = if (e.end > s) minOf(e.end, MIN_OF_DAY) else minOf(s + 30, MIN_OF_DAY)
					val y = untimedH + s * HOUR_H / 60
					val h = maxOf(12, (end - s) * HOUR_H / 60)
					out.add(Hit(Rectangle(x, y, cw - 2, h), o))
				}
			}
		}
		return out
	}

	private fun gridClicked(e: MouseEvent) {
		if (e.x < TIME_W) return
		App.day = start + ((e.x - TIME_W) / colW(grid.width)).coerceIn(0, 6)
		App.refresh()
		if (e.clickCount < 2) return
		val hit = hits(grid.width).lastOrNull { it.rect.contains(e.point) }
		when {
			hit != null -> EventDialog.edit(this, hit.occ.event, hit.occ.day)
			e.y < untimedH -> EventDialog.edit(this, null, App.day)
			else -> EventDialog.edit(this, null, App.day, (e.y - untimedH) / HOUR_H * 60)
		}
	}

	private fun headerClicked(e: MouseEvent) {
		if (e.x < TIME_W) return
		App.day = start + ((e.x - TIME_W) / colW(header.width)).coerceIn(0, 6)
		App.refresh()
	}

	private inner class WeekGrid : ViewportWidthPanel() {
		init {
			addMouseListener(object : MouseAdapter() {
				override fun mouseClicked(e: MouseEvent) = gridClicked(e)
			})
		}

		override fun getPreferredSize(): Dimension = Dimension(TIME_W + 7 * 60, untimedH + 24 * HOUR_H + 1)

		override fun paintComponent(g: Graphics) {
			super.paintComponent(g)
			val w = width
			val cw = colW(w)
			g.font = Theme.font
			val fm = g.fontMetrics
			g.color = Color.WHITE
			g.fillRect(0, 0, w, height)

			// Hour lines and the time labels.
			g.color = Theme.grid
			for (h in 0..24) g.drawLine(0, hourY(h), w, hourY(h))
			g.color = Color.BLACK
			for (h in 0..23) {
				val label = Fmt.timeShort(h * 60)
				g.drawString(label, TIME_W - 4 - fm.stringWidth(label), hourY(h) + fm.ascent + 1)
			}
			drawDiamond(g, TIME_W / 2, untimedH / 2)

			// Day column lines.
			g.color = Theme.grid
			for (i in 0..7) g.drawLine(TIME_W + i * cw, 0, TIME_W + i * cw, height - 1)

			for (hit in hits(w)) paintBox(g, this, hit.rect, hit.occ)
		}
	}

	private inner class WeekHeader : ViewportWidthPanel() {
		init {
			addMouseListener(object : MouseAdapter() {
				override fun mouseClicked(e: MouseEvent) = headerClicked(e)
			})
		}

		override fun getPreferredSize(): Dimension = Dimension(TIME_W + 7 * 60, HEAD_H)

		override fun paintComponent(g: Graphics) {
			super.paintComponent(g)
			val cw = colW(width)
			g.font = Theme.font
			val fm = g.fontMetrics
			val today = todayEpoch()
			for (i in 0 until 7) {
				val x = TIME_W + i * cw
				val day = start + i
				val isToday = day == today
				g.color = if (isToday) Theme.select else Theme.panel
				g.fillRect(x, 0, cw, height)
				val text = "${Fmt.dowShort[i]} ${Fmt.ld(day).dayOfMonth}"
				g.color = if (isToday) Theme.dateRed else Color.BLACK
				g.drawString(text, x + (cw - fm.stringWidth(text)) / 2, (height + fm.ascent - fm.descent) / 2)
				g.color = Theme.grid
				g.drawLine(x, 0, x, height - 1)
			}
			g.drawLine(TIME_W, height - 1, width, height - 1)
		}
	}
}

/** Draws one event box: light yellow, grid border, the label clipped to the box. */
private fun paintBox(g: Graphics, c: Component, r: Rectangle, o: Occ) {
	g.color = Color(255, 255, 204)
	g.fillRect(r.x, r.y, r.width, r.height)
	g.color = Theme.grid
	g.drawRect(r.x, r.y, r.width - 1, r.height - 1)
	val cg = g.create() as Graphics2D
	cg.clip(r)
	cg.font = Theme.font
	cg.color = Color.BLACK
	var x = r.x + 2
	if (o.event.isPrivate) {
		Icons.privateRecord().paintIcon(c, cg, x, r.y + 1)
		x += 14
	}
	cg.drawString(occLabel(o), x, r.y + cg.fontMetrics.ascent + 1)
	cg.dispose()
}
