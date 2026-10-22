package com.rearman.palmsync

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Sync encryption: PBKDF2-HMAC-SHA256 key derivation and AES-256-GCM sealing of records.
 * The wire format is described in sync-server/PROTOCOL.md, section 5.
 */
object SyncCrypto {
    const val ROUNDS = 600_000
    const val CHECK_ID = "check"
    const val CHECK_TEXT = "palm-suite"
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    fun newSalt(): ByteArray = ByteArray(16).also { random.nextBytes(it) }

    fun deriveKey(passphrase: CharArray, salt: ByteArray, rounds: Int = ROUNDS): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, rounds, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun seal(key: ByteArray, id: String, plain: ByteArray): String =
        seal(key, id, plain, ByteArray(NONCE_BYTES).also { random.nextBytes(it) })

    /** Seals with a given [nonce]. Only the test vectors pass one: a nonce must never repeat under one key. */
    internal fun seal(key: ByteArray, id: String, plain: ByteArray, nonce: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
        return (nonce + cipher.doFinal(plain)).b64()
    }

    fun open(key: ByteArray, id: String, blob: String): ByteArray {
        val raw = blob.unb64()
        require(raw.size > NONCE_BYTES + TAG_BITS / 8) { "sync blob too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, raw.copyOfRange(0, NONCE_BYTES))
        )
        cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(raw.copyOfRange(NONCE_BYTES, raw.size))
    }

    fun checkBlob(key: ByteArray): String = seal(key, CHECK_ID, CHECK_TEXT.toByteArray())

    fun checkOk(key: ByteArray, check: String): Boolean =
        try {
            open(key, CHECK_ID, check).contentEquals(CHECK_TEXT.toByteArray())
        } catch (e: Exception) {
            false
        }
}

fun ByteArray.b64(): String = Base64.getEncoder().encodeToString(this)

fun String.unb64(): ByteArray = Base64.getDecoder().decode(this)
