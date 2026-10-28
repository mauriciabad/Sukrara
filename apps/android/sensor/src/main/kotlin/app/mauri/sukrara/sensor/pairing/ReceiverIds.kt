// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor.pairing

import kotlinx.serialization.Serializable
import java.security.SecureRandom

/**
 * The 32-bit "receiver ID" a Libre 3 is bound to, for its whole life, when it is activated over NFC (0xA0). Taking
 * over a running sensor (0xA8, from another phone or app) works only with that same ID; a wrong one is refused (0xB1)
 * and changes nothing, so several can be tried in one tap.
 *
 * - The LibreView account's ID ([fromAccountId], learned by `LibreViewAccount`) is the one that matters:
 *   Juggluco and LibreLink-era apps signed in to the same account use it too, so a sensor started by any of them can be
 *   taken over by Sukrara and handed back later.
 * - Sukrara's own [ReceiverIdState.own] ID is random, made once; only a last takeover candidate.
 *   Sukrara never activates a sensor with it, so in practice it never succeeds: a sensor started with it could be taken
 *   over by no other app.
 */
object ReceiverIds {
    /** IDs to send for a never-started sensor: only the account's; null = send NOTHING (sign in to LibreView first). */
    fun forStart(account: Long?): List<Long>? = account?.let { listOf(it) }

    /**
     * IDs to try for a takeover, most likely first: the account's, then Sukrara's own. Null without an account ID: the own
     * ID alone can't succeed, so nothing is sent and the person is asked to sign in.
     */
    fun forTakeover(account: Long?, own: Long): List<Long>? = account?.let { listOf(it, own).distinct() }

    /** LibreView account ID (a 36-character GUID) → receiver ID (Juggluco's `getlibreaccountidnumber`). */
    fun fromAccountId(accountId: String): Long {
        var h = 0L
        for (c in accountId.toByteArray(Charsets.UTF_8)) h = ((h * 0x811C9DC5L) xor (c.toLong() and 0xff)) and 0xffffffffL
        return h
    }

    /** A new own ID, clear of the small IDs other apps use as defaults (Juggluco's manual fallback is 1001). */
    fun newOwn(random: SecureRandom = SecureRandom()): Long = (random.nextInt().toLong() and 0xffffffffL) or 0x10000000L
}

/**
 * The receiver IDs Sukrara knows (typed DataStore, `files/datastore/receiver-ids.json`). [own] survives sign-out and a
 * forgotten sensor. [account] and [accountEmail] are set by a LibreView sign-in and cleared by sign-out. No password
 * and no token are ever stored: the receiver ID never changes for an account.
 */
@Serializable
data class ReceiverIdState(val own: Long? = null, val account: Long? = null, val accountEmail: String? = null)
