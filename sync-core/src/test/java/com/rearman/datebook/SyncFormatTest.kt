package com.rearman.datebook

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Format 2 (short keys, defaults left out) and format 1 (long keys), and the plaintext compression. */
class SyncFormatTest {
    private val cat = { uid: String -> if (uid == "cat-1") 3 else if (uid == "cat-7") 7 else 0 }

    @Test fun eventRoundTripsThroughFormat2() {
        val e = Event(
            title = "Dentist \"Dr. Who\"", note = "first\nsecond", date = 19000, start = 600, end = 660,
            alarm = true, alarmAdvance = 2, alarmUnit = 1, repeat = Repeat.WEEK, every = 2,
            repeatEnd = 19100, weekDays = 0x2A, exceptions = mutableSetOf(19014L, 19007L),
            isPrivate = true, uid = "e0"
        )
        val text = SyncCodec.event(e)
        assertEquals("event", SyncCodec.kind(text))
        assertEquals("e", JSONObject(text).getString("k"))
        assertEquals(e.copy(uid = "u1"), SyncCodec.toEvent(text, "u1"))
    }

    @Test fun defaultEventWritesOnlyKindAndTitle() {
        // date 0 is the default; Event() alone would hold today's date.
        val text = SyncCodec.event(Event(title = "Dentist", date = 0))
        assertEquals(setOf("k", "t"), JSONObject(text).keys().asSequence().toSet())
        assertEquals("Dentist", SyncCodec.toEvent(text, "u1").title)
    }

    @Test fun todoRoundTripsThroughFormat2() {
        val t = Todo(
            title = "Call", note = "later", priority = 2, due = 19000, done = true,
            category = 7, isPrivate = true, uid = "t0"
        )
        val text = SyncCodec.todo(t, "cat-7")
        assertEquals("todo", SyncCodec.kind(text))
        assertEquals(t.copy(uid = "t1"), SyncCodec.toTodo(text, "t1", cat))
    }

    @Test fun memoRoundTripsThroughFormat2() {
        val m = Memo(text = "Title\nbody", category = 3, isPrivate = true, pos = 3, uid = "m0")
        val text = SyncCodec.memo(m, "cat-1")
        assertEquals("memo", SyncCodec.kind(text))
        assertEquals(m.copy(uid = "m1"), SyncCodec.toMemo(text, "m1", cat))
    }

    @Test fun addressRoundTripsThroughFormat2() {
        val a = Address(
            lastName = "Earman", firstName = "Ross", company = "Palm Co",
            phoneLabels = listOf(7, 1, 2, 3, 4), phones = listOf("555-0100", "555-0101", "", "", ""),
            showPhone = 1, category = 7, isPrivate = true, uid = "a0"
        )
        val text = SyncCodec.address(a, "cat-7")
        assertEquals("address", SyncCodec.kind(text))
        assertEquals(a.copy(uid = "a1"), SyncCodec.toAddress(text, "a1", cat))
    }

    @Test fun categoryRoundTripsThroughFormat2() {
        val c = Category(5, "Work", 2, "c1")
        val text = SyncCodec.category(c)
        assertEquals("category", SyncCodec.kind(text))
        assertEquals(Category(9, "Work", 2, "c1"), SyncCodec.toCategory(text, "c1", 9))
    }

    @Test fun shortcutsRoundTripThroughFormat2() {
        val list = listOf("Dentist" to "Dentist at ^T", "Mtg" to "Meeting")
        val text = SyncCodec.shortcuts(list)
        assertEquals("shortcuts", SyncCodec.kind(text))
        assertEquals(list, SyncCodec.toShortcuts(text))
    }

