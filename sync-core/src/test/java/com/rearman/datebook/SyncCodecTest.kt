package com.rearman.datebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncCodecTest {
    @Test fun eventRoundTripsAllFields() {
        val e = Event(
            title = "Dentist \"Dr. Who\"", note = "first\nsecond", date = 19000, start = 600, end = 660,
            alarm = true, alarmAdvance = 2, alarmUnit = 1, repeat = Repeat.WEEK, every = 2,
            repeatEnd = 19100, weekDays = 0x2A, exceptions = mutableSetOf(19007L, 19014L),
            isPrivate = true, uid = "e0"
        )
        val text = SyncCodec.event(e)
        assertEquals("event", SyncCodec.kind(text))
        val back = SyncCodec.toEvent(text, "u1")
        assertEquals("u1", back.uid)
        assertEquals(e.dupKey(), back.dupKey())
    }

    @Test fun todoCarriesCategoryByUid() {
        val t = Todo(title = "Call", note = "later", priority = 2, due = 19000, done = true,
            category = 7, isPrivate = true, uid = "t0")
        val text = SyncCodec.todo(t, "cat-uid")
        assertEquals("todo", SyncCodec.kind(text))
        val back = SyncCodec.toTodo(text, "u2") { if (it == "cat-uid") 7 else 0 }
        assertEquals(7, back.category)
        assertEquals(t.dupKey(), back.dupKey())
    }

    @Test fun todoWithoutCategoryGivesZero() {
        val t = Todo(title = "Call", category = 0)
        val back = SyncCodec.toTodo(SyncCodec.todo(t, ""), "u3") { if (it == "cat-uid") 7 else 0 }
        assertEquals(0, back.category)
    }

    @Test fun memoRoundTrips() {
        val m = Memo(text = "Title\nbody", category = 4, isPrivate = true, pos = 3, uid = "m0")
        val text = SyncCodec.memo(m, "cat-m")
        assertEquals("memo", SyncCodec.kind(text))
        val back = SyncCodec.toMemo(text, "m1") { if (it == "cat-m") 4 else 0 }
        assertEquals("Title\nbody", back.text)
        assertEquals(4, back.category)
        assertTrue(back.isPrivate)
        assertEquals(3, back.pos)
        assertEquals("m1", back.uid)
    }

    @Test fun categoryRoundTrips() {
        val text = SyncCodec.category(Category(5, "Work", 1, "c1"))
        assertEquals("category", SyncCodec.kind(text))
        assertEquals(Category(9, "Work", 1, "c1"), SyncCodec.toCategory(text, "c1", 9))
    }

    @Test fun shortcutsRoundTrip() {
        val list = listOf("Dentist" to "Dentist at ^T", "Mtg" to "Meeting")
        val text = SyncCodec.shortcuts(list)
        assertEquals("shortcuts", SyncCodec.kind(text))
        assertEquals(list, SyncCodec.toShortcuts(text))
    }

    @Test fun addressRoundTripsAllFields() {
        val a = Address(
            id = 5, lastName = "Earman", firstName = "Ross", title = "Mr.", company = "Palm Co",
            phoneLabels = listOf(0, 1, 2, 3, 6), phones = listOf("555-0100", "555-0101", "", "", "555-0104"),
            address = "1 Main St", city = "Springfield", state = "IL", zip = "62701", country = "USA",
            custom = listOf("c1", "", "c3", "c4"), note = "Note \"quoted\"", showPhone = 2, category = 7,
            isPrivate = true, uid = "a0"
        )
        val text = SyncCodec.address(a, "cat-7")
        assertEquals("address", SyncCodec.kind(text))
        val back = SyncCodec.toAddress(text, "u1") { if (it == "cat-7") 7 else 0 }
        assertEquals(a.copy(id = 0, uid = "u1"), back)
    }

    @Test fun kindOfNonJsonIsEmpty() {
        assertEquals("", SyncCodec.kind("not json"))
    }
}
