package com.rearman.datebook

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.rearman.palmui.*
import java.time.LocalDateTime

/**
 * Sync with the sync server. The wire format and the endpoints are in sync-server/PROTOCOL.md.
 *
 * Each record goes to the server as one blob sealed with the sync key. A round pushes the dirty
 * rows, applies the server's answer to them, then applies the changes made on other devices.
 *
 * Keep-both rule: a record that changed here and on the server is kept twice. The local copy
 * becomes a new record with its own uid, which goes to the server on the next round, and the
 * server copy takes the place of the original. SyncLogic.decide gives the action for each case.
 * Shortcuts do not follow this rule: the last writer wins.
 *
 * Threads: network calls and key derivation run on [worker]. Store, Prefs and ShortCuts are
 * used only on [main]. [done] is always called on [main].
 */
object SyncEngine {
    /** Called on the main thread after a sync changed records. */
    var onApplied: (() -> Unit)? = null

    /** Each run stops after this many rounds. A later run finishes what is left. */
    private const val MAX_ROUNDS = 5

    private val main = Handler(Looper.getMainLooper())
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var running = false

    /** Sets up sync on a server with no passphrase yet, or joins the one that is there. */
    fun setUp(ctx: Context, url: String, token: String, passphrase: CharArray, done: (String?) -> Unit) {
        worker.execute {
            try {
                val key = firstKey(url, token, passphrase)
                if (key == null) {
                    report(done, "Wrong passphrase.")
                    return@execute
                }
                main.post { finishSetUp(ctx, url, token, key, done) }
            } catch (e: Exception) {
                report(done, e.message ?: "Sync setup failed.")
            } finally {
                passphrase.fill(' ')
            }
        }
    }

    /** Turns sync off on this device and forgets the key and the token. */
    fun turnOff(ctx: Context) {
        val prefs = Prefs(ctx)
        prefs.syncOn = false
        KeyVault.remove(ctx, "key")
        KeyVault.remove(ctx, "token")
        SyncJob.cancelAll(ctx)
        prefs.syncLast = "Sync is off."
    }

    /** Runs one sync. Call it on the main thread. [done] gets null on success. */
    fun run(ctx: Context, done: (String?) -> Unit) {
        if (running) {
            done("A sync is already running.")
            return
        }
        val prefs = Prefs(ctx)
        if (!prefs.syncOn) {
            done("Sync is off.")
            return
        }
        val key = KeyVault.get(ctx, "key")
        val token = KeyVault.get(ctx, "token")
        if (key == null || token == null) {
            done("Set up sync again in Options, Sync.")
            return
        }
        running = true
        nextRound(Session(ctx, key, String(token, Charsets.UTF_8), done))
    }

    // Set up

    /** Worker thread. Returns the sync key, or null for a wrong passphrase. */
    private fun firstKey(url: String, token: String, passphrase: CharArray): ByteArray? {
        val client = SyncClient(url, token)
        val meta = client.getMeta()
        if (meta.salt.isEmpty()) {
            val salt = SyncCrypto.newSalt()
            val key = SyncCrypto.deriveKey(passphrase, salt)
            if (client.putMeta(SyncMeta(salt.b64(), SyncCrypto.checkBlob(key)))) return key
            // 409: another device stored the first passphrase between the two calls.
            return keyFromMeta(passphrase, client.getMeta())
        }
        return keyFromMeta(passphrase, meta)
    }

    /** Worker thread. Derives the key from the stored salt and checks it against the stored check blob. */
    private fun keyFromMeta(passphrase: CharArray, meta: SyncMeta): ByteArray? {
        if (meta.salt.isEmpty()) throw IllegalStateException("Sync setup failed.")
        val key = SyncCrypto.deriveKey(passphrase, meta.salt.unb64())
        return if (SyncCrypto.checkOk(key, meta.check)) key else null
    }

