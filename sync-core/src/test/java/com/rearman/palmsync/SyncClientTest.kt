package com.rearman.palmsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncClientTest {
    @Test fun constructorRequiresHttps() {
        try {
            SyncClient("http://x", "t")
            assertTrue("expected IllegalArgumentException", false)
        } catch (e: IllegalArgumentException) {
            assertEquals("The server address must start with https://", e.message)
        }
    }
}
