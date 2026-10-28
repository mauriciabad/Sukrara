// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.owner

import app.mauri.sukrara.protocol.Wire
import app.mauri.sukrara.protocol.event.Avatar
import app.mauri.sukrara.protocol.event.Profile
import app.mauri.sukrara.protocol.event.RosterCodec
import app.mauri.sukrara.share.OwnerFixtures
import app.mauri.sukrara.share.OwnerFixtures.NOW
import app.mauri.sukrara.share.OwnerFixtures.crypto
import app.mauri.sukrara.share.owner.usecase.ControlEvents
import app.mauri.sukrara.share.owner.usecase.CreateIdentity
import app.mauri.sukrara.share.owner.usecase.CreateInvite
import app.mauri.sukrara.share.owner.usecase.HandleInbox
import app.mauri.sukrara.share.owner.usecase.SetProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** "How you appear" on the wearer: followers get the change in a roster at once (PROTOCOL-v2 §Profiles). */
class SetProfileTest {
    private val ctx = OwnerFixtures.context()
    private val avatar = Avatar.v1(3, "drop", 45, 7)

    @Test fun aChangePublishesRosterAndBackupWithTheProfile() {
        val s0 = CreateIdentity(ctx)(Profile("Alex", null), NOW).state
        val step = SetProfile(ctx)(s0, Profile("  Núria  ", avatar), NOW + 10)!!
        assertEquals(Profile("Núria", avatar), ControlEvents.profile(step.state))
        val roster = step.publish.single { it.tag("d") == Wire.rosterD(s0.sid) }
        val plain = crypto.decrypt(step.state.readKey(crypto).secret, s0.identity(crypto).public, roster.content)!!
        assertEquals(Profile("Núria", avatar), RosterCodec.decode(plain).profile)
        assertEquals(1, step.publish.count { it.tag("d") == Wire.backupD(s0.identity(crypto).public) })
    }

    @Test fun blankNameKeepsThePreviousAndNoChangePublishesNothing() {
        val s0 = CreateIdentity(ctx)(Profile("Alex", avatar), NOW).state
        assertNull(SetProfile(ctx)(s0, Profile("   ", avatar), NOW + 10))
        val renamed = SetProfile(ctx)(s0, Profile("A name that is much longer than forty characters", avatar), NOW + 10)!!
        assertEquals("A name that is much longer than forty ch", ControlEvents.profile(renamed.state)!!.name)
    }

    @Test fun requestCarriesWhenItsInviteWasMade() {
        val created = CreateInvite(ctx)(CreateIdentity(ctx)(null, NOW).state, "Mom", NOW)
        val follower = crypto.generate()
        val s = created.step.state
        val handled = HandleInbox(ctx)(s, OwnerFixtures.request(follower, s, created.link.token, "Mom", NOW + 60), NOW + 60)
        val request =
            handled.step.state.pending
                .single()
        assertEquals(NOW, request.inviteSentAt)
        assertEquals("Mom", request.label)
    }
}
