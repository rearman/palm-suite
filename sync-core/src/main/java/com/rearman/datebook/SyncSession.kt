package com.rearman.datebook

import com.rearman.palmsync.PushItem
import com.rearman.palmsync.SyncResult

/**
 * The round loop of one sync run, shared by the Android app and the desktop app.
 *
 * A run has one or more rounds. Each round collects the dirty rows on the UI thread, sends them
 * on a worker thread, and applies the server answer on the UI thread. A run ends when the server
 * reports no conflicts, or after [SyncRunner.MAX_ROUNDS] rounds. The caller gives the threads.
 *
 * The class holds the state of the one run in progress. Call it on the UI thread. [running] may be
 * read from any thread.
 *
 * @param onWorker runs a block on the worker thread, for the network call.
 * @param onUi runs a block on the UI thread.
 */
class SyncSession(
    private val onWorker: (() -> Unit) -> Unit,
    private val onUi: (() -> Unit) -> Unit,
) {
    /** True from [start] until the run ends. */
    @Volatile
    var running: Boolean = false
        private set

    private var rounds = 0
    private var changed = false
    private var againRequested = false

    /**
     * The inputs and the hooks of one run.
     *
     * @property store the local data that each round reads and writes.
     * @property key the sync key.
     * @property send sends the push items on the worker thread and returns the server answer.
     *   It throws when the call fails. The message of the exception is the error text of the run.
     * @property beforeCollect runs on the UI thread before each collect, for example to prepare
     *   the rows after a format change. An exception here ends the run with its message.
     * @property afterRound runs on the UI thread after each applied round, with what it changed.
     * @property finish runs once on the UI thread when the run ends. It gets the error text, or null
     *   on success, and true when any round changed records.
     * @property again runs on the UI thread after [finish], when [SyncSession.requestAgain] was
     *   called during the run.
     */
    class Run(
        val store: SyncStore,
        val key: ByteArray,
        val send: (since: Long, items: List<PushItem>) -> SyncResult,
        val beforeCollect: () -> Unit = {},
        val afterRound: (SyncOutcome) -> Unit = {},
        val finish: (error: String?, changed: Boolean) -> Unit,
        val again: () -> Unit = {},
    )

    /**
     * Starts a run and its first round. Returns false, and changes nothing, when a run is already
     * in progress.
     */
    fun start(r: Run): Boolean {
        if (running) return false
        running = true
        rounds = 0
        changed = false
        nextRound(r)
        return true
    }

    /**
     * Asks for one more run when the run in progress ends. Does nothing when no run is in progress.
     */
    fun requestAgain() {
        if (running) againRequested = true
    }

    /** UI thread. Collects the dirty rows, then sends them on the worker thread. */
    private fun nextRound(r: Run) {
        rounds++
        val plan = try {
            r.beforeCollect()
            SyncRunner.collect(r.store, r.key)
        } catch (e: Exception) {
            finish(r, e.message ?: "Sync failed.")
            return
        }
        onWorker {
            val net = runCatching { r.send(plan.since, plan.items) }
            onUi { applyNet(r, plan, net) }
        }
    }

    /** UI thread. Applies the server answer of a round, then starts the next round when needed. */
    private fun applyNet(r: Run, plan: SyncPlan, net: Result<SyncResult>) {
        val result = net.getOrNull()
        if (result == null) {
            finish(r, net.exceptionOrNull()?.message ?: "Sync failed.")
            return
        }
        val outcome = try {
            SyncRunner.apply(r.store, r.key, plan, result)
        } catch (e: Exception) {
            finish(r, e.message ?: "Sync failed.")
            return
        }
        r.afterRound(outcome)
        if (outcome.changed) changed = true
        if (outcome.noConflicts || rounds >= SyncRunner.MAX_ROUNDS) finish(r, null) else nextRound(r)
    }

    /** UI thread. Ends the run, then calls [Run.finish], then [Run.again] when it was requested. */
    private fun finish(r: Run, error: String?) {
        running = false
        r.finish(error, changed)
        if (againRequested) {
            againRequested = false
            r.again()
        }
    }
}
