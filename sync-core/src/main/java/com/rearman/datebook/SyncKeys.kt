package com.rearman.datebook

import com.rearman.palmsync.SyncClient
import com.rearman.palmsync.SyncCrypto
import com.rearman.palmsync.SyncMeta
import com.rearman.palmsync.b64
import com.rearman.palmsync.unb64

/**
 * Sync key setup, shared by the Android app and the desktop app. The setup values are the salt
 * and the check blob that the server keeps (sync-server/PROTOCOL.md).
 *
 * Both functions do key derivation and network I/O. Call them on a worker thread.
 */
object SyncKeys {
    /**
     * Returns the sync key for [passphrase]. When the server has no setup values yet, it creates
     * a salt and a check blob and stores them. When another device stores them first (409), it
     * reads them again. Returns null for a wrong passphrase. Throws the errors of [SyncClient].
     */
    fun firstKey(client: SyncClient, passphrase: CharArray): ByteArray? {
        val meta = client.getMeta()
        if (meta.salt.isEmpty()) {
            val salt = SyncCrypto.newSalt()
            val key = SyncCrypto.deriveKey(passphrase, salt)
            if (client.putMeta(SyncMeta(salt.b64(), SyncCrypto.checkBlob(key)))) return key
            // 409: another device stored the first passphrase between the two calls.
            return keyFromMeta(passphrase, client.getMeta())
        }
        return keyFromMeta(passphrase, meta)
    }

    /**
     * Derives the key from the salt in [meta] and checks it against the check blob in [meta].
     * Returns null for a wrong passphrase. Throws IllegalStateException("Sync setup failed.") when
     * the salt is empty.
     */
    fun keyFromMeta(passphrase: CharArray, meta: SyncMeta): ByteArray? {
        if (meta.salt.isEmpty()) throw IllegalStateException("Sync setup failed.")
        val key = SyncCrypto.deriveKey(passphrase, meta.salt.unb64())
        return if (SyncCrypto.checkOk(key, meta.check)) key else null
    }
}
