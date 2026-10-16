package com.rearman.palmlauncher

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.MotionEvent
import android.view.RoundedCorner
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import kotlin.math.sqrt

fun Activity.hideStatusBar() = window.hideStatusBar()

// Hides the status bar in this window. A swipe from the top edge shows it for a moment, as in
// full-screen apps. The window also draws into the camera cutout, so the Palm header covers the
// status bar area. Call it after setContentView, for each window that shows (the activity, the menu
// bar and the dialogs), and again on each activity focus: Android 10 and earlier clear the flags.
fun Window.hideStatusBar() {
	if (Build.VERSION.SDK_INT >= 28) {
		attributes = attributes.apply {
			layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
		}
	}
	if (Build.VERSION.SDK_INT >= 30) {
		insetsController?.let {
			it.hide(WindowInsets.Type.statusBars())
			it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
		}
	} else {
		@Suppress("DEPRECATION")
		decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
	}
}

// Opens the notification shade. StatusBarManager.expandNotificationsPanel is a hidden API, called
// through reflection as other launchers do, with the EXPAND_STATUS_BAR permission. Limit: a device
// can block the call. Then this does nothing, and the system edge swipe still opens the shade.
@SuppressLint("WrongConstant")
fun Context.expandNotifications() {
	try {
		val sbm = getSystemService("statusbar") ?: return
		sbm.javaClass.getMethod("expandNotificationsPanel").invoke(sbm)
	} catch (e: Exception) {
		// This device blocks the call.
	}
}

// Left and right padding that keeps a header of height `h` at the top edge clear of the rounded
// screen corners (Android 12 and later) and of a camera cutout in a top corner. A cutout in the
// middle third needs no padding: the header has empty space there.
fun headerSidePadding(insets: WindowInsets, width: Int, h: Int): Pair<Int, Int> {
	var left = 0
	var right = 0
	if (Build.VERSION.SDK_INT >= 31) {
		// The x inset of the corner curve at half the header height.
		fun inset(c: RoundedCorner?, fromLeft: Boolean): Int {
			if (c == null) return 0
			val r = c.radius.toFloat()
			val dy = c.center.y - h / 2f
			if (dy <= 0f) return 0
			val dx = sqrt(maxOf(0f, r * r - dy * dy))
			return if (fromLeft) (c.center.x - dx).toInt() else (width - c.center.x - dx).toInt()
		}
		left = inset(insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT), true)
		right = inset(insets.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT), false)
	}
	if (Build.VERSION.SDK_INT >= 28) {
		for (r in insets.displayCutout?.boundingRects.orEmpty()) {
			if (r.top > 0) continue
			if (r.right < width / 3) left = maxOf(left, r.right)
			else if (r.left > width * 2 / 3) right = maxOf(right, width - r.left)
		}
	}
	return left to right
}

// Wraps the launcher screen. A drag down that starts while `atTop()` is true calls onSwipe() once,
// and the children get a cancel. It watches in dispatchTouchEvent, because the app list ScrollView
// blocks onInterceptTouchEvent once it starts its own drag.
class SwipeDownFrame(ctx: Context, private val atTop: () -> Boolean, private val onSwipe: () -> Unit) : FrameLayout(ctx) {
	// Drag distance before the shade opens: 48 dp (adjustable).
	private val minDrag = 48f * ctx.resources.displayMetrics.density
	private var startX = 0f
	private var startY = 0f
	private var armed = false
	private var swallow = false

	override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
		when (ev.actionMasked) {
			MotionEvent.ACTION_DOWN -> {
				startX = ev.x
				startY = ev.y
				armed = atTop()
				swallow = false
			}
			MotionEvent.ACTION_MOVE -> if (armed && swipeDown(ev.x - startX, ev.y - startY, minDrag)) {
				armed = false
				swallow = true
				val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
				super.dispatchTouchEvent(cancel)
				cancel.recycle()
				onSwipe()
				return true
			}
		}
		// After the swipe, the rest of the touch goes to no child.
		if (swallow) return true
		return super.dispatchTouchEvent(ev)
	}
}
