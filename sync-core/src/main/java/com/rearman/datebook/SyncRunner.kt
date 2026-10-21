package com.rearman.datebook

import com.rearman.palmsync.Change
import com.rearman.palmsync.PushItem
import com.rearman.palmsync.SyncCrypto
import com.rearman.palmsync.SyncResult
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/** One row of a sync table. A tombstone has deleted = true and no live record. */
data class SyncRow(val table: String, val id: Long, val uid: String, val rev: Long, val dirty: Boolean, val deleted: Boolean)

/**
 * The local data that one sync round reads and writes. Each app implements it over its own store.
 *
 * record: the live record of a row (Event, Todo, Memo, Address or Category), or null for a tombstone.
 * saveCopy: keeps a record as a new dirty record with a new uid.
 */
interface SyncStore {
    fun dirtyRows(): List<SyncRow>                 // dirty rows of "categories", "events", "todos", "memos", "addresses", categories first
    fun syncRow(uid: String): SyncRow?             // any table, live or tombstone
    fun record(row: SyncRow): Any?                 // the live Event/Todo/Memo/Address/Category of the row, or null
    fun categoryUid(id: Int): String               // "" for 0 or unknown
    fun categoryIdForUid(uid: String, app: Int): Int // 0 when blank or unknown
    fun markSynced(table: String, uid: String, rev: Long, clean: Boolean)
    fun putRemote(e: Event, rev: Long)
    fun putRemote(t: Todo, rev: Long)
    fun putRemote(m: Memo, rev: Long)
    fun putRemote(a: Address, rev: Long)
    fun putRemote(c: Category, rev: Long)
    fun dropRemote(table: String, uid: String)
    fun saveCopy(record: Any)                      // keep-both: save as a new dirty record with a new uid
    val shortCuts: List<Pair<String, String>>
    var shortCutsDirty: Boolean
    var shortCutsRev: Long
    fun setShortCutsFromSync(list: List<Pair<String, String>>, rev: Long)
    var syncSince: Long
}

/** The push items of one round, with the plaintext of each pushed row (null for a delete). */
class SyncPlan(val items: List<PushItem>, val pushed: Map<String, String?>, val since: Long)

/** What applying one server answer changed. */
class SyncOutcome(val noConflicts: Boolean, val changed: Boolean, val shortCutsChanged: Boolean)

/**
 * One sync round over a [SyncStore]. The wire format is in sync-server/PROTOCOL.md.
 *
 * Keep-both rule: a record that changed here and on the server is kept twice. The local copy
 * becomes a new record with its own uid, and the server copy takes the place of the original.
 * SyncLogic.decide gives the action for each case. ShortCuts do not follow this rule: the last
 * writer wins.
 */
object SyncRunner {
    /** Each run stops after this many rounds. A later run finishes what is left. */
    const val MAX_ROUNDS = 5

    private const val SHORTCUTS_ID = "shortcuts"
    private const val DECRYPT_MESSAGE = "A record could not be decrypted. Check the passphrase."

    /** Step A: builds the push items from the dirty rows and the ShortCuts list. */
    fun collect(store: SyncStore, key: ByteArray): SyncPlan {
        val items = mutableListOf<PushItem>()
        val pushed = HashMap<String, String?>()
        for (row in store.dirtyRows()) {
            if (row.deleted) {
                items.add(PushItem(row.uid, row.rev, true, ""))
                pushed[row.uid] = null
                continue
            }
            val rec = store.record(row) ?: continue
            val plain = encode(store, rec)
            items.add(PushItem(row.uid, row.rev, false, seal(key, row.uid, plain)))
            pushed[row.uid] = plain
        }
        if (store.shortCutsDirty) {
            val plain = SyncCodec.shortcuts(store.shortCuts)
            items.add(PushItem(SHORTCUTS_ID, store.shortCutsRev, false, seal(key, SHORTCUTS_ID, plain)))
            pushed[SHORTCUTS_ID] = plain
        }
        return SyncPlan(items, pushed, store.syncSince)
    }

