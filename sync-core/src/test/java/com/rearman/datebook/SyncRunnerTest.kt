package com.rearman.datebook

import com.rearman.palmsync.Change
import com.rearman.palmsync.SyncCrypto
import com.rearman.palmsync.SyncResult
import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SyncRunnerTest {
    private val key = ByteArray(32).also { SecureRandom().nextBytes(it) }

    private class Entry(
        val table: String,
        val uid: String,
        var rev: Long,
        var dirty: Boolean,
        var deleted: Boolean,
        var rec: Any?,
    ) {
        fun row() = SyncRow(table, 0, uid, rev, dirty, deleted)
    }

    /** An in-memory SyncStore. The rows are kept by uid, and each one holds its record. */
    private class FakeStore : SyncStore {
        private val tables = listOf("categories", "events", "todos", "memos", "addresses")
        val rows = LinkedHashMap<String, Entry>()
        val copies = mutableListOf<Any>()
        override var shortCuts: List<Pair<String, String>> = emptyList()
        override var shortCutsDirty = false
        override var shortCutsRev = 0L
        override var syncSince = 0L

        override fun dirtyRows(): List<SyncRow> =
            rows.values.filter { it.dirty }.sortedBy { tables.indexOf(it.table) }.map { it.row() }

        override fun syncRow(uid: String): SyncRow? = rows[uid]?.row()

        override fun record(row: SyncRow): Any? = rows[row.uid]?.takeIf { !it.deleted }?.rec

        override fun categoryUid(id: Int): String =
            if (id == 0) "" else rows.values.firstOrNull { it.table == "categories" && !it.deleted && (it.rec as Category).id == id }?.uid.orEmpty()

        override fun categoryIdForUid(uid: String, app: Int): Int =
            (rows[uid]?.takeIf { !it.deleted }?.rec as? Category)?.takeIf { it.app == app }?.id ?: 0

        override fun markSynced(table: String, uid: String, rev: Long, clean: Boolean) {
            rows[uid]?.let {
                it.rev = rev
                it.dirty = !clean
            }
        }

        override fun putRemote(e: Event, rev: Long) = put("events", e.uid, e, rev)
        override fun putRemote(t: Todo, rev: Long) = put("todos", t.uid, t, rev)
        override fun putRemote(m: Memo, rev: Long) = put("memos", m.uid, m, rev)
        override fun putRemote(c: Category, rev: Long) = put("categories", c.uid, c, rev)
        override fun putRemote(a: Address, rev: Long) = put("addresses", a.uid, a, rev)

        override fun dropRemote(table: String, uid: String) {
            rows.remove(uid)
        }

        override fun putTombstone(table: String, uid: String, rev: Long) {
            rows[uid] = Entry(table, uid, rev, true, true, null)
        }

        override fun resetSync() {
            rows.values.removeAll { it.deleted }
            rows.values.forEach {
                it.rev = 0
                it.dirty = true
            }
        }

        override fun saveCopy(record: Any) {
            val uid = "copy-" + (copies.size + 1)
            val (table, rec) = when (record) {
                is Event -> "events" to record.copy(uid = uid)
                is Todo -> "todos" to record.copy(uid = uid)
                is Memo -> "memos" to record.copy(uid = uid)
                is Category -> "categories" to record.copy(uid = uid)
                else -> return
            }
            copies.add(rec)
            rows[uid] = Entry(table, uid, 0, true, false, rec)
        }

        override fun setShortCutsFromSync(list: List<Pair<String, String>>, rev: Long) {
            shortCuts = list
            shortCutsRev = rev
            shortCutsDirty = false
        }

        fun add(table: String, uid: String, rec: Any, rev: Long = 0, dirty: Boolean = true, deleted: Boolean = false) {
            rows[uid] = Entry(table, uid, rev, dirty, deleted, rec)
        }

        private fun put(table: String, uid: String, rec: Any, rev: Long) {
            rows[uid] = Entry(table, uid, rev, false, false, rec)
        }
    }

    private fun event(title: String, uid: String) =
        Event(title = title, date = 19000L, start = 540, end = 600, uid = uid)

    private fun blob(k: ByteArray, uid: String, text: String): String =
        SyncCrypto.seal(k, uid, text.toByteArray(Charsets.UTF_8))

    /** A live change when [text] is not null, otherwise a delete. */
    private fun change(uid: String, rev: Long, text: String?, k: ByteArray = key): Change =
        if (text == null) Change(uid, rev, true, "") else Change(uid, rev, false, blob(k, uid, text))

    private fun result(rev: Long, accepted: Map<String, Long> = emptyMap(), changes: List<Change> = emptyList()) =
        SyncResult(rev, accepted, emptySet(), changes)

    @Test fun collectSealsDirtyRowsAndSendsTombstones() {
        val store = FakeStore()
        store.add("events", "e1", event("Dentist", "e1"), rev = 0)
        store.add("events", "e2", event("Gone", "e2"), rev = 3, deleted = true)

        val plan = SyncRunner.collect(store, key)

        assertEquals(2, plan.items.size)
        val live = plan.items[0]
        assertEquals("e1", live.id)
        assertFalse(live.deleted)
        val plain = PlainBytes.unpack(SyncCrypto.open(key, "e1", live.blob))
        assertEquals("event", SyncCodec.kind(plain))
        assertEquals("Dentist", SyncCodec.toEvent(plain, "e1").title)
        assertEquals(plain, plan.pushed["e1"])
        val tomb = plan.items[1]
        assertEquals("e2", tomb.id)
        assertTrue(tomb.deleted)
        assertEquals("", tomb.blob)
        assertEquals(3L, tomb.baseRev)
        assertTrue(plan.pushed.containsKey("e2"))
        assertNull(plan.pushed["e2"])
        assertEquals(store.syncSince, plan.since)
    }

    @Test fun acceptedUnchangedRowBecomesClean() {
        val store = FakeStore()
        store.add("events", "e1", event("Dentist", "e1"))
        val plan = SyncRunner.collect(store, key)

        val outcome = SyncRunner.apply(store, key, plan, result(5, accepted = mapOf("e1" to 5L)))

        assertEquals(5L, store.rows["e1"]!!.rev)
        assertFalse(store.rows["e1"]!!.dirty)
        assertTrue(outcome.noConflicts)
        assertFalse(outcome.changed)
    }

    @Test fun acceptedRowChangedAfterCollectStaysDirty() {
        val store = FakeStore()
        store.add("events", "e1", event("Dentist", "e1"))
        val plan = SyncRunner.collect(store, key)
        store.rows["e1"]!!.rec = event("Dentist, moved", "e1")

        SyncRunner.apply(store, key, plan, result(5, accepted = mapOf("e1" to 5L)))

        assertEquals(5L, store.rows["e1"]!!.rev)
        assertTrue(store.rows["e1"]!!.dirty)
    }

    @Test fun remoteNewEventIsInserted() {
        val store = FakeStore()
        val plan = SyncRunner.collect(store, key)
        val remote = SyncCodec.event(event("Remote", "e9"))

        val outcome = SyncRunner.apply(store, key, plan, result(4, changes = listOf(change("e9", 4, remote))))

        val rec = store.record(store.syncRow("e9")!!) as Event
        assertEquals("Remote", rec.title)
        assertEquals(4L, store.rows["e9"]!!.rev)
        assertFalse(store.rows["e9"]!!.dirty)
        assertTrue(outcome.changed)
        assertEquals(4L, store.syncSince)
    }

    @Test fun remoteNewAddressIsInsertedWithAddressCategory() {
        val store = FakeStore()
        store.add("categories", "cat-a", Category(6, "Work", 2, "cat-a"), dirty = false)
        val plan = SyncRunner.collect(store, key)
        val remote = SyncCodec.address(Address(lastName = "Earman", firstName = "Ross", uid = "a9"), "cat-a")

        val outcome = SyncRunner.apply(store, key, plan, result(4, changes = listOf(change("a9", 4, remote))))

        val rec = store.record(store.syncRow("a9")!!) as Address
        assertEquals("Earman", rec.lastName)
        assertEquals(6, rec.category)
        assertEquals(4L, store.rows["a9"]!!.rev)
        assertFalse(store.rows["a9"]!!.dirty)
        assertTrue(outcome.changed)
    }

    @Test fun dirtyLocalEventWithDifferentRemoteKeepsBoth() {
        val store = FakeStore()
        store.add("events", "e1", event("Local", "e1"))
        val plan = SyncRunner.collect(store, key)
        val remote = SyncCodec.event(event("Remote", "e1"))

        val outcome = SyncRunner.apply(store, key, plan, result(7, changes = listOf(change("e1", 7, remote))))

        assertEquals(1, store.copies.size)
        assertEquals("Local", (store.copies[0] as Event).title)
        assertEquals("Remote", (store.rows["e1"]!!.rec as Event).title)
        assertEquals(7L, store.rows["e1"]!!.rev)
        assertFalse(store.rows["e1"]!!.dirty)
        assertTrue(outcome.changed)
    }

    @Test fun dirtyLocalEventWithSameContentTakesRemoteWithoutCopy() {
        val store = FakeStore()
        store.add("events", "e1", event("Same", "e1"))
        val plan = SyncRunner.collect(store, key)
        val remote = SyncCodec.event(event("Same", "e1"))

        SyncRunner.apply(store, key, plan, result(7, changes = listOf(change("e1", 7, remote))))

        assertTrue(store.copies.isEmpty())
        assertEquals("Same", (store.rows["e1"]!!.rec as Event).title)
        assertFalse(store.rows["e1"]!!.dirty)
        assertEquals(7L, store.rows["e1"]!!.rev)
    }

    @Test fun remoteDeleteOfCleanRowDropsIt() {
        val store = FakeStore()
        store.add("events", "e1", event("Old", "e1"), rev = 2, dirty = false)
        val plan = SyncRunner.collect(store, key)

        val outcome = SyncRunner.apply(store, key, plan, result(8, changes = listOf(change("e1", 8, null))))

        assertNull(store.syncRow("e1"))
        assertTrue(outcome.changed)
    }

    @Test fun shortCutsDirtyWithRemoteChangeRebases() {
        val store = FakeStore()
        store.shortCuts = listOf("a" to "1")
        store.shortCutsDirty = true
        store.shortCutsRev = 3
        val plan = SyncRunner.collect(store, key)
        assertEquals(3L, plan.items.single { it.id == "shortcuts" }.baseRev)
        val remote = SyncCodec.shortcuts(listOf("b" to "2"))

        val outcome = SyncRunner.apply(store, key, plan, result(9, changes = listOf(change("shortcuts", 9, remote))))

        assertEquals(9L, store.shortCutsRev)
        assertTrue(store.shortCutsDirty)
        assertEquals(listOf("a" to "1"), store.shortCuts)
        assertFalse(outcome.shortCutsChanged)
        assertFalse(outcome.changed)
    }

    @Test fun recordDeletedDuringItsFirstPushBecomesDirtyTombstone() {
        val store = FakeStore()
        store.add("memos", "m1", Memo(text = "Short-lived", uid = "m1"))
        val plan = SyncRunner.collect(store, key)
        store.rows.remove("m1") // a rev-0 row is deleted outright

        SyncRunner.apply(store, key, plan, result(5, accepted = mapOf("m1" to 5L)))

        val row = store.syncRow("m1")!!
        assertEquals("memos", row.table)
        assertEquals(5L, row.rev)
        assertTrue(row.dirty)
        assertTrue(row.deleted)
        val next = SyncRunner.collect(store, key).items.single()
        assertTrue(next.deleted)
        assertEquals(5L, next.baseRev)
    }

    @Test fun oversizedRecordIsHeldBackAndTheRestIsPushed() {
        val store = FakeStore()
        // Random text does not deflate, so its blob is over the server's limit.
        val rnd = java.util.Random(1)
        val big = "Big\n" + String(CharArray(80_000) { ('!' + rnd.nextInt(90)) })
        store.add("memos", "m1", Memo(text = big, uid = "m1"))
        store.add("events", "e1", event("Dentist", "e1"))

        val plan = SyncRunner.collect(store, key)

        assertEquals(listOf("e1"), plan.items.map { it.id })
        assertFalse(plan.pushed.containsKey("m1"))
        assertEquals(listOf("Big"), plan.held)
        assertTrue(store.rows["m1"]!!.dirty)
    }

    @Test fun serverRevisionBelowSinceResetsForFullResync() {
        val store = FakeStore()
        store.syncSince = 500
        store.shortCutsRev = 480
        store.add("events", "e1", event("Clean", "e1"), rev = 470, dirty = false)
        store.add("events", "e2", event("Dirty", "e2"), rev = 490)
        store.add("events", "e3", event("Gone", "e3"), rev = 495, deleted = true)
        val plan = SyncRunner.collect(store, key)

        val outcome = SyncRunner.apply(store, key, plan, result(451, accepted = mapOf("e2" to 451L)))

        assertEquals(0L, store.syncSince)
        assertEquals(0L, store.shortCutsRev)
        assertNull(store.syncRow("e3"))
        for (uid in listOf("e1", "e2")) {
            assertEquals(0L, store.rows[uid]!!.rev)
            assertTrue(store.rows[uid]!!.dirty)
        }
        assertFalse(outcome.noConflicts)
        assertEquals(setOf("e1", "e2"), SyncRunner.collect(store, key).items.map { it.id }.toSet())
    }

    @Test fun blobSealedWithAnotherKeyThrowsAndChangesNothing() {
        val store = FakeStore()
        store.add("events", "e1", event("Local", "e1"))
        val plan = SyncRunner.collect(store, key)
        val other = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val remote = SyncCodec.event(event("Remote", "e1"))

        try {
            SyncRunner.apply(
                store, key, plan,
                result(7, accepted = mapOf("e1" to 5L), changes = listOf(change("e1", 7, remote, other))),
            )
            fail("A bad key must throw")
        } catch (e: IllegalStateException) {
            assertEquals("A record could not be decrypted. Check the passphrase.", e.message)
        }

        assertEquals(0L, store.rows["e1"]!!.rev)
        assertTrue(store.rows["e1"]!!.dirty)
        assertEquals("Local", (store.rows["e1"]!!.rec as Event).title)
        assertTrue(store.copies.isEmpty())
        assertEquals(0L, store.syncSince)
    }
}
