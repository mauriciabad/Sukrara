// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.protocol

import app.mauri.sukrara.protocol.event.EventFactory
import app.mauri.sukrara.protocol.event.LivePayload
import app.mauri.sukrara.protocol.event.RosterPayload
import app.mauri.sukrara.protocol.event.Rumor
import app.mauri.sukrara.protocol.event.SettingsPayload
import app.mauri.sukrara.protocol.event.WireReading
import app.mauri.sukrara.protocol.follow.FollowResult
import app.mauri.sukrara.protocol.follow.FollowStatus
import app.mauri.sukrara.protocol.follow.FollowerState
import app.mauri.sukrara.protocol.testing.FollowerModel
import app.mauri.sukrara.protocol.testing.OgsVectors
import app.mauri.sukrara.protocol.testing.OgsVectors.Companion.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PROTOCOL-v2 §Acceptance rules through [app.mauri.sukrara.protocol.follow.StreamFollower]: the OGS reference
 * scenario step by step (every verdict and the final state), then the rules it doesn't cover (settings, roster epoch
 * mismatch, rekey throttling, invite-used / declined).
 */
class AcceptanceRulesTest {
    private val crypto = SdkTestCrypto()
    private val v = OgsVectors()
    private val factory = EventFactory(crypto)
    private val i = v.key("I")
    private val d = v.key("D")
    private val g1 = v.key("G1")

    private fun model() = FollowerModel(crypto, FollowerState(follower = v.key("F"), identity = i.public))

    @Test fun ogsScenario() {
        val sc = v.root.getValue("scenario").jsonObject
        val now = sc.str("now").toLong()
        val m = model()
        sc.getValue("steps").jsonArray.forEachIndexed { n, step ->
            val s = step.jsonObject
            assertEquals("step $n ${s.str("event")}", s.str("expect"), m.feed(v.events.getValue(s.str("event")), now).code)
        }
        val fin = sc.getValue("final").jsonObject
        val st = m.follower.state
        assertEquals(FollowStatus.Revoked, st.status)
        assertEquals(fin.getValue("epochs").jsonArray.map { it.jsonPrimitive.content.toInt() }, st.epochs.values.map { it.n }.sorted())
        assertEquals(fin.str("readings").toInt(), m.readings.size)
        assertEquals(fin.str("treatments").toInt(), m.treatments.size)
        assertEquals(fin.str("deleted").toInt(), m.treatments.values.count { it.deleted })
        assertEquals(fin.getValue("writers").jsonArray.map { it.jsonPrimitive.content }, st.writers)
        val sensor = fin.getValue("sensor").jsonObject
        assertEquals(sensor.str("ss").toLong(), m.sensor!!.start)
        assertEquals(sensor.str("se").toLong(), m.sensor!!.end)
        assertEquals(sensor.str("sw").toLong(), m.sensor!!.warmupEnd)
        assertEquals(fin.str("alerts").toInt(), m.alerts.size)
    }

    /** A follower with key-1 and roster-1 applied (epoch 1, device D). */
    private fun joined(): FollowerModel = model().apply {
        assertEquals("accepted:key", feed(v.events.getValue("key-1"), v.now).code)
        assertEquals("accepted:roster", feed(v.events.getValue("roster-1"), v.now).code)
    }

    private fun settings(signer: KeyPair, u: Long, createdAt: Long) =
        factory.settings(signer, g1.public, v.sid, SettingsPayload(u, JsonObject(mapOf("low" to JsonPrimitive(70))), JsonObject(emptyMap())), createdAt)

    @Test fun settingsNewestUWinsAndOnlyFromADevice() {
        val m = joined()
        assertEquals("accepted:settings", m.feed(settings(d, 100, v.now), v.now).code)
        assertEquals("ignored:settings-older", m.feed(settings(d, 100, v.now + 1), v.now).code)
        assertEquals("ignored:settings-older", m.feed(settings(d, 99, v.now + 2), v.now).code)
        assertEquals("rejected:author-not-device", m.feed(settings(v.key("W"), 200, v.now + 3), v.now).code)
        assertEquals("accepted:settings", m.feed(settings(d, 101, v.now + 4), v.now).code)
        assertEquals(101, m.settings!!.u)
        assertEquals(JsonPrimitive(70), m.settings!!.graph["low"])
    }

