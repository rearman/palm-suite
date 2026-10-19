package com.rearman.palmdesktop

import com.rearman.datebook.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * The Palm Desktop record store. It keeps the records in memory and saves them to one JSON file
 * after each write. A synced record that was deleted stays as a tombstone in [meta] until sync
 * removes it. Tombstones are not in the record lists.
 */
class DesktopStore private constructor(private val file: File) : SyncStore {

	companion object {
		const val MAX_CATEGORIES = 15
		private val tables = listOf("categories", "events", "todos", "memos")

		/** Loads the store from [file]. A missing file gives a new store with the 4 default categories. */
		fun open(file: File): DesktopStore {
			val store = DesktopStore(file)
			if (file.exists()) store.load(JSONObject(file.readText(Charsets.UTF_8)))
			else store.addDefaultCategories()
			return store
		}
	}

	/** Sync state of one record. [deleted] marks a tombstone. */
	private class Meta(var uid: String, var rev: Long, var dirty: Boolean, var deleted: Boolean)

	/** Sync state by table, then by local id. */
	private val meta: Map<String, MutableMap<Long, Meta>> = tables.associateWith { mutableMapOf<Long, Meta>() }

	val events = mutableListOf<Event>()
	val todos = mutableListOf<Todo>()
	val memos = mutableListOf<Memo>()
	val categories = mutableListOf<Category>()      // To Do (app 0)
	val memoCategories = mutableListOf<Category>()  // Memo Pad (app 1)

	/** Called after every local write. The SyncStore writes do not call it. */
	var onChange: (() -> Unit)? = null

	private var shortCutList: List<Pair<String, String>> = emptyList()
	private var since = 0L
	private var scRev = 0L
	private var scDirty = false

	override val shortCuts: List<Pair<String, String>> get() = shortCutList

	override var shortCutsDirty: Boolean
		get() = scDirty
		set(v) {
			scDirty = v
			persist()
		}

	override var shortCutsRev: Long
		get() = scRev
		set(v) {
			scRev = v
			persist()
		}

	override var syncSince: Long
		get() = since
		set(v) {
			since = v
			persist()
		}

	// lookups

	fun categoriesFor(app: Int): MutableList<Category> = if (app == 1) memoCategories else categories

	fun categoryName(id: Int): String =
		(categories + memoCategories).firstOrNull { it.id == id }?.name ?: "Unfiled"

	override fun categoryUid(id: Int): String =
		if (id == 0) "" else (categories + memoCategories).firstOrNull { it.id == id }?.uid ?: ""

	override fun categoryIdForUid(uid: String, app: Int): Int =
		if (uid.isBlank()) 0 else categoriesFor(app).firstOrNull { it.uid == uid }?.id ?: 0

	/** Occurrences on one day: untimed first, then by start time, then by end time. */
	fun dayEvents(day: Long): List<Occ> =
		events.filter { Recur.occursOn(it, day) }
			.sortedWith(compareBy<Event>({ !it.untimed }, { it.start }, { it.end }))
			.map { Occ(it, day) }

	/** Events, To Dos and Memos whose text contains [text], ignoring case. */
	fun find(text: String): List<Any> {
		val out = mutableListOf<Any>()
		events.filterTo(out) { it.title.contains(text, true) || it.note.contains(text, true) }
		todos.filterTo(out) { it.title.contains(text, true) || it.note.contains(text, true) }
		memos.filterTo(out) { it.text.contains(text, true) }
		return out
	}

	// local writes

	fun save(e: Event) {
		putEvent(e)
		changed()
	}

	fun save(t: Todo) {
		putTodo(t)
		changed()
	}

	fun save(m: Memo) {
		putMemo(m)
		changed()
	}

	fun delete(e: Event) {
		removeLocal("events", e.id)
		events.removeAll { it.id == e.id }
		changed()
	}

	fun delete(t: Todo) {
		removeLocal("todos", t.id)
		todos.removeAll { it.id == t.id }
		changed()
	}

	fun delete(m: Memo) {
		removeLocal("memos", m.id)
		memos.removeAll { it.id == m.id }
		changed()
	}

	fun addCategory(name: String, app: Int): Category? {
		val cat = newCategory(name, app) ?: return null
		changed()
		return cat
	}

