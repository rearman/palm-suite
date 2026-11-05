package com.rearman.datebook

import com.rearman.palmui.*
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalDate

/** Palm "Event Details" form. Edits a copy; the copy is saved on OK. */
class EventDetails(
    private val act: MainActivity,
    private val orig: Event,
    private val occDay: Long,
    private val isNew: Boolean
) : PalmDialog(act, "Event Details", height = 119,
    tips = "Tap Time or Date to change it.\n\nCheck Alarm to be reminded before the event.\n\n" +
        "Tap Repeat to make the event repeat.\n\nCheck Private to hide or mask the event with Security.") {
    private val work = orig.deepCopy().apply { date = occDay }
    private val adv = act.numberField(work.alarmAdvance)

    init {
        val area = act.formArea(this)
        act.rightLabel(area, "Time:", 51, 18)
        act.placeForm(area, act.selector(Fmt.range(work)) { tv ->
            TimeDialog(act, work.untimed, work.start, work.end, act.prefs.dayStart..act.prefs.dayEnd) { u, s, e ->
                work.untimed = u; work.start = s; work.end = e; tv.text = Fmt.range(work)
            }.show()
        }.apply { maxWidth = act.pp(156 - 56 - 4) }, 56, 18, WRAP, 11)
        act.rightLabel(area, "Date:", 51, 34)
        act.placeForm(area, act.selector(Fmt.dowDate(work.date)) { tv ->
            DateDialog(act, "Set Date", work.date) { d -> work.date = d; tv.text = Fmt.dowDate(d) }.show()
        }.apply { maxWidth = act.pp(156 - 56 - 4) }, 56, 34, WRAP, 11)

        // The alarm field and unit stay in place and can be set when the check box is off.
        act.rightLabel(area, "Alarm:", 51, 50)
        val units = listOf("Minutes", "Hours", "Days")
        val unit = act.popupTrigger(units[work.alarmUnit]) { tv ->
            act.popupList(tv, units, work.alarmUnit) { i -> work.alarmUnit = i; tv.setTrigger(units[i]) }
        }
        val chk = act.checkBox("", work.alarm) { b -> work.alarm = b }
        chk.setPadding(0, 0, 0, 0)
        act.placeForm(area, chk, 53, 49, WRAP, 14)
        act.placeForm(area, adv, 72, 50, 14, 11)
        act.placeForm(area, unit, 90, 49)

        act.rightLabel(area, "Repeat:", 51, 66)
        act.placeForm(area, act.selector(Recur.shortName(work)) { tv ->
            RepeatDialog(act, work) { r ->
                work.repeat = r.repeat; work.every = r.every; work.repeatEnd = r.repeatEnd
                work.weekDays = r.weekDays
                if (r.repeat == Repeat.NONE) work.exceptions.clear()
                tv.text = Recur.shortName(work)
            }.show()
        }, 56, 66)
        act.rightLabel(area, "Private:", 51, 82)
        val priv = act.checkBox("", work.isPrivate) { b -> work.isPrivate = b }
        priv.setPadding(0, 0, 0, 0)
        act.placeForm(area, priv, 53, 81, WRAP, 14)

        button("OK", 25) { collect(); dismiss(); act.saveEventEdit(orig, occDay, work, isNew) {} }
        button("Cancel", 35) { dismiss() }
        if (!isNew) button("Delete...", 40) { dismiss(); act.deleteEvent(orig, occDay) }
        button("Note", 28) {
            collect(); dismiss()
            act.saveEventEdit(orig, occDay, work, isNew) { o -> act.openNote(o) }
        }
    }

    private fun collect() {
        work.alarmAdvance = adv.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
    }
}

/** Puts [v] in [area] at Palm form (x, y). The body origin is form (4, 16). */
private fun Context.placeAt(area: FrameLayout, v: View, x: Int, y: Int) =
    area.addView(v, FrameLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = pp(x - 4); topMargin = pp(y - 16) })

/** Sets the gap above a row, in Palm px. Palm rows are 14 px high with 0 to 1 px between them. */
private fun LinearLayout.topPx(p: Int) {
    (layoutParams as LinearLayout.LayoutParams).topMargin = context.pp(p)
}

