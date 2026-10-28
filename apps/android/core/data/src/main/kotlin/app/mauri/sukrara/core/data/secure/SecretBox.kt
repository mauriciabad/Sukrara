// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core.data.secure

/**
 * AES-256-GCM with one non-exportable key per purpose ([alias]). The Android implementation is
 * [KeystoreSecretBox]; tests use a software key with the same format.
 *
 * Sealed format: 12-byte IV ‖ ciphertext ‖ 16-byte tag; [aad] (the secret's name) is authenticated, so a file
 * copied under another name doesn't open.
 */
interface SecretBox {
    /** Encrypts with the key [alias], creating the key the first time. */
    fun seal(
        alias: String,
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray

    /** Decrypts; throws [SecretOpenException]. Never deletes or replaces the key. */
    fun open(
        alias: String,
        sealed: ByteArray,
        aad: ByteArray,
    ): ByteArray
}

/** [transient] = retry later may work (Keystore busy, e.g. after an OS update); false = the data can't be opened. */
class SecretOpenException(
    val transient: Boolean,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** GCM parameters shared by every [SecretBox]. */
object SecretFormat {
    const val IV_BYTES = 12
    const val TAG_BITS = 128
    const val TRANSFORMATION = "AES/GCM/NoPadding"
}
