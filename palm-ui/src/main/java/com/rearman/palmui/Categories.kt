package com.rearman.palmui

import android.content.Context
import android.widget.ScrollView

/** One editable category: its id and name. */
class CategoryItem(val id: Int, val name: String)

/**
 * The Palm OS Edit Categories form: a list of [items] with OK, New, Rename and Delete. New and Rename ask a name
 * in a second form; an empty name does nothing and the form stays open. Delete asks [deleteText] first.
 *
 * [items] gives the list each time the form shows it. [add] takes a new name and returns its id, which becomes
 * the selection. [rename] takes an id and the new name. [delete] takes the id of the category to remove. [onClose]
 * runs when OK closes the list. [reserved] gives categories that are not in [items], such as Unfiled.
 *
 * As on the Palm, a name already used by another category (ignoring case, in [items] or [reserved]) merges: Rename
 * calls [merge] with the id of the renamed category and the id of the one that has the name, which keeps its name
 * and becomes the selection, with no alert. New with a used name adds nothing and selects the category that has it.
 */
fun Context.editCategories(
	items: () -> List<CategoryItem>,
	add: (String) -> Int,            // returns the new id, which becomes the selection
	rename: (Int, String) -> Unit,
	deleteText: (CategoryItem) -> String,
	delete: (Int) -> Unit,
	merge: (Int, Int) -> Unit,       // (from, into): moves the records of from into into, then removes from
	onClose: () -> Unit,
	reserved: List<CategoryItem> = listOf(CategoryItem(0, "Unfiled")),
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
			val same = (items() + reserved).firstOrNull { it.id != self && it.name.equals(name, ignoreCase = true) }
			f2.dismiss()
			if (same != null) {
				if (cat != null) merge(cat.id, same.id)
				selId = same.id
			} else if (cat == null) {
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
