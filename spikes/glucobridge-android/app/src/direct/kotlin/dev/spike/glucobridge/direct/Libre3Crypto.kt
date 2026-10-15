package dev.spike.glucobridge.direct

/**
 * Bindings to the Libre 3 app-side security core (Juggluco's portable C, compiled by
 * src/direct/cpp/CMakeLists.txt) plus tinycrypt AES-128-CCM for the data plane.
 *
 * All functions return null / 0 on failure; none of them log key material.
 */
internal object Libre3Crypto {
    init { System.loadLibrary("glucobridge") }

    /** Opens a session from the saved 149-byte authorization record. Returns a native handle, or 0. */
    external fun begin(kAuth: ByteArray, securityVersion: Int): Long

    /** 36 bytes (r1‖r2‖PIN) -> 40 bytes for the ChallengeData characteristic. */
    external fun encryptChallengeReply(ctx: Long, nonce7: ByteArray, plain36: ByteArray): ByteArray?

    /** 60 bytes -> 56 bytes (r2‖r1‖kEnc[16]‖ivEnc[8]). */
    external fun decryptChallengeResponse(ctx: Long, nonce7: ByteArray, cipher60: ByteArray): ByteArray?

    external fun free(ctx: Long)

    /** Data-plane AES-128-CCM: 13-byte nonce, 4-byte tag. Input is ciphertext‖tag. */
    external fun ccmDecrypt(key16: ByteArray, nonce13: ByteArray, cipherAndTag: ByteArray): ByteArray?

    /** Same cipher, other direction: returns ciphertext‖tag. */
    external fun ccmEncrypt(key16: ByteArray, nonce13: ByteArray, plain: ByteArray): ByteArray?
}
