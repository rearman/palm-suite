package com.rearman.datebook

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import java.time.LocalDate
import java.time.temporal.WeekFields
import kotlin.math.abs

/**
 * The root view. When [editor] is true it is a text editor, so that the keyboard can stay
 * open when no field is selected. Keys typed into it are discarded.
 */
private class RootBox(ctx: Context) : LinearLayout(ctx) {
    var editor = false
    override fun onCheckIsTextEditor() = editor
    override fun onCreateInputConnection(out: EditorInfo): InputConnection? {
        if (!editor) return null
        out.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        out.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        return BaseInputConnection(this, false)
    }
}

/** The Date Book app. [TodoActivity] is the To Do List app; both use the same records. */
open class MainActivity : Activity() {
    companion object {
        const val EXTRA_DAY = "day"
        const val EXTRA_EVENT = "event"
        const val EXTRA_SELECT = "select"
        const val EXTRA_TODO = "todo"
        const val EXTRA_MEMO = "memo"
        private const val REQ_EXPORT = 11
        private const val REQ_IMPORT = 12
        const val DAY = 0
        const val WEEK = 1
        const val MONTH = 2
        const val AGENDA = 3
        const val TODO = 4
        const val MEMO = 5
        // A swipe must move this far and this fast to change the date.
        private const val SWIPE_MIN_DP = 80
        private const val SWIPE_MIN_SPEED_DP = 300 // per second
    }

    lateinit var store: Store
    lateinit var prefs: Prefs
    var day: Long = LocalDate.now().toEpochDay()
    private var mode = DAY
    /** True in the To Do List app. */
    protected open val isTodoApp = false
    /** True in the Memo Pad app. */
    protected open val isMemoApp = false
    /** The memo open in the Memo Pad memo view, or null for the Memo List. */
    internal var memoOpen: Memo? = null
    /** Private records the user chose to see while records are masked ("e12", "t5"). */
    val revealed = mutableSetOf<String>()

    private lateinit var root: RootBox
    private var selected: Occ? = null
    private var selectedTodo: Todo? = null
    private var focusKey: String? = null
    internal var lastEdit: EditText? = null
    private var weekSel: Occ? = null
    /** The Week view event box, while it shows. */
    private var eventBox: PopupWindow? = null
    private var pendingExport: String? = null
    private var afterExport: (() -> Unit)? = null
    private var swipeDir = 0
    /** The command buttons of the current screen, for [fitButtons]. */
    private val cmdButtons = mutableListOf<TextView>()

    private val today: Long get() = LocalDate.now().toEpochDay()

    /**
     * True on a screen taller than wide. The keyboard then stays open below the app, and
     * adjustResize fits the screens into the area above it. A square screen shows no keyboard
     * until a field is selected.
     */
    private val keepKeyboard: Boolean
        get() = resources.displayMetrics.let { it.heightPixels > it.widthPixels * 5 / 4 }

