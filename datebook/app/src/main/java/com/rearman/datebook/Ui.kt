package com.rearman.datebook

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/** Palm OS look: black ink on a green LCD screen, with grays for shading. */
object Palm {
    const val INK = Color.BLACK
    const val GRAY = 0xFF808080.toInt()
    const val LIGHT = 0xFFC4C4C4.toInt()
    /** The Green LCD paper color. The launcher copies it (palm-launcher MainActivity.paper). */
    val paper = 0xFFC9D3A8.toInt()
    /** Text size in Palm pixels. 16 draws the Palm OS font at one font pixel per Palm pixel, with an 11 px line. */
    var text = 16f

    private var regular: Typeface? = null
    private var boldFace: Typeface? = null

    /** The Palm OS font (Damien Guard's recreation, assets/fonts), loaded one time. */
    fun typeface(ctx: Context, bold: Boolean): Typeface {
        if (regular == null) {
            val a = ctx.applicationContext.assets
            regular = Typeface.createFromAsset(a, "fonts/palm-os.ttf")
            boldFace = Typeface.createFromAsset(a, "fonts/palm-os-bold.ttf")
        }
        return (if (bold) boldFace else regular)!!
    }

    fun apply(p: Prefs) {
        text = when (p.font) { 0 -> 13f; 2 -> 20f; else -> 16f }
    }
}

/**
 * The Palm OS font has no arrow glyphs, so these come from the system font, which draws them
 * larger. This shrinks each one to 0.6 of the text size.
 */
fun symbols(s: CharSequence): CharSequence {
    if (s.none { it == '◀' || it == '▶' || it == '▼' || it == '▲' }) return s
    val sp = SpannableString(s)
    s.forEachIndexed { i, ch ->
        if (ch == '◀' || ch == '▶' || ch == '▼' || ch == '▲')
            sp.setSpan(RelativeSizeSpan(0.6f), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    return sp
}

typealias LP = LinearLayout.LayoutParams

val MATCH: Int = ViewGroup.LayoutParams.MATCH_PARENT
val WRAP: Int = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.dp(v: Float): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

fun Context.dp(v: Int): Int = dp(v.toFloat())

/** Device pixels for [v] Palm pixels. The shorter screen side is 160 Palm px, as on the Palm IIIxe. */
fun Context.ppf(v: Float): Float =
    v * minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) / 160f

fun Context.pp(v: Float): Int = if (v <= 0f) 0 else maxOf(1, ppf(v).roundToInt())

fun Context.pp(v: Int): Int = pp(v.toFloat())

fun lp(w: Int = WRAP, h: Int = WRAP, weight: Float = 0f) = LP(w, h, weight)

fun Context.label(text: CharSequence, bold: Boolean = false, size: Float = Palm.text): TextView =
    TextView(this).apply {
        this.text = symbols(text)
        setTextColor(Palm.INK)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, ppf(size))
        includeFontPadding = false
        typeface = Palm.typeface(this@label, bold)
    }

fun Context.hbox(gravity: Int = Gravity.CENTER_VERTICAL): LinearLayout =
    LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; this.gravity = gravity }

fun Context.vbox(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

fun Context.box(fill: Int, stroke: Int, strokePp: Float, radiusPp: Float, dashed: Boolean = false): GradientDrawable =
    GradientDrawable().apply {
        setColor(fill)
        cornerRadius = pp(radiusPp).toFloat()
        if (dashed) setStroke(pp(strokePp), stroke, ppf(1f), ppf(1f))
        else if (strokePp > 0) setStroke(pp(strokePp), stroke)
    }

/**
 * Rounded Palm command button in the regular font, 12 px high. Inverts while pressed.
 * [width] is the Palm width. 0 gives the button at least 16 px wide.
 */
fun Context.palmButton(text: CharSequence, width: Int = 0, onClick: () -> Unit): TextView =
    label(text, bold = false).apply {
        gravity = Gravity.CENTER
        val normal = box(Palm.paper, Palm.INK, 1f, 3.5f)
        val pressed = box(Palm.INK, Palm.INK, 1f, 3.5f)
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), normal)
        }
        setTextColor(
            ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
                intArrayOf(Palm.paper, Palm.INK)
            )
        )
        setPadding(pp(2), 0, pp(2), 0)
        minHeight = pp(12)
        minimumHeight = pp(12)
        if (width > 0) setWidth(pp(width)) else minWidth = pp(16)
        setOnClickListener { onClick() }
    }

