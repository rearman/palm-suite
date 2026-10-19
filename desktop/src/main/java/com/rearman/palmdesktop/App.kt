package com.rearman.palmdesktop

import java.time.LocalDate

/**
 * The state that every window part shares: the store, the sync, the selected day and the main
 * window. Views register a refresh listener and run in the Swing event thread.
 */
object App {
	lateinit var store: DesktopStore
	lateinit var sync: DesktopSync
	lateinit var frame: MainFrame

	/** Selected date of the Date views, as an epoch day. Starts on today. */
	var day: Long = LocalDate.now().toEpochDay()

	/** Shows the private records. Saved as the Settings key "showPrivate". */
	var showPrivate: Boolean = Settings.getBool("showPrivate", false)

	private val listeners = mutableListOf<() -> Unit>()

	fun onRefresh(listener: () -> Unit) {
		listeners.add(listener)
	}

	fun refresh() {
		for (listener in listeners.toList()) listener()
	}

	fun status(text: String) {
		frame.setStatus(text)
	}

	/** Shows a view ("date", "todo" or "memo") and selects the record, when one is given. */
	fun show(view: String, select: Any? = null) {
		frame.showView(view, select)
	}
}

/** What the main window asks of the view it shows. Each panel implements it. */
interface AppView {
	fun newRecord()
	fun editSelected()
	fun deleteSelected()
	fun select(record: Any)
	fun printLines(): Pair<String, List<String>>
}
