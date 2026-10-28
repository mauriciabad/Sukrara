// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.ble

import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.SourceId
import app.mauri.sukrara.core.testing.FakeClock
import app.mauri.sukrara.core.testing.InMemoryReadingsRepository
import app.mauri.sukrara.core.testing.InMemorySecureStore
import app.mauri.sukrara.core.testing.InMemoryTreatmentsRepository
import app.mauri.sukrara.protocol.Wire
import app.mauri.sukrara.protocol.ble.BleMessages
import app.mauri.sukrara.protocol.event.Profile
import app.mauri.sukrara.protocol.follow.FollowStatus
import app.mauri.sukrara.share.FakeRelayTransport
import app.mauri.sukrara.share.OwnerFixtures
import app.mauri.sukrara.share.OwnerFixtures.NOW
import app.mauri.sukrara.share.OwnerFixtures.crypto
import app.mauri.sukrara.share.follower.FollowState
import app.mauri.sukrara.share.follower.FollowerEngine
import app.mauri.sukrara.share.follower.FollowerStore
import app.mauri.sukrara.share.follower.JoinResult
import app.mauri.sukrara.share.follower.NearbyOffer
import app.mauri.sukrara.share.follower.NearbySearch
import app.mauri.sukrara.share.owner.usecase.ApproveRequest
import app.mauri.sukrara.share.owner.usecase.CreateIdentity
import app.mauri.sukrara.share.owner.usecase.CreateInvite
import app.mauri.sukrara.share.owner.usecase.HandleInbox
import app.mauri.sukrara.share.owner.usecase.PublishReadings
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** The follower engine with a fake Bluetooth path beside the fake relays (the test plays the wearer's phone). */
class FollowerNearbyTest {
    private val ctx = OwnerFixtures.context()
    private val clock = FakeClock(Instant.ofEpochSecond(NOW))
    private val readings = InMemoryReadingsRepository()
    private val lookup = FakeRelayTransport()
    private val relay = FakeRelayTransport()
    private var lookupFirst = true
    private val nearbys = mutableListOf<FakeNearbyTransport>()

    private val wearer0 = CreateIdentity(ctx)(Profile("Ana", null), NOW).state
    private val created = CreateInvite(ctx)(wearer0, "Phone", NOW)

    private fun TestScope.engine() =
        FollowerEngine(
            crypto,
            FollowerStore(InMemorySecureStore(), crypto),
            { _, _ -> if (lookupFirst) lookup.also { lookupFirst = false } else relay },
            readings,
            InMemoryTreatmentsRepository(),
            clock,
            StandardTestDispatcher(testScheduler),
            nearbyTransports = { id -> FakeNearbyTransport(id).also { nearbys += it } },
        )

    private val received = mutableListOf<List<Reading>>()

    /** Joined, attached, and approved by the wearer (its events over Bluetooth only). */
    private fun TestScope.following(e: FollowerEngine): Pair<FakeNearbyTransport, Job> {
        lookup.stored += created.step.publish.first { it.kind == Wire.KIND_WRAP }
        var result: JoinResult? = null
        backgroundScope.launch { result = e.join(created.link.link, Profile("Bea", null)) }
        runCurrent()
        assertEquals(JoinResult.Joined, result)
        val job = backgroundScope.launch { e.readings.collect { received += it } }
        runCurrent()
        val nb = nearbys.single()
        assertTrue(nb.started)
        // before the first key the link knows the wearer but not the stream
        assertNull(nb.identity.sid)
        val request = relay.published.single { it.kind == Wire.KIND_WRAP }
        val handled = HandleInbox(ctx)(created.step.state, request, NOW)
        val step =
            ApproveRequest(ctx)(
                handled.step.state,
                handled.step.state.pending
                    .single()
                    .follower,
                NOW,
            )!!
        step.publish.forEach { nb.deliver(it) }
        runCurrent()
        wearer = step.state
        return nb to job
    }

    private var wearer = wearer0

    @Test fun theWearersEventsOverBluetoothAloneMakeAnActiveFollowWithReadings() =
        runTest {
            val e = engine()
            val (nb, _) = following(e)
            assertEquals(FollowStatus.Active, (e.state.value as FollowState.Following).status)
            // the first key: the link now proves who we are (HELLO again with the proof)
            assertNotNull(nb.identity.sid)
            assertEquals(wearer.sid, nb.identity.sid)

            val live =
                PublishReadings(ctx).onReadings(wearer, listOf(Reading(Instant.ofEpochSecond(NOW - 30), 123, 0.5, SourceId.Libre3)), NOW)
            live.filter { it.kind == Wire.KIND_LIVE }.forEach { nb.deliver(it) }
            runCurrent()
            assertEquals(listOf(123), received.flatten().map { it.mgdl })
            // one source for the follow stream: a copy over relays and Bluetooth is one reading
            assertEquals(SourceId.Relay, received.flatten().single().source)

            // the same event again over the relays: already seen
            live.filter { it.kind == Wire.KIND_LIVE }.forEach { relay.deliver(FollowerEngine.LIVE, it) }
            runCurrent()
            assertEquals(1, received.flatten().size)
        }

    @Test fun anotherWearersPhoneIsLeftAndStatusFollowsTheLink() =
        runTest {
            val e = engine()
            val (nb, job) = following(e)
            nb.info(BleMessages.Owner.Other, phone = "stranger")
            runCurrent()
            // that phone, for 10 minutes (B§6), even if the link has moved on to our wearer's phone since
            assertEquals(listOf("stranger" to 600L), nb.left)
            nb.info(BleMessages.Owner.Ours)
            runCurrent()
            assertEquals(NearbyStatus.Connected(1), e.nearbyStatus.value)

            // the service stops: Bluetooth stops with the relays
            job.cancel()
            runCurrent()
            assertTrue(nb.stopped)
            assertEquals(NearbyStatus.Stopped, e.nearbyStatus.value)
        }

    @Test fun findPhonesNearbyListsOffersAndJoiningSendsTheRequestOverBluetooth() =
        runTest {
            val e = engine()
            val wearerKey = wearer0.identity(crypto).public
            val pairing = BleMessages.Pairing(wearerKey, emptyList(), created.link.token, NOW + 600, Profile("Ana", null))
            var search: NearbySearch? = null
            val finding = backgroundScope.launch { e.findNearby().collect { search = it } }
            runCurrent()
            val finder = nearbys.single()
            assertNull("the finder knows no identity: it writes nothing but HELLO", finder.identity.identity)
            finder.info(BleMessages.Owner.Unknown, phone = "ana", pairing = pairing)
            finder.info(BleMessages.Owner.Unknown, phone = "other")
            runCurrent()
            assertEquals(listOf(NearbyOffer("ana", pairing)), search!!.offers)
            // each phone is left so the scan moves on to the next: one offering is looked at again soon, one offering
            // nothing only in a minute
            assertEquals(listOf("ana" to FollowerEngine.NEARBY_OFFER_RECHECK_S, "other" to FollowerEngine.NEARBY_IDLE_S), finder.left)
            // the phone stops answering (it stopped advertising): gone after a missed check, not minutes later
            clock.advance(Duration.ofSeconds(FollowerEngine.NEARBY_OFFER_DROP_S + 1))
            advanceTimeBy(1_100)
            assertTrue(search!!.offers.isEmpty())
            // back in range, still offering
            finder.info(BleMessages.Owner.Unknown, phone = "ana", pairing = pairing)
            runCurrent()
            assertEquals(listOf(NearbyOffer("ana", pairing)), search!!.offers)
            // the offer closes on the phone: gone from the list at its next check
            finder.info(BleMessages.Owner.Unknown, phone = "ana")
            runCurrent()
            assertTrue(search!!.offers.isEmpty())
            finding.cancel()
            runCurrent()
            assertTrue(finder.stopped)

            assertEquals(JoinResult.Joined, e.joinNearby(NearbyOffer("ana", pairing), Profile("Bea", null)))
            assertTrue("no relay lookup", lookupFirst)
            backgroundScope.launch { e.readings.collect { received += it } }
            runCurrent()
            val nb = nearbys.last()
            assertEquals(wearerKey, nb.identity.identity)
            nb.sent.clear()
            // the phone's INFO names our wearer (`i` while pairing is open): the request goes over Bluetooth
            nb.info(BleMessages.Owner.Ours)
            runCurrent()
            val request = nb.sent.single { it.kind == Wire.KIND_WRAP }
            val handled = HandleInbox(ctx)(created.step.state, request, NOW)
            assertEquals(
                "Bea",
                handled.step.state.pending
                    .single()
                    .name,
            )
        }

    @Test fun anExpiredOfferIsNotJoined() =
        runTest {
            val e = engine()
            val pairing = BleMessages.Pairing(wearer0.identity(crypto).public, emptyList(), created.link.token, NOW, null)
            assertEquals(JoinResult.Expired, e.joinNearby(NearbyOffer("ana", pairing), null))
        }

    @Test fun theAccessRequestGoesToTheRelaysAndOverBluetooth() =
        runTest {
            val e = engine()
            val (nb, _) = following(e)
            val request = relay.published.first { it.kind == Wire.KIND_WRAP }
            // the link itself refuses to write it until the phone proved it's the wearer's (NearbyLinkTest)
            assertTrue(request in nb.sent)
        }
}
