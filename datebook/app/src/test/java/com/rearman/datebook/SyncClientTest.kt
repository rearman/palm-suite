package com.rearman.datebook

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncClientTest {
    @Test fun pushBodyRoundTrips() {
        val push = listOf(
            PushItem("a", 3, false, "QUJD"),
            PushItem("b", 1, true, "not used"),
        )
        val o = JSONObject(SyncClient.pushBody(7, push))
        assertEquals(7L, o.getLong("since"))
        val items = o.getJSONArray("push")
        assertEquals(2, items.length())
        val first = items.getJSONObject(0)
        assertEquals("a", first.getString("id"))
        assertEquals(3L, first.getLong("baseRev"))
        assertEquals(false, first.getBoolean("deleted"))
        assertEquals("QUJD", first.getString("blob"))
        val second = items.getJSONObject(1)
        assertEquals("b", second.getString("id"))
        assertEquals(1L, second.getLong("baseRev"))
        assertEquals(true, second.getBoolean("deleted"))
        assertEquals("", second.getString("blob"))
    }

    @Test fun parseResultReadsAllFields() {
        val text = "{\"rev\":12," +
            "\"accepted\":[{\"id\":\"a\",\"rev\":11}]," +
            "\"conflicts\":[\"b\"]," +
            "\"changes\":[" +
            "{\"id\":\"c\",\"rev\":10,\"deleted\":true,\"blob\":\"\"}," +
            "{\"id\":\"a\",\"rev\":11,\"deleted\":false,\"blob\":\"QUJD\"}]}"
        val r = SyncClient.parseResult(text)
        assertEquals(12L, r.rev)
        assertEquals(mapOf("a" to 11L), r.accepted)
        assertEquals(setOf("b"), r.conflicts)
        assertEquals(2, r.changes.size)
        assertEquals(Change("c", 10, true, ""), r.changes[0])
        assertEquals(Change("a", 11, false, "QUJD"), r.changes[1])
    }

    @Test fun parseMetaReadsEmptyFields() {
        assertEquals(SyncMeta("", ""), SyncClient.parseMeta("{\"salt\":\"\",\"check\":\"\"}"))
    }

    @Test fun constructorRequiresHttps() {
        try {
            SyncClient("http://x", "t")
            assertTrue("expected IllegalArgumentException", false)
        } catch (e: IllegalArgumentException) {
            assertEquals("The server address must start with https://", e.message)
        }
    }
}
