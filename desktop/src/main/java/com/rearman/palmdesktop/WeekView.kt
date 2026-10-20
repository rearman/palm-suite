package com.rearman.palmdesktop

import com.rearman.datebook.Event
import com.rearman.datebook.Fmt
import com.rearman.datebook.Occ
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

private const val HOUR_H = 38
private const val TIME_W = 52
private const val HEAD_H = 20
private const val UNTIMED_LINE = 16
private const val MIN_UNTIMED_H = 20
private const val MIN_OF_DAY = 1440
private const val CHANGE_TEXT = "Apply the changes to the current event only or to all occurrences?"

private val LONG_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US)

/** Occurrences of [day]. Private records are hidden unless App.showPrivate. */
internal fun visibleEvents(day: Long): List<Occ> =
	App.store.dayEvents(day).filter { App.showPrivate || !it.event.isPrivate }

internal fun todayEpoch(): Long = LocalDate.now().toEpochDay()

/** Event box in the week grid, with the occurrence it shows. */
private class Hit(val rect: Rectangle, val occ: Occ)

/** A press on a box body (move) or on the blue bar of the selected box (resize), until release. */
private class Drag(val occ: Occ, val rect: Rectangle, val origin: Point, val resize: Boolean) {
	val dur: Int = durOf(occ.event)
	val grabY: Int = origin.y - rect.y
	var moved = false
	var newDay: Long = occ.day
	var newStart: Int = occ.event.start
	var newEnd: Int = occ.event.start + dur
	var outline: Rectangle? = null
}

/** Height of a box for an event of [min] minutes, as the grid draws it. */
private fun boxH(min: Int): Int = maxOf(12, min * HOUR_H / 60 - EventBox.SEL_H)

/** [min] rounded to the nearest 30 minutes. */
private fun round30(min: Int): Int = (Math.round(min / 30.0) * 30).toInt()

