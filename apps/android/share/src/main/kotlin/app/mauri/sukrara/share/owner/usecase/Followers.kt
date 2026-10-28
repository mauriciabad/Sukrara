// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.owner.usecase

import app.mauri.sukrara.protocol.PublicKeyHex
import app.mauri.sukrara.protocol.SignedEvent
import app.mauri.sukrara.protocol.Wire
import app.mauri.sukrara.protocol.event.Profile
import app.mauri.sukrara.protocol.event.Rumor
import app.mauri.sukrara.protocol.event.RumorCodec
import app.mauri.sukrara.protocol.invite.InviteLink
import app.mauri.sukrara.protocol.invite.Invites
import app.mauri.sukrara.share.owner.InviteRecord
import app.mauri.sukrara.share.owner.OwnerState
import app.mauri.sukrara.share.owner.PendingRequest

/** A single-use invite: the link to hand over and the invite wrap on the relays; the backup records the token. */
class CreateInvite(private val ctx: OwnerContext) {
    data class Created(val step: Step, val link: InviteLink, val expiry: Long)

    operator fun invoke(s: OwnerState, label: String, now: Long): Created {
        val expiry = now + TTL
        val profile = ControlEvents.profile(s) ?: Profile("", null)
        val (link, wrap) =
            Invites.create(
                ctx.crypto,
                s.identity(ctx.crypto),
                ctx.random.next(InviteLink.SECRET_LEN),
                now,
                expiry,
                s.relays,
                profile,
            )
        val invite = InviteRecord(link.token, expiry, label.trim().take(Profile.MAX_NAME), sentAt = now)
        val next = s.copy(invites = s.invites.filter { it.expiry >= now } + invite)
        return Created(Step(next, listOf(wrap)) + { ControlEvents.backup(ctx, it, now) }, link, expiry)
    }

    companion object {
        const val TTL = 24 * 3600L
    }
}

/** What an incoming gift wrap asked for, beyond the state change (the engine shows / notifies it). */
sealed interface InboxSignal {
    data class NewRequest(val request: PendingRequest) : InboxSignal

    /** A follower asked for the hours since [since]: the engine republishes them (rate limited). */
    data class HistoryWanted(val since: Long) : InboxSignal
}

/**
 * One gift wrap addressed to the identity (30500 from followers): `request` (single-use invite claim), `profile`,
 * `rekey`, `history`. Unknown senders and stale or invalid requests change nothing.
 */
class HandleInbox(private val ctx: OwnerContext) {
    data class Handled(val step: Step, val signal: InboxSignal? = null)

    operator fun invoke(s: OwnerState, wrap: SignedEvent, now: Long): Handled {
        val none = Handled(Step(s))
        if (wrap.kind != Wire.KIND_WRAP || !ctx.crypto.verify(wrap)) return none
        val u = ctx.crypto.unwrap(s.identity(ctx.crypto).secret, wrap) ?: return none
        if (u.kind != Wire.KIND_TO_OWNER) return none
        val rumor = runCatching { RumorCodec.decode(u.kind, u.content) }.getOrNull() ?: return none
        val sender = u.sender.hex
        val known = s.followers.lastOrNull { it.key == sender }
        return when (rumor) {
            is Rumor.Request -> {
                request(s, sender, rumor, now)
            }

            is Rumor.ProfileUpdate -> {
                if (known?.active == true) {
                    val updated =
                        known.copy(
                            name = rumor.profile.name.ifBlank { known.name },
                            avatarJson =
                                rumor.profile.avatar
                                    ?.json
                                    ?.toString(),
                        )
                    val next = s.copy(followers = s.followers.map { if (it.key == sender) updated else it })
                    Handled(ControlEvents.backup(ctx, next, now))
                } else {
                    none
                }
            }

            is Rumor.Rekey -> {
                when {
                    known?.active == true -> Handled(ControlEvents.deliverKey(ctx, s, sender, now))
                    known != null -> Handled(ControlEvents.notice(ctx, s, sender, Rumor.Revoked, now))
                    else -> none
                }
            }

            is Rumor.History -> {
                if (known?.active == true &&
                    now - u.createdAt <= Wire.HOUR
                ) {
                    Handled(Step(s), InboxSignal.HistoryWanted(rumor.since))
                } else {
                    none
                }
            }

            else -> {
                none
            }
        }
    }

