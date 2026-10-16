package com.rearman.datebook

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalDate

/**
 * Adds [v] at form (x, y) of a page whose origin is form (4, 16), as in a PalmDialog body.
 * [w] and [h] are Palm sizes. 0 keeps the size of the view. A negative offset places the view above or left of the origin.
 */
private fun android.widget.FrameLayout.formAt(v: android.view.View, x: Int, y: Int, w: Int = 0, h: Int = 0) {
    val c = context
    addView(v, android.widget.FrameLayout.LayoutParams(
        if (w > 0) c.pp(w) else WRAP, if (h > 0) c.pp(h) else WRAP).apply {
        leftMargin = c.signedPp(x - 4)
        topMargin = c.signedPp(y - 16)
    })
}

/** Device px for a Palm px offset, keeping its sign. */
private fun Context.signedPp(v: Int): Int = if (v < 0) -pp(-v) else pp(v)

/** Palm "Go to Date" / "Set Date" form: year arrows, month buttons, day grid. */
class DateDialog(
    private val ctx: Context,
    title: String,
    initial: Long,
    private val onPick: (Long) -> Unit
) : PalmDialog(ctx, title, scroll = false, height = 156) {
    private var year: Int
    private var month: Int
    private val selected: Long = initial
    private val yearLabel = ctx.label("", bold = true, size = Palm.text)
    private val months: PushGroup
    private val grid = ctx.vbox()

    init {
        val d = LocalDate.ofEpochDay(initial)
        year = d.year
        month = d.monthValue
        // Form coordinates, origin (4, 16). The arrows sit 2 px above the origin, so the page must not clip.
        val page = ctx.frame()
        page.clipChildren = false
        fun arrow(text: String, step: Int) = ctx.label(text, bold = true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { year += step; draw() }
        }
        page.formAt(yearLabel, 65, 16)
        page.formAt(arrow("◀", -1), 46, 14, 15, 15)
        page.formAt(arrow("▶", 1), 92, 14, 15, 15)
        months = PushGroup(ctx, Fmt.monthShort.toList(), month - 1, height = 13, widths = List(12) { 22 }) { g ->
            month = g.selected + 1; draw()
        }
        // The 12 month cells form 2 rows of 6, 1 px apart, at form y 31 and 45.
        val cells = (0 until months.view.childCount).map { months.view.getChildAt(it) }
        months.view.removeAllViews()
        for (r in 0 until 2) {
            val row = ctx.hbox()
            for (c in 0 until 6) row.addView(cells[r * 6 + c], LinearLayout.LayoutParams(ctx.pp(22), WRAP).apply {
                marginEnd = ctx.pp(1)
            })
            page.formAt(row, 9, 31 + r * 14, 0, 13)
        }
        // The day grid: a header row and 6 week rows, 70 px high.
        page.formAt(grid, 7, 63, 140, 70)
        body.addView(page, LP(MATCH, ctx.pp(120)))
        button("Cancel", 40) { dismiss() }
        button("Today", 40) { dismiss(); onPick(LocalDate.now().toEpochDay()) }
        draw()
    }

    private fun draw() {
        yearLabel.text = year.toString()
        months.select(month - 1)
        grid.removeAllViews()
        val head = ctx.hbox()
        for (i in 0..6) head.addView(ctx.label(Fmt.dowLetter[i], bold = true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(0, WRAP, 1f))
        grid.addView(head, LP(MATCH, 0, 1f))
        val first = LocalDate.of(year, month, 1)
        val lead = Recur.dow(first)
        val days = first.lengthOfMonth()
        val today = LocalDate.now().toEpochDay()
        var n = 1 - lead
        repeat(6) {
            val row = ctx.hbox()
            for (c in 0..6) {
                val cell: TextView
                if (n in 1..days) {
                    val ep = LocalDate.of(year, month, n).toEpochDay()
                    cell = ctx.label(n.toString()).apply {
                        gravity = Gravity.CENTER
                        when {
                            ep == selected -> { background = ctx.box(Palm.INK, Palm.INK, 0f, 1f); setTextColor(Palm.paper) }
                            ep == today -> background = ctx.box(android.graphics.Color.TRANSPARENT, Palm.INK, 1f, 1f)
                        }
                        setOnClickListener { dismiss(); onPick(ep) }
                    }
                } else cell = ctx.label("")
                row.addView(cell, LinearLayout.LayoutParams(0, MATCH, 1f))
                n++
            }
            grid.addView(row, LP(MATCH, 0, 1f))
        }
    }
}

/**
 * Palm "Set Time" form. The start and end boxes and No Time are at the left. The hour list
 * (24 hours, with a line under 11 am in 12-hour mode) and the minute list are at the right.
 * Positions are form coordinates. The content starts at form (4, 16).
 */
class TimeDialog(
    private val ctx: Context,
    untimed: Boolean,
    start: Int,
    end: Int,
    private val onPick: (untimed: Boolean, start: Int, end: Int) -> Unit
) : PalmDialog(ctx, "Set Time", scroll = false, height = 156) {
    private var s = if (untimed) 8 * 60 else start
    private var e = if (untimed) 9 * 60 else end
    private var editingEnd = false
    private val startBox = ctx.label("", bold = true)
    private val endBox = ctx.label("", bold = true)
    private val hourList = TimeList(ctx,
        (0..23).map { h -> if (Fmt.h24) "$h" else "${if (h % 12 == 0) 12 else h % 12}" },
        divideAfter = if (Fmt.h24) -1 else 11, centered = true) { pickHour(it) }
    private val minList = TimeList(ctx,
        (0..55 step 5).map { m -> if (m % 30 == 0) "•%02d".format(m) else " %02d".format(m) },
        divideAfter = -1, centered = false) { pickMinute(it * 5) }
    // The form content. place() puts each view at form coordinates.
    private val content = FrameLayout(ctx)

    init {
        startBox.gravity = Gravity.CENTER
        endBox.gravity = Gravity.CENTER
        for ((i, b) in listOf(startBox, endBox).withIndex()) b.setOnClickListener { editingEnd = i == 1; draw() }
        place(ctx.label("Start Time:", bold = true), 10, 17)
        place(startBox, 10, 30, 50, 13)
        place(ctx.label("End Time:", bold = true), 10, 48)
        place(endBox, 10, 61, 50, 13)
        place(ctx.palmButton("No Time", 50) { dismiss(); onPick(true, s, e) }
            .apply { typeface = Palm.typeface(ctx, true) }, 10, 115)
        place(hourList, 92, 16, 33, 134)
        place(minList, 130, 16, 23, 134)
        body.addView(content, LP(MATCH, MATCH))
        // The body runs down under the button row, so the lists reach form y 150. A negative
        // top margin keeps the button row at form y 137 to 155.
        buttons.layoutParams = LP(MATCH, WRAP).apply { topMargin = -ctx.pp(18) }
        button("OK", 37) { dismiss(); onPick(false, s, maxOf(e, s)) }
        button("Cancel", 37) { dismiss() }
        draw()
    }

    /** Adds [v] to the content at form (x, y). A size of 0 wraps the view. */
    private fun place(v: View, x: Int, y: Int, w: Int = 0, h: Int = 0) {
        content.addView(v, FrameLayout.LayoutParams(
            if (w > 0) ctx.pp(w) else WRAP, if (h > 0) ctx.pp(h) else WRAP).apply {
            leftMargin = ctx.pp(x - 4)
            topMargin = ctx.pp(y - 16)
        })
    }

    private fun cur() = if (editingEnd) e else s

    private fun set(v: Int) {
        val t = v.coerceIn(0, 24 * 60 - 1)
        if (editingEnd) {
            e = maxOf(t, s)
        } else {
            val dur = e - s
            s = t
            e = minOf(s + dur, 24 * 60 - 1)
        }
        draw()
    }

    private fun pickHour(h: Int) = set(h * 60 + cur() % 60)

    private fun pickMinute(m: Int) = set((cur() / 60) * 60 + m)

    private fun draw() {
        startBox.text = Fmt.time(s)
        endBox.text = Fmt.time(e)
        for ((i, b) in listOf(startBox, endBox).withIndex()) {
            val sel = (i == 1) == editingEnd
            b.background = ctx.box(if (sel) Palm.INK else Palm.paper, Palm.INK, 1f, 0f)
            b.setTextColor(if (sel) Palm.paper else Palm.INK)
        }
        val v = cur()
        hourList.current = v / 60
        hourList.showRow(v / 60)
        minList.current = (v % 60) / 5
    }
}

/**
 * A Palm list of 12 rows, each 11 px high, drawn on a canvas. The current row is inverted.
 * A tap picks a row. A drag moves the window one row for each 11 px. Arrows at the right edge
 * show where rows lie above or below the window. A tap on an arrow moves the window one row.
 * [divideAfter] draws a 1 px line under that row, or -1 for none. [centered] centers the text.
 * Otherwise the text is left-aligned.
 * Limit: no fling. The window moves only while a finger is on the list.
 */
private class TimeList(
    private val ctx: Context,
    private val labels: List<String>,
    private val divideAfter: Int,
    private val centered: Boolean,
    private val onPick: (Int) -> Unit
) : View(ctx) {
    companion object {
        const val ROWS = 12
    }

    /** The index of the current row. The list inverts this row. */
    var current = 0
        set(v) {
            field = v
            invalidate()
        }

    /** The index of the first visible row. */
    var first = 0
        private set

    private val rowPx = ctx.pp(11)
    private val linePx = ctx.pp(1)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Palm.typeface(ctx, true)
        textSize = ctx.ppf(Palm.text)
        textAlign = if (centered) Paint.Align.CENTER else Paint.Align.LEFT
    }
    private var lastY = 0f
    private var moved = false
    private val maxFirst: Int get() = maxOf(0, labels.size - ROWS)

    /** Moves the window the least amount that shows row [i]. */
    fun showRow(i: Int) {
        val f = when {
            i < first -> i
            i >= first + ROWS -> i - ROWS + 1
            else -> first
        }
        first = f.coerceIn(0, maxFirst)
        invalidate()
    }

    private fun moveBy(n: Int) {
        first = (first + n).coerceIn(0, maxFirst)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val fm = paint.fontMetrics
        val x = if (centered) w / 2f else 0f
        for (r in 0 until ROWS) {
            val i = first + r
            val text = labels.getOrNull(i) ?: continue
            val y0 = (r * rowPx).toFloat()
            val inverted = i == current
            if (inverted) {
                paint.color = Palm.INK
                c.drawRect(0f, y0, w, y0 + rowPx, paint)
            }
            paint.color = if (inverted) Palm.paper else Palm.INK
            val baseline = y0 + (rowPx - (fm.descent - fm.ascent)) / 2f - fm.ascent
            c.drawText(text, x, baseline, paint)
            if (i == divideAfter) {
                paint.color = Palm.INK
                c.drawRect(0f, y0 + rowPx - linePx, w, y0 + rowPx, paint)
            }
        }
        if (first > 0) arrow(c, w, up = true, inverted = first == current)
        if (first < maxFirst) arrow(c, w, up = false, inverted = first + ROWS - 1 == current)
    }

    /** Draws a 5 by 3 Palm px arrow at the right edge, in the top row (up) or the bottom row. */
    private fun arrow(c: Canvas, w: Float, up: Boolean, inverted: Boolean) {
        val cx = w - ctx.ppf(4f)
        val hw = ctx.ppf(2.5f)
        val h = ctx.ppf(3f)
        val edge = ctx.ppf(1f)
        val y0 = if (up) edge else ROWS * rowPx - edge - h
        val apex = if (up) y0 else y0 + h
        val base = if (up) y0 + h else y0
        val p = Path().apply {
            moveTo(cx, apex)
            lineTo(cx + hw, base)
            lineTo(cx - hw, base)
            close()
        }
        paint.color = if (inverted) Palm.paper else Palm.INK
        c.drawPath(p, paint)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastY = ev.y
                moved = false
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                while (ev.y - lastY >= rowPx) { lastY += rowPx; moved = true; moveBy(-1) }
                while (lastY - ev.y >= rowPx) { lastY -= rowPx; moved = true; moveBy(1) }
            }
            MotionEvent.ACTION_UP -> if (!moved) tap(ev.x, ev.y)
        }
        return true
    }

    private fun tap(x: Float, y: Float) {
        performClick()
        val r = (y / rowPx).toInt()
        val onArrows = x >= width - ctx.ppf(8f)
        when {
            onArrows && r == 0 && first > 0 -> moveBy(-1)
            onArrows && r == ROWS - 1 && first < maxFirst -> moveBy(1)
            r < ROWS && first + r < labels.size -> onPick(first + r)
        }
    }
}