	/** Adds a dirty category and does not save. Null when the app has [MAX_CATEGORIES] categories. */
	private fun newCategory(name: String, app: Int): Category? {
		val list = categoriesFor(app)
		if (list.size >= MAX_CATEGORIES) return null
		val id = nextId("categories").toInt()
		val cat = Category(id, name, app, UUID.randomUUID().toString())
		markDirty("categories", id.toLong(), cat.uid)
		list.add(cat)
		return cat
	}

	fun renameCategory(cat: Category, name: String) {
		cat.name = name
		markDirty("categories", cat.id.toLong(), cat.uid)
		changed()
	}

	/** Deletes a category. Its items move to Unfiled, and they are marked dirty. */
	fun deleteCategory(cat: Category) {
		removeLocal("categories", cat.id.toLong())
		categoriesFor(cat.app).removeAll { it.id == cat.id }
		moveToUnfiled(cat.id, dirty = true)
		changed()
	}

	/** Deletes the events that ended before [before]. Returns the count. */
	fun purgeEvents(before: Long): Int {
		val old = events.filter { Recur.lastDay(it) < before }
		for (e in old) removeLocal("events", e.id)
		events.removeAll { Recur.lastDay(it) < before }
		if (old.isNotEmpty()) changed()
		return old.size
	}

	/** Deletes the completed To Do items. Returns the count. */
	fun purgeTodos(): Int {
		val done = todos.filter { it.done }
		for (t in done) removeLocal("todos", t.id)
		todos.removeAll { it.done }
		if (done.isNotEmpty()) changed()
		return done.size
	}

	/** Replaces the ShortCuts list after a local edit. The list syncs on the next run. */
	fun setShortCuts(list: List<Pair<String, String>>) {
		shortCutList = list.toList()
		scDirty = true
		changed()
	}

	// SyncStore

	override fun dirtyRows(): List<SyncRow> = tables.flatMap { table ->
		meta.getValue(table).toSortedMap().filter { it.value.dirty }.map { rowOf(table, it.key, it.value) }
	}

	override fun syncRow(uid: String): SyncRow? =
		tables.firstNotNullOfOrNull { table ->
			meta.getValue(table).entries.firstOrNull { it.value.uid == uid }
				?.let { rowOf(table, it.key, it.value) }
		}

	override fun record(row: SyncRow): Any? =
		if (row.deleted) null else liveRecord(row.table, row.id)

	override fun markSynced(table: String, uid: String, rev: Long, clean: Boolean) {
		val id = idOf(table, uid) ?: return
		val map = meta.getValue(table)
		val m = map.getValue(id)
		if (clean && m.deleted) {
			map.remove(id)
		} else {
			m.rev = rev
			if (clean) m.dirty = false
		}
		persist()
	}

	override fun putRemote(e: Event, rev: Long) {
		e.id = idFor("events", e.uid)
		putInList(events, e) { it.id == e.id }
		setServer("events", e.id, e.uid, rev)
		persist()
	}

	override fun putRemote(t: Todo, rev: Long) {
		t.id = idFor("todos", t.uid)
		putInList(todos, t) { it.id == t.id }
		setServer("todos", t.id, t.uid, rev)
		persist()
	}

	override fun putRemote(m: Memo, rev: Long) {
		m.id = idFor("memos", m.uid)
		putInList(memos, m) { it.id == m.id }
		setServer("memos", m.id, m.uid, rev)
		persist()
	}

	override fun putRemote(c: Category, rev: Long) {
		val id = idFor("categories", c.uid).toInt()
		categories.removeAll { it.uid == c.uid }
		memoCategories.removeAll { it.uid == c.uid }
		categoriesFor(c.app).apply {
			add(Category(id, c.name, c.app, c.uid))
			sortBy { it.id }
		}
		setServer("categories", id.toLong(), c.uid, rev)
		persist()
	}

	/** Removes a row that another device deleted. A category also moves its items to Unfiled, without marking them dirty. */
	override fun dropRemote(table: String, uid: String) {
		val id = idOf(table, uid) ?: return
		meta.getValue(table).remove(id)
		when (table) {
			"categories" -> {
				categories.removeAll { it.id.toLong() == id }
				memoCategories.removeAll { it.id.toLong() == id }
				moveToUnfiled(id.toInt(), dirty = false)
			}
			"events" -> events.removeAll { it.id == id }
			"todos" -> todos.removeAll { it.id == id }
			else -> memos.removeAll { it.id == id }
		}
		persist()
	}

