// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core.data

import app.mauri.sukrara.core.data.secure.SecretBox
import app.mauri.sukrara.core.data.secure.SecretFormat
import app.mauri.sukrara.core.data.secure.SecretOpenException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** [SecretBox] with in-memory software keys and the Keystore box's format; [busy] simulates a transient Keystore error. */
class SoftwareSecretBox : SecretBox {
    private val keys = mutableMapOf<String, SecretKey>()
    var busy = false

    override fun seal(
        alias: String,
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        val key = keys.getOrPut(alias) { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
        val cipher = Cipher.getInstance(SecretFormat.TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad)
        return cipher.iv + cipher.doFinal(plaintext)
    }

    override fun open(
        alias: String,
        sealed: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        if (busy) throw SecretOpenException(transient = true, "busy")
        val key = keys[alias] ?: throw SecretOpenException(transient = false, "no key")
        if (sealed.size <= SecretFormat.IV_BYTES) throw SecretOpenException(transient = false, "too short")
        return try {
            val cipher = Cipher.getInstance(SecretFormat.TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(SecretFormat.TAG_BITS, sealed, 0, SecretFormat.IV_BYTES))
            cipher.updateAAD(aad)
            cipher.doFinal(sealed, SecretFormat.IV_BYTES, sealed.size - SecretFormat.IV_BYTES)
        } catch (e: AEADBadTagException) {
            throw SecretOpenException(transient = false, "authentication failed", e)
        }
    }
}