/** Palm "Change Repeat" form. Edits a copy of the event and returns it. */
class RepeatDialog(
    private val ctx: Context,
    source: Event,
    private val onPick: (Event) -> Unit
) : PalmDialog(ctx, "Change Repeat", scroll = false, height = 156) {
    private val ev = source.deepCopy()
    private val tabs: PushGroup
    // Form origin (4, 16). The tabs and the summary sit in page. The controls for the repeat type sit in area.
    private val page = ctx.frame()
    private val area = ctx.frame()
    private val summary = ctx.label("")
    private var every = ctx.numberField(maxOf(1, ev.every))

    init {
        val tabIndex = when (ev.repeat) {
            Repeat.DAY -> 1; Repeat.WEEK -> 2; Repeat.MONTH_DAY, Repeat.MONTH_DATE -> 3; Repeat.YEAR -> 4; else -> 0
        }
        tabs = PushGroup(ctx, listOf("None", "Day", "Week", "Month", "Year"), tabIndex, height = 11,
            widths = listOf(26, 23, 30, 32, 25)) { g ->
            ev.repeat = when (g.selected) {
                1 -> Repeat.DAY; 2 -> Repeat.WEEK; 3 -> Repeat.MONTH_DATE; 4 -> Repeat.YEAR; else -> Repeat.NONE
            }
            if (ev.repeat == Repeat.WEEK && ev.weekDays == 0) ev.weekDays = 1 shl Recur.dow(LocalDate.ofEpochDay(ev.date))
            buildArea()
        }
        page.clipChildren = false
        page.formAt(tabs.view, 7, 20)
        val summaryBox = ctx.frame().apply {
            addView(summary.apply { setPadding(ctx.pp(7), ctx.pp(10), ctx.pp(4), 0) },
                android.widget.FrameLayout.LayoutParams(MATCH, WRAP))
        }
        page.formAt(summaryBox, 3, 90, 150, 45)
        page.addView(area, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
        body.addView(page, LP(MATCH, ctx.pp(120)))
        button("OK", 25) {
            ev.every = every.text.toString().toIntOrNull()?.coerceIn(1, 99) ?: 1
            if (ev.repeat == Repeat.NONE) { ev.repeatEnd = -1; ev.exceptions.clear() }
            dismiss(); onPick(ev)
        }
        button("Cancel", 36) { dismiss() }
        buildArea()
    }

    private fun unit(): String = when (ev.repeat) {
        Repeat.DAY -> "Day(s)"; Repeat.WEEK -> "Week(s)"; Repeat.YEAR -> "Year(s)"; else -> "Month(s)"
    }

    private fun buildArea() {
        area.removeAllViews()
        if (ev.repeat == Repeat.NONE) {
            area.formAt(ctx.label("No Repeat").apply { gravity = Gravity.CENTER }, 7, 38, 89, 33)
            updateSummary(); return
        }
        area.formAt(ctx.label("Every:", bold = true).apply { gravity = Gravity.END }, 24, 38, 31, 11)
        every = ctx.numberField(maxOf(1, ev.every)).apply {
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    ev.every = s.toString().toIntOrNull()?.coerceIn(1, 99) ?: 1; updateSummary()
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        area.formAt(every, 60, 38, 17, 11)
        area.formAt(ctx.label(unit()), 83, 38)

        area.formAt(ctx.label("End on:", bold = true).apply { gravity = Gravity.END }, 21, 55, 34, 11)
        val endText = if (ev.repeatEnd < 0) "No End Date" else Fmt.dowDate(ev.repeatEnd)
        val endTrigger = ctx.popupTrigger(endText) { tv ->
            ctx.popupList(tv, listOf("No End Date", "Choose Date..."), if (ev.repeatEnd < 0) 0 else 1) { i ->
                if (i == 0) { ev.repeatEnd = -1; buildArea() }
                else DateDialog(ctx, "Ending On", if (ev.repeatEnd >= 0) ev.repeatEnd else ev.date) { d ->
                    ev.repeatEnd = maxOf(d, ev.date); buildArea()
                }.show()
            }
        }
        area.formAt(endTrigger, 60, 55)

        if (ev.repeat == Repeat.WEEK) {
            area.formAt(ctx.label("Repeat on:", bold = true), 5, 72)
            val g = PushGroup(ctx, Fmt.dowLetter.toList(), -1, multi = true, height = 12,
                widths = List(7) { 10 }) { g ->
                var mask = 0
                for (i in 0..6) if (g.on[i]) mask = mask or (1 shl i)
                if (mask == 0) { mask = 1 shl Recur.dow(LocalDate.ofEpochDay(ev.date)); g.on[Recur.dow(LocalDate.ofEpochDay(ev.date))] = true; g.refresh() }
                ev.weekDays = mask; updateSummary()
            }
            for (i in 0..6) g.on[i] = (ev.weekDays and (1 shl i)) != 0
            g.refresh()
            area.formAt(g.view, 63, 72)
        }
        if (ev.repeat == Repeat.MONTH_DAY || ev.repeat == Repeat.MONTH_DATE) {
            area.formAt(ctx.label("Repeat by:", bold = true), 5, 72)
            val g = PushGroup(ctx, listOf("Day", "Date"), if (ev.repeat == Repeat.MONTH_DAY) 0 else 1,
                height = 12, widths = listOf(40, 40)) { g ->
                ev.repeat = if (g.selected == 0) Repeat.MONTH_DAY else Repeat.MONTH_DATE; updateSummary()
            }
            area.formAt(g.view, 63, 72)
        }
        updateSummary()
    }

    private fun updateSummary() {
        summary.text = if (ev.repeat == Repeat.NONE) "" else Recur.describe(ev)
    }
}

/** Palm Note view: a full page of text for one record. */
class NoteDialog(
    ctx: Context,
    title: String,
    text: String,
    onDone: (String) -> Unit,
    onDelete: (() -> Unit)?
) : PalmDialog(ctx, title, height = 160) {
    init {
        val f = ctx.field(text, "Write a note", multiLine = true).apply {
            minLines = 8
            gravity = Gravity.TOP or Gravity.START
        }
        add(f)
        button("Done") { dismiss(); onDone(f.text.toString()) }
        if (onDelete != null) button("Delete...") {
            ctx.ask("Delete Note", "Delete this note?", "Yes" to { dismiss(); onDelete() }, "No" to {})
        }
    }
}