    @Test fun eventDecodesFromFormat1() {
        val text = """{"kind":"event","title":"Dentist","note":"","date":19000,"untimed":false,"start":600,"end":660,"alarm":true,"alarmAdvance":2,"alarmUnit":1,"repeat":2,"every":2,"repeatEnd":19100,"weekDays":42,"exceptions":[19007,19014],"private":true}"""
        assertEquals("event", SyncCodec.kind(text))
        assertEquals(
            Event(
                title = "Dentist", date = 19000, start = 600, end = 660, alarm = true, alarmAdvance = 2,
                alarmUnit = 1, repeat = Repeat.WEEK, every = 2, repeatEnd = 19100, weekDays = 0x2A,
                exceptions = mutableSetOf(19007L, 19014L), isPrivate = true, uid = "u1"
            ),
            SyncCodec.toEvent(text, "u1")
        )
    }

    @Test fun todoDecodesFromFormat1() {
        val text = """{"kind":"todo","title":"Call","note":"later","priority":2,"due":19000,"done":true,"category":"cat-1","private":true}"""
        assertEquals("todo", SyncCodec.kind(text))
        assertEquals(
            Todo(title = "Call", note = "later", priority = 2, due = 19000, done = true, category = 3, isPrivate = true, uid = "t1"),
            SyncCodec.toTodo(text, "t1", cat)
        )
    }

    @Test fun memoDecodesFromFormat1() {
        val text = """{"kind":"memo","text":"Title\nbody","category":"","private":false,"pos":3}"""
        assertEquals("memo", SyncCodec.kind(text))
        assertEquals(Memo(text = "Title\nbody", pos = 3, uid = "m1"), SyncCodec.toMemo(text, "m1", cat))
    }

    @Test fun addressDecodesFromFormat1() {
        val text = """{"kind":"address","lastName":"Earman","firstName":"Ross","title":"","company":"Palm Co","phoneLabels":[7,1,2,3,4],"phones":["555-0100","555-0101","","",""],"address":"","city":"","state":"","zip":"","country":"","custom":["","","",""],"note":"","showPhone":1,"category":"cat-7","private":false}"""
        assertEquals("address", SyncCodec.kind(text))
        assertEquals(
            Address(
                lastName = "Earman", firstName = "Ross", company = "Palm Co", phoneLabels = listOf(7, 1, 2, 3, 4),
                phones = listOf("555-0100", "555-0101", "", "", ""), showPhone = 1, category = 7, uid = "a1"
            ),
            SyncCodec.toAddress(text, "a1", cat)
        )
    }

    @Test fun categoryDecodesFromFormat1() {
        val text = """{"kind":"category","name":"Work","app":2}"""
        assertEquals("category", SyncCodec.kind(text))
        assertEquals(Category(9, "Work", 2, "c1"), SyncCodec.toCategory(text, "c1", 9))
    }

    @Test fun shortcutsDecodeFromFormat1() {
        val text = """{"kind":"shortcuts","list":[{"name":"Dentist","text":"Dentist at ^T"},{"name":"Mtg","text":"Meeting"}]}"""
        assertEquals("shortcuts", SyncCodec.kind(text))
        assertEquals(listOf("Dentist" to "Dentist at ^T", "Mtg" to "Meeting"), SyncCodec.toShortcuts(text))
    }

    @Test fun shortPlainTextStaysUtf8Json() {
        val json = SyncCodec.memo(Memo(text = "A"), "")
        val packed = PlainBytes.pack(json)
        assertEquals('{'.code.toByte(), packed[0])
        assertArrayEquals(json.toByteArray(Charsets.UTF_8), packed)
        assertEquals(json, PlainBytes.unpack(packed))
    }

    @Test fun longMemoIsDeflatedAndUnpacksToItsText() {
        val text = buildString {
            var i = 1
            while (length < 2000) append("Memo line ").append(i++).append('\n')
        }
        val json = SyncCodec.memo(Memo(text = text, uid = "m1"), "")
        val packed = PlainBytes.pack(json)
        assertEquals(1.toByte(), packed[0])
        assertTrue(packed.size < json.toByteArray(Charsets.UTF_8).size)
        assertEquals(json, PlainBytes.unpack(packed))
    }
}