/** Palm selector trigger: a value in a dotted box. Tap to change it. */
fun Context.selector(text: String, onClick: (TextView) -> Unit): TextView =
    label(text).apply {
        background = box(Color.TRANSPARENT, Palm.GRAY, 1f, 1f, dashed = true)
        setPadding(pp(2), 0, pp(2), 0)
        setOnClickListener { onClick(this) }
    }

/** Palm pop-up trigger: "▼ value". */
fun Context.popupTrigger(text: String, onClick: (TextView) -> Unit): TextView =
    label("▼ $text", bold = true).apply {
        setPadding(pp(1), pp(1), pp(2), pp(1))
        setOnClickListener { onClick(this) }
    }

fun TextView.setTrigger(text: String) { this.text = symbols("▼ $text") }

fun Context.checkBox(text: String, checked: Boolean, onChange: (Boolean) -> Unit = {}): CheckBox =
    CheckBox(this).apply {
        this.text = text
        isChecked = checked
        setTextColor(Palm.INK)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, ppf(Palm.text))
        includeFontPadding = false
        typeface = Palm.typeface(context, true)
        buttonDrawable = PalmCheckDrawable(this@checkBox, pp(10))
        minHeight = pp(14)
        minimumHeight = pp(14)
        minWidth = 0
        minimumWidth = 0
        setPadding(pp(3), 0, 0, 0)
        setOnCheckedChangeListener { _, b -> onChange(b) }
    }