/** Palm "To Do Item Details" form. */
class TodoDetails(private val act: MainActivity, private val t: Todo) : PalmDialog(act, "To Do Item Details", height = 101,
    tips = "Tap a number to set the priority: 1 is the most important.\n\nTap Due Date to set when the item is due.\n\n" +
        "Check Private to hide or mask the item with Security.") {
    private companion object {
        // The labels end at form x 54: "Category:" and "Due Date:" are 50 px wide in this font, so all four controls line up.
        const val LABEL_END = 54
    }

    private val work = t.copy()

    init {
        val pri = PushGroup(act, listOf("1", "2", "3", "4", "5"), work.priority - 1,
            height = 11, widths = listOf(11, 11, 11, 11, 11)) { g -> work.priority = g.selected + 1 }
        row("Priority:", pri.view, labelEnd = LABEL_END)

        row("Category:", act.popupTrigger(act.store.categoryName(work.category)) { tv ->
            act.pickCategory(tv, work.category, includeAll = false) { id -> work.category = id; tv.setTrigger(act.store.categoryName(id)) }
        }, labelEnd = LABEL_END).topPx(0)
        row("Due Date:", act.popupTrigger(dueText(work.due)) { tv ->
            act.pickDue(tv, work.due) { d -> work.due = d; tv.setTrigger(dueText(d)) }
        }, labelEnd = LABEL_END).topPx(1)
        row("Private:", act.checkBox("", work.isPrivate) { b -> work.isPrivate = b }, labelEnd = LABEL_END).topPx(0)

        button("OK", 25) { dismiss(); act.saveTodo(work) }
        button("Cancel", 35) { dismiss() }
        button("Delete...", 40) { dismiss(); act.deleteTodo(t) }
        button("Note", 28) { dismiss(); act.saveTodo(work); act.openTodoNote(work) }
    }

    private fun dueText(d: Long) = if (d < 0) "No Date" else Fmt.dowDate(d)
}

// ---------------- Option dialogs ----------------

/** A full-width layer in the dialog body for views placed at Palm form coordinates. It does not clip, so a view can sit at form x 2. */
internal fun Context.formArea(d: PalmDialog): FrameLayout {
    d.body.clipChildren = false
    d.body.clipToPadding = false
    val area = FrameLayout(this).apply { clipChildren = false }
    d.body.addView(area, LP(MATCH, WRAP))
    return area
}

/** Puts [v] at Palm form (x, y) in [area], [w] by [h] Palm px. WRAP keeps the view's own size. */
internal fun Context.placeForm(area: FrameLayout, v: View, x: Int, y: Int, w: Int = WRAP, h: Int = WRAP) {
    val lp = FrameLayout.LayoutParams(if (w == WRAP) WRAP else pp(w), if (h == WRAP) WRAP else pp(h))
    // The body origin is form (4, 16). Form x below 4 gives a negative margin.
    lp.leftMargin = if (x >= 4) pp(x - 4) else -pp(4 - x)
    lp.topMargin = pp(y - 16)
    area.addView(v, lp)
}

/** A bold label that ends at form x [right], with its top at form y [y]. It has room to the form edge, so it never wraps. */
internal fun Context.rightLabel(area: FrameLayout, text: String, right: Int, y: Int) =
    placeForm(area, label(text, bold = true).apply { gravity = Gravity.END; setSingleLine(true) }, 0, y, right, 11)