    /** Main thread. Saves the key and the settings, then runs the first sync. */
    private fun finishSetUp(ctx: Context, url: String, token: String, key: ByteArray, done: (String?) -> Unit) {
        try {
            KeyVault.put(ctx, "key", key)
            KeyVault.put(ctx, "token", token.toByteArray(Charsets.UTF_8))
            val prefs = Prefs(ctx)
            prefs.syncUrl = url
            prefs.syncOn = true
            prefs.syncSince = 0
            // Push the ShortCuts only when this device changed them, so a new device with the
            // default list does not replace the list of the other devices.
            prefs.shortCutsDirty = prefs.shortCuts != ShortCuts.defaults
            prefs.shortCutsRev = 0
            Store.get(ctx).resetSync()
            SyncJob.schedulePeriodic(ctx)
        } catch (e: Exception) {
            done(e.message ?: "Sync setup failed.")
            return
        }
        run(ctx, done)
    }

    // Rounds

    /** The state of one run. */
    private class Session(val ctx: Context, val key: ByteArray, val token: String, val done: (String?) -> Unit) {
        var rounds = 0
        var changed = false
    }

    /** The push items of one round, with the plaintext of each pushed row (null for a delete). */
    private class Plan(val items: List<PushItem>, val pushed: Map<String, String?>, val url: String, val since: Long)

    /** Main thread. Starts the next round: collects the dirty rows, then sends them on the worker. */
    private fun nextRound(s: Session) {
        s.rounds++
        val plan = try {
            collect(s)
        } catch (e: Exception) {
            finish(s, e.message ?: "Sync failed.")
            return
        }
        worker.execute {
            val net = runCatching { SyncClient(plan.url, s.token).sync(plan.since, plan.items) }
            main.post { applyNet(s, plan, net) }
        }
    }

    /** Main thread. Step A: builds the push items from the dirty rows and the shortcuts. */
    private fun collect(s: Session): Plan {
        val store = Store.get(s.ctx)
        val prefs = Prefs(s.ctx)
        val items = mutableListOf<PushItem>()
        val pushed = HashMap<String, String?>()
        for (row in store.dirtyRows()) {
            if (row.deleted) {
                items.add(PushItem(row.uid, row.rev, true, ""))
                pushed[row.uid] = null
                continue
            }
            val rec = recordOf(store, row.table, row.id) ?: continue
            val plain = encode(store, rec)
            items.add(PushItem(row.uid, row.rev, false, SyncCrypto.seal(s.key, row.uid, plain.toByteArray(Charsets.UTF_8))))
            pushed[row.uid] = plain
        }
        if (prefs.shortCutsDirty) {
            val plain = SyncCodec.shortcuts(prefs.shortCuts)
            items.add(PushItem("shortcuts", prefs.shortCutsRev, false,
                SyncCrypto.seal(s.key, "shortcuts", plain.toByteArray(Charsets.UTF_8))))
            pushed["shortcuts"] = plain
        }
        return Plan(items, pushed, prefs.syncUrl, prefs.syncSince)
    }

    /** Main thread. Gets the server answer of a round, then applies it. */
    private fun applyNet(s: Session, plan: Plan, net: Result<SyncResult>) {
        val result = net.getOrNull()
        if (result == null) {
            finish(s, net.exceptionOrNull()?.message ?: "Sync failed.")
            return
        }
        val noConflicts = try {
            applyResult(s, plan, result)
        } catch (e: Exception) {
            finish(s, e.message ?: "Sync failed.")
            return
        }
        if (noConflicts || s.rounds >= MAX_ROUNDS) finish(s, null) else nextRound(s)
    }