	/** Keeps [record] as a new dirty record with a new uid. False when the categories are full. */
	override fun saveCopy(record: Any): Boolean {
		when (record) {
			is Event -> putEvent(record.deepCopy().apply { id = 0; uid = "" })
			is Todo -> putTodo(record.copy(id = 0, uid = ""))
			is Memo -> putMemo(record.copy(id = 0, uid = ""))
			is Category -> {
				val added = newCategory(record.name, record.app) != null
				if (added) persist()
				return added
			}
			else -> return false
		}
		persist()
		return true
	}

	override fun setShortCutsFromSync(list: List<Pair<String, String>>, rev: Long) {
		shortCutList = list.toList()
		scRev = rev
		scDirty = false
		persist()
	}

	// file

	/** Writes the whole store to the file: a temp file, then an atomic move over the old file. */
	fun persist() {
		val root = JSONObject()
			.put("version", 1)
			.put("since", since)
			.put("scRev", scRev)
			.put("scDirty", scDirty)
			.put("shortcuts", JSONArray(shortCutList.map { JSONObject().put("name", it.first).put("text", it.second) }))
			.put("rows", JSONArray(rowsJson()))
		file.absoluteFile.parentFile?.mkdirs()
		val tmp = File(file.path + ".tmp")
		tmp.writeText(root.toString(), Charsets.UTF_8)
		Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
	}

	private fun load(root: JSONObject) {
		since = root.optLong("since")
		scRev = root.optLong("scRev")
		scDirty = root.optBoolean("scDirty")
		val sc = root.optJSONArray("shortcuts") ?: JSONArray()
		shortCutList = (0 until sc.length()).map { i ->
			sc.getJSONObject(i).let { it.getString("name") to it.getString("text") }
		}
		val rows = root.getJSONArray("rows")
		val all = (0 until rows.length()).map { rows.getJSONObject(it) }
		// Categories load first, so that the other rows can map category uids to ids.
		all.filter { it.getString("table") == "categories" }.forEach { loadRow(it) }
		all.filter { it.getString("table") != "categories" }.forEach { loadRow(it) }
		memos.sortBy { it.pos }
	}

	private fun loadRow(o: JSONObject) {
		val table = o.getString("table")
		val rowId = o.getLong("id")
		val uid = o.getString("uid")
		val deleted = o.getBoolean("deleted")
		meta.getValue(table)[rowId] = Meta(uid, o.getLong("rev"), o.getBoolean("dirty"), deleted)
		if (deleted) return
		val text = o.getString("plain")
		when (table) {
			"categories" -> SyncCodec.toCategory(text, uid, rowId.toInt()).let { categoriesFor(it.app).add(it) }
			"events" -> events.add(SyncCodec.toEvent(text, uid).apply { this.id = rowId })
			"todos" -> todos.add(SyncCodec.toTodo(text, uid) { categoryIdForUid(it, 0) }.apply { this.id = rowId })
			else -> memos.add(SyncCodec.toMemo(text, uid) { categoryIdForUid(it, 1) }.apply { this.id = rowId })
		}
	}

	private fun rowsJson(): List<JSONObject> = tables.flatMap { table ->
		meta.getValue(table).entries.sortedBy { it.key }.map { (rowId, m) ->
			JSONObject()
				.put("table", table)
				.put("id", rowId)
				.put("uid", m.uid)
				.put("rev", m.rev)
				.put("dirty", m.dirty)
				.put("deleted", m.deleted)
				.apply { if (!m.deleted) put("plain", plainOf(table, rowId)) }
		}
	}

	private fun plainOf(table: String, rowId: Long): String = when (val r = liveRecord(table, rowId)) {
		is Event -> SyncCodec.event(r)
		is Todo -> SyncCodec.todo(r, categoryUid(r.category))
		is Memo -> SyncCodec.memo(r, categoryUid(r.category))
		is Category -> SyncCodec.category(r)
		else -> error("No live record for $table $rowId")
	}