    // ---------------- lifecycle ----------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store.get(this)
        prefs = Prefs(this)
        mode = if (isMemoApp) MEMO else if (isTodoApp) TODO else prefs.lastView
        root = RootBox(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            editor = keepKeyboard
        }
        setContentView(root)
        window.hideStatusBar()
        handleIntent(intent)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        Alarms.ensureChannel(this)
    }

    override fun onResume() {
        super.onResume()
        render()
        Alarms.scheduleNext(this)
    }

    /**
     * Hides the status bar again (Android 10 and earlier show it after a focus change), and opens the
     * keyboard again on a tall screen when a dialog or another app closes.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.hideStatusBar()
        if (hasFocus && keepKeyboard) showKeyboard()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
        render()
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        if (i.hasExtra(EXTRA_MEMO)) {
            val mid = i.getLongExtra(EXTRA_MEMO, 0)
            memoOpen = store.memos.firstOrNull { it.id == mid }
            if (memoOpen != null) prefs.memoCategory = -1
            i.removeExtra(EXTRA_MEMO)
        }
        if (i.hasExtra(EXTRA_TODO)) {
            val tid = i.getLongExtra(EXTRA_TODO, 0)
            selectedTodo = store.todos.firstOrNull { it.id == tid }
            if (selectedTodo != null) prefs.todoCategory = -1
            i.removeExtra(EXTRA_TODO)
        }
        if (!i.hasExtra(EXTRA_DAY)) return
        day = i.getLongExtra(EXTRA_DAY, today)
        mode = DAY
        val sel = i.getLongExtra(EXTRA_SELECT, 0)
        if (sel != 0L) store.event(sel)?.let { selected = Occ(it, day) }
        i.removeExtra(EXTRA_SELECT)
        val id = i.getLongExtra(EXTRA_EVENT, 0)
        if (id != 0L) {
            Alarms.cancelRemind(this, id, day)
            store.event(id)?.let { e ->
                getSystemService(android.app.NotificationManager::class.java).cancel(Alarms.notifId(e, day))
                selected = Occ(e, day)
            }
        }
        i.removeExtra(EXTRA_DAY)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (mode) {
            TODO, DAY -> super.onBackPressed()
            else -> { mode = DAY; render() }
        }
    }

    // ---------------- swipe ----------------

    /** A horizontal swipe in a date view sets [swipeDir]: 1 for next, -1 for previous. */
    private val swipe by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null || mode == TODO || mode == MEMO) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dx) < dp(SWIPE_MIN_DP) || abs(dx) < 2 * abs(dy) || abs(vx) < dp(SWIPE_MIN_SPEED_DP)) return false
                swipeDir = if (dx < 0) 1 else -1
                return true
            }
        })
    }

    /** The field in edit when a tap started on no control, until the tap ends or moves. */
    private var tapOutside: EditText? = null
    private var tapX = 0f
    private var tapY = 0f

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (swipe.onTouchEvent(ev)) {
            tapOutside = null
            // Cancel the gesture for the views under the finger, so that no tap fires.
            ev.action = MotionEvent.ACTION_CANCEL
            super.dispatchTouchEvent(ev)
            stepDate(swipeDir)
            return true
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tapX = ev.rawX
                tapY = ev.rawY
                tapOutside = (currentFocus as? EditText)?.takeIf { !onControl(root, ev.rawX.toInt(), ev.rawY.toInt()) }
            }
            MotionEvent.ACTION_MOVE -> {
                val slop = ViewConfiguration.get(this).scaledTouchSlop
                if (abs(ev.rawX - tapX) > slop || abs(ev.rawY - tapY) > slop) tapOutside = null
            }
            MotionEvent.ACTION_CANCEL -> tapOutside = null
        }
        val handled = super.dispatchTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP) {
            val ed = tapOutside
            tapOutside = null
            // A tap on another line moved the focus there; a tap on no control ends the edit.
            if (ed != null && currentFocus === ed) deselect()
        }
        return handled
    }

    /** True when a clickable view or a text field is under the screen point (x, y). */
    private fun onControl(v: View, x: Int, y: Int): Boolean {
        if (v.visibility != View.VISIBLE) return false
        val r = Rect()
        if (!v.getGlobalVisibleRect(r) || !r.contains(x, y)) return false
        if (v !== root && (v.isClickable || v.isLongClickable || v is EditText)) return true
        return v is ViewGroup && (0 until v.childCount).any { onControl(v.getChildAt(it), x, y) }
    }

    /** Ends the edit, which saves the text, and clears the selected event or To Do item. */
    private fun deselect() {
        endEdit()
        selected = null
        selectedTodo = null
    }

    /**
     * Palm behavior: a character typed with no text field selected starts a new record in the
     * Day view, the To Do List and the Memo List. The typed text becomes its first text.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Ctrl and a command letter runs the menu command, as the Palm command stroke does.
        if (event.action == KeyEvent.ACTION_DOWN && event.isCtrlPressed && event.repeatCount == 0) {
            commandKey(event.keyCode)?.let { k -> menus().command(k) }?.let { it.action(); return true }
        }
        if (currentFocus !is EditText) {
            val typed = when (event.action) {
                // A held key repeats; only its first press starts a record.
                KeyEvent.ACTION_DOWN -> if (event.repeatCount > 0) null else event.unicodeChar.takeIf { it != 0 }?.let { String(Character.toChars(it)) }
                KeyEvent.ACTION_MULTIPLE -> event.characters
                else -> null
            }
            if (typed != null && typed.isNotEmpty() && !typed[0].isWhitespace() && !typed[0].isISOControl()) {
                when {
                    mode == DAY -> { typedEvent(typed); return true }
                    mode == TODO -> { newTodo(typed); return true }
                    mode == MEMO && memoOpen == null -> { newMemo(typed); return true }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Moves one day (Day, Agenda), one week or one month forward (1) or back (-1). */
    private fun stepDate(dir: Int) {
        endEdit()
        when (mode) {
            DAY, AGENDA -> { day += dir; selected = null }
            WEEK -> { day += 7L * dir; weekSel = null }
            MONTH -> day = Fmt.ld(day).plusMonths(dir.toLong()).toEpochDay()
            else -> return
        }
        render()
    }

    // ---------------- shared helpers used by the views and dialogs ----------------

    fun dayOccs(d: Long): List<Occ> =
        store.dayEvents(d).filter { !(prefs.security == 2 && it.event.isPrivate) }

    fun isMasked(e: Event) = prefs.security == 1 && e.isPrivate && !revealed.contains("e${e.id}")
    private fun isMasked(t: Todo) = prefs.security == 1 && t.isPrivate && !revealed.contains("t${t.id}")
    internal fun isMasked(m: Memo) = prefs.security == 1 && m.isPrivate && !revealed.contains("m${m.id}")

    fun revealAsk(e: Event) =
        ask("Private Record", "Show this private record?", "Show" to { revealed.add("e${e.id}"); render() }, "Cancel" to {})

    private fun revealAsk(t: Todo) =
        ask("Private Record", "Show this private record?", "Show" to { revealed.add("t${t.id}"); render() }, "Cancel" to {})

    internal fun revealAsk(m: Memo) =
        ask("Private Record", "Show this private record?", "Show" to { revealed.add("m${m.id}"); render() }, "Cancel" to {})

    fun goDay(d: Long, occ: Occ?) {
        day = d; mode = DAY; prefs.lastView = DAY; selected = occ
        render()
    }

    fun editEvent(o: Occ) = EventDetails(this, o.event, o.day, false).show()

    /**
     * Palm form 2100 for a repeating record: the Current, Future and All choices, and Cancel.
     * Each choice closes the form, then runs its action.
     */
    private fun repeatForm(title: String, message: String, current: () -> Unit, future: () -> Unit, all: () -> Unit) {
        val d = PalmDialog(this, title, height = 122)
        // Form coordinates. The body starts at form (4, 16), so each margin is the form value less that origin.
        val area = FrameLayout(this)
        fun place(v: View, formX: Int, formY: Int) =
            area.addView(v, FrameLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = pp(formX - 4); topMargin = pp(formY - 16) })
        place(IconView(this, IconView.CONFIRM), 8, 19)
        place(label(message, bold = true), 27, 19)
        place(palmButton("Current", 61) { d.dismiss(); current() }, 9, 71)
        place(palmButton("Future", 61) { d.dismiss(); future() }, 9, 88)
        place(palmButton("All", 61) { d.dismiss(); all() }, 9, 105)
        place(palmButton("Cancel", 44) { d.dismiss() }, 105, 105)
        d.body.addView(area, LP(MATCH, WRAP))
        d.show()
    }

    /** Saves changes made in Event Details. Asks Current/Future/All for repeating events. */
    fun saveEventEdit(orig: Event, occDay: Long, work: Event, isNew: Boolean, after: (Occ) -> Unit) {
        if (isNew) {
            work.id = 0
            store.save(work); selected = Occ(work, work.date); day = work.date
            changed(); after(Occ(work, work.date)); return
        }
        val origView = orig.deepCopy().apply { date = occDay }
        if (work == origView) { after(Occ(orig, occDay)); return }
        if (!orig.repeats) {
            work.id = orig.id
            store.save(work); selected = Occ(work, work.date); day = work.date
            changed(); after(Occ(work, work.date)); return
        }
        repeatForm(
            "Repeating Event", "Apply the changes to:\n-the Current record,\n-this & all Future records,\n-All occurrences?",
            current = {
                orig.exceptions.add(occDay); store.save(orig)
                val n = work.deepCopy().apply {
                    id = 0; repeat = Repeat.NONE; repeatEnd = -1; exceptions.clear()
                }
                store.save(n); selected = Occ(n, n.date); day = n.date
                changed(); after(Occ(n, n.date))
            },
            future = {
                val (short, series) = splitFuture(orig, occDay, work)
                if (short == null) store.delete(orig) else store.save(short)
                store.save(series); selected = Occ(series, series.date); day = series.date
                changed(); after(Occ(series, series.date))
            },
            all = {
                val m = work.deepCopy().apply {
                    id = orig.id
                    date = if (repeat == Repeat.NONE) work.date else orig.date + (work.date - occDay)
                }
                store.save(m); selected = Occ(m, work.date); day = work.date
                changed(); after(Occ(m, work.date))
            }
        )
    }

    fun deleteEvent(e: Event, d: Long) {
        val done = { selected = null; changed() }
        if (e.repeats) {
            repeatForm(
                "Delete Repeating Event", "Delete the:\n-Current record,\n-this & all Future records,\n-All occurrences?",
                current = { e.exceptions.add(d); store.save(e); done() },
                future = {
                    if (d <= e.date) store.delete(e) else { e.repeatEnd = d - 1; store.save(e) }
                    done()
                },
                all = { store.delete(e); done() }
            )
        } else {
            palmAlert("Delete Event", "Delete selected\nevent?", 97, archiveY = 54) { archive ->
                val delete = { store.delete(e); done() }
                if (archive) exportFile("datebook-archive", store.exportJson(listOf(e), emptyList(), emptyList())) { delete() } else delete()
            }
        }
    }

    fun openNote(o: Occ) {
        val e = o.event
        NoteDialog(
            this, "Note: " + e.title.ifBlank { "(no description)" }.take(24), e.note,
            { t -> e.note = t; store.save(e); render() },
            if (e.note.isNotEmpty()) ({ e.note = ""; store.save(e); render() }) else null
        ).show()
    }

    fun saveTodo(t: Todo) { store.save(t); selectedTodo = t; render() }

    fun deleteTodo(t: Todo) {
        val delete = { store.delete(t); selectedTodo = null; render() }
        palmAlert("Delete To Do", "Delete selected To Do\nitem?", 97, archiveY = 54) { archive ->
            if (archive) exportFile("todo-archive", store.exportJson(emptyList(), listOf(t), emptyList())) { delete() } else delete()
        }
    }

    fun openTodoNote(t: Todo) {
        NoteDialog(
            this, "Note: " + t.title.ifBlank { "(no description)" }.take(24), t.note,
            { s -> t.note = s; store.save(t); render() },
            if (t.note.isNotEmpty()) ({ t.note = ""; store.save(t); render() }) else null
        ).show()
    }

    /** Switches to the To Do List app, as the Palm To Do button does. */
    fun openTodoApp(todoId: Long?) {
        val i = Intent(this, TodoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (todoId != null) i.putExtra(EXTRA_TODO, todoId)
        startActivity(i)
    }

    /** Switches to the Memo Pad app. */
    fun openMemoApp(memoId: Long?) {
        val i = Intent(this, MemoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (memoId != null) i.putExtra(EXTRA_MEMO, memoId)
        startActivity(i)
    }

    /** Switches to the Date Book app. */
    fun openDateBook(d: Long?, eventId: Long?) {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (d != null) i.putExtra(EXTRA_DAY, d)
        if (eventId != null) i.putExtra(EXTRA_SELECT, eventId)
        startActivity(i)
    }

    fun changed() {
        Alarms.scheduleNext(this)
        render()
    }

    fun pickDue(anchor: View, cur: Long, cb: (Long) -> Unit) {
        val items = listOf("Today", "Tomorrow", "One week later", "No Date", "Choose Date...")
        popupList(anchor, items, if (cur < 0) 3 else -1) { i ->
            when (i) {
                0 -> cb(today)
                1 -> cb(today + 1)
                2 -> cb(today + 7)
                3 -> cb(-1)
                else -> DateDialog(this, "Due Date", if (cur >= 0) cur else today) { d -> cb(d) }.show()
            }
        }
    }

    fun pickCategory(anchor: View, cur: Int, includeAll: Boolean, app: Int = 0, cb: (Int) -> Unit) {
        val ids = mutableListOf<Int>()
        val names = mutableListOf<String>()
        if (includeAll) { ids.add(-1); names.add("All") }
        ids.add(0); names.add("Unfiled")
        store.categoriesFor(app).forEach { ids.add(it.id); names.add(it.name) }
        names.add("Edit Categories...")
        popupList(anchor, names, ids.indexOf(cur)) { i ->
            if (i < ids.size) cb(ids[i]) else showEditCategories(app)
        }
    }

    internal fun hideKeyboard() {
        if (keepKeyboard) return // On a tall screen the keyboard stays open.
        val imm = getSystemService(InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(root.windowToken, 0)
    }

    /** Shows the keyboard for the selected field, or for [root] when no field is selected. */
    private fun showKeyboard() {
        val v = currentFocus?.takeIf { it.onCheckIsTextEditor() } ?: root.apply { requestFocus() }
        root.post { getSystemService(InputMethodManager::class.java).showSoftInput(v, 0) }
    }

    /** Ends any edit in progress, so that typed text is saved. */
    internal fun endEdit() {
        focusKey = null
        root.requestFocus()
        if (keepKeyboard) showKeyboard() else hideKeyboard()
    }

    // ---------------- screen ----------------

    fun render() {
        eventBox?.dismiss(); eventBox = null
        Palm.apply(prefs)
        Fmt.h24 = DateFormat.is24HourFormat(this)
        root.removeAllViews()
        root.setBackgroundColor(Palm.paper)
        window.decorView.setBackgroundColor(Palm.paper)
        lastEdit = null

        val header = hbox()
        // Palm title: a 13 px tab row, then the 2 px line below it.
        header.setPadding(0, 0, pp(1), 0)
        val content = FrameLayout(this)
        cmdButtons.clear()
        // View icons and command buttons, left aligned. Find is added at the right below.
        val cmds = hbox()
        val bottom = hbox().apply { setPadding(pp(1), 0, pp(1), 0) }

        when (mode) {
            DAY -> buildDay(header, content, cmds)
            WEEK -> buildWeek(header, content, cmds)
            MONTH -> buildMonth(header, content, cmds)
            AGENDA -> buildAgenda(header, content, cmds)
            MEMO -> if (memoOpen != null) buildMemoEdit(header, content, cmds) else buildMemoList(header, content, cmds)
            else -> buildTodo(header, content, cmds)
        }

        root.addView(header, lp(MATCH))
        root.addView(View(this).apply { setBackgroundColor(Palm.INK) }, LP(MATCH, pp(2)))
        // Palm content areas start at 18 (Day, Agenda, To Do, Memo) and 15 (Week, Month).
        // The title line ends at 15. The content ends 1 px above the bottom row at 147.
        val (spaceTop, spaceBottom) = when (mode) { WEEK, MONTH -> 0 to 1; else -> 3 to 1 }
        root.addView(content, LP(MATCH, 0, 1f).apply { topMargin = pp(spaceTop); bottomMargin = pp(spaceBottom) })
        bottom.addView(cmds, LP(0, WRAP, 1f))
        bottom.addView(ViewIcon(this, 5, false) { endEdit(); showFind() })
        fitButtons(bottom)
        root.addView(bottom, lp(MATCH))
        if (keepKeyboard && currentFocus?.onCheckIsTextEditor() != true) showKeyboard()
    }

    /**
     * Shrinks the command button text when the bottom row is wider than the screen
     * (Large font, or the Day view with all its buttons). Limit: it measures one time, at
     * the full screen width, so in split-screen mode the row can still be cut off.
     */
    private fun fitButtons(bottom: LinearLayout) {
        if (cmdButtons.isEmpty()) return
        val spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        bottom.measure(spec, spec)
        val over = bottom.measuredWidth - resources.displayMetrics.widthPixels
        if (over <= 0) return
        val textW = cmdButtons.sumOf { it.paint.measureText(it.text.toString()).toDouble() }.toFloat()
        val f = maxOf(0.5f, (textW - over) / textW)
        cmdButtons.forEach { it.setTextSize(TypedValue.COMPLEX_UNIT_PX, it.textSize * f) }
    }

    /**
     * The black title tab. It fits its text with 3 px padding, and a spacer follows it, so the
     * items after it sit at the right. With [clock], it shows the current time and keeps it current.
     */
    internal fun tab(h: LinearLayout, title: String, clock: Boolean = false) {
        val v = if (clock) TextClock(this).apply {
            format12Hour = "h:mm a"; format24Hour = "H:mm"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, ppf(Palm.text)); includeFontPadding = false; typeface = Palm.typeface(this@MainActivity, true)
        } else label(title, bold = true)
        val t = v.apply {
            setTextColor(Palm.paper)
            val r = pp(3).toFloat()
            background = GradientDrawable().apply {
                setColor(Palm.INK); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            setPadding(pp(3), pp(1), pp(3), pp(1))
            setOnClickListener { showMenu() }
        }
        h.addView(t, lp().apply { marginStart = pp(1); gravity = Gravity.BOTTOM })
        h.addView(View(this), LP(0, 1, 1f))
    }

    private fun arrow(left: Boolean, onClick: () -> Unit): TextView =
        label(if (left) "◀" else "▶", bold = true).apply {
            setPadding(pp(2), pp(1), pp(2), pp(1))
            setOnClickListener { endEdit(); onClick() }
        }

    /** Width of a time column: the widest time at the current font, plus [pad], and never below [min]. */
    private fun timeColW(pad: Int, min: Int): Int =
        maxOf(min, label("", bold = true).paint.measureText("00:00").toInt() + pad)

    /** The four view icons: 9 px wide, 1 px apart, at x 1, 11, 21, 31 and y 148. */
    private fun viewIcons(cmds: LinearLayout) {
        for (k in 0..3) {
            cmds.addView(ViewIcon(this, k, mode == k) {
                endEdit(); mode = k; prefs.lastView = k; render()
            }, lp().apply { topMargin = pp(1); if (k > 0) marginStart = pp(1) })
        }
    }

    /**
     * A command button, 12 px tall at y 147, as wide as its text plus 3 px each side.
     * [gap] is the Palm space before it.
     */
    internal fun cmd(cmds: LinearLayout, text: String, gap: Int = 4, onClick: () -> Unit) {
        val b = palmButton(text, onClick = onClick)
        b.setPadding(pp(3), 0, pp(3), 0)
        cmdButtons.add(b)
        cmds.addView(b, LP(WRAP, pp(12)).apply { marginStart = pp(gap) })
    }

    // ---------------- Day view ----------------

    private class Row(val occ: Occ?, val min: Int, val untimed: Boolean)

    private fun buildDay(h: LinearLayout, content: FrameLayout, cmds: LinearLayout) {
        tab(h, Fmt.dayTitle(day))
        val weekStart = day - Recur.dow(Fmt.ld(day))
        h.addView(arrow(true) { day -= 7; render() }.apply { gravity = Gravity.CENTER },
            LP(pp(12), pp(13)))
        for (i in 0..6) {
            val d = weekStart + i
            h.addView(label(Fmt.dowLetter[i], bold = true).apply {
                gravity = Gravity.CENTER
                if (d == day) { background = box(Palm.INK, Palm.INK, 0f, 1f); setTextColor(Palm.paper) }
                else if (d == today) background = box(Color.TRANSPARENT, Palm.INK, 1f, 1f)
                setOnClickListener { endEdit(); day = d; selected = null; render() }
            }, LP(pp(10), pp(12)).apply { topMargin = pp(1); if (i > 0) marginStart = pp(1) })
        }
        h.addView(arrow(false) { day += 7; render() }.apply { gravity = Gravity.CENTER },
            LP(pp(11), pp(13)).apply { marginStart = pp(1) })

        val occs = dayOccs(day)
        val timed = occs.filter { !it.event.untimed }
        val rows = mutableListOf<Row>()
        occs.filter { it.event.untimed }.forEach { rows.add(Row(it, -1, true)) }
        val list = mutableListOf<Row>()
        timed.forEach { list.add(Row(it, it.event.start, false)) }
        if (!prefs.compress) {
            freeTimes(timed, prefs.dayStart, prefs.dayEnd, prefs.timeBars).forEach { list.add(Row(null, it, false)) }
        }
        rows.addAll(list.sortedWith(compareBy<Row>({ it.min }, { if (it.occ == null) 1 else 0 })))
        if (rows.isEmpty()) rows.add(Row(null, prefs.dayStart * 60, false))

        val (laneOf, nLanes) = lanes(timed)
        val barW = if (prefs.timeBars) pp(2) + nLanes * pp(3) else 0
        val timeW = timeColW(pp(2), pp(20))
        // Rows are 11 px Palm lines, with the dotted underline inside the last px of each line.
        // Time bar ticks meet the time text at its center.
        val mid = label("", bold = true).lineHeight / 2

        // The Palm Day view has 3 px between the title line and the first row.
        val col = vbox().apply { setPadding(0, pp(3), 0, 0) }
        var focusView: EditText? = null
        rows.forEachIndexed { idx, row ->
            val rowEnd = rows.drop(idx + 1).firstOrNull { !it.untimed }?.min
                ?: maxOf(row.min + 60, row.occ?.event?.end ?: 0)
            val free = row.occ == null && !row.untimed
            val nextFree = rows.getOrNull(idx + 1)?.let { it.occ == null && !it.untimed && it.min == rowEnd } == true
            val r = hbox(Gravity.TOP)
            if (barW > 0) {
                r.addView(TimeBar(this, if (row.untimed) emptyList() else timed, laneOf, row.min, rowEnd,
                    free, nextFree, mid),
                    LP(barW, MATCH))
            }
            val occ = row.occ
            val e = occ?.event
            val timeText = when {
                row.untimed -> "•"
                else -> Fmt.timeShort(row.min)
            }
            r.addView(label(timeText, bold = true).apply {
                gravity = Gravity.END
                setPadding(0, 0, pp(2), 0)
                setTextColor(Palm.INK)
                setOnClickListener { endEdit(); timeTapped(row) }
                maxLines = 1
            }, LP(timeW, WRAP))

            // The description and its icons, with a dotted underline under each text line, from the
            // time to the right edge.
            val slot = hbox(Gravity.TOP)
            var slotEd: TextView? = null
            if (e != null && isMasked(e)) {
                slot.addView(label("").apply {
                    background = box(Palm.LIGHT, Palm.LIGHT, 0f, 1f)
                    setOnClickListener { revealAsk(e) }
                }, LP(0, pp(7), 1f).apply { topMargin = pp(2); marginEnd = pp(3) })
            } else {
                val ed = rowEdit(e?.title ?: "").apply { setPadding(pp(1), 0, pp(1), 0) }
                slotEd = ed
                val key = if (e != null) "e${e.id}" else "s${row.min}"
                ed.tag = key
                ed.setOnFocusChangeListener { _, has ->
                    if (has) { selected = row.occ; focusKey = key; lastEdit = ed }
                    else commitDayRow(row, ed.text.toString())
                }
                slot.addView(ed, LP(0, WRAP, 1f))
                if (key == focusKey) focusView = ed
                if (e != null && occ != null) {
                    val icons = hbox().apply { setPadding(0, pp(1.5f), pp(1), 0) }
                    if (e.note.isNotEmpty()) icons.addView(IconView(this, IconView.NOTE).apply {
                        setOnClickListener { endEdit(); openNote(occ) }
                    }, lp().apply { marginStart = pp(1) })
                    if (e.alarm) icons.addView(IconView(this, IconView.ALARM).apply {
                        setOnClickListener { endEdit(); editEvent(occ) }
                    }, lp().apply { marginStart = pp(1) })
                    if (e.repeats) icons.addView(IconView(this, IconView.REPEAT).apply {
                        setOnClickListener { endEdit(); editEvent(occ) }
                    }, lp().apply { marginStart = pp(1) })
                    slot.addView(icons)
                }
            }
            slot.background = LinedBackground(this, slotEd)
            r.addView(slot, LP(0, WRAP, 1f))
            col.addView(r, lp(MATCH))
        }
        val sv = ScrollView(this).apply { addView(col); isFillViewport = true; isVerticalScrollBarEnabled = false }
        content.addView(sv)
        focusView?.let { f ->
            f.post { f.requestFocus(); f.setSelection(f.text.length) }
        }

        viewIcons(cmds)
        cmd(cmds, "New") { endEdit(); newEvent(null) }
        cmd(cmds, "Details") {
            endEdit()
            root.post {
                val s = selected
                if (s != null && store.event(s.event.id) != null) editEvent(Occ(store.event(s.event.id)!!, s.day))
                else ask("Select Event", "Tap an event first, then tap Details.", "OK" to {})
            }
        }
        cmd(cmds, "Go To") { endEdit(); DateDialog(this, "Go to Date", day) { d -> day = d; selected = null; render() }.show() }
    }

    /** A row text field. With [multiLine], Enter adds a new line (To Do items); without it, Enter ends the edit. */
    /** A Day view or To Do line. Backspace in an empty line ends the edit, which deletes the record. */
    private fun rowEdit(text: String, multiLine: Boolean = false): EditText = BackEdit(this).apply {
        onEmptyBack = { endEdit() }
        setText(text)
        background = null
        setTextColor(Palm.INK)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, ppf(Palm.text))
        includeFontPadding = false
        typeface = Palm.typeface(context, false)
        minHeight = 0
        minimumHeight = 0
        setPadding(pp(1), 0, pp(1), 0)
        setHorizontallyScrolling(false)
        maxLines = 20
        if (multiLine) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        } else {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_DONE) { endEdit(); true } else false
            }
        }
    }

    private fun newEventAt(start: Int, end: Int, untimed: Boolean): Event =
        Event(
            date = day, start = start, end = end.coerceAtMost(24 * 60 - 1), untimed = untimed,
            alarm = prefs.presetOn, alarmAdvance = prefs.presetAdvance, alarmUnit = prefs.presetUnit
        )

    private fun defaultStart(): Int = if (day == today) minOf((java.time.LocalTime.now().hour + 1) * 60, 23 * 60) else prefs.dayStart * 60

    /** Palm "New": choose a time, then type the description on the new line. */
    private fun newEvent(start: Int?) {
        val s = start ?: defaultStart()
        TimeDialog(this, false, s, s + 60) { u, a, b ->
            val e = newEventAt(a, b, u)
            store.save(e)
            selected = Occ(e, day)
            focusKey = "e${e.id}"
            changed()
            showKeyboardSoon()
        }.show()
    }

    /** Palm Day view: typing with no event selected starts a new event with the typed text. */
    private fun typedEvent(initial: String) {
        val s = defaultStart()
        val e = newEventAt(s, s + 60, false).apply { title = initial }
        store.save(e)
        selected = Occ(e, day)
        focusKey = "e${e.id}"
        changed()
        showKeyboardSoon()
    }

    internal fun showKeyboardSoon() {
        root.postDelayed({
            currentFocus?.let { getSystemService(InputMethodManager::class.java).showSoftInput(it, 0) }
        }, 250)
    }

    private fun timeTapped(row: Row) {
        val o = row.occ
        if (o == null) { newEvent(row.min); return }
        if (isMasked(o.event)) { revealAsk(o.event); return }
        val e = o.event
        TimeDialog(this, e.untimed, e.start, e.end) { u, s, en ->
            val work = e.deepCopy().apply { date = o.day; untimed = u; start = s; end = en }
            saveEventEdit(e, o.day, work, false) {}
        }.show()
    }

    private fun commitDayRow(row: Row, raw: String) {
        val text = raw.trim()
        val o = row.occ
        if (o == null) {
            if (text.isEmpty()) return
            val e = newEventAt(row.min, row.min + 60, false).apply { title = text }
            store.save(e)
            selected = Occ(e, day)
            root.post { changed() }
            return
        }
        val e = store.event(o.event.id) ?: return
        if (text.isEmpty() && e.note.isEmpty() && !e.repeats) {
            store.delete(e); if (selected?.event?.id == e.id) selected = null
            root.post { changed() }
            return
        }
        if (text.isEmpty() || text == e.title) {
            if (text.isEmpty()) root.post { render() }
            return
        }
        if (!e.repeats) {
            e.title = text; store.save(e)
            return
        }
        val work = e.deepCopy().apply { date = o.day; title = text }
        root.post { saveEventEdit(e, o.day, work, false) {} }
    }

    // ---------------- Week view ----------------

    private fun buildWeek(h: LinearLayout, content: FrameLayout, cmds: LinearLayout) {
        val ws = day - Recur.dow(Fmt.ld(day))
        val wd = Fmt.ld(ws)
        val we = Fmt.ld(ws + 6)
        fun short(x: LocalDate) = "${Fmt.monthShort[x.monthValue - 1]} '${"%02d".format(x.year % 100)}"
        // "Dec '95 - Jan '96" when the week spans two months, else one "Oct '26".
        tab(h, if (wd.month == we.month) short(wd) else "${short(wd)} - ${short(we)}")
        h.addView(arrow(true) { stepDate(-1) }.apply { gravity = Gravity.CENTER; layoutParams = LP(pp(14), pp(13)) })
        // 35 px label, 2 px from each arrow.
        h.addView(label("Week  ${wd.get(WeekFields.SUNDAY_START.weekOfWeekBasedYear())}").apply {
            layoutParams = LP(pp(35), WRAP).apply { marginStart = pp(2); marginEnd = pp(2) }
        })
        h.addView(arrow(false) { stepDate(1) }.apply { gravity = Gravity.CENTER; layoutParams = LP(pp(14), pp(13)) })

        val col = vbox()
        val grid = WeekGrid(this, this, ws) { o ->
            weekSel = o
            if (o != null) showEventBox(o)
        }
        // Lines up with the grid: its hour label column on the left, its 3 px margin on the right.
        // The day letter, date and untimed dot rows: 27.5 Palm px, as the Palm gadget header.
        val head = hbox(Gravity.TOP).apply { setPadding(grid.gutter.toInt(), 0, pp(3), 0) }
        for (i in 0..6) {
            val d = ws + i
            val untimed = dayOccs(d).count { it.event.untimed }
            val cell = vbox().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setOnClickListener { goDay(d, null) }
                if (d == today) background = box(Palm.INK, Palm.INK, 0f, 1f)
            }
            val fg = if (d == today) Palm.paper else Palm.INK
            cell.addView(label(Fmt.dowLetter[i], bold = true, size = Palm.text).apply { setTextColor(fg); gravity = Gravity.CENTER })
            cell.addView(label(Fmt.ld(d).dayOfMonth.toString(), bold = true).apply { setTextColor(fg); gravity = Gravity.CENTER })
            // Untimed events: Palm font dots, so every cell has the same height.
            cell.addView(label("•".repeat(minOf(untimed, 3)), size = Palm.text / 2).apply { setTextColor(fg); gravity = Gravity.CENTER })
            head.addView(cell, LP(0, WRAP, 1f))
        }
        col.addView(head, lp(MATCH, pp(27.5f)))
        // fillViewport: the grid gets the viewport height and stretches its hours to fill it.
        val sv = ScrollView(this).apply { addView(grid); isVerticalScrollBarEnabled = false; isFillViewport = true }
        col.addView(sv, LP(MATCH, 0, 1f))
        content.addView(col)

        viewIcons(cmds)
        cmd(cmds, "Go To") { DateDialog(this, "Go to Date", day) { d -> day = d; render() }.show() }
    }

    // ---------------- Month view ----------------

    /**
     * The Palm Week view event box: full width at the top of the screen for 3 seconds.
     * Line 1: "Mon 26-10-12" at the left, "8:00 - 8:30" at the right. Line 2: the description.
     */
    private fun showEventBox(o: Occ) {
        val e = o.event
        val d = Fmt.ld(o.day)
        val panel = vbox().apply {
            background = box(Palm.paper, Palm.INK, 1f, 0f)
            setPadding(pp(2), pp(1), pp(2), pp(1))
        }
        val top = hbox()
        top.addView(label("${Fmt.dowShort[Recur.dow(d)]} ${"%02d-%02d-%02d".format(d.year % 100, d.monthValue, d.dayOfMonth)}"),
            LP(0, WRAP, 1f))
        if (!e.untimed) top.addView(label("${Fmt.timeShort(e.start)} - ${Fmt.timeShort(e.end)}"))
        panel.addView(top, lp(MATCH))
        panel.addView(label(e.title.ifBlank { "(no description)" }), lp(MATCH))
        eventBox?.dismiss()
        val pw = PopupWindow(panel, MATCH, WRAP, false)
        eventBox = pw
        pw.showAtLocation(root, Gravity.TOP, 0, 0)
        root.postDelayed({ if (eventBox === pw) { pw.dismiss(); eventBox = null } }, 3000)
    }

    private fun buildMonth(h: LinearLayout, content: FrameLayout, cmds: LinearLayout) {
        val d = Fmt.ld(day)
        tab(h, "${d.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)} ${d.year}")
        h.addView(arrow(true) { stepDate(-1) }.apply { gravity = Gravity.CENTER; layoutParams = LP(pp(14), pp(13)) })
        h.addView(arrow(false) { stepDate(1) }.apply { gravity = Gravity.CENTER; layoutParams = LP(pp(14), pp(13)) })
        content.addView(MonthGrid(this, this, d.year, d.monthValue),
            FrameLayout.LayoutParams(MATCH, WRAP))
        viewIcons(cmds)
        cmd(cmds, "Go To") { DateDialog(this, "Go to Date", day) { x -> day = x; render() }.show() }
    }

    // ---------------- Agenda view ----------------

    private fun agendaTodos(): List<Todo> {
        val cat = prefs.todoCategory
        // All open items. Completed items show only when the To Do List shows
        // them, and only on the day they were due or completed.
        return sortTodos(store.todos.filter {
            (cat < 0 || it.category == cat) &&
                (!it.done || (prefs.showCompleted && it.due == day)) &&
                !(prefs.security == 2 && it.isPrivate)
        })
    }

    private fun buildAgenda(h: LinearLayout, content: FrameLayout, cmds: LinearLayout) {
        tab(h, "", clock = true)
        val ld = Fmt.ld(day)
        h.addView(arrow(true) { stepDate(-1) }.apply { gravity = Gravity.CENTER }, LP(pp(12), pp(13)))
        h.addView(label("${Fmt.dowShort[Recur.dow(ld)]}, ${Fmt.monthShort[ld.monthValue - 1]} ${ld.dayOfMonth}", bold = true)
            .apply { gravity = Gravity.CENTER; maxLines = 1; setOnClickListener { goDay(day, null) } },
            LP(pp(54), pp(12)).apply { topMargin = pp(1); marginStart = pp(1) })
        h.addView(arrow(false) { stepDate(1) }.apply { gravity = Gravity.CENTER }, LP(pp(11), pp(13)))

        val col = vbox()

        val occs = dayOccs(day)
        if (occs.isEmpty()) col.addView(label("No Appointments Today", size = Palm.text).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, pp(2.5f), 0, pp(2.5f))
        })
        val timeW = timeColW(pp(3), pp(19))
        for (o in occs) {
            val r = hbox(Gravity.TOP).apply {
                setOnClickListener { if (isMasked(o.event)) revealAsk(o.event) else goDay(day, o) }
            }
            r.addView(label(if (o.event.untimed) "•" else Fmt.timeShort(o.event.start), bold = true).apply {
                gravity = Gravity.END; setPadding(0, 0, pp(3), 0); maxLines = 1
            }, LP(timeW, WRAP))
            if (isMasked(o.event)) r.addView(label("").apply { background = box(Palm.LIGHT, Palm.LIGHT, 0f, 1f) },
                LP(0, pp(7), 1f))
            else r.addView(label(o.event.title.ifBlank { "(no description)" }), LP(0, WRAP, 1f))
            col.addView(r, LP(MATCH, pp(11)).apply { marginEnd = pp(7) })
        }

        // The To Do divider band: a short centered rule, then the category trigger at the right.
        // The trigger shows the name of the active category.
        val th = vbox()
        th.addView(View(this).apply { setBackgroundColor(Palm.INK) },
            LP(pp(53), pp(1)).apply {
                topMargin = pp(4); bottomMargin = pp(1.5f); gravity = Gravity.CENTER_HORIZONTAL
            })
        val triggerRow = hbox()
        triggerRow.addView(View(this), LP(0, 1, 1f))
        val trigger = popupTrigger(categoryLabel(prefs.todoCategory)) { tv ->
            pickCategory(tv, prefs.todoCategory, includeAll = true) { id -> prefs.todoCategory = id; render() }
        }.apply { setPadding(pp(1), 0, pp(1), 0) }
        triggerRow.addView(trigger, LP(WRAP, pp(13)))
        th.addView(triggerRow, lp(MATCH))
        val todoCol = vbox()
        val tds = agendaTodos()
        if (tds.isEmpty()) todoCol.addView(label("No open items",size = Palm.text).apply {
            setPadding(0, pp(2.5f), 0, pp(2.5f))
        })
        // The check box is 14 px high with a 1 px top margin. This puts each text line level with the middle of the box.
        val textTop = pp(1) + (pp(14) - label("").lineHeight) / 2
        for (t in tds) {
            val r = hbox(Gravity.TOP).apply { isBaselineAligned = false }
            r.addView(checkBox("", t.done) { b -> completeTodo(t, b) }, lp().apply { topMargin = pp(1); marginStart = pp(2) })
            if (prefs.showPriorities) r.addView(todoPriorityLabel(t), lp().apply { topMargin = textTop })
            if (isMasked(t)) r.addView(label("").apply {
                background = box(Palm.LIGHT, Palm.LIGHT, 0f, 1f); setOnClickListener { revealAsk(t) }
            }, LP(0, pp(7), 1f).apply { topMargin = textTop + pp(2); marginEnd = pp(3) })
            else r.addView(label(t.title.ifBlank { "(no description)" }).apply {
                // Only the first line, with an ellipsis when the text is cut.
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(pp(1), 0, pp(1), 0)
                setOnClickListener { openTodoApp(t.id) }
            }, LP(0, WRAP, 1f).apply { topMargin = textTop })
            if (prefs.showDueDates) r.addView(todoDueLabel(t, day), lp().apply { topMargin = textTop })
            if (prefs.showCategories) r.addView(todoCategoryLabel(t), lp().apply { topMargin = textTop })
            todoCol.addView(r, lp(MATCH))
        }
        // Palm 2200: the appointment area fits its rows, up to 68 px (y 18 to 86), then the divider band,
        // then the To Do list. The 7 px at the right of the appointment rows are the scroll bar gap.
        val apptH = if (occs.isEmpty()) pp(16) else minOf(pp(68), occs.size * pp(11))
        val apptScroll = ScrollView(this).apply { addView(col); isVerticalScrollBarEnabled = false }
        val todoScroll = ScrollView(this).apply { addView(todoCol); isVerticalScrollBarEnabled = false }
        val page = vbox()
        page.addView(apptScroll, LP(MATCH, apptH))
        page.addView(th, lp(MATCH))
        page.addView(todoScroll, LP(MATCH, 0, 1f))
        content.addView(page, LP(MATCH, MATCH))
        viewIcons(cmds)
        cmd(cmds, "Go To") { DateDialog(this, "Go to Date", day) { d -> day = d; render() }.show() }
    }

    // ---------------- To Do List ----------------

    internal fun categoryLabel(id: Int) = if (id < 0) "All" else store.categoryName(id)

    private fun sortTodos(list: List<Todo>): List<Todo> {
        val dueKey = { t: Todo -> if (t.due < 0) Long.MAX_VALUE else t.due }
        val catKey = { t: Todo -> store.categoryName(t.category).lowercase() }
        val cmp: Comparator<Todo> = when (prefs.sortMode) {
            1 -> compareBy<Todo>({ dueKey(it) }, { it.priority }, { it.id })
            2 -> compareBy<Todo>({ catKey(it) }, { it.priority }, { dueKey(it) }, { it.id })
            3 -> compareBy<Todo>({ catKey(it) }, { dueKey(it) }, { it.priority }, { it.id })
            else -> compareBy<Todo>({ it.priority }, { dueKey(it) }, { it.id })
        }
        return list.sortedWith(cmp)
    }

    private fun visibleTodos(): List<Todo> {
        val cat = prefs.todoCategory
        return sortTodos(store.todos.filter {
            (cat < 0 || it.category == cat) &&
                (prefs.showCompleted || !it.done) &&
                (!prefs.onlyDue || (it.due in 0..today)) &&
                !(prefs.security == 2 && it.isPrivate)
        })
    }

    /** Due date column, as set in Show options. One line; it sizes to its text. */
    private fun todoDueLabel(t: Todo, ref: Long): TextView {
        val late = !t.done && t.due in 0 until ref
        val txt = if (t.due < 0) "-" else {
            val d = Fmt.ld(t.due); "${d.monthValue}/${d.dayOfMonth}" + if (late) "!" else ""
        }
        return label(txt, bold = late).apply {
            gravity = Gravity.START
            // A fixed-width column, so all dates start at the same x.
            minWidth = label("", bold = true).paint.measureText("00/00!").toInt() + pp(3)
            maxLines = 1
            setPadding(pp(2), 0, pp(1), 0)
            if (t.due < 0) {
                // The no-date bar sits at the center of where a date would be.
                setPadding(pp(2) + ((paint.measureText("00/00") - paint.measureText("-")) / 2).toInt(), 0, pp(1), 0)
            }
            setOnClickListener { tv -> endEdit(); pickDue(tv, t.due) { d -> t.due = d; saveTodo(t) } }
        }
    }

    /**
     * Category column, as set in Show options. As on the Palm, the column fits "Vehicl" and
     * "Movi": longer names are cut to the whole characters that fit, with no ellipsis.
     */
    private fun todoCategoryLabel(t: Todo): TextView = label("").apply {
        val textW = maxOf(paint.measureText("Vehicl"), paint.measureText("Movi"))
        val name = store.categoryName(t.category)
        var n = name.length
        while (n > 0 && paint.measureText(name, 0, n) > textW) n--
        text = name.substring(0, n)
        gravity = Gravity.START
        maxLines = 1
        setPadding(pp(2), 0, pp(3), 0)
        val w = textW.toInt() + 1 + pp(2) + pp(3)
        minWidth = w
        maxWidth = w
        setOnClickListener { tv ->
            endEdit()
            pickCategory(tv, t.category, includeAll = false) { id -> t.category = id; saveTodo(t) }
        }
    }

    private fun todoPriorityLabel(t: Todo): TextView =
        label("${t.priority}", bold = true).apply {
            setPadding(pp(1), 0, pp(2), 0)
            setOnClickListener { tv ->
                endEdit()
                popupList(tv, listOf("1", "2", "3", "4", "5"), t.priority - 1) { i -> t.priority = i + 1; saveTodo(t) }
            }
        }

    private fun completeTodo(t: Todo, done: Boolean) {
        t.done = done
        if (done && prefs.recordCompletion) t.due = today
        store.save(t)
        root.post { render() }
    }

    private fun buildTodo(h: LinearLayout, content: FrameLayout, cmds: LinearLayout) {
        tab(h, "To Do")
        // Palm x 143 to 160, 13 px high. The name grows the trigger to the left. The header
        // has 1 px of right padding, so a -1 px end margin puts the right edge at x 160.
        h.addView(popupTrigger(categoryLabel(prefs.todoCategory)) { tv ->
            endEdit()
            pickCategory(tv, prefs.todoCategory, includeAll = true) { id -> prefs.todoCategory = id; render() }
        }.apply { minWidth = pp(17) }, LP(WRAP, pp(13)).apply { marginEnd = -pp(1) })

        val col = vbox()
        val items = visibleTodos()
        var focusView: EditText? = null
        if (items.isEmpty()) col.addView(label("No items. Tap New to add one.").apply {
            setTextColor(Palm.GRAY); setPadding(pp(5), pp(6.5f), pp(5), pp(6.5f))
        })
        // The check box is 14 px high with a 1 px top margin. This puts each text line level with the middle of the box.
        val textTop = pp(1) + (pp(14) - label("").lineHeight) / 2
        for (t in items) {
            val r = hbox(Gravity.TOP)
            // Baseline alignment lets the 14 px check box, with its lower baseline, push a wrapped
            // EditText down inside a row measured too short. Top alignment keeps every child at the top edge.
            r.isBaselineAligned = false
            r.addView(checkBox("", t.done) { b -> endEdit(); completeTodo(t, b) }, lp().apply { topMargin = pp(1); marginStart = pp(2) })
            if (prefs.showPriorities) r.addView(todoPriorityLabel(t), lp().apply { topMargin = textTop })
            if (isMasked(t)) {
                r.addView(label("").apply {
                    background = box(Palm.LIGHT, Palm.LIGHT, 0f, 1f)
                    setOnClickListener { revealAsk(t) }
                }, LP(0, pp(7), 1f).apply { topMargin = textTop + pp(2); marginEnd = pp(3) })
            } else {
                val ed = rowEdit(t.title, multiLine = true)
                ed.background = LinedBackground(this, ed)
                val key = "t${t.id}"
                ed.tag = key
                ed.setOnFocusChangeListener { _, has ->
                    if (has) { selectedTodo = t; focusKey = key; lastEdit = ed }
                    else commitTodo(t, ed.text.toString())
                }
                if (key == focusKey) focusView = ed
                r.addView(ed, LP(0, WRAP, 1f).apply { topMargin = textTop })
            }
            if (prefs.showDueDates) r.addView(todoDueLabel(t, today), lp().apply { topMargin = textTop })
            if (prefs.showCategories) r.addView(todoCategoryLabel(t), lp().apply { topMargin = textTop })
            col.addView(r, lp(MATCH))
        }
        content.addView(ScrollView(this).apply { addView(col); isFillViewport = true; isVerticalScrollBarEnabled = false })
        focusView?.let { f -> f.post { f.requestFocus(); f.setSelection(f.text.length) } }

        cmd(cmds, "New", gap = 0) { endEdit(); newTodo() }
        cmd(cmds, "Details...") {
            endEdit()
            root.post {
                val t = selectedTodo?.let { s -> store.todos.firstOrNull { it.id == s.id } }
                if (t != null) TodoDetails(this, t).show()
                else ask("Select Item", "Tap an item first, then tap Details.", "OK" to {})
            }
        }
        cmd(cmds, "Show...") { endEdit(); showShowOptions() }
        cmds.addView(View(this), LP(0, 1, 1f))
    }

    private fun newTodo(initial: String = "") {
        val cat = if (prefs.todoCategory > 0) prefs.todoCategory else 0
        val t = Todo(title = initial, priority = 1, category = cat)
        store.save(t)
        selectedTodo = t
        focusKey = "t${t.id}"
        if (!prefs.showCompleted || prefs.onlyDue) {
            // A new item has no due date, so it would be hidden by "Show Only Due Items".
            prefs.onlyDue = false
        }
        render()
        showKeyboardSoon()
    }

    private fun commitTodo(t: Todo, raw: String) {
        val text = raw.trim()
        if (text.isEmpty() && t.note.isEmpty()) {
            store.delete(t); if (selectedTodo?.id == t.id) selectedTodo = null
            root.post { render() }
            return
        }
        if (text.isEmpty() || text == t.title) return
        t.title = text
        store.save(t)
    }

    // ---------------- Menus ----------------

    private fun menus(): List<Pair<String, List<MenuItem>>> {
        val todo = mode == TODO
        val memo = mode == MEMO
        val record: List<MenuItem> = if (memo) listOf(
            MenuItem("New Memo", 'N') { newMemo() },
            MenuItem("Delete Memo...", 'D') { memoOpen?.let { deleteMemoAsk(it) } ?: toast("Open a memo first.") },
            MenuItem("Export Backup...") { exportFile("datebook-backup", store.exportJson()) },
            MenuItem("Import Backup...") { importFile() }
        ) else if (todo) listOf(
            MenuItem("New Item", 'N') { newTodo() },
            MenuItem("Delete Item...", 'D') { selectedTodo?.let { deleteTodo(it) } ?: toast("Select an item first.") },
            MenuItem("Attach Note", 'A') { selectedTodo?.let { openTodoNote(it) } ?: toast("Select an item first.") },
            MenuItem("Delete Note...", 'O') {
                selectedTodo?.let { t ->
                    if (t.note.isEmpty()) toast("This item has no note.")
                    else ask("Delete Note", "Delete the note?", "Yes" to { t.note = ""; store.save(t); render() }, "No" to {})
                } ?: toast("Select an item first.")
            },
            MenuItem("Purge...", 'E') { showPurgeTodos() },
            MenuItem("Export Backup...") { exportFile("datebook-backup", store.exportJson()) },
            MenuItem("Import Backup...") { importFile() }
        ) else listOf(
            MenuItem("New Event", 'N') { if (mode != DAY) { mode = DAY; render() }; newEvent(null) },
            MenuItem("Delete Event...", 'D') { selected?.let { deleteEvent(it.event, it.day) } ?: toast("Select an event first.") },
            MenuItem("Attach Note", 'A') { selected?.let { openNote(it) } ?: toast("Select an event first.") },
            MenuItem("Delete Note...", 'O') {
                selected?.let { o ->
                    if (o.event.note.isEmpty()) toast("This event has no note.")
                    else ask("Delete Note", "Delete the note?", "Yes" to { o.event.note = ""; store.save(o.event); render() }, "No" to {})
                } ?: toast("Select an event first.")
            },
            MenuItem("Purge...", 'E') { showPurgeEvents() },
            MenuItem("Export Backup...") { exportFile("datebook-backup", store.exportJson()) },
            MenuItem("Import Backup...") { importFile() }
        )
        val ed = lastEdit
        val edit: List<MenuItem> = listOf(
            MenuItem("Undo", 'U') { textOp(ed, android.R.id.undo) },
            MenuItem("Cut", 'X') { textOp(ed, android.R.id.cut) },
            MenuItem("Copy", 'C') { textOp(ed, android.R.id.copy) },
            MenuItem("Paste", 'P') { textOp(ed, android.R.id.paste) },
            MenuItem("Select All", 'S') { textOp(ed, android.R.id.selectAll) },
            MenuItem("Keyboard", 'K') {
                ed?.let { it.requestFocus(); getSystemService(InputMethodManager::class.java).showSoftInput(it, 0) }
                    ?: toast("Tap a line of text first.")
            }
        )
        val options: List<MenuItem> = if (memo) listOf(
            MenuItem("Font...", 'F') { showFont() },
            MenuItem("Preferences...", 'R') { showMemoPreferences() },
            MenuItem("Edit Categories...") { showEditCategories(1) },
            MenuItem("Security...", 'H') { showSecurity() },
            MenuItem("About Memo Pad") { showMemoAbout() }
        ) else if (todo) listOf(
            MenuItem("Font...", 'F') { showFont() },
            MenuItem("Edit Categories...") { showEditCategories() },
            MenuItem("Security...", 'H') { showSecurity() },
            MenuItem("About To Do List") { showAbout(true) }
        ) else listOf(
            MenuItem("Font...", 'F') { showFont() },
            MenuItem("Preferences...", 'R') { showPreferences() },
            MenuItem("Display Options...", 'Y') { showDisplayOptions() },
            MenuItem("Security...", 'H') { showSecurity() },
            MenuItem("About Date Book") { showAbout(false) }
        )
        return listOf("Record" to record, "Edit" to edit, "Options" to options)
    }

    /** The Palm menu bar of this screen. A tap on the title tab opens it. */
    private fun showMenu() = MenuBar(this, menus()).show()

    private fun textOp(ed: EditText?, id: Int) {
        if (ed == null) { toast("Tap a line of text first."); return }
        ed.requestFocus()
        ed.onTextContextMenuItem(id)
    }

    // ---------------- Find ----------------

    private fun showFind() {
        val d = PalmDialog(this, "Find", height = 60)
        val f = field("", "Find text")
        // Form coordinates. The body starts at form (4, 16), so each margin is the form value less that origin.
        val row = FrameLayout(this)
        row.addView(label("Find:", bold = true), FrameLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = pp(3); topMargin = pp(4) })
        row.addView(f, FrameLayout.LayoutParams(pp(112), pp(11)).apply { leftMargin = pp(28); topMargin = pp(4) })
        d.add(row, 0)
        d.button("OK", 37) {
            val q = f.text.toString().trim()
            d.dismiss(); hideKeyboard()
            if (q.isNotEmpty()) showFindResults(q)
        }
        d.button("Cancel", 37) { d.dismiss() }
        d.show()
        f.requestFocus()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    private fun showFindResults(q: String) {
        val d = PalmDialog(this, "Find", height = 156)
        // The body starts at form (2, 16) here, so each margin is the form value less that origin.
        d.body.setPadding(pp(1), pp(2), pp(1), pp(1))
        val list = vbox()
        val area = FrameLayout(this)
        area.addView(label("Matches for \"$q\"", bold = true), FrameLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = pp(5) })
        area.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(list) },
            FrameLayout.LayoutParams(pp(152), pp(99)).apply { topMargin = pp(15) })
        d.add(area, 0)
        fun addRow(v: View, top: Int) = list.addView(v, LP(MATCH, WRAP).apply { topMargin = pp(top) })
        val hideP = prefs.security != 0
        val evs = store.events.filter {
            !(hideP && it.isPrivate) && (it.title.contains(q, true) || it.note.contains(q, true))
        }.sortedBy { it.date }
        val tds = store.todos.filter {
            !(hideP && it.isPrivate) && (it.title.contains(q, true) || it.note.contains(q, true))
        }
        val memos = store.memos.filter {
            !(hideP && it.isPrivate) && it.text.contains(q, true)
        }
        fun result(text: String, onClick: () -> Unit) =
            addRow(label(text).apply { setPadding(pp(3), pp(3), pp(1.5f), pp(3)); setOnClickListener { d.dismiss(); onClick() } }, 0)
        addRow(label("Date Book", bold = true).apply { setTextColor(Palm.GRAY) }, 10)
        if (evs.isEmpty()) addRow(label("  No matches"), 0)
        for (e in evs) {
            val shown = nextOccurrence(e)
            result("${Fmt.shortDate(shown)}  ${e.title.ifBlank { "(no description)" }}") {
                if (isTodoApp || isMemoApp) openDateBook(shown, e.id) else goDay(shown, Occ(e, shown))
            }
        }
        addRow(label("To Do List", bold = true).apply { setTextColor(Palm.GRAY) }, 10)
        if (tds.isEmpty()) addRow(label("  No matches"), 0)
        for (t in tds) {
            result(t.title.ifBlank { "(no description)" }) {
                if (isTodoApp) { prefs.todoCategory = -1; selectedTodo = t; render() } else openTodoApp(t.id)
            }
        }
        addRow(label("Memo Pad", bold = true).apply { setTextColor(Palm.GRAY) }, 10)
        if (memos.isEmpty()) addRow(label("  No matches"), 0)
        for (m in memos) {
            result(m.title.ifBlank { "(no description)" }) {
                if (isMemoApp) { prefs.memoCategory = -1; memoOpen = m; render() } else openMemoApp(m.id)
            }
        }
        d.button("Done", 37) { d.dismiss() }
        d.show()
    }

    /** The first occurrence on or after today, or the last one if all are past. */
    private fun nextOccurrence(e: Event): Long {
        if (!e.repeats) return e.date
        var d = maxOf(today, e.date)
        val last = minOf(Recur.lastDay(e), d + 800)
        while (d <= last) { if (Recur.occursOn(e, d)) return d; d++ }
        return e.date
    }

    // ---------------- Backup files ----------------

    fun exportFile(base: String, text: String, after: () -> Unit = {}) {
        pendingExport = text
        afterExport = after
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json")
            .putExtra(Intent.EXTRA_TITLE, "$base-${LocalDate.now()}.json")
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_EXPORT)
    }

    private fun importFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_IMPORT)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            if (requestCode == REQ_EXPORT) { pendingExport = null; afterExport = null; toast("Not saved.") }
            return
        }
        when (requestCode) {
            REQ_EXPORT -> {
                try {
                    contentResolver.openOutputStream(uri)?.use { it.write((pendingExport ?: "").toByteArray()) }
                    toast("Saved.")
                    afterExport?.invoke()
                } catch (ex: Exception) {
                    ask("Export", "The file was not saved: ${ex.message}", "OK" to {})
                }
                pendingExport = null; afterExport = null
            }
            REQ_IMPORT -> {
                try {
                    val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                    val (ne, nt, nm) = store.importJson(text)
                    changed()
                    ask("Import", "Added $ne events, $nt To Do items and $nm memos.", "OK" to {})
                } catch (ex: Exception) {
                    ask("Import", "This file is not a Date Book backup.", "OK" to {})
                }
            }
        }
    }
}

