package com.rearman.datebook

import com.rearman.palmui.*
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * All records live in one SQLite file on the phone. The lists are cached in memory.
 * Deleted rows stay as tombstones until sync; the lists hold only live rows.
 */
class Store private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx, "datebook.db", null, 4) {

    companion object {
        @Volatile private var inst: Store? = null
        fun get(ctx: Context): Store =
            inst ?: synchronized(this) {
                inst ?: Store(ctx.applicationContext).also { inst = it }
            }
        private const val SYNC_COLS = "uid TEXT, rev INTEGER DEFAULT 0, dirty INTEGER DEFAULT 1, deleted INTEGER DEFAULT 0"
    }

    /** Called after every local write. The sync functions do not call it. */
    var onWrite: (() -> Unit)? = null

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, note TEXT, " +
                "date INTEGER, untimed INTEGER, start INTEGER, end_ INTEGER, alarm INTEGER, " +
                "adv INTEGER, unit INTEGER, rep INTEGER, every INTEGER, rep_end INTEGER, " +
                "days INTEGER, exc TEXT, priv INTEGER, $SYNC_COLS)"
        )
        db.execSQL(
            "CREATE TABLE todos (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, note TEXT, " +
                "pri INTEGER, due INTEGER, done INTEGER, cat INTEGER, priv INTEGER, $SYNC_COLS)"
        )
        db.execSQL("CREATE TABLE categories (id INTEGER PRIMARY KEY, name TEXT, app INTEGER DEFAULT 0, $SYNC_COLS)")
        db.execSQL("INSERT INTO categories (id, name, app, uid) VALUES (1, 'Business', 0, 'default-0-business')")
        db.execSQL("INSERT INTO categories (id, name, app, uid) VALUES (2, 'Personal', 0, 'default-0-personal')")
        db.execSQL("INSERT INTO categories (id, name, app, uid) VALUES (3, 'Business', 1, 'default-1-business')")
        db.execSQL("INSERT INTO categories (id, name, app, uid) VALUES (4, 'Personal', 1, 'default-1-personal')")
        db.execSQL(
            "CREATE TABLE memos (id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT, cat INTEGER, " +
                "priv INTEGER, pos INTEGER, $SYNC_COLS)"
        )
        createAddresses(db)
        db.execSQL("INSERT INTO categories (id, name, app, uid) VALUES (5, 'Business', 2, 'default-2-business')")
        db.execSQL("INSERT INTO categories (id, name, app, uid) VALUES (6, 'Personal', 2, 'default-2-personal')")
        db.execSQL("INSERT INTO categories (id, name, app, uid) VALUES (7, 'QuickList', 2, 'default-2-quicklist')")
    }

    /** The addresses table of the Address Book. */
    private fun createAddresses(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE addresses (id INTEGER PRIMARY KEY AUTOINCREMENT, last TEXT, first TEXT, title TEXT, " +
                "company TEXT, labels TEXT, phones TEXT, address TEXT, city TEXT, state TEXT, zip TEXT, " +
                "country TEXT, custom TEXT, note TEXT, show INTEGER, cat INTEGER, priv INTEGER, $SYNC_COLS)"
        )
    }

    /**
     * Version 2 adds Memo Pad: the memos table and per-app categories.
     * Existing categories get app 0 (To Do). The Memo Pad categories get new ids.
     * Version 3 adds the sync columns (uid, rev, dirty, deleted) to all tables.
     * Version 4 adds the Address Book: the addresses table and the Address categories (app 2).
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
        if (oldVersion < 3) {
            for (t in listOf("events", "todos", "memos", "categories")) {
                db.execSQL("ALTER TABLE $t ADD COLUMN uid TEXT")
                db.execSQL("ALTER TABLE $t ADD COLUMN rev INTEGER DEFAULT 0")
                db.execSQL("ALTER TABLE $t ADD COLUMN dirty INTEGER DEFAULT 1")
                db.execSQL("ALTER TABLE $t ADD COLUMN deleted INTEGER DEFAULT 0")
            }
            for (t in listOf("events", "todos", "memos")) {
                val ids = mutableListOf<Long>()
                db.rawQuery("SELECT id FROM $t", null).use { c -> while (c.moveToNext()) ids.add(c.getLong(0)) }
                for (id in ids) {
                    db.update(t, ContentValues().apply { put("uid", java.util.UUID.randomUUID().toString()) },
                        "id=?", arrayOf(id.toString()))
                }
            }
            val rows = mutableListOf<Triple<Int, String, Int>>()
            db.rawQuery("SELECT id, name, app FROM categories ORDER BY id", null).use { c ->
                while (c.moveToNext()) rows.add(Triple(c.getInt(0), c.getString(1) ?: "", c.getInt(2)))
            }
            val defaultTaken = HashSet<Pair<Int, String>>()
            for ((id, name, app) in rows) {
                val isDefault = (name == "Business" || name == "Personal") && defaultTaken.add(app to name)
                val uid = if (isDefault) "default-$app-${name.lowercase()}" else java.util.UUID.randomUUID().toString()
                db.update("categories", ContentValues().apply { put("uid", uid) }, "id=?", arrayOf(id.toString()))
            }
        }
        if (oldVersion < 4) {
            createAddresses(db)
            db.execSQL("INSERT INTO categories (id, name, app, uid) SELECT IFNULL(MAX(id), 0) + 1, 'Business', 2, 'default-2-business' FROM categories")
            db.execSQL("INSERT INTO categories (id, name, app, uid) SELECT IFNULL(MAX(id), 0) + 1, 'Personal', 2, 'default-2-personal' FROM categories")
            db.execSQL("INSERT INTO categories (id, name, app, uid) SELECT IFNULL(MAX(id), 0) + 1, 'QuickList', 2, 'default-2-quicklist' FROM categories")
        }
    }

    /** Deletes a row that never reached the server. A synced row becomes a tombstone with its text cleared, so sync can tell other devices. */
    private fun remove(table: String, id: Long) {
        val db = writableDatabase
        val args = arrayOf(id.toString())
        db.delete(table, "id=? AND rev=0", args)
        db.update(table, ContentValues().apply {
            put("deleted", 1); put("dirty", 1)
            for (c in cleared.getValue(table)) put(c, "")
        }, "id=?", args)
    }
    private val cleared = mapOf("events" to listOf("title", "note", "exc"), "todos" to listOf("title", "note"),
        "memos" to listOf("text"), "categories" to listOf("name"),
        "addresses" to listOf("last", "first", "title", "company", "phones", "address", "city", "state", "zip", "country", "custom", "note"))

    // ---- events ----

    val events: MutableList<Event> by lazy { loadEvents() }
    val todos: MutableList<Todo> by lazy { loadTodos() }
    val categories: MutableList<Category> by lazy { loadCategories(0) }
    val memoCategories: MutableList<Category> by lazy { loadCategories(1) }
    val addressCategories: MutableList<Category> by lazy { loadCategories(2) }

    fun categoriesFor(app: Int): MutableList<Category> = when (app) {
        1 -> memoCategories
        2 -> addressCategories
        else -> categories
    }

    private fun Cursor.str(c: String): String = getString(getColumnIndexOrThrow(c)) ?: ""
    private fun Cursor.lng(c: String): Long = getLong(getColumnIndexOrThrow(c))
    private fun Cursor.int(c: String): Int = getInt(getColumnIndexOrThrow(c))

    private fun loadEvents(): MutableList<Event> {
        val list = mutableListOf<Event>()
        readableDatabase.rawQuery("SELECT * FROM events WHERE deleted=0", null).use { c ->
            while (c.moveToNext()) {
                list.add(
                    Event(
                        id = c.lng("id"), title = c.str("title"), note = c.str("note"),
                        date = c.lng("date"), untimed = c.int("untimed") != 0,
                        start = c.int("start"), end = c.int("end_"), alarm = c.int("alarm") != 0,
                        alarmAdvance = c.int("adv"), alarmUnit = c.int("unit"),
                        repeat = c.int("rep"), every = c.int("every"), repeatEnd = c.lng("rep_end"),
                        weekDays = c.int("days"), exceptions = parseExc(c.str("exc")),
                        isPrivate = c.int("priv") != 0, uid = c.str("uid")
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
        put("uid", e.uid); put("dirty", 1)
    }

    fun save(e: Event) {
        if (e.uid.isEmpty()) e.uid = java.util.UUID.randomUUID().toString()
        if (e.id == 0L) {
            e.id = writableDatabase.insert("events", null, eventValues(e))
            events.add(e)
        } else {
            writableDatabase.update("events", eventValues(e), "id=?", arrayOf(e.id.toString()))
            val i = events.indexOfFirst { it.id == e.id }
            if (i >= 0) events[i] = e else events.add(e)
        }
        onWrite?.invoke()
    }

    fun delete(e: Event) {
        remove("events", e.id)
        events.removeAll { it.id == e.id }
        onWrite?.invoke()
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
        readableDatabase.rawQuery("SELECT * FROM todos WHERE deleted=0", null).use { c ->
            while (c.moveToNext()) {
                list.add(
                    Todo(
                        id = c.lng("id"), title = c.str("title"), note = c.str("note"),
                        priority = c.int("pri"), due = c.lng("due"), done = c.int("done") != 0,
                        category = c.int("cat"), isPrivate = c.int("priv") != 0, uid = c.str("uid")
                    )
                )
            }
        }
        return list
    }

    private fun todoValues(t: Todo) = ContentValues().apply {
        put("title", t.title); put("note", t.note); put("pri", t.priority); put("due", t.due)
        put("done", if (t.done) 1 else 0); put("cat", t.category); put("priv", if (t.isPrivate) 1 else 0)
        put("uid", t.uid); put("dirty", 1)
    }

    fun save(t: Todo) {
        if (t.uid.isEmpty()) t.uid = java.util.UUID.randomUUID().toString()
        if (t.id == 0L) {
            t.id = writableDatabase.insert("todos", null, todoValues(t))
            todos.add(t)
        } else {
            writableDatabase.update("todos", todoValues(t), "id=?", arrayOf(t.id.toString()))
            val i = todos.indexOfFirst { it.id == t.id }
            if (i >= 0) todos[i] = t else todos.add(t)
        }
        onWrite?.invoke()
    }

    fun delete(t: Todo) {
        remove("todos", t.id)
        todos.removeAll { it.id == t.id }
        onWrite?.invoke()
    }

    // ==== memos ====

    val memos: MutableList<Memo> by lazy { loadMemos() }

    private fun loadMemos(): MutableList<Memo> {
        val list = mutableListOf<Memo>()
        readableDatabase.rawQuery("SELECT id, text, cat, priv, pos, uid FROM memos WHERE deleted=0 ORDER BY pos", null).use { c ->
            while (c.moveToNext()) {
                list.add(
                    Memo(
                        id = c.lng("id"), text = c.str("text"), category = c.int("cat"),
                        isPrivate = c.int("priv") != 0, pos = c.int("pos"), uid = c.str("uid")
                    )
                )
            }
        }
        return list
    }

    private fun memoValues(m: Memo) = ContentValues().apply {
        put("text", m.text); put("cat", m.category); put("priv", if (m.isPrivate) 1 else 0); put("pos", m.pos)
        put("uid", m.uid); put("dirty", 1)
    }

    fun save(m: Memo) {
        if (m.uid.isEmpty()) m.uid = java.util.UUID.randomUUID().toString()
        if (m.id == 0L) {
            m.pos = if (memos.isEmpty()) 0 else memos.maxOf { it.pos } + 1
            m.id = writableDatabase.insert("memos", null, memoValues(m))
            memos.add(m)
        } else {
            writableDatabase.update("memos", memoValues(m), "id=?", arrayOf(m.id.toString()))
            val i = memos.indexOfFirst { it.id == m.id }
            if (i >= 0) memos[i] = m else memos.add(m)
        }
        onWrite?.invoke()
    }

    fun delete(m: Memo) {
        remove("memos", m.id)
        memos.removeAll { it.id == m.id }
        onWrite?.invoke()
    }

    /** Writes only the pos column of each memo, in one transaction. */
    fun savePositions(changed: List<Memo>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (m in changed) {
                db.update("memos", ContentValues().apply { put("pos", m.pos); put("dirty", 1) }, "id=?", arrayOf(m.id.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        onWrite?.invoke()
    }

    // ==== addresses ====
    val addresses: MutableList<Address> by lazy { loadAddresses() }

    private fun loadAddresses(): MutableList<Address> {
        val list = mutableListOf<Address>()
        readableDatabase.rawQuery("SELECT * FROM addresses WHERE deleted=0 ORDER BY id", null).use { c ->
            while (c.moveToNext()) {
                list.add(
                    Address(
                        id = c.lng("id"), lastName = c.str("last"), firstName = c.str("first"),
                        title = c.str("title"), company = c.str("company"),
                        phoneLabels = parseLabels(c.str("labels")), phones = jsonStrings(c.str("phones"), 5),
                        address = c.str("address"), city = c.str("city"), state = c.str("state"),
                        zip = c.str("zip"), country = c.str("country"), custom = jsonStrings(c.str("custom"), 4),
                        note = c.str("note"), showPhone = c.int("show"), category = c.int("cat"),
                        isPrivate = c.int("priv") != 0, uid = c.str("uid")
                    )
                )
            }
        }
        return list
    }

    /** Comma-separated label indexes. A missing entry takes its position, as in a new record. */
    private fun parseLabels(s: String): List<Int> {
        val parts = s.split(',')
        return List(5) { i -> parts.getOrNull(i)?.trim()?.toIntOrNull() ?: i }
    }

    /** A JSON array of strings, padded or cut to [n] entries. */
    private fun jsonStrings(s: String, n: Int): List<String> = JSONArray(s.ifBlank { "[]" }).strings(n)

    private fun JSONArray?.strings(n: Int): List<String> = List(n) { i -> this?.optString(i, "") ?: "" }
    private fun JSONArray?.ints(n: Int): List<Int> =
        List(n) { i -> (this?.optInt(i, i) ?: i).coerceIn(0, Address.LABELS.size - 1) }

    private fun addressValues(a: Address) = ContentValues().apply {
        put("last", a.lastName); put("first", a.firstName); put("title", a.title); put("company", a.company)
        put("labels", a.phoneLabels.joinToString(",")); put("phones", JSONArray(a.phones).toString())
        put("address", a.address); put("city", a.city); put("state", a.state); put("zip", a.zip)
        put("country", a.country); put("custom", JSONArray(a.custom).toString()); put("note", a.note)
        put("show", a.showPhone); put("cat", a.category); put("priv", if (a.isPrivate) 1 else 0)
        put("uid", a.uid); put("dirty", 1)
    }

    fun save(a: Address) {
        val all = addresses // Loaded before the insert, so the new row is not added twice.
        if (a.uid.isEmpty()) a.uid = java.util.UUID.randomUUID().toString()
        if (a.id == 0L) {
            a.id = writableDatabase.insert("addresses", null, addressValues(a))
            all.add(a)
        } else {
            writableDatabase.update("addresses", addressValues(a), "id=?", arrayOf(a.id.toString()))
            val i = all.indexOfFirst { it.id == a.id }
            if (i >= 0) all[i] = a else all.add(a)
        }
        onWrite?.invoke()
    }

    fun delete(a: Address) {
        remove("addresses", a.id)
        addresses.removeAll { it.id == a.id }
        onWrite?.invoke()
    }

    fun address(id: Long): Address? = addresses.firstOrNull { it.id == id }

    /**
     * Adds [list] as Address Book records. A category name is matched to an Address category
     * (ignoring case), else the record goes to Unfiled. A record equal to one on the device, or
     * to one added earlier in [list], is skipped. Returns (added, skipped).
     */
    fun addAddresses(list: List<VCard.Parsed>): Pair<Int, Int> {
        val have = addresses.mapTo(HashSet()) { it.dupKey() }
        var added = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (p in list) {
                val cat = addressCategories.firstOrNull { it.name.equals(p.categoryName, true) }?.id ?: 0
                val a = p.address.copy(id = 0, category = cat, uid = "")
                val key = a.dupKey()
                if (key !in have) {
                    have.add(key)
                    save(a)
                    added++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return added to list.size - added
    }

    // ---- categories ----

    private fun loadCategories(app: Int): MutableList<Category> {
        val list = mutableListOf<Category>()
        readableDatabase.rawQuery("SELECT * FROM categories WHERE app=? AND deleted=0 ORDER BY id", arrayOf(app.toString())).use { c ->
            while (c.moveToNext()) list.add(Category(c.int("id"), c.str("name"), c.int("app"), uid = c.str("uid")))
        }
        sortCategories(list)
        return list
    }

    fun categoryName(id: Int): String =
        if (id == 0) "Unfiled" else (categories + memoCategories + addressCategories).firstOrNull { it.id == id }?.name ?: "Unfiled"

    fun addCategory(name: String, app: Int = 0): Category {
        val list = categoriesFor(app)
        val id = writableDatabase.rawQuery("SELECT IFNULL(MAX(id), 0) + 1 FROM categories", null).use { it.moveToFirst(); it.getInt(0) }
        val cat = Category(id, name, app, uid = java.util.UUID.randomUUID().toString())
        writableDatabase.insert("categories", null, ContentValues().apply {
            put("id", id); put("name", name); put("app", app); put("uid", cat.uid); put("dirty", 1)
        })
        list.add(cat)
        sortCategories(list)
        onWrite?.invoke()
        return cat
    }

    fun renameCategory(cat: Category, name: String) {
        cat.name = name
        sortCategories(categoriesFor(cat.app))
        writableDatabase.update("categories", ContentValues().apply { put("name", name); put("dirty", 1) },
            "id=?", arrayOf(cat.id.toString()))
        onWrite?.invoke()
    }

    /** Deletes a category. Its items move to Unfiled, as on the Palm. */
    fun deleteCategory(cat: Category) {
        remove("categories", cat.id.toLong())
        if (cat.app == 1) {
            writableDatabase.execSQL("UPDATE memos SET cat=0, dirty=1 WHERE cat=${cat.id} AND deleted=0")
            memos.filter { it.category == cat.id }.forEach { it.category = 0 }
            memoCategories.removeAll { it.id == cat.id }
        } else if (cat.app == 2) {
            writableDatabase.execSQL("UPDATE addresses SET cat=0, dirty=1 WHERE cat=${cat.id} AND deleted=0")
            addresses.filter { it.category == cat.id }.forEach { it.category = 0 }
            addressCategories.removeAll { it.id == cat.id }
        } else {
            writableDatabase.execSQL("UPDATE todos SET cat=0, dirty=1 WHERE cat=${cat.id} AND deleted=0")
            todos.filter { it.category == cat.id }.forEach { it.category = 0 }
            categories.removeAll { it.id == cat.id }
        }
        onWrite?.invoke()
    }

    // ---- sync ----

    private val tables = listOf("categories", "events", "todos", "memos", "addresses")

    /** Loads the in-memory lists now, so that a list loaded after a sync write does not add a row twice. */
    private fun loadLists() {
        listOf(events, todos, memos, addresses, categories, memoCategories, addressCategories)
    }

    /** Dirty rows of all five tables, categories first. */
    fun dirtyRows(): List<SyncRow> = tables.flatMap { rowsWhere(it, "dirty=1", emptyArray()) }

    /** The row with [uid] in any table, live or tombstone. */
    fun syncRow(uid: String): SyncRow? =
        tables.firstNotNullOfOrNull { rowsWhere(it, "uid=?", arrayOf(uid)).firstOrNull() }

    private fun rowsWhere(table: String, where: String, args: Array<String>): List<SyncRow> {
        val out = mutableListOf<SyncRow>()
        readableDatabase.rawQuery("SELECT id, uid, rev, dirty, deleted FROM $table WHERE $where", args).use { c ->
            while (c.moveToNext()) {
                out.add(SyncRow(table, c.getLong(0), c.getString(1) ?: "", c.getLong(2), c.getInt(3) != 0, c.getInt(4) != 0))
            }
        }
        return out
    }

    private fun idOf(table: String, uid: String): Long? =
        writableDatabase.rawQuery("SELECT id FROM $table WHERE uid=?", arrayOf(uid)).use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }

    /** Sets the state of a copy that came from the server: clean, live, at [rev]. */
    private fun ContentValues.serverState(rev: Long) {
        put("dirty", 0); put("deleted", 0); put("rev", rev)
    }

    /** Marks every live row dirty, so the next round sends it again. The rev stays, so the server takes it as an edit. */
    fun markAllForUpload() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            tables.forEach { db.execSQL("UPDATE $it SET dirty=1 WHERE deleted=0") }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Records revision [rev] of [uid]. A clean row is no longer dirty, and a clean tombstone is removed. */
    fun markSynced(table: String, uid: String, rev: Long, clean: Boolean) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (clean) db.delete(table, "uid=? AND deleted=1", arrayOf(uid))
            db.update(table, ContentValues().apply {
                put("rev", rev)
                if (clean) put("dirty", 0)
            }, "uid=?", arrayOf(uid))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Stores a server copy of [e] at [rev]. A row with the same uid keeps its local id. */
    fun putRemote(e: Event, rev: Long) {
        loadLists()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val cv = eventValues(e).apply { serverState(rev) }
            val old = idOf("events", e.uid)
            if (old != null) {
                db.update("events", cv, "id=?", arrayOf(old.toString()))
                e.id = old
                val i = events.indexOfFirst { it.id == old }
                if (i >= 0) events[i] = e else events.add(e)
            } else {
                e.id = db.insert("events", null, cv)
                events.add(e)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Stores a server copy of [t] at [rev]. A row with the same uid keeps its local id. */
    fun putRemote(t: Todo, rev: Long) {
        loadLists()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val cv = todoValues(t).apply { serverState(rev) }
            val old = idOf("todos", t.uid)
            if (old != null) {
                db.update("todos", cv, "id=?", arrayOf(old.toString()))
                t.id = old
                val i = todos.indexOfFirst { it.id == old }
                if (i >= 0) todos[i] = t else todos.add(t)
            } else {
                t.id = db.insert("todos", null, cv)
                todos.add(t)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Stores a server copy of [m] at [rev]. A row with the same uid keeps its local id. */
    fun putRemote(m: Memo, rev: Long) {
        loadLists()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val cv = memoValues(m).apply { serverState(rev) }
            val old = idOf("memos", m.uid)
            if (old != null) {
                db.update("memos", cv, "id=?", arrayOf(old.toString()))
                m.id = old
                val i = memos.indexOfFirst { it.id == old }
                if (i >= 0) memos[i] = m else memos.add(m)
            } else {
                m.id = db.insert("memos", null, cv)
                memos.add(m)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Stores a server copy of [a] at [rev]. A row with the same uid keeps its local id. */
    fun putRemote(a: Address, rev: Long) {
        loadLists()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val cv = addressValues(a).apply { serverState(rev) }
            val old = idOf("addresses", a.uid)
            if (old != null) {
                db.update("addresses", cv, "id=?", arrayOf(old.toString()))
                a.id = old
                val i = addresses.indexOfFirst { it.id == old }
                if (i >= 0) addresses[i] = a else addresses.add(a)
            } else {
                a.id = db.insert("addresses", null, cv)
                addresses.add(a)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Stores a server copy of category [c] at [rev], in the list of its app. */
    fun putRemote(c: Category, rev: Long) {
        loadLists()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val old = idOf("categories", c.uid)
            val id = old?.toInt()
                ?: db.rawQuery("SELECT IFNULL(MAX(id), 0) + 1 FROM categories", null).use { it.moveToFirst(); it.getInt(0) }
            val cv = ContentValues().apply {
                put("name", c.name); put("app", c.app); put("uid", c.uid); serverState(rev)
            }
            if (old != null) {
                db.update("categories", cv, "id=?", arrayOf(id.toString()))
            } else {
                cv.put("id", id)
                db.insert("categories", null, cv)
            }
            val mine = categoriesFor(c.app)
            listOf(categories, memoCategories, addressCategories).filter { it !== mine }
                .forEach { list -> list.removeAll { it.uid == c.uid } }
            val stored = Category(id, c.name, c.app, c.uid)
            val i = mine.indexOfFirst { it.uid == c.uid }
            if (i >= 0) mine[i] = stored else mine.add(stored)
            sortCategories(mine)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Removes a row that another device deleted. A category also moves its items to Unfiled, without marking them dirty. */
    fun dropRemote(table: String, uid: String) {
        loadLists()
        val db = writableDatabase
        val id = idOf(table, uid) ?: return
        db.beginTransaction()
        try {
            if (table == "categories") {
                val app = db.rawQuery("SELECT app FROM categories WHERE id=?", arrayOf(id.toString())).use { it.moveToFirst(); it.getInt(0) }
                db.delete(table, "id=?", arrayOf(id.toString()))
                when (app) {
                    1 -> {
                        db.execSQL("UPDATE memos SET cat=0 WHERE cat=$id")
                        memos.filter { it.category == id.toInt() }.forEach { it.category = 0 }
                    }
                    2 -> {
                        db.execSQL("UPDATE addresses SET cat=0 WHERE cat=$id")
                        addresses.filter { it.category == id.toInt() }.forEach { it.category = 0 }
                    }
                    else -> {
                        db.execSQL("UPDATE todos SET cat=0 WHERE cat=$id")
                        todos.filter { it.category == id.toInt() }.forEach { it.category = 0 }
                    }
                }
                categories.removeAll { it.uid == uid }
                memoCategories.removeAll { it.uid == uid }
                addressCategories.removeAll { it.uid == uid }
            } else {
                db.delete(table, "id=?", arrayOf(id.toString()))
                when (table) {
                    "events" -> events.removeAll { it.id == id }
                    "todos" -> todos.removeAll { it.id == id }
                    "addresses" -> addresses.removeAll { it.id == id }
                    else -> memos.removeAll { it.id == id }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Makes every live row due for upload (rev 0, dirty) and removes the tombstones. */
    fun resetSync() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (t in tables) {
                db.execSQL("DELETE FROM $t WHERE deleted=1")
                db.execSQL("UPDATE $t SET rev=0, dirty=1")
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** The uid of category [id], or "" for 0 and unknown ids. */
    fun categoryUid(id: Int): String =
        if (id == 0) "" else (categories + memoCategories + addressCategories).firstOrNull { it.id == id }?.uid ?: ""

    /** The id of the category with [uid] in the list of [app], or 0 when blank or unknown. */
    fun categoryIdForUid(uid: String, app: Int): Int =
        if (uid.isBlank()) 0 else categoriesFor(app).firstOrNull { it.uid == uid }?.id ?: 0

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

    fun addressJson(a: Address) = JSONObject().apply {
        put("lastName", a.lastName); put("firstName", a.firstName); put("title", a.title); put("company", a.company)
        put("phoneLabels", JSONArray(a.phoneLabels)); put("phones", JSONArray(a.phones))
        put("address", a.address); put("city", a.city); put("state", a.state); put("zip", a.zip)
        put("country", a.country); put("custom", JSONArray(a.custom)); put("note", a.note)
        put("showPhone", a.showPhone); put("category", categoryName(a.category)); put("private", a.isPrivate)
    }

    fun exportJson(evs: List<Event> = events, tds: List<Todo> = todos, mms: List<Memo> = memos, ads: List<Address> = addresses): String {
        val root = JSONObject()
        root.put("format", "datebook-backup-1")
        root.put("categories", JSONArray(categories.map { it.name }))
        root.put("memoCategories", JSONArray(memoCategories.map { it.name }))
        root.put("addressCategories", JSONArray(addressCategories.map { it.name }))
        root.put("events", JSONArray(evs.map { eventJson(it) }))
        root.put("todos", JSONArray(tds.map { todoJson(it) }))
        root.put("memos", JSONArray(mms.map { memoJson(it) }))
        root.put("addresses", JSONArray(ads.map { addressJson(it) }))
        return root.toString(1)
    }

    /** Record counts of one import. */
    data class ImportCount(val events: Int, val todos: Int, val memos: Int, val skipped: Int, val addresses: Int = 0)

    /**
     * Adds the records in a backup file. A record equal to one already on the device is a duplicate
     * and is skipped. Equal records in the file itself are all added.
     */
    fun importJson(text: String): ImportCount {
        val root = JSONObject(text)
        val db = writableDatabase
        val haveEvents = events.mapTo(HashSet()) { it.dupKey() }
        val haveTodos = todos.mapTo(HashSet()) { it.dupKey() }
        val haveMemos = memos.mapTo(HashSet()) { it.dupKey() }
        val haveAddresses = addresses.mapTo(HashSet()) { it.dupKey() }
        var ne = 0
        var nt = 0
        var nm = 0
        var na = 0
        var ns = 0
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
            root.optJSONArray("addressCategories")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val n = arr.getString(i)
                    if (addressCategories.none { it.name.equals(n, true) }) addCategory(n, 2)
                }
            }
            root.optJSONArray("events")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val exc = mutableSetOf<Long>()
                    o.optJSONArray("exceptions")?.let { x -> for (k in 0 until x.length()) exc.add(x.getLong(k)) }
                    val e = Event(
                        title = o.optString("title"), note = o.optString("note"),
                        date = o.getLong("date"), untimed = o.optBoolean("untimed"),
                        start = o.optInt("start", 480), end = o.optInt("end", 540),
                        alarm = o.optBoolean("alarm"), alarmAdvance = o.optInt("alarmAdvance", 5),
                        alarmUnit = o.optInt("alarmUnit"), repeat = o.optInt("repeat"),
                        every = o.optInt("every", 1), repeatEnd = o.optLong("repeatEnd", -1),
                        weekDays = o.optInt("weekDays"), exceptions = exc,
                        isPrivate = o.optBoolean("private")
                    )
                    if (e.dupKey() in haveEvents) { ns++; continue }
                    save(e)
                    ne++
                }
            }
            root.optJSONArray("todos")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val catName = o.optString("category", "Unfiled")
                    val cat = categories.firstOrNull { it.name.equals(catName, true) }?.id ?: 0
                    val t = Todo(
                        title = o.optString("title"), note = o.optString("note"),
                        priority = o.optInt("priority", 1), due = o.optLong("due", -1),
                        done = o.optBoolean("done"), category = cat,
                        isPrivate = o.optBoolean("private")
                    )
                    if (t.dupKey() in haveTodos) { ns++; continue }
                    save(t)
                    nt++
                }
            }
            root.optJSONArray("memos")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val catName = o.optString("category", "Unfiled")
                    val cat = memoCategories.firstOrNull { it.name.equals(catName, true) }?.id ?: 0
                    val m = Memo(
                        text = o.optString("text"), category = cat,
                        isPrivate = o.optBoolean("private")
                    )
                    if (m.dupKey() in haveMemos) { ns++; continue }
                    save(m)
                    nm++
                }
            }
            root.optJSONArray("addresses")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val catName = o.optString("category", "Unfiled")
                    val cat = addressCategories.firstOrNull { it.name.equals(catName, true) }?.id ?: 0
                    val a = Address(
                        lastName = o.optString("lastName"), firstName = o.optString("firstName"),
                        title = o.optString("title"), company = o.optString("company"),
                        phoneLabels = o.optJSONArray("phoneLabels").ints(5), phones = o.optJSONArray("phones").strings(5),
                        address = o.optString("address"), city = o.optString("city"), state = o.optString("state"),
                        zip = o.optString("zip"), country = o.optString("country"),
                        custom = o.optJSONArray("custom").strings(4), note = o.optString("note"),
                        showPhone = o.optInt("showPhone").coerceIn(0, 4), category = cat,
                        isPrivate = o.optBoolean("private")
                    )
                    if (a.dupKey() in haveAddresses) { ns++; continue }
                    save(a)
                    na++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return ImportCount(ne, nt, nm, ns, na)
    }

    /** Adds [list], less the events that are already on the device. Returns (added, skipped). */
    fun addEvents(list: List<Event>): Pair<Int, Int> {
        val have = events.mapTo(HashSet()) { it.dupKey() }
        var added = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (e in list) if (e.dupKey() !in have) { save(e); added++ }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return added to list.size - added
    }
}

/** Settings from the Preferences, Display Options, Show Options, Font and Security dialogs. */
class Prefs(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    private fun int(k: String, d: Int) = p.getInt(k, d)
    private fun bool(k: String, d: Boolean) = p.getBoolean(k, d)
    private fun setInt(k: String, v: Int) = p.edit().putInt(k, v).apply()
    private fun setBool(k: String, v: Boolean) = p.edit().putBoolean(k, v).apply()
    private fun str(k: String, d: String) = p.getString(k, d) ?: d
    private fun setStr(k: String, v: String) = p.edit().putString(k, v).apply()
    private fun lng(k: String, d: Long) = p.getLong(k, d)
    private fun setLng(k: String, v: Long) = p.edit().putLong(k, v).apply()

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

    // ShortCuts: a JSON array of {"name","text"}. No saved list (or bad data) gives the Palm defaults.
    var shortCuts: List<ShortCut>
        get() {
            val json = p.getString("shortCuts", null) ?: return ShortCuts.defaults
            return try {
                val a = JSONArray(json)
                List(a.length()) { i ->
                    a.getJSONObject(i).let { ShortCut(it.getString("name"), it.getString("text")) }
                }
            } catch (e: Exception) {
                ShortCuts.defaults
            }
        }
        set(v) {
            writeShortCuts(v)
            setBool("scDirty", true)
        }

    // Sync (see SyncEngine). syncSince is the last server rev pulled; syncLast is the last sync message.
    var syncOn: Boolean get() = bool("syncOn", false); set(v) = setBool("syncOn", v)
    var syncUrl: String get() = str("syncUrl", "https://sync.earman.xyz"); set(v) = setStr("syncUrl", v)
    var syncSince: Long get() = lng("syncSince", 0); set(v) = setLng("syncSince", v)
    var syncLast: String get() = str("syncLast", ""); set(v) = setStr("syncLast", v)
    // Record format of the uploads: 1 long keys, 2 short keys. Below 2, every live record is sent once.
    var syncFormat: Int get() = int("syncFormat", 1); set(v) = setInt("syncFormat", v)

    // Address Book
    // Address List category: -1 is All. Address List order: 0 Last Name, First Name; 1 Company, Last Name.
    var addressCategory: Int get() = int("addrCat", -1); set(v) = setInt("addrCat", v)
    var addressRemember: Boolean get() = bool("addrRemember", false); set(v) = setBool("addrRemember", v)
    var addressListBy: Int get() = int("addrListBy", 0); set(v) = setInt("addrListBy", v)
    var businessCard: Long get() = lng("businessCard", 0); set(v) = setLng("businessCard", v)

    // Custom field names: a JSON array of 4 strings. A missing or bad list gives Custom 1 to Custom 4.
    private val customDefaults = List(4) { "Custom ${it + 1}" }
    var customNames: List<String>
        get() {
            val json = p.getString("customNames", null) ?: return customDefaults
            return try {
                val a = JSONArray(json)
                List(4) { i -> a.optString(i, customDefaults[i]) }
            } catch (e: Exception) {
                customDefaults
            }
        }
        set(v) = setStr("customNames", JSONArray(List(4) { i -> v.getOrElse(i) { customDefaults[i] } }).toString())

    // ShortCuts sync state: dirty when edited on this phone; rev is the server rev of the list.
    var shortCutsDirty: Boolean get() = bool("scDirty", false); set(v) = setBool("scDirty", v)
    var shortCutsRev: Long get() = lng("scRev", 0); set(v) = setLng("scRev", v)

    /** Stores a ShortCuts list that came from the server, at [rev], as clean. */
    fun setShortCutsFromSync(list: List<ShortCut>, rev: Long) {
        writeShortCuts(list)
        setLng("scRev", rev)
        setBool("scDirty", false)
    }

    private fun writeShortCuts(v: List<ShortCut>) {
        val a = JSONArray()
        v.forEach { a.put(JSONObject().put("name", it.name).put("text", it.text)) }
        p.edit().putString("shortCuts", a.toString()).apply()
    }
}
