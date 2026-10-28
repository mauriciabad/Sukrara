// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.protocol.owner

import app.mauri.sukrara.protocol.KeyPair
import app.mauri.sukrara.protocol.NostrCrypto
import app.mauri.sukrara.protocol.SignedEvent
import app.mauri.sukrara.protocol.Wire
import app.mauri.sukrara.protocol.event.BackupCodec
import app.mauri.sukrara.protocol.event.RosterCodec

/**
 * What the wearer's phone must persist to tell its own roster / backup from another app publishing as the same
 * identity (e.g. the old phone still running after a restore): its device key, the read keys it holds by `gk`
 * (epoch number → public key), and the `created_at` of the last roster and backup it published.
 */
data class OwnerMarks(
    val identity: KeyPair,
    val device: String,
    val sid: String,
    val epochsByGk: Map<String, Pair<Int, KeyPair>>,
    val lastRosterAt: Long,
    val lastBackupAt: Long,
)

/** The verdict on one roster or backup signed by our identity. */
sealed interface IdentityCheck {
    /** Ours (an echo), older, or not about our stream: nothing to do. */
    data object Fine : IdentityCheck

    /**
     * Another device publishes as this identity. The engine stops republishing roster / backup and shows a blocking
     * state ("Sukrara is also running somewhere else with this identity") instead of fighting it.
     */
    data class Conflict(val reason: Reason, val otherDevices: List<String> = emptyList()) : IdentityCheck

    enum class Reason { RosterFromOtherDevice, RosterUnknownEpoch, BackupFromElsewhere }
}

/** Detects "another app is publishing as this identity" from the owner's own roster and backup events. */
object IdentityConflict {
    fun check(crypto: NostrCrypto, marks: OwnerMarks, ev: SignedEvent): IdentityCheck {
        if (ev.pubkey != marks.identity.public.hex || ev.kind != Wire.KIND_DATA || !crypto.verify(ev)) return IdentityCheck.Fine
        return when (ev.tag("d")) {
            Wire.rosterD(marks.sid) -> roster(crypto, marks, ev)
            Wire.backupD(marks.identity.public) -> backup(crypto, marks, ev)
            else -> IdentityCheck.Fine
        }
    }

    private fun roster(crypto: NostrCrypto, marks: OwnerMarks, ev: SignedEvent): IdentityCheck {
        if (ev.createdAt <= marks.lastRosterAt) return IdentityCheck.Fine
        val (_, readKey) = marks.epochsByGk[ev.tag("gk")] ?: return IdentityCheck.Conflict(IdentityCheck.Reason.RosterUnknownEpoch)
        val plain = crypto.decrypt(readKey.secret, marks.identity.public, ev.content) ?: return IdentityCheck.Fine
        val roster = runCatching { RosterCodec.decode(plain) }.getOrNull() ?: return IdentityCheck.Fine
        return if (marks.device in roster.devices) {
            IdentityCheck.Fine
        } else {
            IdentityCheck.Conflict(IdentityCheck.Reason.RosterFromOtherDevice, roster.devices)
        }
    }

    /** A newer backup that names an epoch we don't hold, or lacks our newest one, was written by someone else. */
    private fun backup(crypto: NostrCrypto, marks: OwnerMarks, ev: SignedEvent): IdentityCheck {
        if (ev.createdAt <= marks.lastBackupAt) return IdentityCheck.Fine
        val plain = crypto.decrypt(marks.identity.secret, marks.identity.public, ev.content) ?: return IdentityCheck.Fine
        val backup = runCatching { BackupCodec.decode(plain) }.getOrNull() ?: return IdentityCheck.Fine
        val ours =
            marks.epochsByGk.values
                .map { it.first }
                .toSet()
        val theirs = backup.epochs.map { it.n }.toSet()
        val newest = ours.maxOrNull()
        return if (backup.sid != marks.sid || !ours.containsAll(theirs) || (newest != null && newest !in theirs)) {
            IdentityCheck.Conflict(IdentityCheck.Reason.BackupFromElsewhere)
        } else {
            IdentityCheck.Fine
        }
    }
}
