package com.rearman.palmlauncher

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView

// Palm-style pop-up lists and dialogs in the launcher colors (Green LCD) and font (Palm OS, Text Size).

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

// A rounded box: `fill` inside a 1 dp black line.
internal fun MainActivity.box(fill: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
	setColor(fill)
	setStroke(dp(1), Color.BLACK)
	cornerRadius = dp(radiusDp).toFloat()
}

// Palm px: the shorter screen side is 160 Palm px, as in Datebook.
internal fun MainActivity.pp(v: Float): Int {
	val dm = resources.displayMetrics
	return if (v <= 0f) 0 else maxOf(1, Math.round(v * minOf(dm.widthPixels, dm.heightPixels) / 160f))
}

// A Datebook box: `fill` inside a 1 Palm px black line, with `radius` Palm px corners.
internal fun MainActivity.palmBox(fill: Int, radius: Float = 1.5f): GradientDrawable = GradientDrawable().apply {
	setColor(fill)
	setStroke(pp(1f), Color.BLACK)
	cornerRadius = pp(radius).toFloat()
}

// Black text in the launcher font.
internal fun MainActivity.text(s: CharSequence, bold: Boolean = false): TextView = TextView(this).apply {
	text = s
	setTextColor(Color.BLACK)
	font(bold)
}

// A Palm pop-up list at `anchor`. Items in `checked` show inverted and bold, as the Datebook selected
// item. A tap closes the list, then calls onPick(index).
internal fun MainActivity.popupList(anchor: View, items: List<String>, checked: Set<Int> = emptySet(), onPick: (Int) -> Unit) {
	val col = LinearLayout(this).apply {
		orientation = LinearLayout.VERTICAL
		background = palmBox(paper())
		setPadding(pp(1f), pp(1f), pp(1f), pp(1f))
	}
	val sv = ScrollView(this).apply { addView(col); isVerticalScrollBarEnabled = false }
	val pw = PopupWindow(sv, WRAP, WRAP, true)
	items.forEachIndexed { i, s ->
		col.addView(text(s, bold = i in checked).apply {
			setPadding(pp(3f), pp(1f), pp(8f), pp(1f))
			if (i in checked) {
				setBackgroundColor(Color.BLACK)
				setTextColor(paper())
			}
			setOnClickListener { pw.dismiss(); onPick(i) }
		}, LinearLayout.LayoutParams(MATCH, WRAP))
	}
	pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
	pw.elevation = dp(4).toFloat()
	// As in Datebook: the full visible height, at the anchor, moved left or up only as far as needed
	// to stay on the screen. A longer list scrolls.
	val vis = Rect().also { anchor.getWindowVisibleDisplayFrame(it) }
	val maxH = vis.height()
	sv.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
	if (sv.measuredHeight > maxH) pw.height = maxH
	val h = minOf(sv.measuredHeight, maxH)
	val loc = IntArray(2).also { anchor.getLocationOnScreen(it) }
	val x = loc[0].coerceIn(0, maxOf(0, resources.displayMetrics.widthPixels - sv.measuredWidth))
	val y = loc[1].coerceIn(vis.top, maxOf(vis.top, vis.bottom - h))
	pw.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
}

// A Palm form: black title bar, bordered body, buttons along the bottom. As in Datebook it is 156 of
// 160 Palm px wide, at the bottom of the screen, with no dimming behind it.
class PalmDialog(private val act: MainActivity, title: String) : Dialog(act) {
	private val body = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
	private val buttons = LinearLayout(act).apply {
		orientation = LinearLayout.HORIZONTAL
		setPadding(act.dp(8), act.dp(8), act.dp(8), act.dp(8))
	}

	init {
		requestWindowFeature(Window.FEATURE_NO_TITLE)
		val frame = LinearLayout(act).apply {
			orientation = LinearLayout.VERTICAL
			background = act.box(act.paper(), 4)
			setPadding(act.dp(2), act.dp(2), act.dp(2), act.dp(2))
		}
		val bar = act.text(title, bold = true).apply {
			gravity = Gravity.CENTER
			setTextColor(act.paper())
			background = act.box(Color.BLACK, 3)
			setPadding(act.dp(4), act.dp(2), act.dp(4), act.dp(2))
		}
		frame.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
		frame.addView(ScrollView(act).apply { addView(body); isVerticalScrollBarEnabled = false },
			LinearLayout.LayoutParams(MATCH, 0, 1f))
		frame.addView(buttons, LinearLayout.LayoutParams(MATCH, WRAP))
		setContentView(frame)
		window?.let { w ->
			w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
			val dm = act.resources.displayMetrics
			w.setLayout(minOf(dm.widthPixels, dm.heightPixels) * 156 / 160, WindowManager.LayoutParams.WRAP_CONTENT)
			w.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
			w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
			// A dialog is a window of its own: hide the status bar in it too, as the launcher does.
			w.hideStatusBar()
		}
	}

	// A message in the body.
	fun message(s: String) {
		body.addView(act.text(s).apply { setPadding(act.dp(8), act.dp(8), act.dp(8), act.dp(4)) },
			LinearLayout.LayoutParams(MATCH, WRAP))
	}

	// A list row in the body. The `selected` row is inverted, as in a Palm list.
	fun item(s: String, selected: Boolean = false, onClick: () -> Unit) {
		body.addView(act.text(s, bold = selected).apply {
			setPadding(act.dp(8), act.dp(8), act.dp(8), act.dp(8))
			if (selected) {
				setBackgroundColor(Color.BLACK)
				setTextColor(act.paper())
			}
			setOnClickListener { onClick() }
		}, LinearLayout.LayoutParams(MATCH, WRAP))
	}

	// A one-line text field in the body, with the cursor at the end. The keyboard opens with the form.
	fun field(s: String): EditText {
		val ed = EditText(act).apply {
			setText(s)
			setTextColor(Color.BLACK)
			setSingleLine(true)
			with(act) { font(bold = false) }
			setSelection(text.length)
		}
		body.addView(ed, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(act.dp(8), act.dp(8), act.dp(8), 0) })
		window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
			WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
		ed.requestFocus()
		return ed
	}

	// A command button as the Datebook palmButton: the Palm oval (1 px line, 3.5 px corners), 12 Palm px
	// high and at least 16 wide, in the regular font. It is inverted while pressed.
	fun button(s: String, onClick: () -> Unit) {
		val b = act.text(s).apply {
			gravity = Gravity.CENTER
			setPadding(act.pp(2f), 0, act.pp(2f), 0)
			minHeight = act.pp(12f)
			minimumHeight = act.pp(12f)
			minWidth = act.pp(16f)
			background = StateListDrawable().apply {
				addState(intArrayOf(android.R.attr.state_pressed), act.palmBox(Color.BLACK, 3.5f))
				addState(intArrayOf(), act.palmBox(act.paper(), 3.5f))
			}
			setTextColor(ColorStateList(
				arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
				intArrayOf(act.paper(), Color.BLACK),
			))
			setOnClickListener { onClick() }
		}
		buttons.addView(b, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = act.pp(4f) })
	}
}