fun MainActivity.showPreferences() {
    val d = PalmDialog(this, "Preferences", height = 156,
        tips = "Start Time and End Time set the hours of the Day and Week views.\n\n" +
            "Alarm Preset sets an alarm on each new event.\n\nRemind Me and Play Every repeat the alarm.")
    val area = formArea(d)
    val hourNames = (0..24).map { if (it == 24) "Midnight" else Fmt.time(it * 60) }
    var start = prefs.dayStart
    var end = prefs.dayEnd
    rightLabel(area, "Start Time:", 72, 20)
    placeForm(area, popupTrigger(hourNames[start]) { tv ->
        popupList(tv, hourNames.dropLast(1), start) { i -> start = i; if (end <= start) end = start + 1; tv.setTrigger(hourNames[i]) }
    }, 76, 19)
    rightLabel(area, "End Time:", 72, 39)
    placeForm(area, popupTrigger(hourNames[end]) { tv ->
        popupList(tv, hourNames.drop(1), end - 1) { i -> end = maxOf(i + 1, start + 1); tv.setTrigger(hourNames[end]) }
    }, 76, 38)

    // The preset field and unit stay in place and can be set when the check box is off.
    var presetOn = prefs.presetOn
    var unit = prefs.presetUnit
    val adv = numberField(prefs.presetAdvance)
    val units = listOf("Minutes", "Hours", "Days")
    val unitTrigger = popupTrigger(units[unit]) { tv ->
        popupList(tv, units, unit) { i -> unit = i; tv.setTrigger(units[i]) }
    }
    val presetChk = checkBox("", presetOn) { b -> presetOn = b }
    presetChk.setPadding(0, 0, 0, 0)
    placeForm(area, presetChk, 2, 66, WRAP, 14)
    placeForm(area, label("Alarm Preset:", bold = true), 17, 67)
    placeForm(area, adv, 89, 67, 15, 12)
    placeForm(area, unitTrigger, 105, 66)

    rightLabel(area, "Alarm Sound:", 87, 83)
    placeForm(area, palmButton("Change...") {
        Alarms.ensureChannel(this)
        startActivity(
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, Alarms.CHANNEL)
        )
    }, 90, 82, WRAP, 12)

    val remindVals = listOf(1, 2, 3, 5, 10)
    val remindNames = listOf("Once", "Twice", "3 times", "5 times", "10 times")
    var remind = prefs.remindTimes
    rightLabel(area, "Remind Me:", 87, 99)
    placeForm(area, popupTrigger(remindNames[remindVals.indexOf(remind).coerceAtLeast(0)]) { tv ->
        popupList(tv, remindNames, remindVals.indexOf(remind)) { i -> remind = remindVals[i]; tv.setTrigger(remindNames[i]) }
    }, 90, 98)
    val everyVals = listOf(1, 5, 10, 30)
    val everyNames = listOf("1 minute", "5 minutes", "10 minutes", "30 minutes")
    var every = prefs.remindEvery
    rightLabel(area, "Play Every:", 87, 115)
    placeForm(area, popupTrigger(everyNames[everyVals.indexOf(every).coerceAtLeast(0)]) { tv ->
        popupList(tv, everyNames, everyVals.indexOf(every)) { i -> every = everyVals[i]; tv.setTrigger(everyNames[i]) }
    }, 90, 114)

    d.button("OK", 25) {
        prefs.dayStart = start; prefs.dayEnd = end
        prefs.presetOn = presetOn; prefs.presetUnit = unit
        prefs.presetAdvance = adv.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 5
        prefs.remindTimes = remind; prefs.remindEvery = every
        d.dismiss(); render()
    }
    d.button("Cancel", 36) { d.dismiss() }
    d.show()
}

fun MainActivity.showDisplayOptions() {
    val d = PalmDialog(this, "Display Options", height = 148,
        tips = "Time bars show the length of each event and the events that overlap.\n\n" +
            "Compress Day View shows only the times of your events.\n\nThe Month View check boxes choose the marks of the month.")
    val area = formArea(d)
    placeForm(area, label("Day View:", bold = true), 5, 18)
    val bars = checkBox("Show Time Bars", prefs.timeBars)
    val comp = checkBox("Compress Day View", prefs.compress)
    placeForm(area, bars, 2, 31, WRAP, 14)
    placeForm(area, comp, 2, 45, WRAP, 14)
    placeForm(area, label("Month View:", bold = true), 5, 70)
    val timed = checkBox("Show Timed Events", prefs.monthTimed)
    val untimed = checkBox("Show Untimed Events", prefs.monthUntimed)
    val daily = checkBox("Show Daily Repeating Evts", prefs.monthDaily)
    placeForm(area, timed, 2, 83, WRAP, 14)
    placeForm(area, untimed, 2, 97, WRAP, 14)
    placeForm(area, daily, 2, 111, WRAP, 14)
    d.button("OK", 36) {
        prefs.timeBars = bars.isChecked; prefs.compress = comp.isChecked
        prefs.monthTimed = timed.isChecked; prefs.monthUntimed = untimed.isChecked; prefs.monthDaily = daily.isChecked
        d.dismiss(); render()
    }
    d.button("Cancel", 36) { d.dismiss() }
    d.show()
}

fun MainActivity.showFont() {
    val d = PalmDialog(this, "Select Font", height = 61)
    val area = FrameLayout(this)
    placeAt(area, label("Font:", bold = true), 5, 21)
    // Each "A" is drawn in its font: small (the standard font), bold, and large (the large bold font).
    val g = PushGroup(this, listOf("A", "A", "A"), prefs.font, height = 14, widths = listOf(14, 14, 14))
    listOf(16f to false, 16f to true, 20f to true).forEachIndexed { i, (s, bold) ->
        (g.view.getChildAt(i) as TextView).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, ppf(s))
            typeface = Palm.typeface(context, bold, exact = true)
        }
    }
    placeAt(area, g.view, 40, 20)
    d.add(area, 0)
    d.button("OK", 37) { prefs.font = g.selected; d.dismiss(); render() }
    d.button("Cancel", 37) { d.dismiss() }
    d.show()
}

fun MainActivity.showAbout(todo: Boolean) {
    ask(
        if (todo) "About To Do List" else "About Date Book",
        "Date Book and To Do List for Android\nVersion 1.1\n\nAfter the Palm OS 3.5 applications.\nYour records stay on this phone.",
        "OK" to {}
    )
}

