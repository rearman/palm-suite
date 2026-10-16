package com.rearman.datebook

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/** All records live in one SQLite file on the phone. The lists are cached in memory. */
class Store private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx, "datebook.db", null, 2) {

    companion object {
        @Volatile private var inst: Store? = null
        fun get(ctx: Context): Store =
            inst ?: synchronized(this) {
                inst ?: Store(ctx.applicationContext).also { inst = it }
            }
        const val MAX_CATEGORIES = 15
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, note TEXT, " +
                "date INTEGER, untimed INTEGER, start INTEGER, end_ INTEGER, alarm INTEGER, " +
                "adv INTEGER, unit INTEGER, rep INTEGER, every INTEGER, rep_end INTEGER, " +
                "days INTEGER, exc TEXT, priv INTEGER)"
        )
        db.execSQL(
            "CREATE TABLE todos (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, note TEXT, " +
                "pri INTEGER, due INTEGER, done INTEGER, cat INTEGER, priv INTEGER)"
        )
        db.execSQL("CREATE TABLE categories (id INTEGER PRIMARY KEY, name TEXT, app INTEGER DEFAULT 0)")
        db.execSQL("INSERT INTO categories VALUES (1, 'Business', 0)")
        db.execSQL("INSERT INTO categories VALUES (2, 'Personal', 0)")
        db.execSQL("INSERT INTO categories VALUES (3, 'Business', 1)")
        db.execSQL("INSERT INTO categories VALUES (4, 'Personal', 1)")
        db.execSQL(
            "CREATE TABLE memos (id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT, cat INTEGER, " +
                "priv INTEGER, pos INTEGER)"
        )
    }

    /**
     * Version 2 adds Memo Pad: the memos table and per-app categories.
     * Existing categories get app 0 (To Do). The Memo Pad categories get new ids.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE categories ADD COLUMN app INTEGER DEFAULT 0")
            db.execSQL(
                "CREATE TABLE memos (id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT, cat INTEGER, " +
                    "priv INTEGER, pos INTEGER)"
            )
            db.execSQL("INSERT INTO categories (id, name, app) SELECT IFNULL(MAX(id), 0) + 1, 'Business', 1 FROM categories")
            db.execSQL("INSERT INTO categories (id, name, app) SELECT IFNULL(MAX(id), 0) + 1, 'Personal', 1 FROM categories")
        }
    }

    // ---- events ----

    val events: MutableList<Event> by lazy { loadEvents() }
    val todos: MutableList<Todo> by lazy { loadTodos() }
    val categories: MutableList<Category> by lazy { loadCategories(0) }
    val memoCategories: MutableList<Category> by lazy { loadCategories(1) }

    fun categoriesFor(app: Int): MutableList<Category> = if (app == 1) memoCategories else categories

    private fun Cursor.str(c: String): String = getString(getColumnIndexOrThrow(c)) ?: ""
    private fun Cursor.lng(c: String): Long = getLong(getColumnIndexOrThrow(c))
    private fun Cursor.int(c: String): Int = getInt(getColumnIndexOrThrow(c))

    private fun loadEvents(): MutableList<Event> {
        val list = mutableListOf<Event>()
        readableDatabase.rawQuery("SELECT * FROM events", null).use { c ->
            while (c.moveToNext()) {
                list.add(
                    Event(
                        id = c.lng("id"), title = c.str("title"), note = c.str("note"),
                        date = c.lng("date"), untimed = c.int("untimed") != 0,
                        start = c.int("start"), end = c.int("end_"), alarm = c.int("alarm") != 0,
                        alarmAdvance = c.int("adv"), alarmUnit = c.int("unit"),
                        repeat = c.int("rep"), every = c.int("every"), repeatEnd = c.lng("rep_end"),
                        weekDays = c.int("days"), exceptions = parseExc(c.str("exc")),
                        isPrivate = c.int("priv") != 0
                    )
                )
            }
        }
        return list
    }

    private fun parseExc(s: String): MutableSet<Long> =
        s.split(',').mapNotNull { it.trim().toLongOrNull() }.toMutableSet()

    private fun eventValues(e: Event) = ContentValues().apply {
        put("title", e.title); put("note", e.note); put("date", e.date)
        put("untimed", if (e.untimed) 1 else 0); put("start", e.start); put("end_", e.end)
        put("alarm", if (e.alarm) 1 else 0); put("adv", e.alarmAdvance); put("unit", e.alarmUnit)
        put("rep", e.repeat); put("every", e.every); put("rep_end", e.repeatEnd)
        put("days", e.weekDays); put("exc", e.exceptions.sorted().joinToString(","))
        put("priv", if (e.isPrivate) 1 else 0)
    }

    fun save(e: Event) {
        if (e.id == 0L) {
            e.id = writableDatabase.insert("events", null, eventValues(e))
            events.add(e)
        } else {
            writableDatabase.update("events", eventValues(e), "id=?", arrayOf(e.id.toString()))
            val i = events.indexOfFirst { it.id == e.id }
            if (i >= 0) events[i] = e else events.add(e)
        }
    }

    fun delete(e: Event) {
        writableDatabase.delete("events", "id=?", arrayOf(e.id.toString()))
        events.removeAll { it.id == e.id }
    }

    fun event(id: Long): Event? = events.firstOrNull { it.id == id }

    /** Occurrences on one day: untimed first, then by start time. */
    fun dayEvents(day: Long): List<Occ> =
        events.filter { Recur.occursOn(it, day) }
            .sortedWith(compareBy<Event>({ !it.untimed }, { it.start }, { it.end }))
            .map { Occ(it, day) }

    // ---- to dos ----

    private fun loadTodos(): MutableList<Todo> {
        val list = mutableListOf<Todo>()
        readableDatabase.rawQuery("SELECT * FROM todos", null).use { c ->
            while (c.moveToNext()) {
                list.add(
                    Todo(
                        id = c.lng("id"), title = c.str("title"), note = c.str("note"),
                        priority = c.int("pri"), due = c.lng("due"), done = c.int("done") != 0,
                        category = c.int("cat"), isPrivate = c.int("priv") != 0
                    )
                )
            }
        }
        return list
    }

    private fun todoValues(t: Todo) = ContentValues().apply {
        put("title", t.title); put("note", t.note); put("pri", t.priority); put("due", t.due)
        put("done", if (t.done) 1 else 0); put("cat", t.category); put("priv", if (t.isPrivate) 1 else 0)
    }

    fun save(t: Todo) {
        if (t.id == 0L) {
            t.id = writableDatabase.insert("todos", null, todoValues(t))
            todos.add(t)
        } else {
            writableDatabase.update("todos", todoValues(t), "id=?", arrayOf(t.id.toString()))
            val i = todos.indexOfFirst { it.id == t.id }
            if (i >= 0) todos[i] = t else todos.add(t)
        }
    }

    fun delete(t: Todo) {
        writableDatabase.delete("todos", "id=?", arrayOf(t.id.toString()))
        todos.removeAll { it.id == t.id }
    }

    // ==== memos ====

    val memos: MutableList<Memo> by lazy { loadMemos() }

    private fun loadMemos(): MutableList<Memo> {
        val list = mutableListOf<Memo>()
        readableDatabase.rawQuery("SELECT id, text, cat, priv, pos FROM memos ORDER BY pos", null).use { c ->
            while (c.moveToNext()) {
                list.add(
                    Memo(
                        id = c.lng("id"), text = c.str("text"), category = c.int("cat"),
                        isPrivate = c.int("priv") != 0, pos = c.int("pos")
                    )
                )
            }
        }
        return list
    }

    private fun memoValues(m: Memo) = ContentValues().apply {
        put("text", m.text); put("cat", m.category); put("priv", if (m.isPrivate) 1 else 0); put("pos", m.pos)
    }

    fun save(m: Memo) {
        if (m.id == 0L) {
            m.pos = if (memos.isEmpty()) 0 else memos.maxOf { it.pos } + 1
            m.id = writableDatabase.insert("memos", null, memoValues(m))
            memos.add(m)
        } else {
            writableDatabase.update("memos", memoValues(m), "id=?", arrayOf(m.id.toString()))
            val i = memos.indexOfFirst { it.id == m.id }
            if (i >= 0) memos[i] = m else memos.add(m)
        }
    }

    fun delete(m: Memo) {
        writableDatabase.delete("memos", "id=?", arrayOf(m.id.toString()))
        memos.removeAll { it.id == m.id }
    }

    /** Writes only the pos column of each memo, in one transaction. */
    fun savePositions(changed: List<Memo>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (m in changed) {
                db.update("memos", ContentValues().apply { put("pos", m.pos) }, "id=?", arrayOf(m.id.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---- categories ----

    private fun loadCategories(app: Int): MutableList<Category> {
        val list = mutableListOf<Category>()
        readableDatabase.rawQuery("SELECT * FROM categories WHERE app=? ORDER BY id", arrayOf(app.toString())).use { c ->
            while (c.moveToNext()) list.add(Category(c.int("id"), c.str("name"), c.int("app")))
        }
        return list
    }

    fun categoryName(id: Int): String =
        if (id == 0) "Unfiled" else (categories + memoCategories).firstOrNull { it.id == id }?.name ?: "Unfiled"

    fun addCategory(name: String, app: Int = 0): Category? {
        val list = categoriesFor(app)
        if (list.size >= MAX_CATEGORIES) return null
        val id = ((categories + memoCategories).maxOfOrNull { it.id } ?: 0) + 1
        val cat = Category(id, name, app)
        writableDatabase.insert("categories", null, ContentValues().apply {
            put("id", id); put("name", name); put("app", app)
        })
        list.add(cat)
        return cat
    }

    fun renameCategory(cat: Category, name: String) {
        cat.name = name
        writableDatabase.update("categories", ContentValues().apply { put("name", name) },
            "id=?", arrayOf(cat.id.toString()))
    }

    /** Deletes a category. Its items move to Unfiled, as on the Palm. */
    fun deleteCategory(cat: Category) {
        writableDatabase.delete("categories", "id=?", arrayOf(cat.id.toString()))
        if (cat.app == 1) {
            writableDatabase.execSQL("UPDATE memos SET cat=0 WHERE cat=${cat.id}")
            memos.filter { it.category == cat.id }.forEach { it.category = 0 }
            memoCategories.removeAll { it.id == cat.id }
        } else {
            writableDatabase.execSQL("UPDATE todos SET cat=0 WHERE cat=${cat.id}")
            todos.filter { it.category == cat.id }.forEach { it.category = 0 }
            categories.removeAll { it.id == cat.id }
        }
    }

    // ---- backup ----

    fun eventJson(e: Event) = JSONObject().apply {
        put("title", e.title); put("note", e.note); put("date", e.date); put("untimed", e.untimed)
        put("start", e.start); put("end", e.end); put("alarm", e.alarm)
        put("alarmAdvance", e.alarmAdvance); put("alarmUnit", e.alarmUnit)
        put("repeat", e.repeat); put("every", e.every); put("repeatEnd", e.repeatEnd)
        put("weekDays", e.weekDays); put("exceptions", JSONArray(e.exceptions.sorted()))
        put("private", e.isPrivate)
    }

    fun todoJson(t: Todo) = JSONObject().apply {
        put("title", t.title); put("note", t.note); put("priority", t.priority); put("due", t.due)
        put("done", t.done); put("category", categoryName(t.category)); put("private", t.isPrivate)
    }

    fun memoJson(m: Memo) = JSONObject().apply {
        put("text", m.text); put("category", categoryName(m.category)); put("private", m.isPrivate)
    }

    fun exportJson(evs: List<Event> = events, tds: List<Todo> = todos, mms: List<Memo> = memos): String {
        val root = JSONObject()
        root.put("format", "datebook-backup-1")
        root.put("categories", JSONArray(categories.map { it.name }))
        root.put("memoCategories", JSONArray(memoCategories.map { it.name }))
        root.put("events", JSONArray(evs.map { eventJson(it) }))
        root.put("todos", JSONArray(tds.map { todoJson(it) }))
        root.put("memos", JSONArray(mms.map { memoJson(it) }))
        return root.toString(1)
    }

    /** Adds the records in a backup file. Returns (events, to dos, memos) added. */
    fun importJson(text: String): Triple<Int, Int, Int> {
        val root = JSONObject(text)
        val db = writableDatabase
        var ne = 0
        var nt = 0
        var nm = 0
        db.beginTransaction()
        try {
            root.optJSONArray("categories")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val n = arr.getString(i)
                    if (categories.none { it.name.equals(n, true) }) addCategory(n)
                }
            }
            root.optJSONArray("memoCategories")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val n = arr.getString(i)
                    if (memoCategories.none { it.name.equals(n, true) }) addCategory(n, 1)
                }
            }
            root.optJSONArray("events")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val exc = mutableSetOf<Long>()
                    o.optJSONArray("exceptions")?.let { x -> for (k in 0 until x.length()) exc.add(x.getLong(k)) }
                    save(
                        Event(
                            title = o.optString("title"), note = o.optString("note"),
                            date = o.getLong("date"), untimed = o.optBoolean("untimed"),
                            start = o.optInt("start", 480), end = o.optInt("end", 540),
                            alarm = o.optBoolean("alarm"), alarmAdvance = o.optInt("alarmAdvance", 5),
                            alarmUnit = o.optInt("alarmUnit"), repeat = o.optInt("repeat"),
                            every = o.optInt("every", 1), repeatEnd = o.optLong("repeatEnd", -1),
                            weekDays = o.optInt("weekDays"), exceptions = exc,
                            isPrivate = o.optBoolean("private")
                        )
                    )
                    ne++
                }
            }
            root.optJSONArray("todos")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val catName = o.optString("category", "Unfiled")
                    val cat = categories.firstOrNull { it.name.equals(catName, true) }?.id ?: 0
                    save(
                        Todo(
                            title = o.optString("title"), note = o.optString("note"),
                            priority = o.optInt("priority", 1), due = o.optLong("due", -1),
                            done = o.optBoolean("done"), category = cat,
                            isPrivate = o.optBoolean("private")
                        )
                    )
                    nt++
                }
            }
            root.optJSONArray("memos")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val catName = o.optString("category", "Unfiled")
                    val cat = memoCategories.firstOrNull { it.name.equals(catName, true) }?.id ?: 0
                    save(
                        Memo(
                            text = o.optString("text"), category = cat,
                            isPrivate = o.optBoolean("private")
                        )
                    )
                    nm++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return Triple(ne, nt, nm)
    }
}

