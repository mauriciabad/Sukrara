// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.protocol.owner

import app.mauri.sukrara.protocol.NostrCrypto
import app.mauri.sukrara.protocol.SecretKey

/**
 * The identity backup (PROTOCOL-v2 §Backup): the identity's `nsec`, or a NIP-49 `ncryptsec` (log_n 18, key_security
 * 0x01) for a printable recovery sheet. Restoring with it keeps the wearer's existing followers.
 */
object RecoveryCode {
    const val LOG_N = 18

    /** NIP-49 key_security 0x01: not known to have been handled insecurely. */
    const val KEY_SECURITY = 0x01

    fun export(crypto: NostrCrypto, identity: SecretKey, password: String): String =
        crypto.ncryptsec(identity, password, LOG_N, KEY_SECURITY)

    sealed interface Import {
        data class Restored(val identity: SecretKey) : Import

        /** An ncryptsec: ask for its password, then call [import] again with it. */
        data object NeedsPassword : Import

        data object WrongPassword : Import

        data object NotAKey : Import
    }

    /** Whatever the person pasted: `nsec1…`, `ncryptsec1…` (with [password]) or 64 hex characters. */
    fun import(crypto: NostrCrypto, text: String, password: String?): Import {
        val t = text.trim()
        return when {
            t.startsWith("ncryptsec1") -> when {
                password.isNullOrEmpty() -> Import.NeedsPassword
                else -> crypto.parseNcryptsec(t, password)?.let(Import::Restored) ?: Import.WrongPassword
            }
            t.startsWith("nsec1") -> crypto.parseNsec(t)?.let(Import::Restored) ?: Import.NotAKey
            else -> SecretKey.ofHex(t.lowercase())?.takeIf { crypto.keyPair(it) != null }?.let(Import::Restored) ?: Import.NotAKey
        }
    }
}
