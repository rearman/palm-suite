package com.rearman.palmdesktop

import com.rearman.datebook.*
import com.rearman.palmsync.*
import java.time.LocalDateTime
import java.util.concurrent.Executors
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Sync with the sync server, as the phone does it. Each record is sealed with the sync key before
 * it leaves this computer. The wire format is in sync-server/PROTOCOL.md.
 *
 * Threads: [store] and [Settings] are used only on the Swing event thread. Network calls and key
 * derivation run on [worker]. Results return to the event thread with SwingUtilities.invokeLater.
 */
class DesktopSync(private val store: DesktopStore) {
	/** Called on the event thread after a round changed records. */
	var onApplied: (() -> Unit)? = null

	/** Called on the event thread with the status text for the status bar. */
	var onStatus: ((String) -> Unit)? = null

	val isOn: Boolean get() = Settings.getBool("syncOn", false)
	val server: String get() = Settings.get("server", DEFAULT_SERVER)
	val lastResult: String get() = Settings.get("lastSync", "")

	private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "palm-sync").apply { isDaemon = true } }
	private var running = false
	private val changeTimer = Timer(CHANGE_DELAY_MS) { syncNow() }.apply { isRepeats = false }

	/** Sets up sync on the server. Joins the passphrase of the server, or creates one when the server has none. */
	fun setUp(server: String, token: String, passphrase: CharArray, done: (String?) -> Unit) {
		if (running) {
			done("A sync is already running.")
			return
		}
		worker.execute {
			val result = runCatching { firstKey(server, token, passphrase) }
			passphrase.fill(' ')
			SwingUtilities.invokeLater { finishSetUp(server, token, result, done) }
		}
	}

	/** Turns sync off on this computer. The token and the key are removed. */
	fun turnOff() {
		Settings.setBool("syncOn", false)
		Settings.remove("token")
		Settings.remove("key")
	}

	/** Runs one sync. [done] gets null on success, or the message. Call it on the event thread. */
	fun syncNow(done: ((String?) -> Unit)? = null) {
		if (running) {
			done?.invoke("A sync is already running.")
			return
		}
		if (!isOn) {
			done?.invoke("Sync is off.")
			return
		}
		val keyText = Settings.get("key", "")
		val token = Settings.get("token", "")
		if (keyText.isEmpty() || token.isEmpty()) {
			done?.invoke("Set up sync again.")
			return
		}
		running = true
		nextRound(SyncRun(server, token, keyText.unb64(), done))
	}

	/** Syncs now when sync is on. Then syncs every hour, and 30 s after each local change. */
	fun start() {
		if (isOn) syncNow()
		Timer(HOUR_MS) { syncNow() }.start()
		val previous = store.onChange
		store.onChange = {
			previous?.invoke()
			changeTimer.restart()
		}
	}

	/** Event thread. Saves the settings of a successful set up, then runs the first sync. */
	private fun finishSetUp(server: String, token: String, result: Result<ByteArray?>, done: (String?) -> Unit) {
		val key = result.getOrElse {
			done(it.message ?: "Sync setup failed.")
			return
		}
		if (key == null) {
			done("Wrong passphrase.")
			return
		}
		Settings.set("server", server)
		Settings.set("token", token)
		Settings.set("key", key.b64())
		Settings.setBool("syncOn", true)
		store.syncSince = 0L
		syncNow(done)
	}

	/** Worker thread. Returns the sync key, or null for a wrong passphrase. */
	private fun firstKey(server: String, token: String, passphrase: CharArray): ByteArray? {
		val client = SyncClient(server, token)
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

	/** Event thread. Collects the dirty rows, then sends them on the worker. */
	private fun nextRound(run: SyncRun) {
		run.rounds++
		val plan = try {
			SyncRunner.collect(store, run.key)
		} catch (e: Exception) {
			finish(run, e.message ?: "Sync failed.")
			return
		}
		worker.execute {
			val net = runCatching { SyncClient(run.server, run.token).sync(plan.since, plan.items) }
			SwingUtilities.invokeLater { applyNet(run, plan, net) }
		}
	}

	/** Event thread. Applies the server answer of a round, then starts the next round when there are conflicts. */
	private fun applyNet(run: SyncRun, plan: SyncPlan, net: Result<SyncResult>) {
		val result = net.getOrNull()
		if (result == null) {
			finish(run, net.exceptionOrNull()?.message ?: "Sync failed.")
			return
		}
		val outcome = try {
			SyncRunner.apply(store, run.key, plan, result)
		} catch (e: Exception) {
			finish(run, e.message ?: "Sync failed.")
			return
		}
		if (outcome.changed) onApplied?.invoke()
		if (outcome.noConflicts || run.rounds >= SyncRunner.MAX_ROUNDS) finish(run, null) else nextRound(run)
	}

	/** Event thread. Ends a run: saves the result, tells the status bar, then calls the caller. */
	private fun finish(run: SyncRun, error: String?) {
		val text = if (error == null) "Last sync: " + nowText() else "Sync failed: $error"
		Settings.set("lastSync", text)
		running = false
		onStatus?.invoke(text)
		run.done?.invoke(error)
	}

	private fun nowText(): String {
		val now = LocalDateTime.now()
		return Fmt.shortDate(now.toLocalDate().toEpochDay()) + " " + Fmt.time(now.hour * 60 + now.minute)
	}

	/** The state of one run. */
	private class SyncRun(
		val server: String,
		val token: String,
		val key: ByteArray,
		val done: ((String?) -> Unit)?,
	) {
		var rounds = 0
	}

	private companion object {
		const val DEFAULT_SERVER = "https://sync.earman.xyz"
		const val CHANGE_DELAY_MS = 30_000
		const val HOUR_MS = 60 * 60 * 1000
	}
}
