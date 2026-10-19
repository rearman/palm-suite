package com.rearman.datebook

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class SyncCryptoTest {
    private fun randomKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    @Test fun deriveKeyMatchesPbkdf2Vector() {
        val key = SyncCrypto.deriveKey("password".toCharArray(), "salt".toByteArray(), rounds = 1)
        val hex = key.joinToString("") { "%02x".format(it) }
        assertEquals("120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b", hex)
    }

    @Test fun sealThenOpenReturnsPlaintext() {
        val key = randomKey()
        val plain = "Lunch with Ann".toByteArray()
        val blob = SyncCrypto.seal(key, "event-1", plain)
        assertArrayEquals(plain, SyncCrypto.open(key, "event-1", blob))
    }

    @Test fun sealUsesRandomNonce() {
        val key = randomKey()
        val plain = "same text".toByteArray()
        assertNotEquals(SyncCrypto.seal(key, "id", plain), SyncCrypto.seal(key, "id", plain))
    }

    @Test fun openWithWrongKeyThrows() {
        val blob = SyncCrypto.seal(randomKey(), "id", "secret".toByteArray())
        assertThrows(Exception::class.java) { SyncCrypto.open(randomKey(), "id", blob) }
    }

    @Test fun openWithDifferentIdThrows() {
        val key = randomKey()
        val blob = SyncCrypto.seal(key, "id-a", "secret".toByteArray())
        assertThrows(Exception::class.java) { SyncCrypto.open(key, "id-b", blob) }
    }

    @Test fun checkBlobVerifiesOnlyWithItsKey() {
        val key = randomKey()
        val check = SyncCrypto.checkBlob(key)
        assertTrue(SyncCrypto.checkOk(key, check))
        assertFalse(SyncCrypto.checkOk(randomKey(), check))
        assertFalse(SyncCrypto.checkOk(key, "garbage"))
    }

    @Test fun newSaltIsSixteenBytes() {
        assertEquals(16, SyncCrypto.newSalt().size)
    }
}