/** Length of an event in minutes, 30 when it has no end. */
private fun durOf(e: Event): Int = if (e.end > e.start) e.end - e.start else 30

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
			mnemonic = KeyEvent.VK_O
			addActionListener { App.day = todayEpoch(); App.refresh() }
		})
		title.font = Theme.title
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
	private var selected: Occ? = null
	private var untimedH = MIN_UNTIMED_H
	private var suppressClick = false   // set by a drag, so its release does not also click
	private var drag: Drag? = null

	// The new event field: its column, its hour (null for the untimed row) and its untimed line.
	private var typing: JTextField? = null
	private var typingDay = 0L
	private var typingCol = 0
	private var typingMin: Int? = null
	private var typingLine = 0

	// The title field of a box, open after a double click on its body.
	private var titleField: JTextField? = null
	private var titleOcc: Occ? = null

	private val header = WeekHeader()
	private val grid = WeekGrid()
	private val scroll = JScrollPane(grid)

	init {
		scroll.setColumnHeaderView(header)
		add(nav, BorderLayout.NORTH)
		add(scroll, BorderLayout.CENTER)
		App.onRefresh { rebuild() }
		rebuild()
		// Start the time grid at the work day hour.
		SwingUtilities.invokeLater { scroll.viewport.viewPosition = Point(0, hourY(DateSettings.workDay)) }
	}

	/** The occurrence selected in the grid, or null when it is no longer shown. */
	fun selectedOcc(): Occ? = find(selected)

	/** The occurrence in [days] that matches [occ] by event and day, or null. */
	private fun find(occ: Occ?): Occ? =
		occ?.let { s -> days.flatten().firstOrNull { it.event.id == s.event.id && it.day == s.day } }

	private fun rebuild() {
		// A new week saves the field first, so its text is not lost.
		if (typing != null && weekStartOf(App.day) != start) endTyping(true)
		start = weekStartOf(App.day)
		days = (0 until 7).map { visibleEvents(start + it) }
		selected = find(selected)
		val mostUntimed = days.maxOf { d -> d.count { it.event.untimed } }
		untimedH = maxOf(MIN_UNTIMED_H, mostUntimed * UNTIMED_LINE + 4)
		// An open untimed field keeps its line.
		if (typing != null && typingMin == null) untimedH = maxOf(untimedH, (typingLine + 1) * UNTIMED_LINE + 4)
		val first = Fmt.ld(start)
		val fields = if (DateSettings.weekBegins == 1) WeekFields.ISO else WeekFields.SUNDAY_START
		val week = first.get(fields.weekOfYear())
		nav.setTitle("${LONG_DATE.format(first)} - ${LONG_DATE.format(first.plusDays(6))} Week $week")
		grid.revalidate()
		grid.repaint()
		header.repaint()
	}

	/** Y of the top of [hour] in the grid, below the untimed row. */
	private fun hourY(hour: Int): Int = untimedH + hour * HOUR_H

	private fun colW(w: Int): Int = ((w - TIME_W) / 7).coerceAtLeast(1)

	/** Minutes of the grid row at [y]. */
	private fun minOfY(y: Int): Int = (y - untimedH) * 60 / HOUR_H

	/** Start of the hour at [y] in minutes, or null for the untimed row. */
	private fun slotStart(y: Int): Int? = if (y < untimedH) null else ((y - untimedH) / HOUR_H).coerceIn(0, 23) * 60

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
					out.add(Hit(Rectangle(x, 2 + untimedIndex * UNTIMED_LINE, cw - 3, UNTIMED_LINE - 2), o))
					untimedIndex++
				} else {
					// Start and end in minutes. A record without an end gets 30 minutes.
					val s = e.start.coerceIn(0, MIN_OF_DAY - 1)
					val end = if (e.end > s) minOf(e.end, MIN_OF_DAY) else minOf(s + 30, MIN_OF_DAY)
					val y = untimedH + s * HOUR_H / 60
					out.add(Hit(Rectangle(x, y, cw - 3, boxH(end - s)), o))
				}
			}
		}
		return out
	}

	/** The box under [p] in the grid, or null. */
	private fun hitAt(p: Point): Hit? = hits(grid.width).lastOrNull { it.rect.contains(p) }

	/** Box of the field of a new event, or null when no field is open. */
	private fun typingRect(w: Int): Rectangle? {
		typing ?: return null
		val cw = colW(w)
		val x = TIME_W + typingCol * cw + 1
		val min = typingMin
		return if (min == null) Rectangle(x, 2 + typingLine * UNTIMED_LINE, cw - 3, UNTIMED_LINE - 2)
		else Rectangle(x, untimedH + min * HOUR_H / 60, cw - 3, boxH(60))
	}

	/** Opens a field in the column [col] for the title of a new event at [min], or on untimed [line]. */
	private fun startTyping(col: Int, min: Int?, line: Int) {
		endTyping(true)
		typingDay = start + col
		typingCol = col
		typingMin = min
		typingLine = line
		if (min == null) untimedH = maxOf(untimedH, (line + 1) * UNTIMED_LINE + 4)
		val f = JTextField()
		f.font = Theme.view
		f.border = null
		f.isOpaque = false
		val day = typingDay
		f.addActionListener { endTyping(true) }
		f.registerKeyboardAction({ endTyping(false) }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_FOCUSED)
		f.addFocusListener(object : FocusAdapter() {
			override fun focusLost(e: FocusEvent) {
				if (!e.isTemporary) endTyping(true)
			}
		})
		f.addMouseListener(object : MouseAdapter() {
			override fun mouseClicked(e: MouseEvent) {
				// A double click in the field opens the event dialog, as on an empty hour.
				if (e.clickCount == 2) {
					endTyping(false)
					EventDialog.edit(this@WeekView, null, day, min)
				}
			}
		})
		typing = f
		grid.add(f)
		grid.revalidate()
		grid.repaint()
		f.requestFocusInWindow()
	}

	/** Closes the field. With [save], a title that is not empty becomes a new event. */
	private fun endTyping(save: Boolean) {
		val f = typing ?: return
		typing = null   // first, so the focus loss of the removed field does nothing
		grid.remove(f)
		grid.repaint()
		val title = f.text.trim()
		val day = typingDay
		val min = typingMin
		if (!save || title.isEmpty()) return
		val ev = if (min == null) Event(title = title, date = day, untimed = true)
		else Event(title = title, date = day, start = min, end = min + 60, untimed = false)
		applyAlarmPreset(ev)
		App.store.save(ev)
		App.refresh()
		selected = visibleEvents(day).firstOrNull { it.event.id == ev.id }
		grid.repaint()
	}

	/** Opens the title of [hit] for editing, over the title area of the box. */
	private fun startTitleEdit(hit: Hit) {
		endTitleEdit(true)
		val r = hit.rect
		val x = EventBox.titleX(r, hit.occ.event)
		val f = JTextField(hit.occ.event.title)
		f.font = Theme.view
		f.border = null
		f.isOpaque = false
		f.setBounds(x, r.y + 1, r.x + r.width - EventBox.BAR_W - x, f.preferredSize.height)
		f.addActionListener { endTitleEdit(true) }
		f.registerKeyboardAction({ endTitleEdit(false) }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_FOCUSED)
		f.addFocusListener(object : FocusAdapter() {
			override fun focusLost(e: FocusEvent) {
				if (!e.isTemporary) endTitleEdit(true)
			}
		})
		titleField = f
		titleOcc = hit.occ
		grid.add(f)
		grid.repaint()
		f.requestFocusInWindow()
		f.selectAll()
	}

	/** Closes the title field. With [save], a changed title that is not empty is saved. */
	private fun endTitleEdit(save: Boolean) {
		val f = titleField ?: return
		val occ = titleOcc ?: return
		titleField = null
		titleOcc = null
		grid.remove(f)
		grid.repaint()
		val text = f.text.trim()
		if (!save || text.isEmpty() || text == occ.event.title) return
		changeOccurrence(this, occ, CHANGE_TEXT) { it.title = text }
	}

	/** Closes both fields. A new event is saved only with [save]. */
	private fun closeFields(save: Boolean) {
		endTitleEdit(save)
		endTyping(save)
	}

	/** Mouse press on the grid: starts a move of a box body, or a resize from the blue bar of the selected box. */
	private fun pressed(e: MouseEvent) {
		suppressClick = false
		if (e.isPopupTrigger) {
			gridPopup(e)
			return
		}
		if (!SwingUtilities.isLeftMouseButton(e) || e.x < TIME_W) return
		val sel = selectedOcc()
		val selHit = hits(grid.width).firstOrNull { it.occ === sel }
		if (selHit != null && !selHit.occ.event.untimed && EventBox.onResizeBar(selHit.rect, e.point)) {
			drag = Drag(selHit.occ, selHit.rect, e.point, resize = true)
			return
		}
		val hit = hitAt(e.point) ?: return
		val icon = EventBox.markerAt(hit.rect, hit.occ.event, e.point, hit.occ.event.untimed)
		if (EventBox.onHandle(hit.rect, e.point) || icon != null) return
		selected = hit.occ
		drag = Drag(hit.occ, hit.rect, e.point, resize = false)
		grid.repaint()
	}

	/** Mouse drag: moves the outline of the box to the slot under the mouse. */
	private fun dragged(e: MouseEvent) {
		val d = drag ?: return
		if (!d.moved) {
			if (Math.abs(e.x - d.origin.x) <= 4 && Math.abs(e.y - d.origin.y) <= 4) return
			d.moved = true
		}
		val cw = colW(grid.width)
		val col = ((e.x - TIME_W) / cw).coerceIn(0, 6)
		when {
			d.resize -> {
				d.newEnd = minOf(maxOf(round30(minOfY(e.y)), d.occ.event.start + 30), MIN_OF_DAY)
				d.outline = Rectangle(d.rect.x, d.rect.y, d.rect.width, boxH(d.newEnd - d.occ.event.start))
			}
			d.occ.event.untimed -> {
				d.newDay = start + col
				d.outline = Rectangle(TIME_W + col * cw + 1, d.rect.y, cw - 3, d.rect.height)
			}
			else -> {
				d.newDay = start + col
				d.newStart = minOf(maxOf(round30(minOfY(e.y - d.grabY)), 0), MIN_OF_DAY - d.dur)
				d.newEnd = d.newStart + d.dur
				d.outline = Rectangle(TIME_W + col * cw + 1, untimedH + d.newStart * HOUR_H / 60, cw - 3, boxH(d.dur))
			}
		}
		grid.repaint()
	}

	/** Mouse release: saves a moved box or a resized box. A drag is not also a click. */
	private fun released(e: MouseEvent) {
		if (e.isPopupTrigger) {
			drag = null
			gridPopup(e)
			return
		}
		val d = drag ?: return
		drag = null
		grid.repaint()
		if (!d.moved) return
		suppressClick = true
		val occ = d.occ
		val ev = occ.event
		when {
			d.resize -> if (d.newEnd != ev.end) {
				val end = d.newEnd
				changeOccurrence(this, occ, CHANGE_TEXT) { it.end = end }
			}
			ev.untimed -> if (d.newDay != occ.day) {
				val day = d.newDay
				changeOccurrence(this, occ, CHANGE_TEXT) { it.date = day }
			}
			else -> if (d.newDay != occ.day || d.newStart != ev.start) {
				val day = d.newDay
				val s = d.newStart
				val dur = d.dur
				changeOccurrence(this, occ, CHANGE_TEXT) {
					it.date = day
					it.start = s
					it.end = s + dur
				}
			}
		}
	}

	/** Mouse click on the grid: a new event on empty space, select or edit a box. */
	private fun gridClicked(e: MouseEvent) {
		if (suppressClick) {
			suppressClick = false
			return
		}
		if (!SwingUtilities.isLeftMouseButton(e) || e.x < TIME_W) return
		closeFields(save = e.clickCount < 2)
		val col = ((e.x - TIME_W) / colW(grid.width)).coerceIn(0, 6)
		App.day = start + col
		val hit = hitAt(e.point)
		if (hit == null) {
			selected = null
			val min = slotStart(e.y)
			if (e.clickCount >= 2) {
				EventDialog.edit(this, null, App.day, min)
				return
			}
			App.refresh()
			startTyping(col, min, days[col].count { it.event.untimed })
			return
		}
		val icon = EventBox.markerAt(hit.rect, hit.occ.event, e.point, hit.occ.event.untimed)
		selected = hit.occ
		App.refresh()
		when {
			e.clickCount >= 2 && EventBox.onHandle(hit.rect, e.point) -> EventDialog.edit(this, hit.occ.event, hit.occ.day)
			e.clickCount >= 2 -> startTitleEdit(hit)
			icon === EventBox.alarmIcon -> editAlarm(this, hit.occ)
			icon === EventBox.noteIcon -> editNote(this, hit.occ)
		}
	}

	/** Right click on the grid: the menu of a box, or of an empty hour or the untimed row. */
	private fun gridPopup(e: MouseEvent) {
		if (e.x < TIME_W) return
		closeFields(save = true)
		val day = start + ((e.x - TIME_W) / colW(grid.width)).coerceIn(0, 6)
		val hit = hitAt(e.point)
		val menu = if (hit != null) {
			selected = hit.occ
			eventMenu(this, hit.occ)
		} else {
			selected = null
			slotMenu(this, day, slotStart(e.y))
		}
		grid.repaint()
		menu.show(grid, e.x, e.y)
	}

	private fun headerClicked(e: MouseEvent) {
		if (e.x < TIME_W) return
		App.day = start + ((e.x - TIME_W) / colW(header.width)).coerceIn(0, 6)
		App.refresh()
	}

	private inner class WeekGrid : ViewportWidthPanel() {
		init {
			layout = null   // doLayout places the field of a new event
			val mouse = object : MouseAdapter() {
				override fun mousePressed(e: MouseEvent) = pressed(e)
				override fun mouseDragged(e: MouseEvent) = dragged(e)
				override fun mouseReleased(e: MouseEvent) = released(e)
				override fun mouseClicked(e: MouseEvent) = gridClicked(e)
			}
			addMouseListener(mouse)
			addMouseMotionListener(mouse)
		}

		override fun getPreferredSize(): Dimension = Dimension(TIME_W + 7 * 60, untimedH + 24 * HOUR_H + 1)

		override fun doLayout() {
			val f = typing ?: return
			val r = typingRect(width) ?: return
			f.setBounds(r.x + 3, r.y + 1, r.width - EventBox.BAR_W - 4, f.preferredSize.height)
		}

		override fun paintComponent(g: Graphics) {
			super.paintComponent(g)
			val w = width
			val cw = colW(w)
			g.font = Theme.view
			val fm = g.fontMetrics
			g.color = Color.WHITE
			g.fillRect(0, 0, w, height)
			// Hour lines and the time labels. A label and its tick sit in the middle of the hour, as in the Day view.
			g.color = Theme.grid
			for (h in 0..24) g.drawLine(0, hourY(h), w, hourY(h))
			// The time column: a raised cell for the untimed area, then one per hour.
			raisedCell(g, 0, 0, TIME_W, untimedH)
			g.color = Color.BLACK
			drawDiamond(g, TIME_W / 2, untimedH / 2)
			for (h in 0..23) {
				raisedCell(g, 0, hourY(h), TIME_W, HOUR_H)
				g.color = Color.BLACK
				val mid = hourY(h) + HOUR_H / 2
				val label = timeLabel(h * 60)
				g.drawString(label, TIME_W - 10 - fm.stringWidth(label), mid + (fm.ascent - fm.descent) / 2)
				g.drawLine(TIME_W - 7, mid, TIME_W - 3, mid)
			}

			// Day column lines.
			g.color = Color.BLACK
			for (i in 0..7) g.drawLine(TIME_W + i * cw, 0, TIME_W + i * cw, height - 1)

			// The selected box last, so its blue bar is on top of its neighbors.
			val sel = selectedOcc()
			for (hit in hits(w).sortedBy { it.occ === sel }) {
				EventBox.paint(this, g, hit.rect, hit.occ.event, hit.occ === sel, hit.occ.event.untimed)
			}
			// The field of a new event: a white box, drawn as selected. The field is on top of it.
			typingRect(w)?.let { EventBox.draw(g, it, Color.WHITE, true) }
			// The outline of a box being moved or resized.
			drag?.outline?.let {
				g.color = Color.BLACK
				g.drawRect(it.x, it.y, it.width - 1, it.height - 1)
			}
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
				g.color = if (day == today) Theme.select else Theme.panel
				g.fillRect(x, 0, cw, height)
				val text = "${Fmt.dowShort[dowIndex(i)]} ${Fmt.ld(day).dayOfMonth}"
				g.color = if (day == today) Theme.dateRed else Color.BLACK
				g.drawString(text, x + (cw - fm.stringWidth(text)) / 2, (height + fm.ascent - fm.descent) / 2)
				g.color = Theme.grid
				g.drawLine(x, 0, x, height - 1)
			}
			g.drawLine(TIME_W, height - 1, width, height - 1)
		}
	}
}
