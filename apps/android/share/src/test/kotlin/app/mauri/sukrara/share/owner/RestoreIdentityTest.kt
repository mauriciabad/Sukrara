// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.owner

import app.mauri.sukrara.protocol.SecretKey
import app.mauri.sukrara.protocol.Wire
import app.mauri.sukrara.protocol.event.RosterCodec
import app.mauri.sukrara.protocol.event.Rumor
import app.mauri.sukrara.share.OwnerFixtures
import app.mauri.sukrara.share.OwnerFixtures.NOW
import app.mauri.sukrara.share.OwnerFixtures.crypto
import app.mauri.sukrara.share.owner.usecase.ApproveRequest
import app.mauri.sukrara.share.owner.usecase.CreateIdentity
import app.mauri.sukrara.share.owner.usecase.CreateInvite
import app.mauri.sukrara.share.owner.usecase.ExportRecovery
import app.mauri.sukrara.share.owner.usecase.HandleInbox
import app.mauri.sukrara.share.owner.usecase.RestoreIdentity
import app.mauri.sukrara.share.owner.usecase.RestoreIdentity.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreIdentityTest {
    private val ctx = OwnerFixtures.context()
    private val use = RestoreIdentity(ctx)
    private val follower = crypto.generate()

    /** The old phone: identity, one approved follower, its published roster + backup. */
    private val identityStep = CreateIdentity(ctx)(null, NOW - 50)
    private val old = run {
        val created = CreateInvite(ctx)(identityStep.state, "Mac", NOW)
        val req = HandleInbox(ctx)(created.step.state, OwnerFixtures.request(follower, created.step.state, created.link.token, "Mac"), NOW)
        ApproveRequest(ctx)(req.step.state, follower.public.hex, NOW)!!
    }
    private val relayEvents = (identityStep.publish + old.publish).filter { it.kind == Wire.KIND_DATA }

    @Test fun nsecAndNcryptsecCodesParse() {
        // built at run time: no key literals in the repo (secrets check); the codec itself is NostrCryptoContractTest's
        val secret = old.state.identity(crypto).secret
        assertEquals(secret, use.parse(crypto.nsec(secret), null).first!!.secret)
        val ncryptsec = crypto.ncryptsec(secret, "nostr", 16, 1)
        assertEquals(secret, use.parse(ncryptsec, "nostr").first!!.secret)
        assertEquals(Result.WrongPassword, use.parse(ncryptsec, "wrong").second)
        assertEquals(Result.NeedsPassword, use.parse(ncryptsec, null).second)
        assertEquals(Result.NotAKey, use.parse("hello", null).second)
    }

    @Test fun exportedRecoveryCodeRestoresTheSameIdentity() {
        val code = ExportRecovery(ctx)(old.state, "correct horse")
        val (identity, _) = use.parse(code, "correct horse")
        assertEquals(old.state.identity(crypto).public, identity!!.public)
        assertEquals(crypto.npub(old.state.identity(crypto).public), crypto.npub(identity.public))
    }

    @Test fun restoreBringsFollowersBackWithANewDeviceAndEpoch() {
        val identity = crypto.keyPair(SecretKey.ofHex(old.state.identityHex)!!)!!
        val r = use.restore(identity, relayEvents, answered = 2, now = NOW + 100) as Result.Restored
        val s = r.step.state
        assertEquals(1, r.followers)
        assertEquals(old.state.sid, s.sid)
        assertNotEquals(old.state.deviceHex, s.deviceHex)
        assertEquals(old.state.current.n + 1, s.current.n)
        // the follower gets the new epoch with the new device before the roster, so it keeps following (rotation.json)
        val key = r.step.publish.mapNotNull { OwnerFixtures.open(follower, it) }.filterIsInstance<Rumor.KeyDelivery>().single()
        assertEquals(s.current.n, key.epoch)
        assertEquals(listOf(s.device(crypto).public.hex), key.devices)
        val rosterIndex = r.step.publish.indexOfFirst { it.tag("d") == Wire.rosterD(s.sid) }
        assertTrue(rosterIndex > r.step.publish.indexOfFirst { OwnerFixtures.open(follower, it) != null })
        val roster = r.step.publish[rosterIndex]
        assertEquals(listOf(s.device(crypto).public.hex), RosterCodec.decode(crypto.decrypt(s.readKey(crypto).secret, identity.public, roster.content)!!).devices)
        // relays keep the newest (author, d): the new roster beats the old phone's
        assertTrue(roster.createdAt > relayEvents.filter { it.tag("d") == Wire.rosterD(s.sid) }.maxOf { it.createdAt })
    }

    @Test fun nothingFoundIsTypedAndNeverStartsFresh() {
        val identity = crypto.keyPair(SecretKey.ofHex(old.state.identityHex)!!)!!
        assertEquals(Result.RelaysUnreachable, use.restore(identity, emptyList(), answered = 0, now = NOW))
        assertTrue(use.restore(identity, emptyList(), answered = 3, now = NOW) is Result.NoBackupFound)
        val stranger = crypto.generate()
        assertTrue(use.restore(stranger, relayEvents, answered = 1, now = NOW) is Result.NoBackupFound)
    }
}
