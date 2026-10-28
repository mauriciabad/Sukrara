// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.follower

import app.mauri.sukrara.core.model.LinkState
import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.SourceId
import app.mauri.sukrara.core.model.SourceStatus
import app.mauri.sukrara.core.model.Treatment
import app.mauri.sukrara.core.model.TreatmentKind
import app.mauri.sukrara.core.model.TreatmentOrigin
import app.mauri.sukrara.core.model.storage.TreatmentDelivery
import app.mauri.sukrara.core.model.storage.TreatmentRecord
import app.mauri.sukrara.core.testing.FakeClock
import app.mauri.sukrara.core.testing.InMemoryReadingsRepository
import app.mauri.sukrara.core.testing.InMemorySecureStore
import app.mauri.sukrara.core.testing.InMemoryTreatmentsRepository
import app.mauri.sukrara.protocol.SignedEvent
import app.mauri.sukrara.protocol.Wire
import app.mauri.sukrara.protocol.event.Profile
import app.mauri.sukrara.protocol.event.Rumor
import app.mauri.sukrara.protocol.event.RumorCodec
import app.mauri.sukrara.protocol.follow.FollowStatus
import app.mauri.sukrara.share.FakeRelayTransport
import app.mauri.sukrara.share.OwnerFixtures
import app.mauri.sukrara.share.OwnerFixtures.NOW
import app.mauri.sukrara.share.OwnerFixtures.crypto
import app.mauri.sukrara.share.owner.OwnerState
import app.mauri.sukrara.share.owner.usecase.ApproveRequest
import app.mauri.sukrara.share.owner.usecase.CreateIdentity
import app.mauri.sukrara.share.owner.usecase.CreateInvite
import app.mauri.sukrara.share.owner.usecase.DenyRequest
import app.mauri.sukrara.share.owner.usecase.HandleInbox
import app.mauri.sukrara.share.owner.usecase.PublishReadings
import app.mauri.sukrara.share.owner.usecase.RemoveFollower
import app.mauri.sukrara.share.owner.usecase.SetWriter
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The follower engine against a fake relay, with the wearer's real use-cases on the other side. Synthetic keys and values. */
class FollowerEngineTest {
    private val ctx = OwnerFixtures.context()
    private val clock = FakeClock(Instant.ofEpochSecond(NOW))
    private val secure = InMemorySecureStore()
    private val readings = InMemoryReadingsRepository()
    private val treatments = InMemoryTreatmentsRepository()
    private val lookup = FakeRelayTransport()
    private val relay = FakeRelayTransport()
    private var links = mutableListOf<LinkState?>()
    private val notices = mutableListOf<FollowerNotice>()

    private val wearer0 = CreateIdentity(ctx)(Profile("Ana", null), NOW).state
    private val created = CreateInvite(ctx)(wearer0, "Phone", NOW)
    private val inviteWrap = created.step.publish.first { it.kind == Wire.KIND_WRAP }

    private fun TestScope.engine(timeoutMs: Long = 25_000) =
        FollowerEngine(
            crypto,
            FollowerStore(secure, crypto),
            { _, _ -> if (lookupFirst) lookup.also { lookupFirst = false } else relay },
            readings,
            treatments,
            clock,
            StandardTestDispatcher(testScheduler),
            setLink = { links += it },
            onNotice = { n, _ -> notices += n },
            joinTimeoutMs = timeoutMs,
        )

    private var lookupFirst = true

    private val received = mutableListOf<List<Reading>>()

    private fun TestScope.attach(e: FollowerEngine) {
        backgroundScope.launch { e.readings.collect { received += it } }
        runCurrent()
    }

    /** Joins, connects, and returns the request the follower sent. */
    private fun TestScope.joined(e: FollowerEngine): SignedEvent {
        lookup.stored += inviteWrap
        var result: JoinResult? = null
        backgroundScope.launch { result = e.join(created.link.link, Profile("Bea", null)) }
        runCurrent()
        assertEquals(JoinResult.Joined, result)
        attach(e)
        return relay.published.single { it.kind == Wire.KIND_WRAP }
    }

    /** The wearer receives [request] and approves it: what it publishes. */
    private fun approve(request: SignedEvent): Pair<OwnerState, List<SignedEvent>> {
        val handled = HandleInbox(ctx)(created.step.state, request, NOW)
        val follower = handled.step.state.pending.single().follower
        val step = ApproveRequest(ctx)(handled.step.state, follower, NOW)!!
        return step.state to step.publish
    }

