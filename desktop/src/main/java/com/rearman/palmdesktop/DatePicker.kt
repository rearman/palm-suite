package com.rearman.palmdesktop

import com.rearman.datebook.Fmt
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dialog
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.Insets
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.LocalDate
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.WindowConstants
import javax.swing.border.BevelBorder
import javax.swing.border.EmptyBorder
import javax.swing.border.LineBorder

/** The Palm Desktop date dialog: Select Day, Select Week or Select Month. */
object DatePicker {
	/** Shows a modal dialog for [mode] ("day", "week" or "month") and returns the chosen epoch day, or null on Cancel. */
	fun pick(owner: Component, mode: String, day: Long): Long? = Picker(owner, mode, day).choose()
}

private class Picker(private val owner: Component, private val mode: String, day: Long) {
	private val today: LocalDate = LocalDate.now()
	private var sel: LocalDate = LocalDate.ofEpochDay(day)
	private var result: Long? = null
	private val dialog = JDialog(
		SwingUtilities.getWindowAncestor(owner),
		when (mode) { "week" -> "Select Week"; "month" -> "Select Month"; else -> "Select Day" },
		Dialog.ModalityType.APPLICATION_MODAL
	)
	private val yearLabel = JLabel("", SwingConstants.CENTER).apply {
		font = Theme.bold
		border = BorderFactory.createBevelBorder(BevelBorder.LOWERED)
	}
	private val monthButtons = List(12) { m ->
		JButton(Fmt.monthShort[m]).apply {
			font = Theme.font
			margin = Insets(0, 0, 0, 0)
			isFocusPainted = false
			border = LineBorder(Theme.grid)
			addActionListener { move(sel.withMonth(m + 1)) }
		}
	}
	private val dates = Array(42) { sel }
	private val dayCells = List(42) { i ->
		JLabel("", SwingConstants.CENTER).apply {
			font = Theme.font
			isOpaque = true
			addMouseListener(object : MouseAdapter() {
				override fun mouseClicked(e: MouseEvent) {
					sel = dates[i]
					refresh()
					if (e.clickCount == 2) ok()
				}
			})
		}
	}

	fun choose(): Long? {
		dialog.isResizable = false
		dialog.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
		dialog.contentPane = layout()
		bindKeys()
		refresh()
		dialog.pack()
		dialog.setSize(maxOf(dialog.width, 210), maxOf(dialog.height, 250))
		dialog.setLocationRelativeTo(owner)
		dialog.isVisible = true // A modal dialog returns here after dispose.
		return result
	}

	private fun layout(): JPanel {
		val top = JPanel(BorderLayout(4, 0)).apply {
			add(arrowButton(SwingConstants.WEST) { move(sel.minusYears(1)) }, BorderLayout.WEST)
			add(yearLabel, BorderLayout.CENTER)
			add(arrowButton(SwingConstants.EAST) { move(sel.plusYears(1)) }, BorderLayout.EAST)
		}
		val months = JPanel(GridLayout(2, 6, 2, 2)).apply {
			monthButtons.forEach { add(it) }
		}
		val calendar = JPanel(GridLayout(7, 7)).apply {
			border = LineBorder(Theme.grid)
			for (h in listOf("S", "M", "T", "W", "T", "F", "S")) {
				add(JLabel(h, SwingConstants.CENTER).apply { font = Theme.bold })
			}
			dayCells.forEach { add(it) }
		}
		val okButton = JButton("OK").apply { addActionListener { ok() } }
		val cancelButton = JButton("Cancel").apply { addActionListener { cancel() } }
		val todayButton = JButton(todayText()).apply {
			mnemonic = KeyEvent.VK_T
			addActionListener {
				move(today)
				ok()
			}
		}
		dialog.rootPane.defaultButton = okButton
		val buttons = JPanel(FlowLayout(FlowLayout.CENTER, 4, 0)).apply {
			add(okButton)
			add(cancelButton)
			add(todayButton)
		}
		val body = JPanel(BorderLayout(0, 6)).apply {
			add(months, BorderLayout.NORTH)
			add(calendar, BorderLayout.CENTER)
		}
		return JPanel(BorderLayout(0, 6)).apply {
			border = EmptyBorder(6, 6, 6, 6)
			add(top, BorderLayout.NORTH)
			add(body, BorderLayout.CENTER)
			add(buttons, BorderLayout.SOUTH)
		}
	}

	private fun todayText(): String = when (mode) {
		"week" -> "This Week"
		"month" -> "This Month"
		else -> "Today"
	}

	private fun arrow(text: String, act: () -> Unit): JButton = JButton(text).apply {
		font = Theme.font
		margin = Insets(0, 2, 0, 2)
		isFocusPainted = false
		addActionListener { act() }
	}

	private fun bindKeys() {
		bind(KeyEvent.VK_LEFT, "day-back") { move(sel.minusDays(1)) }
		bind(KeyEvent.VK_RIGHT, "day-forward") { move(sel.plusDays(1)) }
		bind(KeyEvent.VK_UP, "week-back") { move(sel.minusDays(7)) }
		bind(KeyEvent.VK_DOWN, "week-forward") { move(sel.plusDays(7)) }
		bind(KeyEvent.VK_PAGE_UP, "month-back") { move(sel.minusMonths(1)) }
		bind(KeyEvent.VK_PAGE_DOWN, "month-forward") { move(sel.plusMonths(1)) }
		bind(KeyEvent.VK_ESCAPE, "cancel") { cancel() }
	}

	private fun bind(keyCode: Int, name: String, act: () -> Unit) {
		val root = dialog.rootPane
		root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(keyCode, 0), name)
		root.actionMap.put(name, object : AbstractAction() {
			override fun actionPerformed(e: ActionEvent) = act()
		})
	}

	private fun move(d: LocalDate) {
		sel = d
		refresh()
	}

	private fun ok() {
		result = sel.toEpochDay()
		dialog.dispose()
	}

	private fun cancel() {
		dialog.dispose()
	}

	/** Updates the year, the month buttons and the calendar from [sel]. */
	private fun refresh() {
		yearLabel.text = sel.year.toString()
		monthButtons.forEachIndexed { m, b ->
			val on = m + 1 == sel.monthValue
			b.isContentAreaFilled = on
			b.background = Theme.select
			b.foreground = if (on) Color.WHITE else Color.BLACK
		}
		val first = sel.withDayOfMonth(1)
		val start = first.minusDays((first.dayOfWeek.value % 7).toLong())
		val weekStart = sel.minusDays((sel.dayOfWeek.value % 7).toLong())
		for (i in dayCells.indices) {
			val d = start.plusDays(i.toLong())
			val picked = when (mode) {
				"week" -> !d.isBefore(weekStart) && d.isBefore(weekStart.plusDays(7))
				"month" -> false
				else -> d == sel
			}
			dates[i] = d
			dayCells[i].apply {
				text = d.dayOfMonth.toString()
				background = if (picked) Theme.select else Color.WHITE
				foreground = when {
					d == today -> Theme.dateRed
					picked -> Color.WHITE
					d.monthValue != sel.monthValue -> Color.GRAY
					else -> Color.BLACK
				}
			}
		}
	}
}
