// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.owner.usecase

import app.mauri.sukrara.protocol.NostrCrypto
import app.mauri.sukrara.protocol.PublicKeyHex
import app.mauri.sukrara.protocol.SecretKey
import app.mauri.sukrara.protocol.SignedEvent
import app.mauri.sukrara.protocol.Wire
import app.mauri.sukrara.protocol.event.Avatar
import app.mauri.sukrara.protocol.event.BackupEpoch
import app.mauri.sukrara.protocol.event.BackupFollower
import app.mauri.sukrara.protocol.event.BackupInvite
import app.mauri.sukrara.protocol.event.BackupPayload
import app.mauri.sukrara.protocol.event.EventFactory
import app.mauri.sukrara.protocol.event.Profile
import app.mauri.sukrara.protocol.event.RosterPayload
import app.mauri.sukrara.protocol.event.Rumor
import app.mauri.sukrara.share.owner.EpochKey
import app.mauri.sukrara.share.owner.FollowerRecord
import app.mauri.sukrara.share.owner.InviteRecord
import app.mauri.sukrara.share.owner.OwnerState
import app.mauri.sukrara.share.owner.PendingControl
import kotlinx.serialization.json.Json

/** Random bytes for stream ids and invite secrets (SecureRandom in the app, seeded in tests). */
fun interface RandomBytes {
    fun next(size: Int): ByteArray
}

/** What every use-case needs. Use-cases are pure: `(state, input, now) → Step`; the engine persists, then publishes. */
class OwnerContext(val crypto: NostrCrypto, val random: RandomBytes) {
    val events = EventFactory(crypto)
}

/** A use-case's result: the new state and the events to publish, in order (deliveries before the roster). */
data class Step(val state: OwnerState, val publish: List<SignedEvent> = emptyList()) {
    operator fun plus(next: (OwnerState) -> Step): Step = next(state).let { Step(it.state, publish + it.publish) }
}

/**
 * The owner's control events. Each is also saved in [OwnerState.pendingControl] until a relay accepts it (crash-safe
 * rotation); a newer roster / backup replaces the older pending one, so the list stays small.
 */
internal object ControlEvents {
    /** created_at must strictly increase per (author, d), and beat another app's event after a conflict retry. */
    private fun nextAt(now: Long, last: Long, s: OwnerState) = maxOf(now, last + 1, (s.conflict?.eventCreatedAt ?: 0) + 1)

    fun roster(ctx: OwnerContext, s: OwnerState, now: Long): Step {
        val at = nextAt(now, s.rosterPublishedAt, s)
        val readKey = s.readKey(ctx.crypto).public
        val payload = RosterPayload(s.current.n, listOf(s.device(ctx.crypto).public.hex), s.activeWriters(), s.relays, profile(s))
        val ev = ctx.events.roster(s.identity(ctx.crypto), readKey, s.sid, payload, at)
        return pending(s.copy(rosterPublishedAt = at), "roster", ev, null)
    }

    fun backup(ctx: OwnerContext, s: OwnerState, now: Long): Step {
        val at = nextAt(now, s.backupPublishedAt, s)
        val ev = ctx.events.backup(s.identity(ctx.crypto), backupPayload(ctx.crypto, s), at)
        return pending(s.copy(backupPublishedAt = at), "backup", ev, null)
    }

    /** The current read key to [follower] (30501 `key`, devices = this phone only). */
    fun deliverKey(ctx: OwnerContext, s: OwnerState, follower: String, now: Long): Step {
        val rumor = Rumor.KeyDelivery(s.sid, s.current.n, ctx.crypto.nsec(s.readKey(ctx.crypto).secret), listOf(s.device(ctx.crypto).public.hex), s.relays)
        val ev = ctx.events.gift(s.identity(ctx.crypto), PublicKeyHex(follower), rumor, now, now + Wire.TTL_KEY_DELIVERY)
        return pending(s, "key:$follower", ev, follower)
    }

    /** `revoked`, `declined` or `invite-used` to [follower]. */
    fun notice(ctx: OwnerContext, s: OwnerState, follower: String, rumor: Rumor, now: Long): Step {
        val ev = ctx.events.gift(s.identity(ctx.crypto), PublicKeyHex(follower), rumor, now, now + Wire.TTL_REQUEST)
        return pending(s, "${rumor.type}:$follower", ev, follower)
    }

    private fun pending(s: OwnerState, key: String, ev: SignedEvent, follower: String?): Step {
        val list = s.pendingControl.filter { it.key != key } + PendingControl(key, ev.id, ev.toJson(), follower)
        return Step(s.copy(pendingControl = list), listOf(ev))
    }

    fun profile(s: OwnerState): Profile? = s.profileJson?.let { Profile.of(Json.parseToJsonElement(it)) }

    fun backupPayload(crypto: NostrCrypto, s: OwnerState) = BackupPayload(
        sid = s.sid,
        epochs = s.epochs.sortedBy { it.n }.map { BackupEpoch(it.n, crypto.nsec(SecretKey.ofHex(it.secretHex)!!)) },
        followers = s.followers.map { BackupFollower(it.key, it.name, avatar(it.avatarJson), it.since, it.revoked) },
        invites = s.invites.map { BackupInvite(it.token, it.expiry, it.label, false, it.sentAt, it.claimedBy, it.done) },
        relays = s.relays,
        writers = s.writers.toList(),
        profile = profile(s),
    )

    /** The state a backup describes, with this phone's new device key (restore). Null if an epoch key is unreadable. */
    fun fromBackup(crypto: NostrCrypto, identityHex: String, deviceHex: String, b: BackupPayload): OwnerState? {
        val epochs = b.epochs.map { e -> EpochKey(e.n, crypto.parseNsec(e.nsec)?.revealHex() ?: return null) }
        if (epochs.isEmpty()) return null
        return OwnerState(
            identityHex = identityHex,
            deviceHex = deviceHex,
            sid = b.sid,
            epochs = epochs.sortedBy { it.n },
            followers = b.followers.map { FollowerRecord(it.key, it.name, it.since, it.revoked, it.avatar?.json?.toString()) },
            invites = b.invites.map { InviteRecord(it.token, it.expiry, it.label, it.sentAt, it.claimedBy, it.done) },
            writers = b.writers.toSet(),
            profileJson = b.profile?.toJson()?.toString(),
        )
    }

    fun newSid(random: RandomBytes): String = random.next(32).joinToString("") { "%02x".format(it) }

    private fun avatar(json: String?): Avatar? = json?.let { Avatar.of(Json.parseToJsonElement(it)) }
}
