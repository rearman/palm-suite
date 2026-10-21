package com.rearman.datebook

import com.rearman.palmsync.PushItem
import com.rearman.palmsync.SyncResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncSessionTest {
    private val key = ByteArray(32)
    private val finished = mutableListOf<Pair<String?, Boolean>>()
    private var againCount = 0
    private var afterRoundCount = 0

    /** A store with no rows. The round logic only reads it. */
    private class EmptyStore : SyncStore {
        override fun dirtyRows(): List<SyncRow> = emptyList()
        override fun syncRow(uid: String): SyncRow? = null
        override fun record(row: SyncRow): Any? = null
        override fun categoryUid(id: Int): String = ""
        override fun categoryIdForUid(uid: String, app: Int): Int = 0
        override fun markSynced(table: String, uid: String, rev: Long, clean: Boolean) {}
        override fun putRemote(e: Event, rev: Long) {}
        override fun putRemote(t: Todo, rev: Long) {}
        override fun putRemote(m: Memo, rev: Long) {}
        override fun putRemote(a: Address, rev: Long) {}
        override fun putRemote(c: Category, rev: Long) {}
        override fun dropRemote(table: String, uid: String) {}
        override fun saveCopy(record: Any) {}
        override val shortCuts: List<Pair<String, String>> = emptyList()
        override var shortCutsDirty = false
        override var shortCutsRev = 0L
        override fun setShortCutsFromSync(list: List<Pair<String, String>>, rev: Long) {}
        override var syncSince = 0L
    }

    /** Runs each block at once, on the same thread. */
    private fun session() = SyncSession(onWorker = { block -> block() }, onUi = { block -> block() })

    private fun emptyResult(conflicts: Set<String> = emptySet()) =
        SyncResult(0, emptyMap(), conflicts, emptyList())

    private fun newRun(send: (Long, List<PushItem>) -> SyncResult) = SyncSession.Run(
        store = EmptyStore(),
        key = key,
        send = send,
        afterRound = { afterRoundCount++ },
        finish = { error, changed -> finished.add(error to changed) },
        again = { againCount++ },
    )

    @Test fun cleanRunFinishesOnceWithNullAndNoChange() {
        val session = session()

        assertTrue(session.start(newRun { _, _ -> emptyResult() }))

        assertEquals(listOf<Pair<String?, Boolean>>(null to false), finished)
        assertEquals(1, afterRoundCount)
        assertFalse(session.running)
    }

    @Test fun startWhileRunningReturnsFalse() {
        val session = session()
        var inner: Boolean? = null
        lateinit var run: SyncSession.Run
        run = newRun { _, _ ->
            inner = session.start(run)
            emptyResult()
        }

        assertTrue(session.start(run))

        assertEquals(false, inner)
        assertEquals(listOf<Pair<String?, Boolean>>(null to false), finished)
        assertFalse(session.running)
    }

    @Test fun failedSendFinishesWithItsMessage() {
        val session = session()

        session.start(newRun { _, _ -> throw IllegalStateException("No network.") })

        assertEquals(listOf<Pair<String?, Boolean>>("No network." to false), finished)
        assertEquals(0, afterRoundCount)
        assertFalse(session.running)
    }

    @Test fun failedSendWithoutMessageUsesDefaultText() {
        val session = session()

        session.start(newRun { _, _ -> throw RuntimeException() })

        assertEquals(listOf<Pair<String?, Boolean>>("Sync failed." to false), finished)
    }

    @Test fun requestAgainDuringRunCallsAgainOnceAfterFinish() {
        val session = session()

        session.start(newRun { _, _ ->
            session.requestAgain()
            emptyResult()
        })

        assertEquals(listOf<Pair<String?, Boolean>>(null to false), finished)
        assertEquals(1, againCount)
    }

    @Test fun requestAgainWithoutRunDoesNothing() {
        val session = session()

        session.requestAgain()
        session.start(newRun { _, _ -> emptyResult() })

        assertEquals(0, againCount)
    }

    @Test fun conflictsRunUpToMaxRounds() {
        val session = session()
        var sends = 0

        session.start(newRun { _, _ ->
            sends++
            emptyResult(conflicts = setOf("e1"))
        })

        assertEquals(SyncRunner.MAX_ROUNDS, sends)
        assertEquals(SyncRunner.MAX_ROUNDS, afterRoundCount)
        assertEquals(listOf<Pair<String?, Boolean>>(null to false), finished)
    }
}
