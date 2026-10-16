package com.rearman.datebook

import android.content.ClipData
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.DragEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/*
 * Memo Pad screens, after the Palm OS 3.5 Memo Pad. Each function is an extension on
 * MainActivity, the same way Details.kt builds its screens.
 */

/** Memos shown in the list: the category filter, the private filter, and the sort order. */
internal fun MainActivity.visibleMemos(): List<Memo> {
    val cat = prefs.memoCategory
    val hidePrivate = prefs.security == 2
    return memoOrder(store.memos, prefs.memoSort == 1).filter { m ->
        (cat < 0 || m.category == cat) && !(hidePrivate && m.isPrivate)
    }
}

/**
 * Memo List screen. One line per memo, in the chosen order. Tap a line to open it.
 * In manual sort, a long press drags a line onto another line to move it.
 */
internal fun MainActivity.buildMemoList(h: LinearLayout, content: FrameLayout, cmds: LinearLayout) {
    tab(h, "Memo")
    // Palm: a 26 px trigger at the right edge. The header has a 1 px right padding, so the margin cancels it.
    val trigger = popupTrigger(categoryLabel(prefs.memoCategory)) { tv ->
        endEdit()
        pickCategory(tv, prefs.memoCategory, includeAll = true, app = 1) { id -> prefs.memoCategory = id; render() }
    }.apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    h.addView(trigger, LP(pp(26), WRAP).apply { marginEnd = -pp(1) })

    val manual = prefs.memoSort == 0
    val items = visibleMemos()
    val col = vbox()
    if (items.isEmpty()) col.addView(label("No memos. Tap New to add one.").apply {
        setPadding(pp(5), pp(6.5f), pp(5), pp(6.5f))
    })
    items.forEachIndexed { i, m -> col.addView(memoRow(m, i + 1, manual), lp(MATCH)) }
    withScrollBar(content, ScrollView(this).apply { addView(col); isFillViewport = true; isVerticalScrollBarEnabled = false })

    cmd(cmds, "New", gap = 0) { newMemo() }
    cmds.addView(View(this), LP(0, 1, 1f))
}

/**
 * One list row for [m], numbered [n]. A masked memo shows a gray bar; a tap asks to show
 * it. In manual sort, the row also takes a drag and a drop.
 */
private fun MainActivity.memoRow(m: Memo, n: Int, manual: Boolean): View {
    val row: View = if (isMasked(m)) hbox().apply {
        addView(label("").apply {
            background = box(Palm.LIGHT, Palm.LIGHT, 0f, 1f)
            setOnClickListener { revealAsk(m) }
        }, LP(0, pp(7), 1f).apply { topMargin = pp(2); marginEnd = pp(3) })
    } else label("$n. ${m.title}").apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(pp(3), 0, pp(3), 0)
        setOnClickListener { endEdit(); memoOpen = m; render() }
    }
    if (manual) {
        row.setOnLongClickListener { v ->
            v.startDragAndDrop(ClipData.newPlainText("memo", m.id.toString()), View.DragShadowBuilder(v), m, 0)
        }
        row.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> true
                DragEvent.ACTION_DROP -> {
                    val dragged = event.localState as Memo
                    val full = memoOrder(store.memos, false)
                    val changed = moveMemo(full, full.indexOf(dragged), full.indexOf(m))
                    store.savePositions(changed)
                    render()
                    true
                }
                else -> false
            }
        }
    }
    return row
}

/**
 * Memo Edit screen for [MainActivity.memoOpen]. The body is one multi-line field on lined paper.
 * A change saves the memo when it has text, or when it already exists in the store.
 */