	// helpers

	/** Saves after a local write: the file, then [onChange]. */
	private fun changed() {
		persist()
		onChange?.invoke()
	}

	private fun addDefaultCategories() {
		val defaults = listOf(
			Category(1, "Business", 0, "default-0-business"),
			Category(2, "Personal", 0, "default-0-personal"),
			Category(3, "Business", 1, "default-1-business"),
			Category(4, "Personal", 1, "default-1-personal"),
		)
		for (c in defaults) {
			categoriesFor(c.app).add(c)
			markDirty("categories", c.id.toLong(), c.uid)
		}
	}

	private fun putEvent(e: Event) {
		if (e.uid.isEmpty()) e.uid = UUID.randomUUID().toString()
		if (e.id == 0L) e.id = nextId("events")
		putInList(events, e) { it.id == e.id }
		markDirty("events", e.id, e.uid)
	}

	private fun putTodo(t: Todo) {
		if (t.uid.isEmpty()) t.uid = UUID.randomUUID().toString()
		if (t.id == 0L) t.id = nextId("todos")
		putInList(todos, t) { it.id == t.id }
		markDirty("todos", t.id, t.uid)
	}

	private fun putMemo(m: Memo) {
		if (m.uid.isEmpty()) m.uid = UUID.randomUUID().toString()
		if (m.id == 0L) {
			m.pos = if (memos.isEmpty()) 0 else memos.maxOf { it.pos } + 1
			m.id = nextId("memos")
		}
		putInList(memos, m) { it.id == m.id }
		markDirty("memos", m.id, m.uid)
	}

	/** Replaces the item that [same] finds, or adds [item] when none is found. */
	private fun <T> putInList(list: MutableList<T>, item: T, same: (T) -> Boolean) {
		val i = list.indexOfFirst(same)
		if (i >= 0) list[i] = item else list.add(item)
	}

	/** Marks a local write: the row is dirty and live. */
	private fun markDirty(table: String, rowId: Long, uid: String) {
		val m = meta.getValue(table).getOrPut(rowId) { Meta(uid, 0, dirty = true, deleted = false) }
		m.dirty = true
		m.deleted = false
	}

	/** Deletes a row that never reached the server. A synced row becomes a tombstone. */
	private fun removeLocal(table: String, rowId: Long) {
		val map = meta.getValue(table)
		val m = map[rowId] ?: return
		if (m.rev == 0L) {
			map.remove(rowId)
		} else {
			m.deleted = true
			m.dirty = true
		}
	}

	/** Stores the sync state of a server copy: clean, live, at [rev]. */
	private fun setServer(table: String, rowId: Long, uid: String, rev: Long) {
		meta.getValue(table)[rowId] = Meta(uid, rev, dirty = false, deleted = false)
	}

	/** Moves the items of category [id] to Unfiled. [dirty] is true for a local delete only. */
	private fun moveToUnfiled(id: Int, dirty: Boolean) {
		for (t in todos) if (t.category == id) {
			t.category = 0
			if (dirty) markDirty("todos", t.id, t.uid)
		}
		for (m in memos) if (m.category == id) {
			m.category = 0
			if (dirty) markDirty("memos", m.id, m.uid)
		}
	}

	private fun liveRecord(table: String, rowId: Long): Any? = when (table) {
		"categories" -> (categories + memoCategories).firstOrNull { it.id.toLong() == rowId }
		"events" -> events.firstOrNull { it.id == rowId }
		"todos" -> todos.firstOrNull { it.id == rowId }
		else -> memos.firstOrNull { it.id == rowId }
	}

	private fun idOf(table: String, uid: String): Long? =
		meta.getValue(table).entries.firstOrNull { it.value.uid == uid }?.key

	/** The local id of [uid] in [table], or a new id when this device has not seen the uid. */
	private fun idFor(table: String, uid: String): Long = idOf(table, uid) ?: nextId(table)

	/** One more than the highest id of [table], tombstones included. */
	private fun nextId(table: String): Long = (meta.getValue(table).keys.maxOrNull() ?: 0L) + 1

	private fun rowOf(table: String, rowId: Long, m: Meta) =
		SyncRow(table, rowId, m.uid, m.rev, m.dirty, m.deleted)
}
