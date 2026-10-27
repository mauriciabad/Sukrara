// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.nostrid

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/**
 * A JSON document at rest, encrypted with an AES-256-GCM key that lives in the Android Keystore (TEE/StrongBox
 * backed where available, never exportable). Each role has its own key alias and file, so the owner's secrets
 * and the follower's secrets can't be read through each other's code path.
 *
 * File layout: 12-byte IV ‖ ciphertext+tag.
 */
class SecureStore(private val file: File, private val alias: String) {

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(): SecretKey {
        (keyStore().getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun exists() = file.exists()

    @Synchronized
    fun read(): JSONObject? {
        if (!file.exists()) return null
        val bytes = file.readBytes()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        return JSONObject(String(c.doFinal(bytes, 12, bytes.size - 12)))
    }

    @Synchronized
    fun write(j: JSONObject) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(j.toString().toByteArray())
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(out)
        check(tmp.renameTo(file)) { "rename failed" }
    }

    @Synchronized
    fun wipe() {
        file.delete()
        runCatching { keyStore().deleteEntry(alias) }
    }
}