    private suspend fun deliverAll(events: List<SignedEvent>) {
        events.forEach { ev ->
            val sub =
                when {
                    ev.kind == Wire.KIND_WRAP -> FollowerEngine.INBOX
                    ev.kind == Wire.KIND_LIVE || ev.kind == Wire.KIND_ALERT -> FollowerEngine.LIVE
                    else -> FollowerEngine.ROSTER
                }
            relay.deliver(sub, ev)
        }
    }

    @Test fun joinOpensTheInviteAndTheRequestGoesOutOnConnect() =
        runTest {
            val e = engine()
            val request = joined(e)
            assertFalse(lookup.started)
            val opened = crypto.unwrap(wearer0.identity(crypto).secret, request)!!
            val rumor = RumorCodec.decode(opened.kind, opened.content) as Rumor.Request
            assertEquals(created.link.token, rumor.token)
            assertEquals("Bea", rumor.profile?.name)
            val s = e.state.value as FollowState.Following
            assertEquals(FollowStatus.Requested, s.status)
            assertEquals("Ana", s.wearer?.name)
            assertEquals("Ana", links.last()?.wearerName)
            // only the inbox while waiting: no stream subscriptions before a key
            assertEquals(setOf(FollowerEngine.INBOX), relay.subscriptions.keys)
        }

    @Test fun anInviteThatNeverAppearsIsNotFoundAndNothingIsSaved() =
        runTest {
            val e = engine(timeoutMs = 10_000)
            var result: JoinResult? = null
            backgroundScope.launch { result = e.join(created.link.link, null) }
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(JoinResult.NotFound, result)
            assertNull(secure.read(FollowerStore.NAME))
            assertEquals(FollowState.NotFollowing, e.state.value)
        }

    @Test fun anythingButAnInviteLinkIsRefusedAtOnce() =
        runTest {
            val e = engine()
            var result: JoinResult? = null
            backgroundScope.launch { result = e.join("https://example.com/join#abc", null) }
            runCurrent()
            assertEquals(JoinResult.InvalidLink, result)
        }

    @Test fun approvedFollowerGetsTheKeyRosterAndReadings() =
        runTest {
            val e = engine()
            val (wearer, published) = approve(joined(e))
            deliverAll(published)
            runCurrent()
            val s = e.state.value as FollowState.Following
            assertEquals(FollowStatus.Active, s.status)
            assertTrue(relay.subscriptions.keys.containsAll(listOf(FollowerEngine.CHUNKS, FollowerEngine.LIVE, FollowerEngine.TREATMENTS)))

            val live = PublishReadings(ctx).onReadings(wearer, listOf(Reading(Instant.ofEpochSecond(NOW - 30), 123, 0.5, SourceId.Libre3)), NOW)
            deliverAll(live.filter { it.kind == Wire.KIND_LIVE })
            runCurrent()
            assertEquals(listOf(123), received.flatten().map { it.mgdl })
            assertEquals(SourceId.Relay, received.flatten().single().source)
            assertEquals(SourceStatus.Live(Instant.ofEpochSecond(NOW - 30)), e.status.value)
            assertEquals(Instant.ofEpochSecond(NOW), links.last()?.lastHeardAt)
        }

    @Test fun aStrangerSigningLiveEventsIsNotBelieved() =
        runTest {
            val e = engine()
            val (wearer, published) = approve(joined(e))
            deliverAll(published)
            runCurrent()
            val forged = wearer.copy(deviceHex = crypto.generate().secret.revealHex())
            val live = PublishReadings(ctx).onReadings(forged, listOf(Reading(Instant.ofEpochSecond(NOW - 30), 40, null, SourceId.Libre3)), NOW)
            deliverAll(live.filter { it.kind == Wire.KIND_LIVE })
            runCurrent()
            assertTrue(received.isEmpty())
        }

    @Test fun declinedAndRemovedAreToldOnce() =
        runTest {
            val e = engine()
            val request = joined(e)
            val handled = HandleInbox(ctx)(created.step.state, request, NOW)
            val denied = DenyRequest(ctx)(handled.step.state, handled.step.state.pending.single().follower, NOW)!!
            clock.instant = (Instant.ofEpochSecond(NOW + 5))
            deliverAll(denied.publish)
            runCurrent()
            assertEquals(FollowStatus.Declined, (e.state.value as FollowState.Following).status)
            assertEquals(listOf(FollowerNotice.Declined), notices)
        }

