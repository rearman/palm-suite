package com.rearman.palmui

import android.content.Context
import android.widget.ScrollView

/** One editable category: its id and name. */
class CategoryItem(val id: Int, val name: String)

/**
 * The Palm OS Edit Categories form: a list of [items] with OK, New, Rename and Delete. New and Rename ask a name
 * in a second form; a name that is empty or already used (ignoring case) is refused with an alert and the form
 * stays open. Delete asks [deleteText] first.
 *
 * [items] gives the list each time the form shows it. [add] takes a new name and returns its id, which becomes
 * the selection. [rename] takes an id and the new name. [delete] takes the id of the category to remove. [onClose]
 * runs when OK closes the list. [reserved] gives names that are taken but are not in [items], such as Unfiled.
 * A name that matches one (ignoring case) is refused like a duplicate.
 */
fun Context.editCategories(
	items: () -> List<CategoryItem>,
	add: (String) -> Int,            // returns the new id, which becomes the selection
	rename: (Int, String) -> Unit,
	deleteText: (CategoryItem) -> String,
	delete: (Int) -> Unit,
	onClose: () -> Unit,
	reserved: List<String> = listOf("Unfiled"),
) {
	val d = PalmDialog(this, "Edit Categories", height = 156)
	val list = vbox()
	var selId: Int? = null
	fun selected(): CategoryItem? = items().firstOrNull { it.id == selId }
	fun fill() {
		list.removeAllViews()
		val all = items()
		// Keep the selection on the same category after a rename. A category that is gone clears it.
		if (all.none { it.id == selId }) selId = null
		for (cat in all) {
			val picked = cat.id == selId
			list.addView(label(cat.name).apply {
				minHeight = pp(11)
				minimumHeight = pp(11)
				if (picked) { setBackgroundColor(Palm.INK); setTextColor(Palm.paper) }
				setOnClickListener { selId = cat.id; fill() }
			}, lp(MATCH))
		}
		if (all.isEmpty()) list.addView(label("No categories"))
	}
	fun nameForm(cat: CategoryItem?) {
		val f2 = PalmDialog(this, "Edit Categories", height = 69)
		f2.add(label("Enter a new category name:", bold = true), 0)
		val input = field(cat?.name.orEmpty(), "Category name")
		input.minHeight = pp(20)
		f2.add(input, 3)
		f2.button("OK", 37) {
			val name = input.text.toString().trim()
			if (name.isEmpty()) return@button
			val self = cat?.id
			val taken = items().any { it.id != self && it.name.equals(name, ignoreCase = true) } ||
				reserved.any { it.equals(name, ignoreCase = true) }
			if (taken) {
				palmAlert("Edit Categories", "A category with that name already exists.", 69) { }
				return@button
			}
			f2.dismiss()
			if (cat == null) {
				selId = add(name)
			} else {
				rename(cat.id, name)
			}
			fill()
		}
		f2.button("Cancel", 37) { f2.dismiss() }
		f2.show()
	}
	fill()
	// Palm list: 11 px rows in a 101 px box. The list scrolls when it is longer.
	val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(list) }
	d.add(scroll, 6)
	scroll.layoutParams.height = pp(101)
	d.button("OK", 22) { d.dismiss(); onClose() }
	d.button("New", 27) { nameForm(null) }
	d.button("Rename", 42) { selected()?.let { nameForm(it) } }
	d.button("Delete", 37) {
		val cat = selected() ?: return@button
		ask("Delete Category", deleteText(cat),
			"Yes" to { delete(cat.id); selId = null; fill() },
			"No" to {})
	}
	d.show()
}
