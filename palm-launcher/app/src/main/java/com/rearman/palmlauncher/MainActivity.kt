package com.rearman.palmlauncher

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView

class MainActivity : Activity() {
	lateinit var state: LauncherState
	var apps: List<AppEntry> = emptyList()
	var receiver: BroadcastReceiver? = null
	var hasFocus = false
	private var scroll: ScrollView? = null
	// The battery gauge in the header and its last reading (ACTION_BATTERY_CHANGED, while resumed).
	private var battery: BatteryGauge? = null
	// The last gauge margin after the clock tab. A new header starts with it, so the gauge does not jump.
	private var gaugeMargin = 0
	private var batteryLevel = 100
	private var batteryCharging = false
	private val batteryReceiver = object : BroadcastReceiver() {
		override fun onReceive(c: Context, i: Intent) = readBattery(i)
	}

	// Damien Guard's Palm OS font, the same files as in Datebook (assets/fonts).
	private val palmRegular by lazy { Typeface.createFromAsset(assets, "fonts/palm-os.ttf") }
	private val palmBold by lazy { Typeface.createFromAsset(assets, "fonts/palm-os-bold.ttf") }

	private val match = ViewGroup.LayoutParams.MATCH_PARENT
	private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

	fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

	// The screen is always Green LCD: the paper color of the Datebook app, and a darker green for the dock.
	internal fun paper() = 0xFFC9D3A8.toInt()
	private fun dockColor() = 0xFFB5BF94.toInt()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		state = Store.load(this)
		reload()
		receiver = Apps.watch(this) { reload(); render(keepScroll = true) }
		render()
		hideStatusBar()
	}

	override fun onDestroy() {
		super.onDestroy()
		receiver?.let { unregisterReceiver(it) }
	}

	override fun onResume() {
		super.onResume()
		// ACTION_BATTERY_CHANGED is sticky, so registerReceiver returns the current reading.
		registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { readBattery(it) }
	}

	override fun onPause() {
		super.onPause()
		unregisterReceiver(batteryReceiver)
	}

	private fun readBattery(i: Intent) {
		val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
		val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
		if (level >= 0 && scale > 0) batteryLevel = level * 100 / scale
		batteryCharging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
		battery?.set(batteryLevel, batteryCharging)
	}

	override fun onWindowFocusChanged(focus: Boolean) {
		super.onWindowFocusChanged(focus)
		hasFocus = focus
		if (focus) hideStatusBar()
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		// Home pressed while the launcher already shows: cycle the Palm category.
		if (intent.action == Intent.ACTION_MAIN && hasFocus &&
			(intent.flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT) == 0) {
			state.selected = state.nextCategory(apps.map { it.component })
			save()
			render()
		}
	}

	// Ctrl and a command letter runs the menu command, as the Palm command stroke does.
	override fun dispatchKeyEvent(event: KeyEvent): Boolean {
		if (event.action == KeyEvent.ACTION_DOWN && event.isCtrlPressed && event.repeatCount == 0) {
			commandKey(event.keyCode)?.let { k -> menus().command(k) }?.let { it.action(); return true }
		}
		return super.dispatchKeyEvent(event)
	}

	// Back does nothing on the home screen.
	@Deprecated("Deprecated in Java")
	@Suppress("DEPRECATION")
	override fun onBackPressed() {
	}

	fun reload() {
		apps = Apps.load(this)
		if (apps.isNotEmpty()) {
			state.prune(apps.map { it.component }.toSet())
			save()
		}
	}

	fun save() = Store.save(this, state)

	// keepScroll: stay at the same place in the app list (an edit of one app); else go to the top.
	fun render(keepScroll: Boolean = false) {
		val y = if (keepScroll) scroll?.scrollY ?: 0 else 0
		// Wallpaper: the system wallpaper shows behind the app list; the top bar and dock keep their colors.
		val back = if (state.wallpaper) Color.TRANSPARENT else paper()
		if (state.wallpaper) window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
		else window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
		val root = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			setBackgroundColor(back)
		}
		val area = appArea()
		scroll = area
		root.addView(topBar(), lp(match, wrap))
		// The 2 Palm px title line, as in Datebook.
		root.addView(rule(), lp(match, pp(2f)))
		root.addView(area, lp(match, 0, 1f))
		root.addView(rule(), lp(match, dp(1)))
		root.addView(dock(), lp(match, wrap))
		// A swipe down on the header, or on the app list at its top, opens the notification shade.
		val frame = SwipeDownFrame(this, { (scroll?.scrollY ?: 0) == 0 }) { expandNotifications() }
		frame.addView(root, FrameLayout.LayoutParams(match, match))
		setContentView(frame)
		window.decorView.setBackgroundColor(back)
		// The new list has no height until layout, so scroll after it.
		if (y > 0) area.post { area.scrollTo(0, y) }
	}

	private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

	private fun rule() = View(this).apply { setBackgroundColor(Color.BLACK) }

	// Sets the Palm OS font and size of launcher text; call it after the text is set. All text uses the
	// Datebook sizes: Text Size gives 13, 16 or 20 Palm px, where the shorter screen side is 160 Palm px
	// (Palm IIIxe).
	internal fun TextView.font(bold: Boolean) {
		val dm = resources.displayMetrics
		setTextSize(TypedValue.COMPLEX_UNIT_PX, state.textSize.palmPx * minOf(dm.widthPixels, dm.heightPixels) / 160f)
		typeface = if (bold) palmBold else palmRegular
		includeFontPadding = false
		// The Palm OS font has no ▾ or ✓ glyph, so Android draws them from the system font, which draws
		// them larger. As in Datebook, this shrinks each one to 0.6 of the text size.
		if (text.none { it == '▾' || it == '✓' }) return
		val s = SpannableString(text)
		text.forEachIndexed { i, ch ->
			if (ch == '▾' || ch == '✓') s.setSpan(RelativeSizeSpan(0.6f), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
		}
		text = s
	}

	// Palm title row, as in Datebook: a black tab with the current time (a tap opens the menu), the battery
	// gauge, and the category trigger at the right.
	private fun topBar(): LinearLayout {
		val clock = TextClock(this).apply {
			// As on the Palm IIIxe: the time with no am or pm.
			format12Hour = "h:mm"
			format24Hour = "H:mm"
			font(bold = true)
			setTextColor(paper())
			val r = pp(3f).toFloat()
			background = GradientDrawable().apply {
				setColor(Color.BLACK)
				cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
			}
			setPadding(pp(3f), pp(1f), pp(3f), pp(1f))
			setOnClickListener { showMenu() }
		}
		val category = TextView(this).apply {
			text = "▾ " + state.categoryName(state.selected)
			font(bold = true)
			setPadding(pp(1f), pp(1f), pp(2f), pp(1f))
			setOnClickListener { categoryMenu(it) }
		}
		// The gauge is a local val too: a mutable property does not smart-cast to non-null for addView.
		val gauge = BatteryGauge(this, { pp(it).toFloat() }, paper()).also { it.set(batteryLevel, batteryCharging) }
		battery = gauge
		return LinearLayout(this).apply {
			orientation = LinearLayout.HORIZONTAL
			setPadding(0, 0, pp(1f), 0)
			setBackgroundColor(paper())
			addView(clock, lp(wrap, wrap).apply { marginStart = pp(1f); gravity = Gravity.BOTTOM })
			val gaugeLp = lp(wrap, wrap).apply { marginStart = if (gaugeMargin > 0) gaugeMargin else pp(4f); gravity = Gravity.CENTER_VERTICAL }
			addView(gauge, gaugeLp)
			// As on the Palm IIIxe, the gauge starts at Palm x 53, or 4 px after a wider clock tab.
			clock.addOnLayoutChangeListener { _, _, _, right, _, _, _, _, _ ->
				val margin = maxOf(pp(4f), pp(53f) - right)
				gaugeMargin = margin
				if (gaugeLp.marginStart != margin) {
					gaugeLp.marginStart = margin
					// A new layout cannot start inside this layout pass, so post it.
					gauge.post { gauge.layoutParams = gaugeLp }
				}
			}
			// A plain View with WRAP_CONTENT takes all the height it is offered, so the spacer gets 0.
			addView(View(this@MainActivity), lp(0, 0, 1f))
			addView(category, lp(wrap, wrap).apply { gravity = Gravity.BOTTOM })
			// The header is at the top edge: keep it clear of the rounded corners and a corner camera.
			setOnApplyWindowInsetsListener { v, insets ->
				val w = if (v.width > 0) v.width else resources.displayMetrics.widthPixels
				val h = if (v.height > 0) v.height else pp(13f)
				val (left, right) = headerSidePadding(insets, w, h)
				v.setPadding(left, 0, maxOf(pp(1f), right), 0)
				insets
			}
		}
	}

	// The Palm menu bar, after the Palm OS 3.5 Applications menus. Each App command opens a list of
	// the apps in the selected category.
	private fun menus(): List<Pair<String, List<MenuItem>>> {
		val shown = state.filter(apps, { it.component }, { it.label })
		val app: List<MenuItem> = listOf(
			MenuItem("Category...", 'Y') { pickApp(this, shown, "Category") { changeCategory(it) } },
			MenuItem("Info...", 'I') { pickApp(this, shown, "Info") { showInfo(it) } },
			MenuItem("Delete...", 'D') { pickApp(this, shown, "Delete") { deleteApp(it) } },
		)
		val options: List<MenuItem> = listOf(
			MenuItem(if (state.viewMode == ViewMode.GRID) "View as List" else "View as Grid") {
				state.viewMode = if (state.viewMode == ViewMode.GRID) ViewMode.LIST else ViewMode.GRID
				save()
				render()
			},
			MenuItem(if (state.wallpaper) "✓ Wallpaper" else "Wallpaper") {
				state.wallpaper = !state.wallpaper
				save()
				render(keepScroll = true)
			},
			MenuItem("Text Size...") {
				pickSize(this, "Text Size", state.textSize) { s ->
					state.textSize = s
					save()
					render(keepScroll = true)
				}
			},
			MenuItem("Icon Size...") {
				pickSize(this, "Icon Size", state.iconSize) { s ->
					state.iconSize = s
					save()
					render(keepScroll = true)
				}
			},
			// The system wallpaper picker sets the home screen and lock screen wallpapers.
			// The launcher turns Wallpaper on, so the new home screen wallpaper shows.
			MenuItem("Change Wallpaper...") {
				state.wallpaper = true
				save()
				render(keepScroll = true)
				startSafely(Intent(Intent.ACTION_SET_WALLPAPER))
			},
		)
		return listOf("App" to app, "Options" to options)
	}

	private fun showMenu() = MenuBar(this, menus()).show()

	private fun categoryMenu(anchor: View) {
		// Item indexes are positions in ids; the last item is "Edit Categories...".
		val ids = listOf(ALL) + state.categories.map { it.id }
		val items = ids.map { state.categoryName(it) } + "Edit Categories..."
		popupList(anchor, items, setOf(ids.indexOf(state.selected))) { i ->
			if (i == ids.size) {
				editCategories(this, state) { save(); render() }
			} else {
				state.selected = ids[i]
				save()
				render()
			}
		}
	}

	private fun appArea(): ScrollView {
		val grid = state.viewMode == ViewMode.GRID
		val cols = if (grid) 4 else 2
		val cells = state.filter(apps, { it.component }, { it.label }).map { cell(it) }
		// List view: the two columns together are centered on the screen (null widths: equal columns).
		// Limit: it measures against the full screen width, so in split-screen mode the columns can be cut off.
		val widths = if (grid) null else {
			val spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
			columnWidths(cells.map { it.measure(spec, spec); it.measuredWidth }, cols, resources.displayMetrics.widthPixels)
		}
		val list = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			gravity = Gravity.CENTER_HORIZONTAL
		}
		cells.chunked(cols).forEach { chunk ->
			val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
			if (widths != null) {
				chunk.forEachIndexed { c, v -> row.addView(v, lp(widths[c], wrap)) }
				// The fixed row width keeps a short last row at the left of the block.
				list.addView(row, lp(widths.sum(), wrap))
			} else {
				chunk.forEach { row.addView(it, lp(0, wrap, 1f)) }
				// Pad a short last row so its cells keep the same width.
				repeat(cols - chunk.size) { row.addView(View(this), lp(0, 0, 1f)) }
				list.addView(row, lp(match, wrap))
			}
		}
		return ScrollView(this).apply { addView(list) }
	}

	private fun cell(e: AppEntry): View {
		val grid = state.viewMode == ViewMode.GRID
		val cell = LinearLayout(this)
		cell.orientation = if (grid) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
		cell.gravity = if (grid) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
		val side = if (grid) dp(6) else dp(8)
		cell.setPadding(side, dp(6), side, dp(6))
		val iconSize = (dp(if (grid) 48 else 32) * state.iconSize.scale).toInt()
		val icon = ImageView(this).apply { setImageDrawable(e.icon) }
		cell.addView(icon, LinearLayout.LayoutParams(iconSize, iconSize))
		val label = TextView(this).apply {
			text = e.label
			font(bold = false)
			if (grid) {
				gravity = Gravity.CENTER_HORIZONTAL
				maxLines = 2
			} else {
				setSingleLine(true)
			}
			ellipsize = TextUtils.TruncateAt.END
			// A paper-colored glow keeps black text legible on any wallpaper.
			if (state.wallpaper) setShadowLayer(dp(3).toFloat(), 0f, 0f, paper())
		}
		val labelParams = if (grid) {
			LinearLayout.LayoutParams(match, wrap)
		} else {
			LinearLayout.LayoutParams(0, wrap, 1f).apply { marginStart = dp(8) }
		}
		cell.addView(label, labelParams)
		cell.setOnClickListener { Apps.launch(this, e.component) }
		cell.setOnLongClickListener { v -> appMenu(v, e); true }
		return cell
	}

	private fun appMenu(anchor: View, e: AppEntry) {
		val labels = listOf("Category...", "Dock 1", "Dock 2", "Dock 3", "Dock 4", "Info", "Delete")
		popupList(anchor, labels) { i ->
			when (i) {
				0 -> changeCategory(e)
				1, 2, 3, 4 -> {
					state.dock[i - 1] = e.component
					save()
					render(keepScroll = true)
				}
				5 -> showInfo(e)
				6 -> deleteApp(e)
			}
		}
	}

	private fun changeCategory(e: AppEntry) = pickCategory(this, state, state.categoryOf(e.component)) { id ->
		state.assign(e.component, id)
		save()
		render(keepScroll = true)
	}

	private fun showInfo(e: AppEntry) = startSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
		Uri.fromParts("package", e.packageName, null)))

	private fun deleteApp(e: AppEntry) = startSafely(Intent(Intent.ACTION_DELETE,
		Uri.fromParts("package", e.packageName, null)))

	private fun startSafely(intent: Intent) {
		try {
			startActivity(intent)
		} catch (ex: ActivityNotFoundException) {
			// No activity on this device handles the intent.
		}
	}

	private fun dock(): LinearLayout {
		val bar = LinearLayout(this).apply {
			orientation = LinearLayout.HORIZONTAL
			setPadding(dp(8), dp(8), dp(8), dp(8))
			setBackgroundColor(dockColor())
		}
		val iconSize = (dp(48) * state.iconSize.scale).toInt()
		for (i in state.dock.indices) {
			val app = apps.find { it.component == state.dock[i] }
			val slot = FrameLayout(this)
			if (app != null) {
				val icon = ImageView(this).apply { setImageDrawable(app.icon) }
				slot.addView(icon, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))
				slot.setOnClickListener { Apps.launch(this, app.component) }
				slot.setOnLongClickListener { v -> dockMenu(v, i); true }
			}
			// 8 dp above and below the icon, as at Normal size (48 dp icon in a 64 dp slot).
			bar.addView(slot, LinearLayout.LayoutParams(0, iconSize + dp(16), 1f))
		}
		return bar
	}

	private fun dockMenu(anchor: View, i: Int) {
		popupList(anchor, listOf("Clear")) {
			state.dock[i] = null
			save()
			render(keepScroll = true)
		}
	}
}
