package com.rearman.datebook

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps sync secrets (the 32-byte sync key and the device token) on this device.
 *
 * Each value is encrypted with an AES-256 key in the Android Keystore. The Keystore key
 * cannot leave the device. After a device restore that does not bring the Keystore key
 * back, [get] returns null for every name. The user then sets up sync again.
 *
 * Stored form: base64(iv(12) || ciphertext with GCM tag) in SharedPreferences "vault".
 */
object KeyVault {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "palm-sync-vault"
    private const val PREFS = "vault"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /** Encrypts [value] and saves it under [name]. Throws when the write to disk fails. */
    fun put(ctx: Context, name: String, value: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val blob = cipher.iv + cipher.doFinal(value)
        // commit() writes to disk before it returns, so setup reports success only after the save.
        val saved = prefs(ctx).edit().putString(name, Base64.getEncoder().encodeToString(blob)).commit()
        check(saved) { "vault write failed" }
    }

    /** Returns the decrypted value for [name], or null when it is missing or cannot be decrypted. */
    fun get(ctx: Context, name: String): ByteArray? {
        return try {
            val blob = Base64.getDecoder().decode(prefs(ctx).getString(name, null) ?: return null)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
            cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
        } catch (e: Exception) {
            null
        }
    }

    /** Deletes the entry for [name]. */
    fun remove(ctx: Context, name: String) {
        prefs(ctx).edit().remove(name).apply()
    }

    /** Loads the Keystore key, or creates it on first use. */
    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
