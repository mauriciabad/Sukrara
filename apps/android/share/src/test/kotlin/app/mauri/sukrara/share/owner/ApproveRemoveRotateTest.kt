// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.owner

import app.mauri.sukrara.protocol.Wire
import app.mauri.sukrara.protocol.event.RosterCodec
import app.mauri.sukrara.protocol.event.Rumor
import app.mauri.sukrara.protocol.invite.InviteLink
import app.mauri.sukrara.protocol.invite.Invites
import app.mauri.sukrara.share.OwnerFixtures
import app.mauri.sukrara.share.OwnerFixtures.NOW
import app.mauri.sukrara.share.OwnerFixtures.crypto
import app.mauri.sukrara.share.owner.usecase.ApproveRequest
import app.mauri.sukrara.share.owner.usecase.CreateIdentity
import app.mauri.sukrara.share.owner.usecase.CreateInvite
import app.mauri.sukrara.share.owner.usecase.DenyRequest
import app.mauri.sukrara.share.owner.usecase.HandleInbox
import app.mauri.sukrara.share.owner.usecase.InboxSignal
import app.mauri.sukrara.share.owner.usecase.RemoveFollower
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApproveRemoveRotateTest {
    private val ctx = OwnerFixtures.context()
    private val alice = crypto.generate()
    private val bob = crypto.generate()

    /** Invite → Alice's request → approved: the state every test starts from. */
    private fun withAlice(): Pair<OwnerState, String> {
        val created = CreateInvite(ctx)(CreateIdentity(ctx)(null, NOW).state, "Alice", NOW)
        val handled = HandleInbox(ctx)(created.step.state, OwnerFixtures.request(alice, created.step.state, created.link.token, "Alice"), NOW)
        val approved = ApproveRequest(ctx)(handled.step.state, alice.public.hex, NOW)!!
        return approved.state to created.link.token
    }

    @Test fun inviteLinkOpensForTheFollowerAndClaimIsSingleUse() {
        val s0 = CreateIdentity(ctx)(null, NOW).state
        val created = CreateInvite(ctx)(s0, "  Alice  ", NOW)
        val wrap = created.step.publish.first { it.kind == Wire.KIND_WRAP }
        val opened = Invites.open(crypto, InviteLink.parse(created.link.link)!!, listOf(wrap), NOW)
        assertTrue(opened is Invites.Result.Opened)
        assertEquals("Alice", created.step.state.invites.single().label)

        val first = HandleInbox(ctx)(created.step.state, OwnerFixtures.request(alice, created.step.state, created.link.token, "Alice"), NOW)
        val request = (first.signal as InboxSignal.NewRequest).request
        assertEquals(Invites.matchCode(alice.public, created.link.token), request.code)

        // Bob uses the same (leaked) link: invite-used, and Alice's request counts the other attempt
        val second = HandleInbox(ctx)(first.step.state, OwnerFixtures.request(bob, first.step.state, created.link.token, "Bob"), NOW)
        assertEquals(Rumor.InviteUsed, OwnerFixtures.open(bob, second.step.publish.single()))
        assertEquals(1, second.step.state.pending.single().others)
    }

    @Test fun approveDeliversTheCurrentKeyAndUsesTheInviteUp() {
        val (s, token) = withAlice()
        assertTrue(s.invites.single { it.token == token }.done)
        assertEquals(listOf(alice.public.hex), s.activeFollowers().map { it.key })
        val again = HandleInbox(ctx)(s, OwnerFixtures.request(alice, s, token, "Alice"), NOW + 5)
        val key = OwnerFixtures.open(alice, again.step.publish.single()) as Rumor.KeyDelivery
        assertEquals(1, key.epoch)
        assertEquals(listOf(s.device(crypto).public.hex), key.devices)
    }

    @Test fun denySendsDeclinedAndNoKey() {
        val created = CreateInvite(ctx)(CreateIdentity(ctx)(null, NOW).state, "", NOW)
        val handled = HandleInbox(ctx)(created.step.state, OwnerFixtures.request(bob, created.step.state, created.link.token, "Bob"), NOW)
        val denied = DenyRequest(ctx)(handled.step.state, bob.public.hex, NOW)!!
        assertEquals(Rumor.Declined, OwnerFixtures.open(bob, denied.publish.first()))
        assertTrue(denied.state.followers.isEmpty())
        assertTrue(denied.state.invites.single().done)
    }

    @Test fun removeRotatesAndOnlyTheRemainingFollowersGetTheNewKey() {
        val (withA, _) = withAlice()
        val invite = CreateInvite(ctx)(withA, "Bob", NOW)
        val handled = HandleInbox(ctx)(invite.step.state, OwnerFixtures.request(bob, invite.step.state, invite.link.token, "Bob"), NOW)
        val both = ApproveRequest(ctx)(handled.step.state, bob.public.hex, NOW)!!.state

        val removed = RemoveFollower(ctx)(both, alice.public.hex, NOW + 10)!!
        assertEquals(Rumor.Revoked, OwnerFixtures.open(alice, removed.publish.first()))
        assertEquals(2, removed.state.current.n)
        val toAlice = removed.publish.drop(1).mapNotNull { OwnerFixtures.open(alice, it) }
        assertTrue("removed follower gets no new key", toAlice.none { it is Rumor.KeyDelivery })
        val toBob = removed.publish.mapNotNull { OwnerFixtures.open(bob, it) }.filterIsInstance<Rumor.KeyDelivery>().single()
        assertEquals(2, toBob.epoch)
        // the new roster names this phone only and is encrypted to the new read key
        val roster = removed.publish.single { it.tag("d") == Wire.rosterD(both.sid) }
        val plain = crypto.decrypt(removed.state.readKey(crypto).secret, both.identity(crypto).public, roster.content)!!
        assertEquals(listOf(both.device(crypto).public.hex), RosterCodec.decode(plain).devices)
        assertNull(removed.state.pendingControl.firstOrNull { it.follower == alice.public.hex && it.key.startsWith("key:") })
    }

    @Test fun rekeyFromARemovedFollowerGetsRevokedAndUnknownSendersNothing() {
        val (s, _) = withAlice()
        val removed = RemoveFollower(ctx)(s, alice.public.hex, NOW + 10)!!.state
        val rekey = HandleInbox(ctx)(removed, OwnerFixtures.toOwner(alice, removed, Rumor.Rekey("x")), NOW + 20)
        assertEquals(Rumor.Revoked, OwnerFixtures.open(alice, rekey.step.publish.single()))
        val stranger = HandleInbox(ctx)(removed, OwnerFixtures.toOwner(bob, removed, Rumor.Rekey("x")), NOW + 20)
        assertTrue(stranger.step.publish.isEmpty())
        assertNotNull(removed.followers.single().revoked)
    }
}
