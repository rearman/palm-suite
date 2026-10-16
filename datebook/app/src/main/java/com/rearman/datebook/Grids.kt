package com.rearman.datebook

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import java.time.LocalDate

/** Assigns side-by-side lanes to overlapping timed events. Returns lane per occurrence and lane count. */
fun lanes(occs: List<Occ>): Pair<Map<Occ, Int>, Int> {
    val laneEnd = mutableListOf<Int>()
    val out = HashMap<Occ, Int>()
    for (o in occs.sortedWith(compareBy<Occ>({ it.event.start }, { it.event.end }))) {
        val e = o.event
        val endMin = maxOf(e.end, e.start + 15)
        var lane = laneEnd.indexOfFirst { it <= e.start }
        if (lane < 0) { laneEnd.add(endMin); lane = laneEnd.size - 1 } else laneEnd[lane] = endMin
        out[o] = lane
    }
    return Pair(out, maxOf(1, laneEnd.size))
}

/** The Week view time grid: seven columns, gray blocks for events. */
@SuppressLint("ViewConstructor")
class WeekGrid(
    ctx: Context,
    private val act: MainActivity,
    private val weekStart: Long,
    private val onSelect: (Occ?) -> Unit
) : View(ctx) {
    /** Height of one hour. onMeasure sets it: square two-hour cells, or taller to fill the viewport. */
    private var hourH = ctx.ppf(11f)
    /** Space above the first and below the last hour line, so the labels centered on them show. */
    private val y0 = ctx.ppf(3f)
    /** The hours shown: the day start and end times from Preferences. */
    private val h0 = act.prefs.dayStart
    private val h1 = maxOf(act.prefs.dayEnd, h0 + 1)
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palm.INK; strokeWidth = ctx.ppf(1f) }
    /** Hour lines inside the box: black and dotted. */
    private val dotted = Paint().apply {
        color = Palm.INK; strokeWidth = ctx.ppf(1f)
        pathEffect = DashPathEffect(floatArrayOf(ctx.ppf(1f), ctx.ppf(1f)), 0f)
    }
    private val fill = Paint().apply { color = Palm.GRAY }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palm.INK; textSize = ctx.ppf(Palm.text); typeface = Palm.typeface(ctx, true); textAlign = Paint.Align.RIGHT
    }
    /** The hour label column: the widest time in the label font, 1 Palm px on the left and 2 on the right. */
    val gutter = text.measureText("00:00") + ctx.ppf(3f)
    /** Space to the right of the grid, as in the Month view. */
    private val mr = ctx.ppf(3f)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palm.INK; style = Paint.Style.STROKE; strokeWidth = ctx.ppf(1f)
    }
    private val days = (0..6).map { weekStart + it }
    private val timed = days.map { d -> act.dayOccs(d).filter { !it.event.untimed } }
    private val laneInfo = timed.map { lanes(it) }
    var selected: Occ? = null
    private val boxes = mutableListOf<Pair<RectF, Occ>>()

    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val hit = boxes.lastOrNull { it.first.contains(e.x, e.y) }?.second
            if (hit != null) {
                if (act.isMasked(hit.event)) { act.revealAsk(hit.event); return true }
                if (selected == hit) act.goDay(hit.day, hit)
                else { selected = hit; onSelect(hit); invalidate() }
            } else {
                val col = colAt(e.x)
                if (col >= 0) act.goDay(days[col], null)
            }
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            val hit = boxes.lastOrNull { it.first.contains(e.x, e.y) }?.second ?: return
            if (!act.isMasked(hit.event)) act.editEvent(hit)
        }
    })

    private fun colW() = (width - gutter - mr) / 7f
    private fun colAt(x: Float): Int =
        if (x < gutter || x > width - mr) -1 else ((x - gutter) / colW()).toInt().coerceIn(0, 6)

    /**
     * Sets the hour height. Square two-hour cells give the minimum. When the parent gives a
     * viewport height (ScrollView fillViewport), the hours stretch to fill it. Longer ranges
     * need more than the viewport, so they keep square cells and scroll.
     * Limit: the fill applies to any range that fits the viewport, not only the default 8 to 18.
     */
    override fun onMeasure(w: Int, h: Int) {
        val wPx = MeasureSpec.getSize(w)
        val frame = 2 * y0 + 2
        val fit = if (MeasureSpec.getMode(h) == MeasureSpec.UNSPECIFIED) 0f
            else (MeasureSpec.getSize(h) - frame) / (h1 - h0)
        hourH = maxOf((wPx - gutter - mr) / 7f / 2f, fit)
        setMeasuredDimension(wPx, Math.round((h1 - h0) * hourH + frame))
    }

    /** The y position of [min] minutes after midnight, held inside the box. */
    private fun yOf(min: Int) = y0 + (min.coerceIn(h0 * 60, h1 * 60) - h0 * 60) / 60f * hourH

    override fun onDraw(c: Canvas) {
        c.drawColor(Palm.paper)
        val cw = colW()
        val top = yOf(h0 * 60)
        val bottom = yOf(h1 * 60)
        // The box top and bottom are the start and end hours, solid. Even hours between them get
        // a dotted line. Each even hour gets a label centered on its line.
        c.drawLine(gutter, top, width - mr, top, ink)
        c.drawLine(gutter, bottom, width - mr, bottom, ink)
        for (h in h0..h1) {
            if (h % 2 != 0) continue
            val y = yOf(h * 60)
            if (h != h0 && h != h1) c.drawLine(gutter, y, width - mr, y, dotted)
            c.drawText(Fmt.timeShort(h * 60), gutter - context.ppf(2f), y - (text.ascent() + text.descent()) / 2, text)
        }
        // Solid vertical lines.
        for (i in 0..7) {
            val x = gutter + i * cw
            c.drawLine(x, top, x, bottom, ink)
        }
        boxes.clear()
        for (i in 0..6) {
            val (laneOf, nLanes) = laneInfo[i]
            val lw = (cw - context.ppf(2f)) / nLanes
            for (o in timed[i]) {
                val e = o.event
                val end = maxOf(e.end, e.start + 15)
                // Events outside the start and end hours do not show.
                if (end <= h0 * 60 || e.start >= h1 * 60) continue
                val lane = laneOf[o] ?: 0
                val l = gutter + i * cw + context.ppf(1f) + lane * lw
                val r = RectF(l, yOf(e.start) + 1, l + lw - 1, yOf(end) - 1)
                fill.color = when {
                    act.isMasked(e) -> Palm.LIGHT
                    o == selected -> Palm.INK
                    else -> Palm.GRAY
                }
                c.drawRect(r, fill)
                c.drawRect(r, stroke)
                boxes.add(r to o)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = gd.onTouchEvent(event) || super.onTouchEvent(event)
}

/** The Month view: a calendar grid with Palm marks for timed, untimed and daily events. */
@SuppressLint("ViewConstructor")
class MonthGrid(
    ctx: Context,
    private val act: MainActivity,
    private val year: Int,
    private val month: Int
) : View(ctx) {
    private val first = LocalDate.of(year, month, 1)
    private val lead = Recur.dow(first)
    private val len = first.lengthOfMonth()
    /** Palm month grid: an 18 px band for the day letters, six 18 px weeks, 3 px side margins. */
    private val headH = ctx.ppf(18f)
    private val mx = ctx.ppf(3f)
    private val line = Paint().apply { color = Palm.INK; strokeWidth = ctx.ppf(1f) }
    private val thick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palm.INK; style = Paint.Style.STROKE; strokeWidth = ctx.ppf(1.5f)
    }
    private val fill = Paint().apply { color = Palm.INK }
    private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palm.INK; textSize = ctx.ppf(Palm.text); typeface = Palm.typeface(ctx, true)
    }
    /** Height of a digit in the number font (7 px in the Palm font). */
    private val capH = Rect().also { num.getTextBounds("0", 0, 1, it) }.let { -it.top.toFloat() }
    private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palm.INK; textSize = ctx.ppf(Palm.text); typeface = Palm.typeface(ctx, true); textAlign = Paint.Align.CENTER
    }
    private val dash = Paint().apply {
        color = Palm.INK; strokeWidth = ctx.ppf(1f); style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(ctx.ppf(1.5f), ctx.ppf(1.5f)), 0f)
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palm.INK; textSize = ctx.ppf(6f); typeface = Palm.typeface(ctx, false)
    }

    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (e.y < headH) return true
            val col = ((e.x - mx) / ((width - 2 * mx) / 7f)).toInt().coerceIn(0, 6)
            val row = ((e.y - headH) / cellH()).toInt().coerceIn(0, 5)
            val n = row * 7 + col - lead + 1
            if (n in 1..len) act.goDay(LocalDate.of(year, month, n).toEpochDay(), null)
            return true
        }
    })

    private fun cellH() = (height - headH - line.strokeWidth) / 6f

    // The height adds one line width so the outer lines stay inside the view. It shrinks to fit
    // when the screen has less room.
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(
        MeasureSpec.getSize(w),
        resolveSize((headH + 6 * context.ppf(18f) + line.strokeWidth).toInt(), h)
    )

    override fun onDraw(c: Canvas) {
        c.drawColor(Palm.paper)
        val sw = line.strokeWidth
        val cw = (width - 2 * mx - sw) / 7f
        val x0 = mx + sw / 2
        val ch = cellH()
        for (i in 0..6) c.drawText(Fmt.dowLetter[i], x0 + cw * i + cw / 2, headH - ctx(7f), head)
        for (r in 0..6) { val y = headH + sw / 2 + r * ch; c.drawLine(mx, y, width - mx, y, line) }
        for (i in 0..7) { val x = x0 + i * cw; c.drawLine(x, headH, x, height.toFloat(), line) }
        val today = LocalDate.now().toEpochDay()
        val p = act.prefs
        for (n in 1..len) {
            val idx = n - 1 + lead
            val col = idx % 7
            val row = idx / 7
            val x = x0 + col * cw
            val y = headH + sw / 2 + row * ch
            val ep = LocalDate.of(year, month, n).toEpochDay()
            val label = n.toString()
            // The number sits 2 px below the cell line, as on the Palm.
            val base = y + ctx(2.5f) + capH
            if (ep == today) {
                val w = num.measureText(label)
                c.drawRect(x + ctx(1f), y + ctx(1.5f), x + w + ctx(3f), base + ctx(1.5f), fill)
                num.color = Palm.paper
                c.drawText(label, x + ctx(1.5f), base, num)
                num.color = Palm.INK
            } else c.drawText(label, x + ctx(1.5f), base, num)
            if (ep == act.day) c.drawRect(x + ctx(0.75f), y + ctx(0.75f), x + cw - ctx(0.75f), y + ch - ctx(0.75f), thick)

            val occs = act.dayOccs(ep)
            var daily = false
            var untimed = false
            val bands = BooleanArray(3)
            var count = 0
            for (o in occs) {
                val e = o.event
                if (Recur.isDaily(e)) { if (p.monthDaily) daily = true; continue }
                count++
                if (e.untimed) { if (p.monthUntimed) untimed = true }
                else if (p.monthTimed) bands[when { e.start < 12 * 60 -> 0; e.start < 18 * 60 -> 1; else -> 2 }] = true
            }
            // Timed events: marks on the right side, over the full cell height.
            val top = y + ctx(2f)
            val avail = ch - ctx(4f)
            for (b in 0..2) if (bands[b]) {
                val by = top + avail * b / 3f
                c.drawRect(x + cw - ctx(4f), by, x + cw - ctx(1.5f), by + minOf(ctx(2f), avail / 3f), fill)
            }
            if (untimed) {
                val cx = x + ctx(3.5f); val cy = y + ch - ctx(4f); val s = ctx(1.5f)
                c.drawLine(cx - s, cy, cx + s, cy, line)
                c.drawLine(cx, cy - s, cx, cy + s, line)
            }
            if (daily) c.drawLine(x + ctx(1f), y + ch - ctx(1f), x + cw - ctx(1f), y + ch - ctx(1f), dash)
            if (count > 1 && ch > ctx(22f)) c.drawText("$count", x + ctx(6f), y + ch - ctx(2.5f), small)
        }
    }

    private fun ctx(v: Float) = context.ppf(v)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = gd.onTouchEvent(event) || super.onTouchEvent(event)
}