    /**
     * Steps C.1 to C.4. Throws IllegalStateException with a message for the user when a live change
     * cannot be decrypted, or when a keep-both copy of a category cannot be saved. A bad key
     * changes nothing, because every live change is decrypted before any change is applied.
     */
    fun apply(store: SyncStore, key: ByteArray, plan: SyncPlan, result: SyncResult): SyncOutcome {
        val changes = result.changes.filter { result.accepted[it.id] != it.rev }
        val plains = HashMap<String, String>()
        for (c in changes) {
            if (!c.deleted) plains[c.id] = openPlain(key, c)
        }
        val kindOf = plains.mapValues { SyncCodec.kind(it.value) }
        val live = changes.filter { !it.deleted }

        // C.1: the accepted pushes. A row that did not change since the push becomes clean.
        for ((uid, rev) in result.accepted) {
            if (uid == SHORTCUTS_ID) {
                if (SyncCodec.shortcuts(store.shortCuts) == plan.pushed[uid]) {
                    store.setShortCutsFromSync(store.shortCuts, rev)
                } else {
                    store.shortCutsRev = rev
                }
                continue
            }
            val row = store.syncRow(uid) ?: continue
            val now = plainOf(store, row)
            val same = if (row.deleted) plan.pushed[uid] == null else now != null && now == plan.pushed[uid]
            store.markSynced(row.table, uid, rev, same)
        }

        // C.3: live categories first, then other live changes, then deleted ones.
        val order = live.filter { kindOf[it.id] == "category" } +
            live.filter { kindOf[it.id] != "category" } +
            changes.filter { it.deleted }
        var changed = false
        var shortCutsChanged = false
        for (c in order) {
            if (c.id == SHORTCUTS_ID) {
                if (applyShortCuts(store, c, plains[c.id].orEmpty())) shortCutsChanged = true
            } else if (applyChange(store, c, plains[c.id].orEmpty())) {
                changed = true
            }
        }

        // C.4: the server revision is saved only when every change above applied.
        store.syncSince = result.rev
        return SyncOutcome(result.conflicts.isEmpty(), changed, shortCutsChanged)
    }

    /** Step C.3 for the ShortCuts list. Returns true when the list was applied. */
    private fun applyShortCuts(store: SyncStore, c: Change, text: String): Boolean {
        val local = if (store.shortCutsDirty) LocalState.DIRTY else LocalState.CLEAN
        return when (decide(local, c.deleted, same = false, lastWins = true)) {
            Action.APPLY -> {
                store.setShortCutsFromSync(SyncCodec.toShortcuts(text), c.rev)
                true
            }
            Action.REBASE -> {
                store.shortCutsRev = c.rev
                false
            }
            else -> false
        }
    }

    /** Step C.3 for one record change. Returns true when a record changed. */
    private fun applyChange(store: SyncStore, c: Change, text: String): Boolean {
        val row = store.syncRow(c.id)
        val local = when {
            row == null -> LocalState.NONE
            row.deleted -> LocalState.TOMB
            row.dirty -> LocalState.DIRTY
            else -> LocalState.CLEAN
        }
        val kind = if (c.deleted) "" else SyncCodec.kind(text)
        if (!c.deleted && kind.isEmpty()) return false // unknown kind: skip
        val mine = row?.let { store.record(it) }
        val same = local == LocalState.DIRTY && !c.deleted && mine != null &&
            sameContent(store, kind, text, c.id, mine)

        return when (decide(local, c.deleted, same, lastWins = false)) {
            Action.SKIP -> false
            Action.APPLY -> applyRemote(store, kind, text, c)
            Action.COPY_THEN_APPLY -> {
                val copied = copyLocal(store, mine)
                applyRemote(store, kind, text, c) || copied
            }
            Action.COPY_THEN_DROP -> {
                val copied = copyLocal(store, mine)
                dropLocal(store, row, c) || copied
            }
            Action.DROP -> dropLocal(store, row, c)
            Action.REBASE -> false
        }
    }

    /** Saves the server copy of a record, with the local ids of its categories. */
    private fun applyRemote(store: SyncStore, kind: String, text: String, c: Change): Boolean {
        when (kind) {
            "event" -> store.putRemote(SyncCodec.toEvent(text, c.id), c.rev)
            "todo" -> store.putRemote(SyncCodec.toTodo(text, c.id) { store.categoryIdForUid(it, 0) }, c.rev)
            "memo" -> store.putRemote(SyncCodec.toMemo(text, c.id) { store.categoryIdForUid(it, 1) }, c.rev)
            "address" -> store.putRemote(SyncCodec.toAddress(text, c.id) { store.categoryIdForUid(it, 2) }, c.rev)
            "category" -> store.putRemote(SyncCodec.toCategory(text, c.id, 0), c.rev)
            else -> return false
        }
        return true
    }