/** Palm check box: a square with a 1 Palm px frame, and a check mark when checked. */
class PalmCheckDrawable(ctx: Context, private val size: Int) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palm.INK; style = Paint.Style.STROKE; strokeWidth = ctx.ppf(1f)
    }
    private var checked = false

    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size
    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val c = state.contains(android.R.attr.state_checked)
        if (c == checked) return false
        checked = c
        invalidateSelf()
        return true
    }

    override fun draw(c: Canvas) {
        val b = bounds
        val h = p.strokeWidth / 2
        c.drawRect(b.left + h, b.top + h, b.right - h, b.bottom - h, p)
        if (checked) {
            val l = b.left.toFloat(); val t = b.top.toFloat(); val w = b.width().toFloat()
            c.drawLine(l + w * 0.2f, t + w * 0.5f, l + w * 0.42f, t + w * 0.75f, p)
            c.drawLine(l + w * 0.42f, t + w * 0.75f, l + w * 0.85f, t + w * 0.2f, p)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

fun Context.field(text: String, hint: String = "", multiLine: Boolean = false): EditText =
    EditText(this).apply {
        setText(text)
        this.hint = hint
        setTextColor(Palm.INK)
        setHintTextColor(Palm.GRAY)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, ppf(Palm.text))
        includeFontPadding = false
        typeface = Palm.typeface(context, false)
        minHeight = 0
        minimumHeight = 0
        setPadding(pp(1), 0, pp(1), 0)
        backgroundTintList = ColorStateList.valueOf(Palm.GRAY)
        inputType = if (multiLine)
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    }

fun Context.numberField(value: Int): EditText = field(value.toString()).apply {
    inputType = InputType.TYPE_CLASS_NUMBER
    minWidth = pp(20)
    gravity = Gravity.CENTER
}

/**
 * A row of push buttons where one is selected (shown inverted),
 * like the priority 1-5 buttons or the repeat-type tabs.
 * [height] is the cell height in Palm px. [widths] gives a fixed Palm width for each cell.
 * Without [widths] the cells share the row width.
 */
class PushGroup(
    private val ctx: Context,
    labels: List<String>,
    initial: Int,
    private val multi: Boolean = false,
    height: Int = 11,
    private val widths: List<Int>? = null,
    private val onChange: (PushGroup) -> Unit = {}
) {
    val view: LinearLayout = ctx.hbox()
    private val cells = mutableListOf<TextView>()
    var selected: Int = initial
    val on = BooleanArray(labels.size)

    init {
        val cellPx = ctx.pp(height)
        labels.forEachIndexed { i, s ->
            val tv = ctx.label(s, bold = true).apply {
                gravity = Gravity.CENTER
                minHeight = cellPx
                minimumHeight = cellPx
                setPadding(ctx.pp(2), 0, ctx.pp(2), 0)
                setOnClickListener {
                    if (multi) on[i] = !on[i] else selected = i
                    refresh()
                    onChange(this@PushGroup)
                }
            }
            cells.add(tv)
            val cellLp = if (widths != null)
                LP(ctx.pp(widths[i]), WRAP)
            else LP(0, WRAP, 1f)
            view.addView(tv, cellLp.apply { marginEnd = ctx.pp(1) })
        }
        refresh()
    }

    fun select(i: Int) { selected = i; refresh() }

    fun refresh() {
        cells.forEachIndexed { i, tv ->
            val sel = if (multi) on[i] else i == selected
            tv.background = ctx.box(if (sel) Palm.INK else Palm.paper, Palm.INK, 1f, 1.5f)
            tv.setTextColor(if (sel) Palm.paper else Palm.INK)
        }
    }
}

/**
 * A Palm modal form: black title bar, bordered body, buttons along the bottom.
 * [height] is the form height in Palm px, from the Palm OS 3.5 form resources. The form is
 * 156 px wide and sits 2 px above the bottom of the screen, as on the Palm. 0 fits the height
 * to the content (alerts). 160 fills the screen (Note view).
 * The button row sits 5 px in from the left edge and 5 px above the bottom edge of the form.
 */
open class PalmDialog(ctx: Context, title: String, scroll: Boolean = true, height: Int = 0) : Dialog(ctx) {
    val body: LinearLayout = ctx.vbox().apply { setPadding(ctx.pp(3), ctx.pp(2), ctx.pp(3), ctx.pp(1)) }
    val buttons: LinearLayout = ctx.hbox().apply { setPadding(ctx.pp(4), ctx.pp(2), ctx.pp(4), ctx.pp(4)) }
    private val c = ctx

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val frame = ctx.vbox().apply {
            background = ctx.box(Palm.paper, Palm.INK, 1.5f, 2f)
            setPadding(ctx.pp(1), ctx.pp(1), ctx.pp(1), ctx.pp(1))
        }
        val bar = ctx.label(title, bold = true).apply {
            gravity = Gravity.CENTER
            setTextColor(Palm.paper)
            background = ctx.box(Palm.INK, Palm.INK, 0f, 1.5f)
            setPadding(ctx.pp(2), ctx.pp(1), ctx.pp(2), ctx.pp(1))
        }
        frame.addView(bar, lp(MATCH))
        if (scroll) {
            val sv = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false }
            sv.addView(body)
            frame.addView(sv, LP(MATCH, 0, 1f))
        } else {
            frame.addView(body, LP(MATCH, 0, 1f))
        }
        frame.addView(buttons, lp(MATCH))
        setContentView(frame)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val w = window
        if (height >= 160) {
            w?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        } else if (w != null) {
            w.setLayout(ctx.pp(156), if (height > 0) ctx.pp(height) else WindowManager.LayoutParams.WRAP_CONTENT)
            w.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            w.attributes = w.attributes.apply { y = ctx.pp(2) }
            // A Palm form draws over the screen behind it with no dimming.
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        // A dialog is a window of its own: hide the status bar in it too.
        window?.hideStatusBar()
    }

    /** Adds [v] to the body. [top] is the gap above [v], in Palm px. */
    fun add(v: View, top: Int = 2): View {
        body.addView(v, LP(MATCH, WRAP).apply { topMargin = c.pp(top) })
        return v
    }

    /**
     * "Label:  [control]" row, Palm style with a right-aligned bold label. The label ends at
     * form x [labelEnd]. The controls follow it 5 px on. The row is 14 px high, with 2 px above it.
     */
    fun row(name: String, vararg views: View, labelEnd: Int = 51): LinearLayout {
        val r = c.hbox()
        r.addView(c.label(name, bold = true).apply { gravity = Gravity.END; minWidth = c.pp(labelEnd - 4) },
            lp().apply { marginEnd = c.pp(5) })
        views.forEach { r.addView(it, lp().apply { marginEnd = c.pp(2) }) }
        body.addView(r, LP(MATCH, c.pp(14)).apply { topMargin = c.pp(2) })
        return r
    }

    /** Adds a command button to the button row. [width] is the Palm width, 0 for the default. */
    fun button(text: String, width: Int = 0, onClick: () -> Unit): TextView {
        val b = c.palmButton(text, width, onClick)
        buttons.addView(b, lp().apply { marginEnd = c.pp(6) })
        return b
    }
}