/**
 * The icons in the bottom row: the four views (0 to 3), then Find (5). Kind 4 is unused.
 * The four views are 9 Palm px wide and 11 tall, a full box with no inset. Find is
 * 12 wide and 13 tall, with a 1 px inset.
 */
@SuppressLint("ViewConstructor")
class ViewIcon(ctx: Context, private val kind: Int, private val active: Boolean, onClick: () -> Unit) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = ctx.ppf(1f) }

    init { setOnClickListener { onClick() }; contentDescription = if (kind == 5) "Find" else listOf("Day", "Week", "Month", "Agenda")[kind] }

    override fun onMeasure(w: Int, h: Int) =
        if (kind < 4) setMeasuredDimension(context.pp(9), context.pp(11))
        else setMeasuredDimension(context.pp(12), context.pp(13))

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val mx = p.strokeWidth / 2
        val m = if (kind < 4) mx else context.ppf(1f)
        p.style = Paint.Style.FILL
        p.color = if (active) Palm.INK else Palm.paper
        c.drawRect(mx, m, w - mx, h - m, p)
        p.style = Paint.Style.STROKE
        p.color = Palm.INK
        c.drawRect(mx, m, w - mx, h - m, p)
        p.color = if (active) Palm.paper else Palm.INK
        val g = context.ppf(1f)
        val l = mx + g; val r = w - mx - g; val t = m + g; val b = h - m - g
        val dr = context.ppf(0.8f)
        fun dot(x: Float, y: Float) { p.style = Paint.Style.FILL; c.drawCircle(x, y, dr, p) }
        when (kind) {
            0 -> dot(w / 2, h / 2) // Day: one dot in the center
            1 -> for (k in 0..3) dot(l + (r - l) * (k + 0.5f) / 4, h / 2) // Week: four dots across
            2 -> for (i in 0..3) for (j in 0..3) // Month: 4 by 4 grid of dots
                dot(l + (r - l) * (i + 0.5f) / 4, t + (b - t) * (j + 0.5f) / 4)
            3 -> { // Agenda: a dot with two lines under it
                dot(w / 2, t + (b - t) * 0.2f)
                p.style = Paint.Style.STROKE
                c.drawLine(l, t + (b - t) * 0.6f, r, t + (b - t) * 0.6f, p)
                c.drawLine(l, t + (b - t) * 0.9f, r, t + (b - t) * 0.9f, p)
            }
            else -> { // Find: magnifier
                p.style = Paint.Style.STROKE
                val rr = (b - t) * 0.28f
                val cx = w / 2 - rr / 3; val cy = h / 2 - rr / 3
                c.drawCircle(cx, cy, rr, p)
                c.drawLine(cx + rr * 0.7f, cy + rr * 0.7f, cx + rr * 1.7f, cy + rr * 1.7f, p)
            }
        }
    }
}
