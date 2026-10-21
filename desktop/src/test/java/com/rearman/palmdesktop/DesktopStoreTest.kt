package com.rearman.palmdesktop

import com.rearman.datebook.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

class DesktopStoreTest {

	@get:Rule
	val tmp = TemporaryFolder()

	private val day = LocalDate.of(2026, 10, 19).toEpochDay()  // a Monday

	private fun file() = File(tmp.root, "sub/desktop.json")

	@Test
	fun newStoreHasFourDefaultCategories() {
		val store = DesktopStore.open(file())
		assertEquals(listOf("default-0-business", "default-0-personal"), store.categories.map { it.uid })
		assertEquals(listOf("default-1-business", "default-1-personal"), store.memoCategories.map { it.uid })
		assertEquals(listOf(1, 2), store.categories.map { it.id })
		assertEquals(listOf(3, 4), store.memoCategories.map { it.id })
	}

	@Test
	fun saveThenReopenKeepsRecords() {
		val store = DesktopStore.open(file())
		val work = store.addCategory("Work", 0)
		store.save(Event(title = "Dentist", date = day, start = 600, end = 630, alarm = true, alarmAdvance = 10,
			repeat = Repeat.WEEK, weekDays = 2, repeatEnd = day + 60))
		store.save(Event(title = "Lunch", date = day, untimed = true))
		store.save(Todo(title = "Report", priority = 2, due = day, category = work.id))
		store.save(Memo(text = "Shopping\nmilk"))
		store.save(Memo(text = "Ideas"))

		val reopened = DesktopStore.open(file())
		assertEquals(store.events.map { it.dupKey() }, reopened.events.map { it.dupKey() })
		assertEquals(store.events, reopened.events)
		assertEquals(store.todos, reopened.todos)
		assertEquals(store.memos, reopened.memos)
		assertEquals(store.categories, reopened.categories)
		assertEquals(store.memoCategories, reopened.memoCategories)
	}

	@Test
	fun deleteOfNeverSyncedRecordRemovesIt() {
		val store = DesktopStore.open(file())
		val e = Event(title = "Gone", date = day)
		store.save(e)
		store.delete(e)
		assertTrue(store.events.isEmpty())
		assertNull(store.syncRow(e.uid))
	}

	@Test
	fun deleteOfSyncedRecordLeavesTombstone() {
		val store = DesktopStore.open(file())
		val e = Event(title = "Kept", date = day)
		store.save(e)
		store.markSynced("events", e.uid, 5, clean = true)
		store.delete(e)
		assertTrue(store.events.isEmpty())
		val row = store.dirtyRows().single { it.table == "events" }
		assertTrue(row.deleted && row.dirty)
		assertEquals(e.uid, row.uid)
		assertEquals(5L, row.rev)
	}

	@Test
	fun putRemoteKeepsTheLocalId() {
		val store = DesktopStore.open(file())
		val e = Event(title = "Local", date = day)
		store.save(e)
		val remote = Event(title = "Remote", date = day, uid = e.uid)
		store.putRemote(remote, 7)
		assertEquals(e.id, remote.id)
		assertEquals(1, store.events.size)
		assertEquals("Remote", store.events[0].title)
		val row = store.syncRow(e.uid)!!
		assertEquals(7L, row.rev)
		assertTrue(!row.dirty && !row.deleted)
	}

	@Test
	fun dropRemoteCategoryMovesTodosToUnfiled() {
		val store = DesktopStore.open(file())
		val work = store.addCategory("Work", 0)
		val t = Todo(title = "Task", category = work.id)
		store.save(t)
		store.markSynced("todos", t.uid, 3, clean = true)
		store.dropRemote("categories", work.uid)
		assertEquals(0, store.todos[0].category)
		assertTrue(store.categories.none { it.uid == work.uid })
		val row = store.syncRow(t.uid)!!
		assertTrue(!row.dirty)
	}

	@Test
	fun purgeTodosDeletesDoneItems() {
		val store = DesktopStore.open(file())
		store.save(Todo(title = "Done", done = true))
		store.save(Todo(title = "Open"))
		assertEquals(1, store.purgeTodos())
		assertEquals(listOf("Open"), store.todos.map { it.title })
	}

	@Test
	fun weeklyEventOccursOnItsDaysOnly() {
		val store = DesktopStore.open(file())
		store.save(Event(title = "Standup", date = day, repeat = Repeat.WEEK, weekDays = 2))  // bit 1 is Monday
		assertEquals(1, store.dayEvents(day + 7).size)
		assertTrue(store.dayEvents(day + 1).isEmpty())
		assertEquals("Standup", store.dayEvents(day + 14)[0].event.title)
	}

	@Test
	fun findIgnoresCase() {
		val store = DesktopStore.open(file())
		store.save(Event(title = "Dentist", date = day))
		store.save(Todo(title = "Buy MILK"))
		store.save(Memo(text = "Call the DENTIST"))
		assertEquals(2, store.find("dentist").size)
		assertEquals(1, store.find("milk").size)
	}
}