/** Settings from the Preferences, Display Options, Show Options, Font and Security dialogs. */
class Prefs(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    private fun int(k: String, d: Int) = p.getInt(k, d)
    private fun bool(k: String, d: Boolean) = p.getBoolean(k, d)
    private fun setInt(k: String, v: Int) = p.edit().putInt(k, v).apply()
    private fun setBool(k: String, v: Boolean) = p.edit().putBoolean(k, v).apply()

    // Date Book Preferences
    var dayStart: Int get() = int("dayStart", 8); set(v) = setInt("dayStart", v)
    var dayEnd: Int get() = int("dayEnd", 18); set(v) = setInt("dayEnd", v)
    var presetOn: Boolean get() = bool("presetOn", false); set(v) = setBool("presetOn", v)
    var presetAdvance: Int get() = int("presetAdv", 5); set(v) = setInt("presetAdv", v)
    var presetUnit: Int get() = int("presetUnit", 0); set(v) = setInt("presetUnit", v)
    var remindTimes: Int get() = int("remindTimes", 3); set(v) = setInt("remindTimes", v)
    var remindEvery: Int get() = int("remindEvery", 5); set(v) = setInt("remindEvery", v)

    // Display Options
    var timeBars: Boolean get() = bool("timeBars", true); set(v) = setBool("timeBars", v)
    var compress: Boolean get() = bool("compress", false); set(v) = setBool("compress", v)
    var monthTimed: Boolean get() = bool("monthTimed", true); set(v) = setBool("monthTimed", v)
    var monthUntimed: Boolean get() = bool("monthUntimed", true); set(v) = setBool("monthUntimed", v)
    var monthDaily: Boolean get() = bool("monthDaily", true); set(v) = setBool("monthDaily", v)

    // Font: 0 small, 1 normal, 2 large
    var font: Int get() = int("font", 1); set(v) = setInt("font", v)
    // Security: 0 show, 1 mask, 2 hide
    var security: Int get() = int("security", 1); set(v) = setInt("security", v)

    // To Do Show Options
    var sortMode: Int get() = int("sort", 0); set(v) = setInt("sort", v)
    var showCompleted: Boolean get() = bool("showDone", true); set(v) = setBool("showDone", v)
    var onlyDue: Boolean get() = bool("onlyDue", false); set(v) = setBool("onlyDue", v)
    var recordCompletion: Boolean get() = bool("recCompl", false); set(v) = setBool("recCompl", v)
    var showDueDates: Boolean get() = bool("showDue", true); set(v) = setBool("showDue", v)
    var showPriorities: Boolean get() = bool("showPri", true); set(v) = setBool("showPri", v)
    var showCategories: Boolean get() = bool("showCat", false); set(v) = setBool("showCat", v)
    var todoCategory: Int get() = int("todoCat", -1); set(v) = setInt("todoCat", v)

    // Memo Pad
    // 0 manual, 1 alphabetic
    var memoSort: Int get() = int("memoSort", 0); set(v) = setInt("memoSort", v)
    var memoCategory: Int get() = int("memoCat", -1); set(v) = setInt("memoCat", v)

    // Last view: 0 day, 1 week, 2 month, 3 agenda
    var lastView: Int get() = int("lastView", 0); set(v) = setInt("lastView", v)

    var lastFired: Long get() = p.getLong("lastFired", 0); set(v) = p.edit().putLong("lastFired", v).apply()
}