/**
 * Palm dotted underlines: one under each text line of [ed]. As the background of a row it
 * spans the row; as the background of [ed] itself it spans the field. With no editor (a
 * masked record), one line at the bottom. With [fill], the lines continue to the bottom, as on a
 * Memo Pad page.
 */
class LinedBackground(ctx: Context, private val ed: TextView?, private val fill: Boolean = false) : Drawable() {
    private val p = Paint().apply {
        color = Palm.INK; strokeWidth = ctx.ppf(1f)
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(ctx.ppf(1f), ctx.ppf(1f)), 0f)
    }

    override fun draw(c: Canvas) {
        val b = bounds
        val h = p.strokeWidth / 2
        val layout = ed?.layout
        if (ed == null || layout == null) {
            c.drawLine(b.left.toFloat(), b.bottom - h, b.right.toFloat(), b.bottom - h, p)
            return
        }
        val off = if (ed.background === this) 0 else ed.top
        for (i in 0 until layout.lineCount) {
            val y = b.top + off + ed.paddingTop + layout.getLineBottom(i) - h
            c.drawLine(b.left.toFloat(), y, b.right.toFloat(), y, p)
        }
        if (fill) {
            // Lines below the text, one per editor line height, down to the bottom of the bounds.
            val step = ed.lineHeight.toFloat()
            var y = b.top + off + ed.paddingTop + layout.getLineBottom(layout.lineCount - 1) - h + step
            while (step > 0 && y <= b.bottom) {
                c.drawLine(b.left.toFloat(), y, b.right.toFloat(), y, p)
                y += step
            }
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * Day view time bars: one lane per overlapping event. At the start and end, the bar
 * turns right at [mid] (the center of the time text) and runs to the time column.
 * When an empty line starts at an event's end ([nextFree] in the row above, [free] in
 * that line), the bar runs down to the empty line's time and turns there.
 */
@SuppressLint("ViewConstructor")
class TimeBar(
    ctx: Context,
    private val timed: List<Occ>,
    private val laneOf: Map<Occ, Int>,
    private val rs: Int,
    private val re: Int,
    private val free: Boolean,
    private val nextFree: Boolean,
    private val mid: Int
) : View(ctx) {
    private val p = Paint().apply { color = Palm.INK; strokeWidth = ctx.ppf(1f) }

    override fun onDraw(c: Canvas) {
        val lw = context.ppf(3f)
        val x0 = context.ppf(1.5f)
        val xe = width.toFloat()
        val y = mid.toFloat()
        for (o in timed) {
            val e = o.event
            val end = maxOf(e.end, e.start + 1)
            val x = x0 + (laneOf[o] ?: 0) * lw
            if (free && end == rs && e.start < rs) {
                c.drawLine(x, 0f, x, y, p)
                c.drawLine(x, y, xe, y, p)
                continue
            }
            if (!(e.start < re && end > rs)) continue
            val startsHere = e.start >= rs && e.start < re
            val endsHere = end > rs && (end < re || (end == re && !nextFree))
            val top = if (startsHere) y else 0f
            val bot = if (endsHere) height - context.ppf(1f) else height.toFloat()
            c.drawLine(x, top, x, maxOf(top, bot), p)
            if (startsHere) c.drawLine(x, top, xe, top, p)
            if (endsHere) c.drawLine(x, maxOf(top, bot), xe, maxOf(top, bot), p)
        }
    }
}

@Suppress("unused")
private fun unusedImports(c: CheckBox) = c
