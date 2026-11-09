package com.rearman.datebook

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupTest {
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
