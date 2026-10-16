package com.rearman.palmlauncher

const val UNFILED = 0   // fixed category id, cannot be renamed or deleted, always last in categories
const val ALL = -1      // filter value: show all apps

enum class ViewMode { GRID, LIST }

// Text and icon size steps. scale multiplies the Normal icon size. palmPx is the text size in Palm px,
// the same as the Datebook Font sizes.
enum class Size(val label: String, val scale: Float, val palmPx: Float) {
	SMALL("Small", 0.8f, 13f), NORMAL("Normal", 1f, 16f), LARGE("Large", 1.25f, 20f);

	companion object {
		// An unknown or missing name (state saved by older builds) gives NORMAL.
		fun of(name: String?): Size = values().firstOrNull { it.name == name } ?: NORMAL
	}
}

// List view column widths: each column is as wide as its widest entry (entries fill across, then down).
// Null when the columns together are wider than `max`; the caller then uses equal columns.
fun columnWidths(entryWidths: List<Int>, cols: Int, max: Int): IntArray? {
	val w = IntArray(cols)
	entryWidths.forEachIndexed { i, x -> w[i % cols] = maxOf(w[i % cols], x) }
	return if (w.sum() <= max) w else null
}

// A drag of (dx, dy) px is a swipe down when it goes at least `min` px down and more than twice as
// far down as sideways.
fun swipeDown(dx: Float, dy: Float, min: Float): Boolean = dy >= min && dy > 2 * kotlin.math.abs(dx)

data class Category(val id: Int, val name: String)

class LauncherState(
	val categories: MutableList<Category>,     // sorted by name; Unfiled always last
	val assignments: MutableMap<String, Int>,  // component to category id; missing means Unfiled
	val dock: Array<String?>,                  // size 4, component or null
	var selected: Int,                         // ALL or a category id
	var viewMode: ViewMode,
) {
	var textSize = Size.NORMAL  // Size of all launcher text (saved by Store).
	var iconSize = Size.NORMAL  // App and dock icon size (saved by Store).
	var wallpaper = false  // The system wallpaper shows behind the app list (saved by Store).

	init {
		sortCategories()
	}

	companion object {
		// Games(1), Main(2), System(3), Utilities(4), Unfiled(0); empty assignments; dock of 4 nulls; ALL; GRID
		fun defaults(): LauncherState = LauncherState(
			mutableListOf(
				Category(1, "Games"),
				Category(2, "Main"),
				Category(3, "System"),
				Category(4, "Utilities"),
				Category(UNFILED, "Unfiled"),
			),
			mutableMapOf(),
			arrayOfNulls(4),
			ALL,
			ViewMode.GRID,
		)
	}

	fun categoryOf(component: String): Int {
		val id = assignments[component] ?: return UNFILED
		return if (categories.any { it.id == id }) id else UNFILED
	}

	fun assign(component: String, id: Int) {
		assignments[component] = id
	}

	fun categoryName(id: Int): String =
		if (id == ALL) "All" else categories.firstOrNull { it.id == id }?.name ?: "Unfiled"

	// Apps in the selected category (all apps when ALL), sorted by label, case-insensitive.
	fun <T> filter(apps: List<T>, key: (T) -> String, label: (T) -> String): List<T> =
		apps.filter { selected == ALL || categoryOf(key(it)) == selected }
			.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, label))

	// Palm Home-key cycle: order is ALL, then each category in list order. Return the next entry after
	// `selected` that has at least one of `components`; ALL always qualifies. Wraps to ALL.
	fun nextCategory(components: Collection<String>): Int {
		val order = listOf(ALL) + categories.map { it.id }
		val start = order.indexOf(selected)  // -1 when selected is not listed; the cycle then starts at ALL
		for (step in 1..order.size) {
			val id = order[(start + step) % order.size]
			if (id == ALL || components.any { categoryOf(it) == id }) return id
		}
		return ALL
	}

	fun addCategory(name: String): Category {
		val category = Category((categories.maxOfOrNull { it.id } ?: 0) + 1, name)
		categories.add(category)
		sortCategories()
		return category
	}

	fun renameCategory(id: Int, name: String) {
		if (id == UNFILED) return
		val index = categories.indexOfFirst { it.id == id }
		if (index >= 0) categories[index] = categories[index].copy(name = name)
		sortCategories()
	}

	// Sorts by name, case-insensitive; Unfiled stays last.
	private fun sortCategories() {
		categories.sortWith(compareBy<Category> { it.id == UNFILED }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
	}

	fun deleteCategory(id: Int) {
		if (id == UNFILED) return
		categories.removeAll { it.id == id }
		assignments.entries.removeAll { it.value == id }
		if (selected == id) selected = ALL
	}

	fun prune(installed: Set<String>) {
		assignments.keys.removeAll { it !in installed }
		for (i in dock.indices) {
			val component = dock[i]
			if (component != null && component !in installed) dock[i] = null
		}
	}
}