    /** Main thread. Steps C.1 to C.4. Returns true when the server reported no conflicts. */
    private fun applyResult(s: Session, plan: Plan, result: SyncResult): Boolean {
        val store = Store.get(s.ctx)
        val prefs = Prefs(s.ctx)

        // C.1: the accepted pushes. A row that did not change since the push becomes clean.
        for ((uid, rev) in result.accepted) {
            if (uid == "shortcuts") {
                if (SyncCodec.shortcuts(prefs.shortCuts) == plan.pushed[uid]) {
                    prefs.setShortCutsFromSync(prefs.shortCuts, rev)
                } else {
                    prefs.shortCutsRev = rev
                }
                continue
            }
            val row = store.syncRow(uid) ?: continue
            val now = plainOf(store, row)
            val same = if (row.deleted) plan.pushed[uid] == null else now != null && now == plan.pushed[uid]
            store.markSynced(row.table, uid, rev, clean = same)
        }

        // C.2: the changes from the server. Echoes of our own accepted pushes are skipped.
        // Every live change is decrypted before any change is applied, so a bad key changes nothing.
        val changes = result.changes.filter { result.accepted[it.id] != it.rev }
        val plains = HashMap<String, String>()
        for (c in changes) {
            if (!c.deleted) plains[c.id] = openPlain(s.key, c)
        }
        val kindOf = plains.mapValues { SyncCodec.kind(it.value) }
        val live = changes.filter { !it.deleted }

        // C.3: live categories first, then other live changes, then deleted ones.
        val order = live.filter { kindOf[it.id] == "category" } +
            live.filter { kindOf[it.id] != "category" } +
            changes.filter { it.deleted }
        for (c in order) {
            if (applyChange(s, c, plains[c.id].orEmpty())) s.changed = true
        }

        // C.4: the server revision is saved only when every change above applied.
        prefs.syncSince = result.rev
        return result.conflicts.isEmpty()
    }

    /** Main thread. Step C.3 for one change. Returns true when a record changed. */
    private fun applyChange(s: Session, c: Change, text: String): Boolean {
        val store = Store.get(s.ctx)
        val prefs = Prefs(s.ctx)

        if (c.id == "shortcuts") {
            val local = if (prefs.shortCutsDirty) LocalState.DIRTY else LocalState.CLEAN
            when (decide(local, c.deleted, same = false, lastWins = true)) {
                Action.APPLY -> {
                    val list = SyncCodec.toShortcuts(text)
                    prefs.setShortCutsFromSync(list, c.rev)
                    ShortCuts.list = list
                }
                Action.REBASE -> prefs.shortCutsRev = c.rev
                else -> Unit
            }
            return false
        }

        val row = store.syncRow(c.id)
        val local = when {
            row == null -> LocalState.NONE
            row.deleted -> LocalState.TOMB
            row.dirty -> LocalState.DIRTY
            else -> LocalState.CLEAN
        }
        val kind = if (c.deleted) "" else SyncCodec.kind(text)
        if (!c.deleted && kind.isEmpty()) return false // unknown kind: skip
        val mine = row?.let { recordOf(store, it.table, it.id) }
        val same = local == LocalState.DIRTY && !c.deleted && mine != null &&
            sameContent(store, kind, text, c.id, mine)

        return when (decide(local, c.deleted, same, lastWins = false)) {
            Action.SKIP -> false
            Action.APPLY -> applyRemote(store, kind, text, c)
            Action.COPY_THEN_APPLY -> {
                mine?.let { copyLocal(store, it) }
                applyRemote(store, kind, text, c)
            }
            Action.COPY_THEN_DROP -> {
                mine?.let { copyLocal(store, it) }
                dropLocal(store, row, c)
            }
            Action.DROP -> dropLocal(store, row, c)
            Action.REBASE -> false
        }
    }

    /** Main thread. Saves the server copy of a record, with the local ids of its categories. */
    private fun applyRemote(store: Store, kind: String, text: String, c: Change): Boolean {
        when (kind) {
            "event" -> store.putRemote(SyncCodec.toEvent(text, c.id), c.rev)
            "todo" -> store.putRemote(SyncCodec.toTodo(text, c.id) { store.categoryIdForUid(it, 0) }, c.rev)
            "memo" -> store.putRemote(SyncCodec.toMemo(text, c.id) { store.categoryIdForUid(it, 1) }, c.rev)
            "category" -> store.putRemote(SyncCodec.toCategory(text, c.id, 0), c.rev)
            else -> return false
        }
        return true
    }

