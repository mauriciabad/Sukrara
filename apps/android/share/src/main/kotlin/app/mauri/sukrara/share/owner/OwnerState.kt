// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.owner

import app.mauri.sukrara.protocol.KeyPair
import app.mauri.sukrara.protocol.NostrCrypto
import app.mauri.sukrara.protocol.SecretKey
import app.mauri.sukrara.protocol.Wire
import app.mauri.sukrara.protocol.owner.IdentityCheck
import app.mauri.sukrara.protocol.owner.OwnerMarks
import kotlinx.serialization.Serializable

/** One read key G_n. [secretHex] is a secret: never logged. */
@Serializable
data class EpochKey(val n: Int, val secretHex: String) {
    override fun toString() = "EpochKey($n)"
}

@Serializable
data class FollowerRecord(
    val key: String,
    val name: String,
    val since: Long,
    val revoked: Long? = null,
    /** The follower's generated picture as JSON (PROTOCOL-v2 "Profiles"), null if it never sent one. */
    val avatarJson: String? = null,
) {
    val active: Boolean get() = revoked == null
}

/** A single-use invite: [claimedBy] = first requester; [done] = approved or denied (kept until expiry for `invite-used`). */
@Serializable
data class InviteRecord(
    val token: String,
    val expiry: Long,
    val label: String = "",
    val sentAt: Long = 0,
    val claimedBy: String? = null,
    val done: Boolean = false,
)

/** An access request waiting for the wearer. [others] = later phones that tried the same invite (a leaked link). */
@Serializable
data class PendingRequest(
    val follower: String,
    val name: String,
    val token: String,
    val code: String,
    val at: Long,
    val avatarJson: String? = null,
    val label: String = "",
    val others: Int = 0,
)

/** Another app publishes roster / backup as this identity (A3); persisted so the block survives a restart. */
@Serializable
data class ConflictRecord(val reason: IdentityCheck.Reason, val seenAt: Long, val eventCreatedAt: Long)

/**
 * A control event (key delivery, notice, roster, backup) saved before it is published and dropped once a relay
 * accepted it, so a rotation interrupted by a crash still reaches every follower. [key] dedupes (newest roster /
 * backup only); [follower] lets a removed follower's deliveries be dropped.
 */
@Serializable
data class PendingControl(val key: String, val eventJson: String, val follower: String? = null)

/**
 * Everything the wearer's phone knows about sharing (V1 format, new app id: no migration from the showcase — a
 * restore from the recovery code brings the identity over). Encrypted at rest by [OwnerIdentityStore]; holds I, D and
 * every G_n, so [toString] is redacted. The owner backup carries the subset in `BackupPayload` (never D).
 */
@Serializable
data class OwnerState(
    val identityHex: String,
    val deviceHex: String,
    val sid: String,
    val epochs: List<EpochKey>,
    val followers: List<FollowerRecord> = emptyList(),
    val invites: List<InviteRecord> = emptyList(),
    val pending: List<PendingRequest> = emptyList(),
    val writers: Set<String> = emptySet(),
    val relays: List<String> = Wire.DEFAULT_RELAYS,
    /** The wearer's profile JSON (name + picture), sent only to followers. */
    val profileJson: String? = null,
    val rosterPublishedAt: Long = 0,
    val backupPublishedAt: Long = 0,
    val recoveryExportedAt: Long = 0,
    val conflict: ConflictRecord? = null,
    val pendingControl: List<PendingControl> = emptyList(),
) {
    val current: EpochKey get() = epochs.maxBy { it.n }

    fun activeFollowers(): List<FollowerRecord> = followers.filter { it.active }

    /** Writers that are still approved followers (the roster never names a removed one). */
    fun activeWriters(): List<String> = writers.filter { w -> followers.any { it.key == w && it.active } }

    fun identity(crypto: NostrCrypto): KeyPair = pair(crypto, identityHex)

    fun device(crypto: NostrCrypto): KeyPair = pair(crypto, deviceHex)

    fun readKey(crypto: NostrCrypto, e: EpochKey = current): KeyPair = pair(crypto, e.secretHex)

    /** What [app.mauri.sukrara.protocol.owner.IdentityConflict] needs to tell our own roster / backup from another app's. */
    fun marks(crypto: NostrCrypto): OwnerMarks = OwnerMarks(
        identity = identity(crypto),
        device = device(crypto).public.hex,
        sid = sid,
        epochsByGk = epochs.associate { e -> readKey(crypto, e).let { Wire.gk(it.public) to (e.n to it) } },
        lastRosterAt = rosterPublishedAt,
        lastBackupAt = backupPublishedAt,
    )

    override fun toString() =
        "OwnerState(epoch=${current.n}, followers=${activeFollowers().size}, pending=${pending.size}, conflict=${conflict?.reason})"

    private fun pair(crypto: NostrCrypto, hex: String): KeyPair = crypto.keyPair(SecretKey.ofHex(hex)!!)!!
}