internal fun MainActivity.buildMemoEdit(h: LinearLayout, content: FrameLayout, cmds: LinearLayout) {
    val m = memoOpen!!
    val list = visibleMemos()
    val idx = if (m.id == 0L) -1 else list.indexOfFirst { it.id == m.id }
    val n = if (idx < 0) list.size + 1 else idx + 1
    val total = if (idx < 0) list.size + 1 else list.size
    tab(h, "Memo $n of $total")
    h.addView(popupTrigger(categoryLabel(m.category)) { tv ->
        endEdit()
        pickCategory(tv, m.category, includeAll = false, app = 1) { id ->
            m.category = id
            if (m.text.isNotBlank()) store.save(m)
            render()
        }
    })

    val done = {
        endEdit()
        if (m.text.isBlank() && m.id != 0L) store.delete(m)
        memoOpen = null
        render()
    }
    val ed = BackEdit(this).apply {
        onEmptyBack = done // Backspace in an empty memo closes and deletes it.
        typeface = Palm.typeface(context, false)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, ppf(Palm.text))
        includeFontPadding = false
        setTextColor(Palm.INK)
        background = LinedBackground(this@buildMemoEdit, this, fill = true)
        setPadding(pp(2), 0, pp(2), 0)
        gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE
        filters = arrayOf<InputFilter>(InputFilter.LengthFilter(4096)) // Palm OS memo limit
        setText(m.text)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                m.text = s?.toString() ?: ""
                if (m.id != 0L || m.text.isNotBlank()) store.save(m)
            }
        })
    }
    lastEdit = ed
    // WRAP height: the field grows with the text, so the scroll bar has a range to show.
    withScrollBar(content, ScrollView(this).apply {
        addView(ed, LP(MATCH, WRAP))
        isFillViewport = true
        isVerticalScrollBarEnabled = false
    })
    ed.post { ed.requestFocus(); ed.setSelection(ed.text.length) }
    if (m.id == 0L) showKeyboardSoon()

    cmd(cmds, "Done", gap = 0, onClick = done)
    cmd(cmds, "Details") { showMemoDetails(m) }
    cmds.addView(View(this), LP(0, 1, 1f))
}

/**
 * Starts a new memo in the category shown in the list, then opens it for editing.
 * [initial] is text typed in the Memo List; a memo with text is saved at once.
 */
internal fun MainActivity.newMemo(initial: String = "") {
    endEdit()
    val m = Memo(text = initial, category = if (prefs.memoCategory > 0) prefs.memoCategory else 0)
    if (m.text.isNotBlank()) store.save(m)
    memoOpen = m
    render()
}

/** Memo Details dialog: the category and the private flag. OK saves them. Delete asks first. */
private fun MainActivity.showMemoDetails(m: Memo) {
    var cat = m.category
    var priv = m.isPrivate
    val d = PalmDialog(this, "Memo Details", height = 72)
    d.row("Category:", popupTrigger(categoryLabel(cat)) { tv ->
        pickCategory(tv, cat, includeAll = false, app = 1) { id -> cat = id; tv.setTrigger(categoryLabel(id)) }
    }, labelEnd = 52)
    // Palm: the "Private:" label starts at form x 13 and the box at x 58 (body x 54). The box has no padding.
    val privRow = hbox().apply {
        addView(label("Private:", bold = true), LP(pp(45), WRAP).apply { marginStart = pp(9) })
        addView(checkBox("", m.isPrivate) { b -> priv = b }.apply { setPadding(0, 0, 0, 0) }, LP(WRAP, WRAP))
    }
    d.add(privRow, 0)
    d.button("OK", 37) {
        m.category = cat
        m.isPrivate = priv
        if (m.id != 0L || m.text.isNotBlank()) store.save(m)
        d.dismiss()
        render()
    }
    d.button("Cancel", 37) { d.dismiss() }
    d.button("Delete...", 40) { d.dismiss(); deleteMemoAsk(m) }
    d.show()
}

/** Asks before the current memo is deleted. The archive copy is saved first when checked. */
internal fun MainActivity.deleteMemoAsk(m: Memo) {
    palmAlert("Delete Memo", "Delete current memo?", 86, archiveY = 43) { archive ->
        val doIt = { if (m.id != 0L) store.delete(m); memoOpen = null; render() }
        if (archive && m.id != 0L) exportFile("memo-archive", store.exportJson(emptyList(), emptyList(), listOf(m)), doIt)
        else doIt()
    }
}