    /** Main thread. Removes a record that the server deleted. */
    private fun dropLocal(store: Store, row: SyncRow?, c: Change): Boolean {
        if (row == null) return false
        store.dropRemote(row.table, c.id)
        return true
    }

    /** Main thread. Keeps a copy of a local record as a new record, for the keep-both rule. */
    private fun copyLocal(store: Store, rec: Any) {
        when (rec) {
            is Event -> store.save(rec.deepCopy().copy(id = 0, uid = ""))
            is Todo -> store.save(rec.copy(id = 0, uid = ""))
            is Memo -> store.save(rec.copy(id = 0, uid = ""))
            is Category -> {
                if (store.addCategory(rec.name + " (copy)", rec.app) == null) {
                    throw IllegalStateException("Categories are full. Delete one, then sync again.")
                }
            }
        }
    }

    /** Main thread. Step C.2 check: true when the local record has the same content as the server copy. */
    private fun sameContent(store: Store, kind: String, text: String, uid: String, local: Any): Boolean = when (kind) {
        "event" -> (local as? Event)?.dupKey() == SyncCodec.toEvent(text, uid).dupKey()
        "todo" -> (local as? Todo)?.dupKey() ==
            SyncCodec.toTodo(text, uid) { store.categoryIdForUid(it, 0) }.dupKey()
        "memo" -> (local as? Memo)?.dupKey() ==
            SyncCodec.toMemo(text, uid) { store.categoryIdForUid(it, 1) }.dupKey()
        "category" -> (local as? Category)?.let {
            val remote = SyncCodec.toCategory(text, uid, 0)
            it.name == remote.name && it.app == remote.app
        } ?: false
        else -> false
    }

    /** Decrypts a live change. A failure ends the round with a message about the passphrase. */
    private fun openPlain(key: ByteArray, c: Change): String = try {
        String(SyncCrypto.open(key, c.id, c.blob), Charsets.UTF_8)
    } catch (e: Exception) {
        throw IllegalStateException("A record could not be decrypted. Check the passphrase.")
    }

    /** Main thread. Ends a run: saves the result, tells the UI, then calls [Session.done]. */
    private fun finish(s: Session, error: String?) {
        Prefs(s.ctx).syncLast = if (error == null) lastSyncText() else "Sync failed: $error"
        if (s.changed) onApplied?.invoke()
        running = false
        s.done(error)
    }

    private fun lastSyncText(): String {
        val now = LocalDateTime.now()
        return "Last sync: " + Fmt.dowDate(now.toLocalDate().toEpochDay()) + " " +
            Fmt.time(now.hour * 60 + now.minute)
    }

    private fun report(done: (String?) -> Unit, msg: String?) {
        main.post { done(msg) }
    }

    // Local records

    /** The live record of a row from the in-memory lists, or null when it is not there. */
    private fun recordOf(store: Store, table: String, id: Long): Any? = when (table) {
        "events" -> store.events.firstOrNull { it.id == id }
        "todos" -> store.todos.firstOrNull { it.id == id }
        "memos" -> store.memos.firstOrNull { it.id == id }
        "categories" -> (store.categories + store.memoCategories).firstOrNull { it.id.toLong() == id }
        else -> null
    }

    /** The plaintext of a row, or null when the row has no live record. */
    private fun plainOf(store: Store, row: SyncRow): String? = recordOf(store, row.table, row.id)?.let { encode(store, it) }

    private fun encode(store: Store, rec: Any): String = when (rec) {
        is Event -> SyncCodec.event(rec)
        is Todo -> SyncCodec.todo(rec, store.categoryUid(rec.category))
        is Memo -> SyncCodec.memo(rec, store.categoryUid(rec.category))
        is Category -> SyncCodec.category(rec)
        else -> ""
    }
}
