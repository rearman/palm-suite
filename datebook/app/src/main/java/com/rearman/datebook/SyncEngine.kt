package com.rearman.datebook

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.rearman.palmsync.*
import com.rearman.palmui.*
import java.time.LocalDateTime

/**
 * Sync with the sync server. The wire format and the endpoints are in sync-server/PROTOCOL.md.
 *
 * The round logic is in sync-core [SyncRunner]: it collects the dirty rows, applies the server
 * answer, and applies the changes made on other devices. This object runs the rounds on the
 * threads and gives SyncRunner the Android side through [AndroidSyncStore].
 *
 * Keep-both rule (in SyncRunner): a record that changed here and on the server is kept twice.
 * The local copy becomes a new record with its own uid, which goes to the server on the next round,
 * and the server copy takes the place of the original. Shortcuts do not follow this rule: the last
 * writer wins.
 *
 * Threads: network calls and key derivation run on [worker]. Store, Prefs and ShortCuts are
 * used only on [main]. [done] is always called on [main].
 */
object SyncEngine {
    /** Called on the main thread after a sync changed records. */
    var onApplied: (() -> Unit)? = null

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

    /** Main thread. Starts the next round: collects the dirty rows, then sends them on the worker. */
    private fun nextRound(s: Session) {
        s.rounds++
        val prefs = Prefs(s.ctx)
        val plan = try {
            // Format 2 is a new wire format, so each live record goes up once more in it. The ShortCuts list
            // goes up too, but only when it synced before: a new device must not replace the list with its defaults.
            if (prefs.syncFormat < 2) {
                Store.get(s.ctx).markAllForUpload()
                if (prefs.shortCutsRev > 0) prefs.shortCutsDirty = true
                prefs.syncFormat = 2
            }
            SyncRunner.collect(adapter(s.ctx), s.key)
        } catch (e: Exception) {
            finish(s, e.message ?: "Sync failed.")
            return
        }
        val url = prefs.syncUrl
        worker.execute {
            val net = runCatching { SyncClient(url, s.token).sync(plan.since, plan.items) }
            main.post { applyNet(s, plan, net) }
        }
    }

    /** Main thread. Applies the server answer of a round, then starts the next round when needed. */
    private fun applyNet(s: Session, plan: SyncPlan, net: Result<SyncResult>) {
        val result = net.getOrNull()
        if (result == null) {
            finish(s, net.exceptionOrNull()?.message ?: "Sync failed.")
            return
        }
        val outcome = try {
            SyncRunner.apply(adapter(s.ctx), s.key, plan, result)
        } catch (e: Exception) {
            finish(s, e.message ?: "Sync failed.")
            return
        }
        if (outcome.shortCutsChanged) ShortCuts.list = Prefs(s.ctx).shortCuts
        if (outcome.changed) s.changed = true
        if (outcome.noConflicts || s.rounds >= SyncRunner.MAX_ROUNDS) finish(s, null) else nextRound(s)
    }

    /** Main thread. Ends a run: saves the result, tells the UI, then calls [Session.done]. */
    private fun finish(s: Session, error: String?) {
        Prefs(s.ctx).syncLast = if (error == null) lastSyncText() else "Sync failed: $error"
        if (s.changed) onApplied?.invoke()
        running = false
        s.done(error)
    }

    private fun adapter(ctx: Context): AndroidSyncStore = AndroidSyncStore(Store.get(ctx), Prefs(ctx))

    private fun lastSyncText(): String {
        val now = LocalDateTime.now()
        return "Last sync: " + Fmt.dowDate(now.toLocalDate().toEpochDay()) + " " +
            Fmt.time(now.hour * 60 + now.minute)
    }

    private fun report(done: (String?) -> Unit, msg: String?) {
        main.post { done(msg) }
    }
}

/** Connects sync-core to Android: Store holds the rows, Prefs holds the ShortCuts and the pull position. */
private class AndroidSyncStore(private val store: Store, private val prefs: Prefs) : SyncStore {
    override fun dirtyRows(): List<SyncRow> = store.dirtyRows()

    override fun syncRow(uid: String): SyncRow? = store.syncRow(uid)

    /** The live record of a row from the in-memory lists, or null when it is not there. */
    override fun record(row: SyncRow): Any? = when (row.table) {
        "events" -> store.events.firstOrNull { it.id == row.id }
        "todos" -> store.todos.firstOrNull { it.id == row.id }
        "memos" -> store.memos.firstOrNull { it.id == row.id }
        "addresses" -> store.addresses.firstOrNull { it.id == row.id }
        "categories" -> (store.categories + store.memoCategories + store.addressCategories).firstOrNull { it.id.toLong() == row.id }
        else -> null
    }

    override fun categoryUid(id: Int): String = store.categoryUid(id)

    override fun categoryIdForUid(uid: String, app: Int): Int = store.categoryIdForUid(uid, app)

    override fun markSynced(table: String, uid: String, rev: Long, clean: Boolean) =
        store.markSynced(table, uid, rev, clean)

    override fun putRemote(e: Event, rev: Long) = store.putRemote(e, rev)

    override fun putRemote(t: Todo, rev: Long) = store.putRemote(t, rev)

    override fun putRemote(m: Memo, rev: Long) = store.putRemote(m, rev)

    override fun putRemote(a: Address, rev: Long) = store.putRemote(a, rev)

    override fun putRemote(c: Category, rev: Long) = store.putRemote(c, rev)

    override fun dropRemote(table: String, uid: String) = store.dropRemote(table, uid)

    /** Keeps a copy of a local record as a new record, for the keep-both rule. False when categories are full. */
    override fun saveCopy(record: Any): Boolean {
        when (record) {
            is Event -> store.save(record.deepCopy().copy(id = 0, uid = ""))
            is Todo -> store.save(record.copy(id = 0, uid = ""))
            is Memo -> store.save(record.copy(id = 0, uid = ""))
            is Address -> store.save(record.copy(id = 0, uid = ""))
            is Category -> return store.addCategory(record.name + " (copy)", record.app) != null
        }
        return true
    }

    override val shortCuts: List<Pair<String, String>>
        get() = prefs.shortCuts.map { it.name to it.text }

    override var shortCutsDirty: Boolean
        get() = prefs.shortCutsDirty
        set(v) { prefs.shortCutsDirty = v }

    override var shortCutsRev: Long
        get() = prefs.shortCutsRev
        set(v) { prefs.shortCutsRev = v }

    override fun setShortCutsFromSync(list: List<Pair<String, String>>, rev: Long) =
        prefs.setShortCutsFromSync(list.map { ShortCut(it.first, it.second) }, rev)

    override var syncSince: Long
        get() = prefs.syncSince
        set(v) { prefs.syncSince = v }
}
