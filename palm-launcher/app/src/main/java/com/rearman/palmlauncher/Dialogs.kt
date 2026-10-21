package com.rearman.palmlauncher

import com.rearman.palmui.CategoryItem
import com.rearman.palmui.PalmDialog
import com.rearman.palmui.editCategories as palmEditCategories

// Single-choice list of state.categories (no All); `current` is selected. Calls onPick(id).
fun pickCategory(act: MainActivity, state: LauncherState, current: Int, onPick: (Int) -> Unit) {
	val d = PalmDialog(act, "Category")
	for (c in state.categories) d.item(c.name, c.id == current) { d.dismiss(); onPick(c.id) }
	d.button("Cancel") { d.dismiss() }
	d.show()
}

// Single-choice list of the Size steps; `current` is selected. Calls onPick(size).
fun pickSize(act: MainActivity, title: String, current: Size, onPick: (Size) -> Unit) {
	val d = PalmDialog(act, title)
	for (s in Size.values()) d.item(s.label, s == current) { d.dismiss(); onPick(s) }
	d.button("Cancel") { d.dismiss() }
	d.show()
}

// Single-choice list of the grid view column counts; `current` is selected. Calls onPick(columns).
fun pickColumns(act: MainActivity, current: Int, onPick: (Int) -> Unit) {
	val d = PalmDialog(act, "Grid Columns")
	for (n in listOf(3, 4)) d.item("$n", n == current) { d.dismiss(); onPick(n) }
	d.button("Cancel") { d.dismiss() }
	d.show()
}

// A list of `apps` in the given order. A tap closes the form and calls onPick(app).
fun pickApp(act: MainActivity, apps: List<AppEntry>, title: String, onPick: (AppEntry) -> Unit) {
	val d = PalmDialog(act, title)
	for (a in apps) d.item(a.label) { d.dismiss(); onPick(a) }
	d.button("Cancel") { d.dismiss() }
	d.show()
}

// Palm "Edit Categories": the palm-ui form (list, OK, New, Rename, Delete). Calls onChange() after each change
// (caller saves and redraws).
fun editCategories(act: MainActivity, state: LauncherState, onChange: () -> Unit) {
	act.palmEditCategories(
		items = { state.categories.filter { it.id != UNFILED }.map { CategoryItem(it.id, it.name) } },
		add = { name -> val c = state.addCategory(name); onChange(); c.id },
		rename = { id, name -> state.renameCategory(id, name); onChange() },
		deleteText = { "Delete category \"${it.name}\"? Its applications move to Unfiled." },
		delete = { id -> state.deleteCategory(id); onChange() },
		onClose = {},
	)
}
