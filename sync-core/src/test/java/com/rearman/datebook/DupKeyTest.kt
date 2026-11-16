package com.rearman.datebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DupKeyTest {
    @Test fun eventIgnoresIdAndSetOrder() {
        // A one-time event drops its exceptions from the key, so the set order counts only in a repeat.
        val a = Event(id = 4, title = "Lunch", date = 20000, repeat = Repeat.DAY, exceptions = mutableSetOf(20007L, 20014L))
        val b = Event(title = "Lunch", date = 20000, repeat = Repeat.DAY, exceptions = sortedSetOf(20014L, 20007L))
        assertEquals(a.dupKey(), b.dupKey())
        assertEquals(a.dupKey().hashCode(), b.dupKey().hashCode())
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
