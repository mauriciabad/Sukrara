// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core.data.secure

import android.security.KeyStoreException
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [SecretBox] on the Android Keystore (TEE / StrongBox backed where available; keys never leave it).
 *
 * Failure policy (a busy Keystore after an OS update must not cost the person their identity): only a bad tag, a
 * permanently invalidated or missing key count as "can't be opened"; every other Keystore error is transient and
 * nothing is deleted.
 */
class KeystoreSecretBox : SecretBox {
    private val keyStore: KeyStore by lazy { KeyStore.getInstance(PROVIDER).apply { load(null) } }

    @Synchronized
    override fun seal(
        alias: String,
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance(SecretFormat.TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, existingKey(alias) ?: createKey(alias))
        cipher.updateAAD(aad)
        return cipher.iv + cipher.doFinal(plaintext)
    }

    @Synchronized
    override fun open(
        alias: String,
        sealed: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        if (sealed.size <= SecretFormat.IV_BYTES) throw SecretOpenException(transient = false, "too short")
        val key = loadKey(alias) ?: throw SecretOpenException(transient = false, "no key $alias")
        try {
            val cipher = Cipher.getInstance(SecretFormat.TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(SecretFormat.TAG_BITS, sealed, 0, SecretFormat.IV_BYTES))
            cipher.updateAAD(aad)
            return cipher.doFinal(sealed, SecretFormat.IV_BYTES, sealed.size - SecretFormat.IV_BYTES)
        } catch (e: AEADBadTagException) {
            throw SecretOpenException(transient = false, "authentication failed", e)
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw SecretOpenException(transient = false, "key invalidated", e)
        } catch (e: GeneralSecurityException) {
            throw SecretOpenException(transient = isTransient(e), "keystore error", e)
        } catch (e: ProviderException) {
            throw SecretOpenException(transient = isTransient(e), "keystore provider error", e)
        }
    }

    private fun existingKey(alias: String): SecretKey? = keyStore.getKey(alias, null) as? SecretKey

    /** The key for opening; any failure to reach the Keystore (I/O, provider, busy) is transient, never "no key". */
    @Suppress("TooGenericExceptionCaught") // KeyStore.load / getKey wrap system errors in many types
    private fun loadKey(alias: String): SecretKey? =
        try {
            existingKey(alias)
        } catch (e: Exception) {
            throw SecretOpenException(transient = true, "key not loadable", e)
        }

    private fun createKey(alias: String): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    /** A platform [KeyStoreException] in the cause chain says whether the key itself is gone; otherwise assume transient. */
    private fun isTransient(error: Throwable): Boolean {
        val keystore = generateSequence(error) { it.cause }.filterIsInstance<KeyStoreException>().firstOrNull()
            ?: return true
        return when (keystore.numericErrorCode) {
            KeyStoreException.ERROR_KEY_DOES_NOT_EXIST, KeyStoreException.ERROR_KEY_CORRUPTED -> false
            else -> true
        }
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val KEY_BITS = 256
    }
}
