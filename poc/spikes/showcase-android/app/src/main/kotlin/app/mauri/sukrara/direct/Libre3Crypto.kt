// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.direct

/**
 * The Libre 3 app-side security core as [Libre3Driver] uses it: the challenge handshake (a session handle from the
 * saved authorization or a fresh pairing) plus AES-128-CCM for the data plane. [Libre3Crypto] is the real one (JNI);
 * `Libre3DriverTest` replays sessions through a scripted one.
 *
 * All functions return null / 0 / false on failure; none of them log key material.
 */
internal interface Libre3Security {
    /**
     * Opens a session from the saved 149-byte authorization record, or a fresh pairing when [kAuth] is null.
     * Returns a native handle, or 0.
     */
    fun begin(kAuth: ByteArray?, securityVersion: Int): Long

    /** Fresh pairing: the sensor's 140-byte certificate. */
    fun setPatchCertificate(ctx: Long, cert140: ByteArray): Boolean

    /** Fresh pairing: our ephemeral public key, 64 bytes (X‖Y). */
    fun createEphemeralPublicKey(ctx: Long): ByteArray?

    /** Fresh pairing: the sensor's 65-byte ephemeral public key. */
    fun deriveAuthorizationRoot(ctx: Long, patchEphemeral65: ByteArray): Boolean

    /** After the challenge succeeded: the 149-byte record that resumes later sessions (a secret). */
    fun exportSavedAuthorization(ctx: Long): ByteArray?

    /** 36 bytes (r1‖r2‖PIN) -> 40 bytes for the ChallengeData characteristic. */
    fun encryptChallengeReply(ctx: Long, nonce7: ByteArray, plain36: ByteArray): ByteArray?

    /** 60 bytes -> 56 bytes (r2‖r1‖kEnc[16]‖ivEnc[8]). */
    fun decryptChallengeResponse(ctx: Long, nonce7: ByteArray, cipher60: ByteArray): ByteArray?

    fun free(ctx: Long)

    /** Data-plane AES-128-CCM: 13-byte nonce, 4-byte tag. Input is ciphertext‖tag. */
    fun ccmDecrypt(key16: ByteArray, nonce13: ByteArray, cipherAndTag: ByteArray): ByteArray?

    /** Same cipher, other direction: returns ciphertext‖tag. */
    fun ccmEncrypt(key16: ByteArray, nonce13: ByteArray, plain: ByteArray): ByteArray?
}

/**
 * Bindings to the Libre 3 app-side security core (Juggluco's portable C, compiled by src/main/cpp/CMakeLists.txt)
 * plus tinycrypt AES-128-CCM for the data plane. The JNI symbols are bound to this class's name: keep it here.
 */
internal object Libre3Crypto : Libre3Security {
    init { System.loadLibrary("sukrara_libre3") }

    external override fun begin(kAuth: ByteArray?, securityVersion: Int): Long
    external override fun setPatchCertificate(ctx: Long, cert140: ByteArray): Boolean
    external override fun createEphemeralPublicKey(ctx: Long): ByteArray?
    external override fun deriveAuthorizationRoot(ctx: Long, patchEphemeral65: ByteArray): Boolean
    external override fun exportSavedAuthorization(ctx: Long): ByteArray?
    external override fun encryptChallengeReply(ctx: Long, nonce7: ByteArray, plain36: ByteArray): ByteArray?
    external override fun decryptChallengeResponse(ctx: Long, nonce7: ByteArray, cipher60: ByteArray): ByteArray?
    external override fun free(ctx: Long)
    external override fun ccmDecrypt(key16: ByteArray, nonce13: ByteArray, cipherAndTag: ByteArray): ByteArray?
    external override fun ccmEncrypt(key16: ByteArray, nonce13: ByteArray, plain: ByteArray): ByteArray?
}
