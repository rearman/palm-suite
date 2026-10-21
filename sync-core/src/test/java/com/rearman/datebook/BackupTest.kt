package com.rearman.datebook

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {
    private val names = mapOf(1 to "Work", 2 to "Ideas", 3 to "Clients")

    private fun sample() = Backup.Content(
        events = listOf(
            Event(
                title = "Staff meeting", note = "Room 4", date = 19_000, untimed = false,
                start = 540, end = 600, alarm = true, alarmAdvance = 10, alarmUnit = 0,
                repeat = Repeat.WEEK, every = 2, repeatEnd = 19_200, weekDays = 0b0010100,
                exceptions = mutableSetOf(19_014L, 19_028L), isPrivate = true
            )
        ),
        todos = listOf(Todo(title = "Call", note = "Before noon", priority = 2, due = 19_010, done = true, category = 1)),
        memos = listOf(Memo(text = "Ideas list\nsecond line", category = 2)),
        addresses = listOf(
            Address(
                lastName = "Carter", firstName = "Melanie", title = "Dr", company = "Acme",
                phoneLabels = listOf(0, 1, 2, 3, 7), phones = listOf("555-1", "", "555-3", "", "555-9"),
                address = "1 Main St", city = "Oslo", state = "NY", zip = "12345", country = "US",
                custom = listOf("a", "", "", "d"), note = "Note", showPhone = 4, category = 3, isPrivate = true
            )
        )
    )

    /** The category id of each name: id 1 To Do, 2 Memo Pad, 3 Address Book. */
    private val ids = mapOf("Work" to 1, "Ideas" to 2, "Clients" to 3)

    @Test fun roundTripKeepsEveryField() {
        val text = Backup.write(sample()) { names[it] ?: "Unfiled" }
        val back = Backup.read(text) { name, _ -> ids.getValue(name) }
        val c = sample()
        assertEquals(c.events, back.events)
        assertEquals(c.todos, back.todos)
        assertEquals(c.memos, back.memos)
        assertEquals(c.addresses, back.addresses)
    }

    @Test fun writesTheAndroidKeys() {
        val root = JSONObject(Backup.write(sample()) { names[it] ?: "Unfiled" })
        assertEquals(Backup.FORMAT, root.getString("format"))
        for (k in listOf("categories", "memoCategories", "addressCategories", "events", "todos", "memos", "addresses")) {
            assertTrue("missing key $k", root.has(k))
        }
        assertEquals("Work", root.getJSONArray("todos").getJSONObject(0).getString("category"))
        assertEquals("Clients", root.getJSONArray("addresses").getJSONObject(0).getString("category"))
    }

    @Test fun emptyCategorySurvivesWriteAndRead() {
        val c = Backup.Content(emptyList(), emptyList(), emptyList(), emptyList(), todoCategories = listOf("Errands"))
        val text = Backup.write(c) { "Unfiled" }
        assertEquals("Errands", JSONObject(text).getJSONArray("categories").getString(0))
        val back = Backup.read(text) { name, _ -> ids[name] ?: 0 }
        assertEquals(listOf("Errands"), back.todoCategories)
        assertEquals(emptyList<String>(), back.memoCategories)
    }

    @Test fun desktopFileWithoutAddressesReadsEmptyAddressList() {
        val root = JSONObject(Backup.write(sample()) { names[it] ?: "Unfiled" })
        root.remove("addresses")
        root.remove("addressCategories")
        val back = Backup.read(root.toString()) { name, _ -> ids.getValue(name) }
        assertEquals(emptyList<Address>(), back.addresses)
        assertEquals(sample().events, back.events)
    }

    @Test fun unknownCategoryNameCallsCategoryIdWithItsApp() {
        val calls = mutableListOf<Pair<String, Int>>()
        Backup.read(Backup.write(sample()) { names[it] ?: "Unfiled" }) { name, app ->
            calls.add(name to app)
            40 + app
        }
        assertTrue(calls.contains("Work" to 0))
        assertTrue(calls.contains("Ideas" to 1))
        assertTrue(calls.contains("Clients" to 2))
    }

    @Test fun unfiledIsZeroWithoutCallingCategoryId() {
        val text = Backup.write(Backup.Content(emptyList(), listOf(Todo(title = "x")), emptyList(), emptyList())) { "Unfiled" }
        var calls = 0
        val back = Backup.read(text) { _, _ -> calls++; 99 }
        assertEquals(0, back.todos[0].category)
        assertEquals(0, calls)
    }

    @Test fun badFormatThrowsBeforeAnyCategoryCall() {
        var calls = 0
        try {
            Backup.read("{\"format\":\"other\",\"categories\":[\"Work\"]}") { _, _ -> calls++; 1 }
            throw AssertionError("expected JSONException")
        } catch (e: JSONException) {
            assertEquals(0, calls)
        }
    }
}
