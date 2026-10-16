package com.rearman.palmlauncher

import android.widget.Toast

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

// A list of `apps` in the given order. A tap closes the form and calls onPick(app).
fun pickApp(act: MainActivity, apps: List<AppEntry>, title: String, onPick: (AppEntry) -> Unit) {
	val d = PalmDialog(act, title)
	for (a in apps) d.item(a.label) { d.dismiss(); onPick(a) }
	d.button("Cancel") { d.dismiss() }
	d.show()
}

// Palm "Edit Categories". Calls onChange() after each change (caller saves and redraws).
fun editCategories(act: MainActivity, state: LauncherState, onChange: () -> Unit) {
	val d = PalmDialog(act, "Edit Categories")
	for (c in state.categories.filter { it.id != UNFILED }) {
		d.item(c.name) { d.dismiss(); categoryMenu(act, state, c, onChange) }
	}
	d.button("OK") { d.dismiss() }
	d.button("New") { d.dismiss(); nameDialog(act, state, null, onChange) }
	d.show()
}

// Actions for one category: Rename or Delete.
private fun categoryMenu(act: MainActivity, state: LauncherState, category: Category, onChange: () -> Unit) {
	val d = PalmDialog(act, category.name)
	d.item("Rename") { d.dismiss(); nameDialog(act, state, category, onChange) }
	d.item("Delete") { d.dismiss(); confirmDelete(act, state, category, onChange) }
	d.button("Cancel") { d.dismiss() }
	d.show()
}

// Name entry for a new category (category == null) or a rename. A bad name keeps the form open.
private fun nameDialog(act: MainActivity, state: LauncherState, category: Category?, onChange: () -> Unit) {
	val d = PalmDialog(act, if (category == null) "New Category" else "Rename Category")
	val input = d.field(category?.name.orEmpty())
	d.button("OK") {
		val name = input.text.toString().trim()
		val clash = state.categories.any {
			it.id != category?.id && it.name.equals(name, ignoreCase = true)
		}
		if (name.isEmpty() || clash) {
			Toast.makeText(act, "Enter a different name.", Toast.LENGTH_SHORT).show()
		} else {
			d.dismiss()
			if (category == null) {
				state.addCategory(name)
			} else {
				state.renameCategory(category.id, name)
			}
			onChange()
			editCategories(act, state, onChange)
		}
	}
	d.button("Cancel") { d.dismiss() }
	d.show()
}

private fun confirmDelete(act: MainActivity, state: LauncherState, category: Category, onChange: () -> Unit) {
	val d = PalmDialog(act, "Delete Category")
	d.message("Delete category \"${category.name}\"? Its applications move to Unfiled.")
	d.button("Delete") {
		d.dismiss()
		state.deleteCategory(category.id)
		onChange()
		editCategories(act, state, onChange)
	}
	d.button("Cancel") { d.dismiss() }
	d.show()
}
