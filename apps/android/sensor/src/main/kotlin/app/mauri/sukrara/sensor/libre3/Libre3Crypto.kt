// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor.libre3

/**
 * The Libre 3 security core (`libsukrara_libre3.so`, built from Juggluco by this module's CMake, see `src/main/cpp`).
 * The JNI symbol names in `jni.c` are `Java_app_mauri_sukrara_sensor_libre3_Libre3Crypto_<function>`: renaming or
 * moving this object breaks them (Libre3CryptoTest checks both sides).
 *
 * A debug build made without Juggluco has no library: [isAvailable] is false and the sensor shows
 * [app.mauri.sukrara.core.model.SensorState.CoreMissing] instead of crashing. Release builds can't be made without it.
 */
internal object Libre3Crypto : Libre3Security {
    val isAvailable: Boolean =
        try {
            System.loadLibrary("sukrara_libre3")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }

    external override fun begin(
        kAuth: ByteArray?,
        securityVersion: Int,
    ): Long

    external override fun setPatchCertificate(
        ctx: Long,
        cert140: ByteArray,
    ): Boolean

    external override fun createEphemeralPublicKey(ctx: Long): ByteArray?

    external override fun deriveAuthorizationRoot(
        ctx: Long,
        patchEphemeral65: ByteArray,
    ): Boolean

    external override fun exportSavedAuthorization(ctx: Long): ByteArray?

    external override fun encryptChallengeReply(
        ctx: Long,
        nonce7: ByteArray,
        plain36: ByteArray,
    ): ByteArray?

    external override fun decryptChallengeResponse(
        ctx: Long,
        nonce7: ByteArray,
        cipher60: ByteArray,
    ): ByteArray?

    external override fun free(ctx: Long)

    external override fun ccmEncrypt(
        key16: ByteArray,
        nonce13: ByteArray,
        plain: ByteArray,
    ): ByteArray?

    external override fun ccmDecrypt(
        key16: ByteArray,
        nonce13: ByteArray,
        cipherAndTag: ByteArray,
    ): ByteArray?
}
