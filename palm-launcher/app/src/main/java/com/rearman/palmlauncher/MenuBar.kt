package com.rearman.palmlauncher

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.ceil

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

// The letter of a key A to Z, or null for any other key.
fun commandKey(keyCode: Int): Char? =
	if (keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) 'A' + (keyCode - KeyEvent.KEYCODE_A) else null

// The Palm OS menu bar, as on the Palm III: the menu titles in a bar at the top of the screen, and the
// commands of the open menu in a drop-down box under its title. The rest of the screen stays visible,
// and a tap on it closes the whole menu bar. With the menu bar open, a command letter key runs its command.
class MenuBar(
	private val act: MainActivity,
	private val menus: List<Pair<String, List<MenuItem>>>,
) : Dialog(act) {
	private val titles = LinearLayout(act).apply {
		orientation = LinearLayout.HORIZONTAL
		gravity = Gravity.CENTER_VERTICAL
	}
	private val drop = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
	private var open = 0

	init {
		requestWindowFeature(Window.FEATURE_NO_TITLE)
		val frame = FrameLayout(act).apply { setOnClickListener { dismiss() } }
		titles.background = menuBox()
		titles.setPadding(act.pp(2f), act.pp(1f), act.pp(3f), act.pp(2f))
		// A tap on the bar outside the titles keeps the menu bar open.
		titles.isClickable = true
		frame.addView(titles, FrameLayout.LayoutParams(MATCH, WRAP).apply {
			setMargins(act.pp(1f), act.pp(1f), act.pp(1f), 0)
		})
		drop.background = menuBox()
		drop.setPadding(act.pp(1f), act.pp(1f), act.pp(2f), act.pp(2f))
		drop.isClickable = true
		frame.addView(drop, FrameLayout.LayoutParams(WRAP, WRAP))
		setContentView(frame)
		window?.apply {
			setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
			setGravity(Gravity.TOP)
			setLayout(MATCH, MATCH)
			clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
			// The menu bar is a window of its own: hide the status bar in it too, as the launcher does.
			hideStatusBar()
		}
		draw()
	}

	override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
		val item = commandKey(keyCode)?.let { menus.command(it) } ?: return super.onKeyDown(keyCode, event)
		dismiss()
		item.action()
		return true
	}

	private fun draw() {
		titles.removeAllViews()
		menus.forEachIndexed { i, (name, _) ->
			titles.addView(act.text(name, bold = true).apply {
				setPadding(act.pp(3f), 0, act.pp(3f), 0)
				if (i == open) {
					setBackgroundColor(Color.BLACK)
					setTextColor(act.paper())
				}
				setOnClickListener { open = i; draw() }
			})
		}
		drop.removeAllViews()
		for (item in menus[open].second) drop.addView(row(item), LinearLayout.LayoutParams(MATCH, WRAP))
		// The drop-down starts under the left edge of its title, so place it after the titles have a layout.
		drop.visibility = View.INVISIBLE
		titles.post {
			val t = titles.getChildAt(open)
			(drop.layoutParams as FrameLayout.LayoutParams).apply {
				leftMargin = titles.left + t.left - act.pp(1f)
				topMargin = titles.bottom - act.pp(2f)
			}
			drop.requestLayout()
			drop.visibility = View.VISIBLE
		}
	}

	// One command: its name, then the command stroke and letter at the right. Inverted while pressed.
	private fun row(item: MenuItem): View {
		val colors = ColorStateList(
			arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
			intArrayOf(act.paper(), Color.BLACK),
		)
		val r = LinearLayout(act).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.CENTER_VERTICAL
			background = StateListDrawable().apply {
				addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(Color.BLACK))
				addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
			}
			setPadding(act.pp(2f), 0, act.pp(2f), 0)
			setOnClickListener { dismiss(); item.action() }
		}
		r.addView(act.text(item.name, bold = true).apply {
			setTextColor(colors)
			isDuplicateParentStateEnabled = true
		}, LinearLayout.LayoutParams(0, WRAP, 1f))
		val key = item.key ?: return r
		r.addView(CommandStroke(colors), LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = act.pp(8f) })
		r.addView(act.text(key.toString(), bold = true).apply {
			setTextColor(colors)
			isDuplicateParentStateEnabled = true
			// As in Palm OS, the strokes are a fixed column and each letter is left-aligned after its stroke,
			// in a column the width of a "W".
			gravity = Gravity.START
			width = ceil(paint.measureText("W")).toInt()
		})
		return r
	}

	// A Palm menu box: paper in a 1 px black frame with rounded corners, and a 1 px shadow at the right and bottom.
	private fun menuBox(): Drawable {
		val p = act.pp(1f)
		val r = act.pp(2f).toFloat()
		val shadow = GradientDrawable().apply { setColor(Color.BLACK); cornerRadius = r }
		val box = GradientDrawable().apply { setColor(act.paper()); setStroke(p, Color.BLACK); cornerRadius = r }
		return LayerDrawable(arrayOf(shadow, box)).apply {
			setLayerInset(0, p, p, 0, 0)
			setLayerInset(1, 0, 0, p, p)
		}
	}

	// The Palm command stroke before a command letter: a diagonal line up to the right from a dot at its foot.
	private inner class CommandStroke(private val colors: ColorStateList) : View(act) {
		private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			strokeWidth = act.pp(1f).toFloat()
		}

		init {
			isDuplicateParentStateEnabled = true
		}

		override fun onMeasure(widthSpec: Int, heightSpec: Int) = setMeasuredDimension(act.pp(7f), act.pp(9f))

		override fun drawableStateChanged() {
			super.drawableStateChanged()
			invalidate()
		}

		override fun onDraw(c: Canvas) {
			p.color = colors.getColorForState(drawableState, Color.BLACK)
			val u = act.pp(1f).toFloat()
			// The dot: 2 by 2 Palm px at the lower left. The line: from the dot to the top right.
			c.drawRect(0f, 7f * u, 2f * u, 9f * u, p)
			c.drawLine(1.5f * u, 7.5f * u, 6.5f * u, 0.5f * u, p)
		}
	}
}
