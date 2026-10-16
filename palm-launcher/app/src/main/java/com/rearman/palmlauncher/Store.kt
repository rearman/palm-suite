package com.rearman.palmlauncher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object Store {
	private const val PREFS = "launcher"
	private const val KEY = "state"
	private const val DOCK_SIZE = 4

	fun load(ctx: Context): LauncherState {
		val text = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
			?: return LauncherState.defaults()
		return try {
			parse(JSONObject(text))
		} catch (e: Exception) {
			LauncherState.defaults()
		}
	}

	fun save(ctx: Context, s: LauncherState) {
		val cats = JSONArray()
		for (c in s.categories) {
			cats.put(JSONObject().put("id", c.id).put("name", c.name))
		}
		val assign = JSONObject()
		for ((component, id) in s.assignments) {
			assign.put(component, id)
		}
		val dock = JSONArray()
		for (component in s.dock) {
			dock.put(component ?: JSONObject.NULL)
		}
		val o = JSONObject()
			.put("categories", cats)
			.put("assignments", assign)
			.put("dock", dock)
			.put("selected", s.selected)
			.put("viewMode", s.viewMode.name)
			.put("textSize", s.textSize.name)
			.put("iconSize", s.iconSize.name)
			.put("wallpaper", s.wallpaper)
		ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, o.toString()).apply()
	}

	private fun parse(o: JSONObject): LauncherState {
		val cats = o.getJSONArray("categories")
		val categories = mutableListOf<Category>()
		for (i in 0 until cats.length()) {
			val c = cats.getJSONObject(i)
			categories.add(Category(c.getInt("id"), c.getString("name")))
		}
		if (categories.none { it.id == UNFILED }) {
			categories.add(Category(UNFILED, "Unfiled"))
		}

		val assign = o.getJSONObject("assignments")
		val assignments = mutableMapOf<String, Int>()
		val keys = assign.keys()
		while (keys.hasNext()) {
			val component = keys.next()
			assignments[component] = assign.getInt(component)
		}

		// Fixed size: a short array leaves null slots, a long one drops the extras.
		val dockArr = o.getJSONArray("dock")
		val dock = arrayOfNulls<String>(DOCK_SIZE)
		for (i in 0 until DOCK_SIZE) {
			if (i < dockArr.length() && !dockArr.isNull(i)) {
				dock[i] = dockArr.getString(i)
			}
		}

		// opt*: state saved by older builds has no "textSize", "iconSize" or "wallpaper" key, and get* would throw.
		// optString gives "" for a missing key, and Size.of("") gives NORMAL.
		// A "palmFont" key from older builds is ignored: the Palm OS font is always on.
		// A "green" key from older builds is ignored: the screen is always Green LCD.
		return LauncherState(
			categories,
			assignments,
			dock,
			o.getInt("selected"),
			ViewMode.valueOf(o.getString("viewMode")),
		).apply {
			textSize = Size.of(o.optString("textSize"))
			iconSize = Size.of(o.optString("iconSize"))
			wallpaper = o.optBoolean("wallpaper", false)
		}
	}
}