/** Memo Pad Preferences: the sort order. OK saves it. */
internal fun MainActivity.showMemoPreferences() {
    val names = listOf("Manual", "Alphabetic")
    var sort = prefs.memoSort
    val d = PalmDialog(this, "Memo Preferences", height = 57)
    d.row("Sort by:", popupTrigger(names[sort]) { tv ->
        popupList(tv, names, sort) { i -> sort = i; tv.setTrigger(names[i]) }
    }, labelEnd = 45)
    d.button("OK", 37) { prefs.memoSort = sort; d.dismiss(); render() }
    d.button("Cancel", 37) { d.dismiss() }
    d.show()
}

/** Memo Pad About box. */
internal fun MainActivity.showMemoAbout() {
    ask("About Memo Pad", "Memo Pad, after the Palm OS 3.5 Memo Pad.", "OK" to {})
}

/** Puts [sv] in the content area with a Palm scroll bar on its right. The bar is 7 px wide. */
private fun MainActivity.withScrollBar(content: FrameLayout, sv: ScrollView) {
    val bar = PalmScrollBar(this).apply { attach(sv) }
    val row = hbox(Gravity.TOP).apply {
        addView(sv, LP(0, MATCH, 1f))
        addView(bar, LP(pp(7), MATCH))
    }
    content.addView(row, FrameLayout.LayoutParams(MATCH, MATCH))
}

/**
 * Palm scroll bar, 7 px wide: a 1 px line, an arrow at each end and a 3 px thumb. The thumb
 * follows the scroll position of the attached [ScrollView]. The bar keeps its place in the
 * layout and is invisible when the content fits. A tap on an arrow scrolls one line (11 px).
 */
private class PalmScrollBar(ctx: Context) : View(ctx) {
    private var sv: ScrollView? = null
    private val ink = Paint().apply { color = Palm.INK }
    private val end = 4f // Palm px from each end of the bar to the arrow base

    fun attach(view: ScrollView) {
        sv = view
        view.setOnScrollChangeListener { _, _, _, _, _ -> refresh() }
        // The child is added to [view] before attach runs, in both callers, so its layout changes are seen here.
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> refresh() }
        view.getChildAt(0)?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> refresh() }
    }

    private fun refresh() {
        val s = sv ?: return
        val fits = (s.getChildAt(0)?.height ?: 0) <= s.height
        visibility = if (fits) INVISIBLE else VISIBLE
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val s = sv ?: return
        val child = s.getChildAt(0)?.height ?: return
        val u = context.ppf(1f)
        val a = context.ppf(end)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2
        c.drawRect(cx - u / 2, a, cx + u / 2, h - a, ink)
        tri(c, cx, u, a, 2.5f * u)
        tri(c, cx, h - u, h - a, 2.5f * u)
        // The thumb moves along the track between the arrows. Its length is the visible share of the content.
        val track = h - 2 * a
        val thumb = maxOf(context.ppf(5f), track * (s.height.toFloat() / child))
        val range = (child - s.height).coerceAtLeast(1)
        val top = a + (track - thumb) * s.scrollY / range
        c.drawRect(cx - 1.5f * u, top, cx + 1.5f * u, top + thumb, ink)
    }

    /** A filled triangle with its tip at ([x], [tip]) and its base at [base], half as wide as [half]. */
    private fun tri(c: Canvas, x: Float, tip: Float, base: Float, half: Float) {
        val p = Path().apply {
            moveTo(x, tip)
            lineTo(x - half, base)
            lineTo(x + half, base)
            close()
        }
        c.drawPath(p, ink)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val s = sv ?: return false
        if (e.actionMasked != MotionEvent.ACTION_UP) return true
        val step = context.ppf(11f).toInt()
        val a = context.ppf(end)
        when {
            e.y < a -> s.smoothScrollBy(0, -step)
            e.y > height - a -> s.smoothScrollBy(0, step)
            else -> return true
        }
        performClick()
        return true
    }
}