    /** Removes a record that the server deleted. */
    private fun dropLocal(store: SyncStore, row: SyncRow?, c: Change): Boolean {
        if (row == null) return false
        store.dropRemote(row.table, c.id)
        return true
    }

    /** Keeps a copy of a local record as a new record, for the keep-both rule. */
    private fun copyLocal(store: SyncStore, rec: Any?): Boolean {
        if (rec == null) return false
        store.saveCopy(rec)
        return true
    }

    /** Step C.2 check: true when the local record has the same content as the server copy. */
    private fun sameContent(store: SyncStore, kind: String, text: String, uid: String, local: Any): Boolean = when (kind) {
        "event" -> (local as? Event)?.dupKey() == SyncCodec.toEvent(text, uid).dupKey()
        "todo" -> (local as? Todo)?.dupKey() ==
            SyncCodec.toTodo(text, uid) { store.categoryIdForUid(it, 0) }.dupKey()
        "memo" -> (local as? Memo)?.dupKey() ==
            SyncCodec.toMemo(text, uid) { store.categoryIdForUid(it, 1) }.dupKey()
        "address" -> (local as? Address)?.dupKey() ==
            SyncCodec.toAddress(text, uid) { store.categoryIdForUid(it, 2) }.dupKey()
        "category" -> (local as? Category)?.let {
            val remote = SyncCodec.toCategory(text, uid, 0)
            it.name == remote.name && it.app == remote.app
        } ?: false
        else -> false
    }

    /** Decrypts a live change. A failure ends the round with a message about the passphrase. */
    private fun openPlain(key: ByteArray, c: Change): String = try {
        PlainBytes.unpack(SyncCrypto.open(key, c.id, c.blob))
    } catch (e: Exception) {
        throw IllegalStateException(DECRYPT_MESSAGE)
    }

    /** The plaintext of a row, or null when the row has no live record. */
    private fun plainOf(store: SyncStore, row: SyncRow): String? = store.record(row)?.let { encode(store, it) }

    private fun encode(store: SyncStore, rec: Any): String = when (rec) {
        is Event -> SyncCodec.event(rec)
        is Todo -> SyncCodec.todo(rec, store.categoryUid(rec.category))
        is Memo -> SyncCodec.memo(rec, store.categoryUid(rec.category))
        is Address -> SyncCodec.address(rec, store.categoryUid(rec.category))
        is Category -> SyncCodec.category(rec)
        else -> ""
    }

    private fun seal(key: ByteArray, id: String, plain: String): String =
        SyncCrypto.seal(key, id, PlainBytes.pack(plain))
}

/**
 * The bytes that are sealed for one record. The plain text is JSON, and JSON always starts with
 * "{". So a first byte of 0x01 marks deflated bytes, and it never occurs in a plain record. A
 * record that is not deflated is sealed as its UTF-8 text, as before.
 */
internal object PlainBytes {
    private const val DEFLATED: Byte = 0x01
    private const val BUFFER = 1024

    /** The bytes to seal: the marker and the raw deflate when that is shorter, else the UTF-8 text. */
    fun pack(plain: String): ByteArray {
        val bytes = plain.toByteArray(Charsets.UTF_8)
        val deflated = deflate(bytes)
        return if (1 + deflated.size < bytes.size) byteArrayOf(DEFLATED) + deflated else bytes
    }

    /** The text of opened bytes. Bytes that start with the marker are inflated first. */
    fun unpack(raw: ByteArray): String {
        if (raw.isEmpty() || raw[0] != DEFLATED) return String(raw, Charsets.UTF_8)
        return String(inflate(raw, 1), Charsets.UTF_8)
    }

    private fun deflate(bytes: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        try {
            deflater.setInput(bytes)
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(BUFFER)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun inflate(raw: ByteArray, offset: Int): ByteArray {
        val inflater = Inflater(true)
        try {
            inflater.setInput(raw, offset, raw.size - offset)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(BUFFER)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                // Stops when the input ends early, so truncated data cannot loop forever.
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buf, 0, n)
            }
            check(inflater.finished()) { "Truncated deflate data" }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }
}