fun MainActivity.showShowOptions() {
    val d = PalmDialog(this, "To Do Preferences", height = 156,
        tips = "Sort by sets the order of the list.\n\nThe check boxes choose the items and the columns that the list shows.")
    val sorts = listOf("Priority, Due Date", "Due Date, Priority", "Category, Priority", "Category, Due Date")
    var sort = prefs.sortMode
    val area = FrameLayout(this)
    placeAt(area, label("Sort by:", bold = true), 7, 18)
    placeAt(area, popupTrigger(sorts[sort]) { tv -> popupList(tv, sorts, sort) { i -> sort = i; tv.setTrigger(sorts[i]) } }, 50, 17)
    val c1 = checkBox("Show Completed Items", prefs.showCompleted)
    val c2 = checkBox("Show Only Due Items", prefs.onlyDue)
    val c3 = checkBox("Record Completion Date", prefs.recordCompletion)
    val c4 = checkBox("Show Due Dates", prefs.showDueDates)
    val c5 = checkBox("Show Priorities", prefs.showPriorities)
    val c6 = checkBox("Show Categories", prefs.showCategories)
    listOf(c1 to 40, c2 to 54, c3 to 68, c4 to 89, c5 to 103, c6 to 117).forEach { (c, y) -> placeAt(area, c, 4, y) }
    d.add(area, 0)
    d.button("OK", 37) {
        prefs.sortMode = sort
        prefs.showCompleted = c1.isChecked; prefs.onlyDue = c2.isChecked; prefs.recordCompletion = c3.isChecked
        prefs.showDueDates = c4.isChecked; prefs.showPriorities = c5.isChecked; prefs.showCategories = c6.isChecked
        d.dismiss(); render()
    }
    d.button("Cancel", 37) { d.dismiss() }
    d.show()
}

/**
 * Edit Categories dialog for one app.
 *
 * [app] 0 edits the To Do categories, 1 the Memo Pad categories.
 */
fun MainActivity.showEditCategories(app: Int = 0) {
    editCategories(
        items = { store.categoriesFor(app).map { CategoryItem(it.id, it.name) } },
        add = { name -> store.addCategory(name, app).id },
        rename = { id, name -> store.categoriesFor(app).firstOrNull { it.id == id }?.let { store.renameCategory(it, name) } },
        deleteText = { "Delete \"${it.name}\"? Its items move to Unfiled." },
        delete = { id ->
            store.categoriesFor(app).firstOrNull { it.id == id }?.let { cat ->
                store.deleteCategory(cat)
                if (prefs.todoCategory == cat.id) prefs.todoCategory = -1
                if (app == 2 && prefs.addressCategory == cat.id) prefs.addressCategory = -1
            }
        },
        onClose = { render() },
    )
}

fun MainActivity.showPurgeEvents() {
    val d = PalmDialog(this, "Purge", height = 97)
    val area = formArea(d)
    placeForm(area, IconView(this, IconView.CONFIRM), 8, 19)
    placeForm(area, label("Delete events older\nthan:", bold = true), 32, 19)
    val names = listOf("1 week", "2 weeks", "3 weeks", "1 month")
    val days = listOf(7, 14, 21, 30)
    var pick = 0
    placeForm(area, popupTrigger(names[0]) { tv -> popupList(tv, names, pick) { i -> pick = i; tv.setTrigger(names[i]) } }, 60, 29)
    val archive = checkBox("Save archive copy on PC", true)
    placeForm(area, archive, 12, 54, WRAP, 14)
    d.button("OK", 37) {
        val cutoff = LocalDate.now().toEpochDay() - days[pick]
        val old = store.events.filter { Recur.lastDay(it) < cutoff }
        d.dismiss()
        if (old.isEmpty()) { toast("No events to purge."); return@button }
        val doIt = { old.forEach { store.delete(it) }; changed(); toast("Purged ${old.size} events.") }
        if (archive.isChecked) exportFile("datebook-archive", store.exportJson(old, emptyList(), emptyList()), doIt)
        else doIt()
    }
    d.button("Cancel", 37) { d.dismiss() }
    d.show()
}

fun MainActivity.showPurgeTodos() {
    val done = store.todos.filter { it.done }
    if (done.isEmpty()) { toast("No completed items."); return }
    palmAlert("Purge", "Delete all To Do items\nmarked completed?", 97, archiveY = 54, archiveOn = true) { archive ->
        val doIt = { done.forEach { store.delete(it) }; render(); toast("Purged ${done.size} items.") }
        if (archive) exportFile("todo-archive", store.exportJson(emptyList(), done, emptyList()), doIt)
        else doIt()
    }
}
