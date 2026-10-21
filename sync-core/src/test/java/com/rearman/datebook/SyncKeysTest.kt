package com.rearman.datebook

import com.rearman.palmsync.SyncCrypto
import com.rearman.palmsync.SyncMeta
import com.rearman.palmsync.b64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class SyncKeysTest {
    private fun metaFor(passphrase: String): Pair<ByteArray, SyncMeta> {
        val salt = SyncCrypto.newSalt()
        val key = SyncCrypto.deriveKey(passphrase.toCharArray(), salt)
        return key to SyncMeta(salt.b64(), SyncCrypto.checkBlob(key))
    }

    @Test fun rightPassphraseGivesTheKey() {
        val (key, meta) = metaFor("correct horse")

        assertArrayEquals(key, SyncKeys.keyFromMeta("correct horse".toCharArray(), meta))
    }

    @Test fun wrongPassphraseGivesNull() {
        val (_, meta) = metaFor("correct horse")

        assertNull(SyncKeys.keyFromMeta("battery staple".toCharArray(), meta))
    }

    @Test fun emptySaltThrows() {
        try {
            SyncKeys.keyFromMeta("any".toCharArray(), SyncMeta("", ""))
            fail("An empty salt must throw")
        } catch (e: IllegalStateException) {
            assertEquals("Sync setup failed.", e.message)
        }
    }
}