    @Test fun removedFollowerShowsRevokedAndStopsReading() =
        runTest {
            val e = engine()
            val (wearer, published) = approve(joined(e))
            deliverAll(published)
            runCurrent()
            val follower = wearer.activeFollowers().single().key
            clock.instant = (Instant.ofEpochSecond(NOW + 60))
            val removed = RemoveFollower(ctx)(wearer, follower, NOW + 60)!!
            deliverAll(removed.publish.filter { it.kind == Wire.KIND_WRAP && it.tag("p") == follower })
            runCurrent()
            assertEquals(FollowStatus.Revoked, (e.state.value as FollowState.Following).status)
            assertTrue(links.last()!!.revoked)
            assertEquals(listOf(FollowerNotice.Revoked), notices)
        }

    @Test fun aWriterEntryIsSentUnderTheNewestEpochAndMarkedDeliveredOnOk() =
        runTest {
            val e = engine()
            val (wearer, published) = approve(joined(e))
            deliverAll(published)
            runCurrent()
            val follower = wearer.activeFollowers().single().key
            val writer = SetWriter(ctx)(wearer, follower, true, NOW + 1)!!
            clock.instant = (Instant.ofEpochSecond(NOW + 2))
            deliverAll(writer.publish.filter { it.kind == Wire.KIND_DATA })
            runCurrent()
            assertTrue(e.canWrite())
            val entry =
                TreatmentRecord(
                    Treatment("t1", TreatmentKind.Carbs, 20.0, Instant.ofEpochSecond(NOW), "", Instant.ofEpochSecond(NOW + 2), false, TreatmentOrigin.Follower),
                    creator = follower,
                    delivery = TreatmentDelivery.Pending,
                )
            treatments.upsertIfNewer(entry)
            relay.published.clear()
            e.publishTreatment(entry)
            runCurrent()
            val sent = relay.published.single()
            assertEquals(follower, sent.pubkey)
            assertEquals(Wire.treatmentsC(wearer.sid), sent.tag("c"))
            relay.ok(sent.id)
            runCurrent()
            assertEquals(TreatmentDelivery.Delivered, treatments.get("t1")!!.delivery)
        }

    @Test fun stopFollowingWipesTheWearersDataAndTheKey() =
        runTest {
            val e = engine()
            val (wearer, published) = approve(joined(e))
            deliverAll(published)
            runCurrent()
            val live = PublishReadings(ctx).onReadings(wearer, listOf(Reading(Instant.ofEpochSecond(NOW - 30), 123, 0.5, SourceId.Libre3)), NOW)
            deliverAll(live.filter { it.kind == Wire.KIND_LIVE })
            runCurrent()
            readings.upsert(received.flatten())
            var done = false
            backgroundScope.launch { done = e.unfollow() }
            runCurrent()
            assertTrue(done)
            assertNull(secure.read(FollowerStore.NAME))
            assertTrue(readings.range(Instant.EPOCH, Instant.ofEpochSecond(NOW + 1)).isEmpty())
            assertEquals(FollowState.NotFollowing, e.state.value)
            assertNull(links.last())
            assertFalse(relay.started)
        }

    @Test fun theRecordSurvivesARestartAndAnUnreadableOneIsKept() =
        runTest {
            val e = engine()
            val (_, published) = approve(joined(e))
            deliverAll(published)
            runCurrent()
            val again = engine()
            again.load()
            assertEquals(FollowStatus.Active, (again.state.value as FollowState.Following).status)
            assertNotNull(secure.read(FollowerStore.NAME))
            val record = FollowerCodec.decode(crypto, secure.read(FollowerStore.NAME)!!.decodeToString())!!
            // the record never prints a key
            assertFalse(record.toString().contains(record.stream.follower.secret.revealHex()))
        }

    @Test fun missingHoursOnlyWhileTheStreamIsLive() {
        val now = 10 * Wire.HOUR + 600
        assertNull(FollowerEngine.missingHours(listOf(now - 3600), now))
        val stored = listOf(now - 60, now - 2 * Wire.HOUR)
        val missing = FollowerEngine.missingHours(stored, now)!!
        assertEquals(11, missing.size)
        assertFalse(Wire.hourOf(now - 2 * Wire.HOUR) in missing)
    }
}
