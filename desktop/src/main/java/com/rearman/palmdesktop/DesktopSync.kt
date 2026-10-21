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
	private val session = SyncSession(onWorker = { worker.execute(it) }, onUi = { SwingUtilities.invokeLater(it) })
	private var lastEnd = 0L   // System.currentTimeMillis() when the last run ended
	private val changeTimer = Timer(CHANGE_DELAY_MS) { request() }.apply { isRepeats = false }

	/** Sets up sync on the server. Joins the passphrase of the server, or creates one when the server has none. */
	fun setUp(server: String, token: String, passphrase: CharArray, done: (String?) -> Unit) {
		if (session.running) {
			done("A sync is already running.")
			return
		}
		worker.execute {
			val result = runCatching { SyncKeys.firstKey(SyncClient(server, token), passphrase) }
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
		if (session.running) {
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
		val server = this.server
		session.start(SyncSession.Run(
			store = store,
			key = keyText.unb64(),
			send = { since, items -> SyncClient(server, token).sync(since, items) },
			afterRound = { o -> if (o.changed) onApplied?.invoke() },
			finish = { error, _ ->
				val text = if (error == null) "Last sync: " + nowText() else "Sync failed: $error"
				Settings.set("lastSync", text)
				lastEnd = System.currentTimeMillis()
				onStatus?.invoke(text)
				done?.invoke(error)
			},
			again = { SwingUtilities.invokeLater { syncNow() } },
		))
	}

	/** Syncs now, or once more when the running sync ends. For the automatic syncs. */
	fun request() {
		if (session.running) session.requestAgain() else syncNow()
	}

	/** Syncs when the last sync ended more than a minute ago. The main window calls it when it is activated. */
	fun requestIfStale() {
		if (isOn && System.currentTimeMillis() - lastEnd > STALE_MS) request()
	}

	/** Syncs now when sync is on. Then syncs every hour, 1 s after each local change, and when the window is activated after a minute without a sync. */
	fun start() {
		if (isOn) request()
		Timer(HOUR_MS) { request() }.start()
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

	private fun nowText(): String {
		val now = LocalDateTime.now()
		return Fmt.shortDate(now.toLocalDate().toEpochDay()) + " " + Fmt.time(now.hour * 60 + now.minute)
	}

	private companion object {
		const val DEFAULT_SERVER = "https://sync.earman.xyz"
		const val CHANGE_DELAY_MS = 1_000   // 1 s after the last local change, so one action that writes several records syncs once
		const val STALE_MS = 60_000L
		const val HOUR_MS = 60 * 60 * 1000
	}
}
