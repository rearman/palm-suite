package com.rearman.datebook.wear

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.rearman.datebook.Fmt
import com.rearman.datebook.WearAgenda
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The watch screen, which the tiles open: one scrolling list of the appointments still to come
 * in the 7 days from the phone, under a line for each day, and then the open To Dos with their
 * check boxes. Plain Android Views, black on white, with the whole text of each record.
 */
class AgendaActivity : Activity() {
    private val scope = CoroutineScope(Dispatchers.Main)
    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout

    // The first To Do line, to which the To Do tile scrolls. Used one time.
    private var toTodos = false

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        toTodos = intent.getBooleanExtra(EXTRA_TODOS, false)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // A round screen cuts the corners: the list keeps clear of the rim.
            val m = resources.displayMetrics
            setPadding(m.widthPixels / 8, m.heightPixels / 6, m.widthPixels / 8, m.heightPixels / 4)
        }
        scroll = ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(list)
            // The crown scrolls the view that has the focus.
            isFocusableInTouchMode = true
        }
        setContentView(scroll)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        toTodos = intent.getBooleanExtra(EXTRA_TODOS, false)
    }

    override fun onResume() {
        super.onResume()
        Fmt.h24 = DateFormat.is24HourFormat(this)
        scroll.requestFocus()
        scope.launch { build(AgendaStore.load(this@AgendaActivity)) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun text(s: String, bold: Boolean = false, top: Int = 0) = TextView(this).apply {
        text = s
        textSize = 14f
        setTextColor(Color.BLACK)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(top), 0, 0)
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun build(agenda: WearAgenda) {
        list.removeAllViews()
        val (today, now) = Fmt.dayAndMinutes()
        val events = agenda.upcoming(today, now)
        if (events.isEmpty()) list.addView(text("No appointments"))
        var shown = -1L
        for ((day, e) in events) {
            if (day != shown) {
                list.addView(text(Fmt.dowDate(day), bold = true, top = if (shown < 0) 0 else 6))
                shown = day
            }
            list.addView(text(eventText(e)))
        }
        val todos = AgendaStore.openTodos(this, agenda)
        val head = text("To Do", bold = true, top = 10)
        list.addView(head)
        if (todos.isEmpty()) list.addView(text("No To Do items"))
        for (t in todos) {
            if (t.uid.isEmpty()) {
                list.addView(text(todoText(t, today)))
                continue
            }
            // The box and the text are two views: a CheckBox with a text of its own would also
            // check on a tap on the text.
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(CheckBox(this).apply {
                buttonTintList = ColorStateList.valueOf(Color.BLACK)
                contentDescription = t.title
                setOnCheckedChangeListener { box, checked ->
                    if (checked) { box.isEnabled = false; complete(row, t.uid) }
                }
            })
            row.addView(text(todoText(t, today)))
            list.addView(row)
        }
        if (toTodos) {
            toTodos = false
            scroll.post { scroll.scrollTo(0, head.top) }
        }
    }

    /** Takes the checked To Do off the list, tells the phone, and updates the tiles. */
    private fun complete(row: View, uid: String) {
        val app = applicationContext
        // A scope of its own: the put must end although the screen may close first.
        CoroutineScope(Dispatchers.Main).launch {
            AgendaStore.markDone(app, uid)
            Tiles.updateAll(app)
        }
        list.postDelayed({ list.removeView(row) }, 400)
    }

    companion object {
        // The tile's LaunchAction sets it to open the screen at the To Dos.
        internal const val EXTRA_TODOS = "todos"
    }
}