    private fun request(s: OwnerState, sender: String, r: Rumor.Request, now: Long): Handled {
        val known = s.followers.lastOrNull { it.key == sender }
        if (known?.active == true) return Handled(ControlEvents.deliverKey(ctx, s, sender, now))
        val invite = s.invites.firstOrNull { it.token == r.token }?.takeIf { it.expiry >= now } ?: return Handled(Step(s))
        if (invite.claimedBy != null && invite.claimedBy != sender) {
            val next = s.copy(pending = s.pending.map { if (it.token == r.token) it.copy(others = it.others + 1) else it })
            return Handled(ControlEvents.notice(ctx, next, sender, Rumor.InviteUsed, now))
        }
        if (invite.done || s.pending.any { it.follower == sender }) return Handled(Step(s))
        val request =
            PendingRequest(
                follower = sender,
                name = r.profile?.name?.ifBlank { null } ?: "?",
                token = r.token,
                code = Invites.matchCode(PublicKeyHex(sender), r.token),
                at = now,
                avatarJson =
                    r.profile
                        ?.avatar
                        ?.json
                        ?.toString(),
                label = invite.label,
            )
        val next =
            s.copy(
                invites = s.invites.map { if (it.token == r.token) it.copy(claimedBy = sender) else it },
                pending = s.pending + request,
            )
        return Handled(Step(next), InboxSignal.NewRequest(request))
    }
}

/** Allow: the invite is used up, the follower gets the current read key, the backup records it. */
class ApproveRequest(private val ctx: OwnerContext) {
    operator fun invoke(s: OwnerState, follower: String, now: Long): Step? {
        val p = s.pending.firstOrNull { it.follower == follower } ?: return null
        val invite = s.invites.firstOrNull { it.token == p.token }
        val withoutRequest = s.copy(pending = s.pending.filter { it.token != p.token })
        if (invite == null || invite.expiry < now || invite.done || invite.claimedBy != follower) return Step(withoutRequest)
        val next =
            withoutRequest.copy(
                invites = s.invites.map { if (it.token == p.token) it.copy(done = true) else it },
                followers =
                    s.followers.filter { it.key != follower } +
                        app.mauri.sukrara.share.owner
                            .FollowerRecord(follower, p.name, now, avatarJson = p.avatarJson),
            )
        return ControlEvents.deliverKey(ctx, next, follower, now) + { ControlEvents.backup(ctx, it, now) }
    }
}

/** Deny: no key, a `declined` notice; the invite is used up too. */
class DenyRequest(private val ctx: OwnerContext) {
    operator fun invoke(s: OwnerState, follower: String, now: Long): Step? {
        val p = s.pending.firstOrNull { it.follower == follower } ?: return null
        val next =
            s.copy(
                pending = s.pending.filter { it.token != p.token },
                invites = s.invites.map { if (it.token == p.token) it.copy(done = true) else it },
            )
        return ControlEvents.notice(ctx, next, follower, Rumor.Declined, now) + { ControlEvents.backup(ctx, it, now) }
    }
}

/** Remove: a `revoked` notice, then a rotation so the removed follower can't read anything new. */
class RemoveFollower(private val ctx: OwnerContext) {
    operator fun invoke(s: OwnerState, follower: String, now: Long): Step? {
        if (s.followers.none { it.key == follower && it.active }) return null
        val next =
            s.copy(
                followers = s.followers.map { if (it.key == follower && it.active) it.copy(revoked = now) else it },
                writers = s.writers - follower,
                // a delivery still waiting for this follower must not go out after the removal
                pendingControl = s.pendingControl.filter { it.follower != follower },
            )
        return ControlEvents.notice(ctx, next, follower, Rumor.Revoked, now) + { Rotate(ctx)(it, now) }
    }
}

/** "Can add entries" (writer scope): the roster tells followers at once, the backup keeps it. Only active followers. */
class SetWriter(private val ctx: OwnerContext) {
    operator fun invoke(s: OwnerState, follower: String, allowed: Boolean, now: Long): Step? {
        if (s.followers.none { it.key == follower && it.active }) return null
        val writers = if (allowed) s.writers + follower else s.writers - follower
        if (writers == s.writers) return null
        return ControlEvents.roster(ctx, s.copy(writers = writers), now) + { ControlEvents.backup(ctx, it, now) }
    }
}