    @Test fun rosterMustNameTheEpochOfItsKeyAndNewestWins() {
        val m = joined()
        val wrongEpoch = factory.roster(i, g1.public, v.sid, RosterPayload(2, listOf(d.public.hex), emptyList(), emptyList(), null), v.now + 10)
        assertEquals("rejected:roster-epoch-mismatch", m.feed(wrongEpoch, v.now).code)
        val olderAt = v.events.getValue("roster-1").createdAt - 1
        val older = factory.roster(i, g1.public, v.sid, RosterPayload(1, listOf(v.key("X").public.hex), emptyList(), emptyList(), null), olderAt)
        assertEquals("ignored:roster-older", m.feed(older, v.now).code)
        assertEquals(listOf(d.public.hex), m.follower.state.devices)
    }

    @Test fun unknownEpochAsksForTheKeyOncePerFiveMinutesAndOnlyForTrustedAuthors() {
        val m = joined()
        val g3 = crypto.generate()
        val live = { at: Long -> factory.live(d, g3.public, LivePayload(WireReading(at, 120, null), null), at) }
        // older than the key we hold: a backfill, not a missed rotation
        assertNull((m.feed(live(v.now - 60), v.now) as FollowResult.Ignored).rekey)
        val first = m.feed(live(v.now + 10), v.now + 10) as FollowResult.Ignored
        assertEquals(Rumor.Rekey(Wire.gk(g1.public)), first.rekey)
        assertNull((m.feed(live(v.now + 11), v.now + 60) as FollowResult.Ignored).rekey)
        assertNotNull((m.feed(live(v.now + 12), v.now + 311) as FollowResult.Ignored).rekey)
        val stranger = factory.live(v.key("X"), crypto.generate().public, LivePayload(WireReading(v.now + 20, 120, null), null), v.now + 20)
        assertNull((m.feed(stranger, v.now + 400) as FollowResult.Ignored).rekey)
    }

    @Test fun aKeyNewerThanTheRevokeReApprovesAndAnOldRevokeIsIgnored() {
        val f = v.key("F").public
        val m = joined()
        val key = { at: Long -> factory.gift(i, f, Rumor.KeyDelivery(v.sid, 1, crypto.nsec(g1.secret), listOf(d.public.hex), emptyList()), at, at + Wire.TTL_KEY_DELIVERY) }
        assertEquals("ignored:revoked-before-latest-key", m.feed(factory.gift(i, f, Rumor.Revoked, v.now - 10, v.now + 600), v.now).code)
        assertEquals("accepted:revoked", m.feed(factory.gift(i, f, Rumor.Revoked, v.now + 10, v.now + 600), v.now + 10).code)
        assertEquals("ignored:revoked", m.feed(v.events.getValue("live"), v.now + 10).code)
        assertEquals("ignored:key-known", m.feed(key(v.now + 5), v.now + 20).code)
        assertEquals(FollowStatus.Revoked, m.follower.state.status)
        assertEquals("accepted:key", m.feed(key(v.now + 30), v.now + 30).code)
        assertEquals(FollowStatus.Active, m.follower.state.status)
    }

    @Test fun inviteUsedAndDeclinedOnlyWhileRequested() {
        val f = v.key("F").public
        val m = model()
        assertEquals("ignored:declined", m.feed(factory.gift(i, f, Rumor.Declined, v.now, v.now + 600), v.now).code)
        m.follower.requested()
        assertEquals("accepted:invite-used", m.feed(factory.gift(i, f, Rumor.InviteUsed, v.now, v.now + 600), v.now).code)
        assertEquals(FollowStatus.InviteUsed, m.follower.state.status)
        val forged = factory.gift(v.key("X"), f, Rumor.Revoked, v.now, v.now + 600)
        assertEquals("rejected:wrap-not-from-identity", m.feed(forged, v.now).code)
    }

    @Test fun readingsFromTheFutureAreRejected() {
        val m = joined()
        val future = factory.live(d, g1.public, LivePayload(WireReading(v.now + 301, 120, null), null), v.now)
        assertEquals("rejected:reading-in-future", m.feed(future, v.now).code)
    }

    @Test fun seenIdsArePrunedAtTheirExpiration() {
        val m = joined()
        val live = v.events.getValue("live")
        assertEquals("accepted:live", m.feed(live, v.now).code)
        assertEquals("ignored:duplicate", m.feed(live, v.now).code)
        assertEquals("ignored:expired", m.feed(live, live.expiration!!).code)
        m.feed(v.events.getValue("chunk"), live.expiration!! + 1)
        assertEquals(false, live.id in m.follower.state.seen)
    }
}
