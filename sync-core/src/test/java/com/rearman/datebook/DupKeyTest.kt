package com.rearman.datebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.LocalDate

class DupKeyTest {
    @Test fun eventIgnoresIdAndSetOrder() {
        val a = Event(id = 4, title = "Lunch", date = 20000, exceptions = mutableSetOf(20007L, 20014L))
        val b = Event(title = "Lunch", date = 20000, exceptions = sortedSetOf(20014L, 20007L))
        assertEquals(a.dupKey(), b.dupKey())
        assertEquals(a.dupKey().hashCode(), b.dupKey().hashCode())
    }

    @Test fun eventDiffersOnAnyField() {
        val a = Event(title = "Lunch", date = 20000)
        assertNotEquals(a.dupKey(), a.copy(start = 12 * 60).dupKey())
        assertNotEquals(a.dupKey(), a.copy(note = "x").dupKey())
    }

    @Test fun eventIgnoresFieldsThatShowNothing() {
        val a = Event(title = "Holiday", date = 20000, untimed = true, start = 600, alarmAdvance = 9, every = 3)
        assertEquals(a.dupKey(), Event(title = "Holiday", date = 20000, untimed = true).dupKey())
        val w = Event(title = "Gym", date = LocalDate.of(2026, 10, 19).toEpochDay(), repeat = Repeat.WEEK)
        assertEquals(w.dupKey(), w.copy(weekDays = 1 shl 1).dupKey())
        assertNotEquals(w.dupKey(), w.copy(weekDays = 1 shl 2).dupKey())
    }

    @Test fun todoIgnoresIdOnly() {
        val a = Todo(id = 9, title = "Call", category = 2)
        assertEquals(a.dupKey(), Todo(title = "Call", category = 2).dupKey())
        assertNotEquals(a.dupKey(), Todo(title = "Call", category = 3).dupKey())
    }

    @Test fun memoIgnoresIdAndPos() {
        val a = Memo(id = 3, text = "Shopping", pos = 7)
        assertEquals(a.dupKey(), Memo(text = "Shopping").dupKey())
        assertNotEquals(a.dupKey(), Memo(text = "Shopping", isPrivate = true).dupKey())
    }

    @Test fun recordsIgnoreUid() {
        assertEquals(Event(title = "A", date = 20000, uid = "x").dupKey(), Event(title = "A", date = 20000, uid = "y").dupKey())
        assertEquals(Todo(title = "A", uid = "x").dupKey(), Todo(title = "A", uid = "y").dupKey())
        assertEquals(Memo(text = "A", uid = "x").dupKey(), Memo(text = "A", uid = "y").dupKey())
    }
}
