// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.protocol

import java.security.MessageDigest

/** Byte helpers shared by the codecs. JCA SHA-256 only; no primitive is implemented here. */
internal object Hex {
    private val HEX64 = Regex("[0-9a-f]{64}")
    private val HEX = Regex("([0-9a-f]{2})*")

    fun encode(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** Lowercase hex → bytes; null for odd length or any other character. */
    fun decode(hex: String): ByteArray? =
        if (!HEX.matches(hex)) null else ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    fun isKey(s: String): Boolean = HEX64.matches(s)

    fun isHex(s: String, minLen: Int, maxLen: Int): Boolean = s.length in minLen..maxLen && s.all { it in '0'..'9' || it in 'a'..'f' }

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