/**
 * An EditText that runs [onEmptyBack] when Backspace is pressed while the field is empty.
 * A hardware keyboard sends a key event; a soft keyboard calls deleteSurroundingText.
 * Limit: a held soft-keyboard Backspace cannot be told from single presses, so it also
 * runs [onEmptyBack] when it empties the field.
 */
class BackEdit(ctx: Context) : EditText(ctx) {
    var onEmptyBack: (() -> Unit)? = null

    private fun emptyBack(): Boolean {
        val f = onEmptyBack ?: return false
        if (text.isNotEmpty()) return false
        post(f)
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        (keyCode == KeyEvent.KEYCODE_DEL && event.repeatCount == 0 && emptyBack()) || super.onKeyDown(keyCode, event)

    override fun onCreateInputConnection(out: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(out) ?: return null
        return object : InputConnectionWrapper(ic, true) {
            override fun deleteSurroundingText(before: Int, after: Int): Boolean =
                (before > 0 && emptyBack()) || super.deleteSurroundingText(before, after)
        }
    }
}

/** A Palm pop-up list next to its trigger. */
fun Context.popupList(anchor: View, items: List<String>, selected: Int, onPick: (Int) -> Unit) {
    val col = vbox().apply {
        background = box(Palm.paper, Palm.INK, 1f, 1.5f)
        setPadding(pp(1), pp(1), pp(1), pp(1))
    }
    val sv = ScrollView(this).apply { addView(col); isVerticalScrollBarEnabled = false }
    val pw = PopupWindow(sv, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
    items.forEachIndexed { i, s ->
        val tv = label(s, bold = i == selected).apply {
            setPadding(pp(3), pp(1), pp(8), pp(1))
            if (i == selected) { setBackgroundColor(Palm.INK); setTextColor(Palm.paper) }
            setOnClickListener { pw.dismiss(); onPick(i) }
        }
        col.addView(tv, lp(MATCH))
    }
    pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    pw.elevation = dp(4).toFloat()
    // The visible area: above the keyboard when the keyboard shows. The list can fill all of it, and a
    // longer list scrolls.
    val vis = Rect().also { anchor.getWindowVisibleDisplayFrame(it) }
    val maxH = vis.height()
    sv.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
    if (sv.measuredHeight > maxH) pw.height = maxH
    // Show at the trigger, moved left or up only as far as needed to stay in the visible area.
    val loc = IntArray(2)
    anchor.getLocationOnScreen(loc)
    val dm = resources.displayMetrics
    val w = sv.measuredWidth
    val h = minOf(sv.measuredHeight, maxH)
    val x = loc[0].coerceIn(0, maxOf(0, dm.widthPixels - w))
    val y = loc[1].coerceIn(vis.top, maxOf(vis.top, vis.bottom - h))
    pw.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
}

/** A message with buttons. Each button closes the dialog and then runs its action. */
fun Context.ask(title: String, message: String, vararg choices: Pair<String, () -> Unit>) {
    val d = PalmDialog(this, title)
    val line = hbox(Gravity.TOP)
    line.addView(IconView(this, IconView.CONFIRM), LP(WRAP, WRAP).apply { topMargin = pp(1) })
    line.addView(label(message).apply { setPadding(0, pp(1), 0, pp(3)) },
        LP(WRAP, WRAP).apply { marginStart = pp(8) })
    d.add(line)
    for ((text, action) in choices) d.button(text) { d.dismiss(); action() }
    d.show()
}

/**
 * A Palm confirmation form: the question icon, a bold message and OK and Cancel buttons.
 * Lines of [message] break at "\n". [archiveY] > 0 adds a "Save archive copy on PC" check box
 * at that form y. [archiveOn] sets the start state of that check box. [onOk] gets the state of
 * that check box, false when there is none.
 */
fun Context.palmAlert(
    title: String,
    message: String,
    height: Int,
    archiveY: Int = 0,
    archiveOn: Boolean = false,
    okText: String = "OK",
    onOk: (archive: Boolean) -> Unit
) {
    val d = PalmDialog(this, title, height = height)
    // Form coordinates. The body starts at form (4, 16), so each margin is the form value less that origin.
    val area = FrameLayout(this)
    fun place(v: View, formX: Int, formY: Int) =
        area.addView(v, FrameLayout.LayoutParams(WRAP, WRAP).apply {
            leftMargin = pp(formX - 4)
            topMargin = pp(formY - 16)
        })
    place(IconView(this, IconView.CONFIRM), 8, 19)
    place(label(message, bold = true), 32, 19)
    val archive: CheckBox? = if (archiveY > 0)
        checkBox("Save archive copy on PC", archiveOn).also { place(it, 12, archiveY) }
    else null
    d.body.addView(area, LP(MATCH, WRAP))
    d.button(okText, 37) { d.dismiss(); onOk(archive?.isChecked == true) }
    d.button("Cancel", 37) { d.dismiss() }
    d.show()
}

fun Context.toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

/** Small drawn icons: note, alarm and repeat for the Day view, and the 16 px confirmation icon. */
class IconView(ctx: Context, private val kind: Int) : View(ctx) {
    companion object { const val NOTE = 0; const val ALARM = 1; const val REPEAT = 2; const val CONFIRM = 3 }
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = Palm.INK; style = android.graphics.Paint.Style.STROKE; strokeWidth = ctx.ppf(1f)
    }
    // CONFIRM: a filled disc with a "?" in the bold Palm font.
    private val disc = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = Palm.INK; style = android.graphics.Paint.Style.FILL
    }
    private val query = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = android.graphics.Paint.Align.CENTER
        textSize = ctx.ppf(12f)
        typeface = Palm.typeface(ctx, true)
    }

    override fun onMeasure(w: Int, h: Int) {
        val s = context.pp(if (kind == CONFIRM) 16 else 8)
        setMeasuredDimension(s, s)
    }

    override fun onDraw(c: android.graphics.Canvas) {
        val s = width.toFloat()
        val m = s * 0.15f
        when (kind) {
            CONFIRM -> {
                c.drawCircle(s / 2, s / 2, s / 2, disc)
                query.color = Palm.paper
                val fm = query.fontMetrics
                c.drawText("?", s / 2, s / 2 - (fm.ascent + fm.descent) / 2, query)
            }
            NOTE -> {
                c.drawRect(m, m, s - m, s - m, paint)
                c.drawLine(m * 2, s * 0.4f, s - m * 2, s * 0.4f, paint)
                c.drawLine(m * 2, s * 0.6f, s - m * 2, s * 0.6f, paint)
            }
            ALARM -> {
                val cx = s / 2; val cy = s / 2 + m / 3
                c.drawCircle(cx, cy, s / 2 - m, paint)
                c.drawLine(cx, cy, cx, cy - s * 0.22f, paint)
                c.drawLine(cx, cy, cx + s * 0.18f, cy, paint)
            }
            REPEAT -> { // Palm repeat symbol: two overlapping squares
                val k = s * 0.55f
                c.drawRect(m, m, m + k, m + k, paint)
                c.drawRect(s - m - k, s - m - k, s - m, s - m, paint)
            }
        }
    }
}

fun Context.frame(): FrameLayout = FrameLayout(this)

@Suppress("unused")
fun drawableOf(d: Drawable) = d

/**
 * Hides the status bar in this window. A swipe from the top edge shows it for a moment, as in
 * full-screen apps. On a screen with a camera cutout the window stays below the cutout. Call it after
 * setContentView, for each window that shows (the activity, the menu bar and the dialogs), and again
 * on each activity focus: Android 10 and earlier clear the flags.
 */
fun Window.hideStatusBar() {
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
