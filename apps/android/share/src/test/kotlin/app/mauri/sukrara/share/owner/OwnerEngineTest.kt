// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.owner

import app.mauri.sukrara.core.model.AlertUiState
import app.mauri.sukrara.core.model.GlucoseHub
import app.mauri.sukrara.core.model.GlucoseState
import app.mauri.sukrara.core.model.LinkState
import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.SensorState
import app.mauri.sukrara.core.model.SourceId
import app.mauri.sukrara.core.model.SourceStatus
import app.mauri.sukrara.core.model.Treatment
import app.mauri.sukrara.core.model.TreatmentKind
import app.mauri.sukrara.core.model.TreatmentOrigin
import app.mauri.sukrara.core.model.storage.TreatmentRecord
import app.mauri.sukrara.core.testing.FakeClock
import app.mauri.sukrara.core.testing.InMemorySecureStore
import app.mauri.sukrara.core.testing.InMemoryTreatmentsRepository
import app.mauri.sukrara.protocol.event.Rumor
import app.mauri.sukrara.share.FakeRelayTransport
import app.mauri.sukrara.share.OwnerFixtures
import app.mauri.sukrara.share.OwnerFixtures.NOW
import app.mauri.sukrara.share.OwnerFixtures.crypto
import app.mauri.sukrara.share.owner.usecase.CreateIdentity
import app.mauri.sukrara.share.owner.usecase.RemoveFollower
import app.mauri.sukrara.share.owner.usecase.RestoreIdentity
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerEngineTest {
    private val ctx = OwnerFixtures.context()
    private val relay = FakeRelayTransport()
    private val store = OwnerIdentityStore(InMemorySecureStore())

    private fun TestScope.engine() = OwnerEngine(
        ctx, store, relay, NoHub, { _, _ -> true }, InMemoryTreatmentsRepository(), FakeClock(Instant.ofEpochSecond(NOW)), StandardTestDispatcher(testScheduler),
    )

    @Test fun aRotationInterruptedByACrashIsResentOnStartUntilARelayAccepts() = runTest {
        val follower = crypto.generate()
        val base = CreateIdentity(ctx)(null, NOW).state.let {
            it.copy(followers = listOf(FollowerRecord(follower.public.hex, "Mac", NOW)), rosterPublishedAt = NOW, backupPublishedAt = NOW)
        }
        val follower2 = FollowerRecord(crypto.generate().public.hex, "Old", NOW)
        // saved before publishing, then the process died: nothing reached a relay
        val rotated = RemoveFollower(ctx)(base.copy(followers = base.followers + follower2), follower2.key, NOW)!!.state
        store.save(rotated)

        val e = engine()
        e.start(backgroundScope)
        runCurrent()
        val delivery = relay.published.mapNotNull { OwnerFixtures.open(follower, it) }.filterIsInstance<Rumor.KeyDelivery>().single()
        assertEquals(rotated.current.n, delivery.epoch)

        rotated.pendingControl.forEach { relay.ok(it.eventId) }
        runCurrent()
        assertTrue((store.load() as OwnerIdentityStore.Load.Loaded).state.pendingControl.isEmpty())
        e.stop()
    }

    @Test fun restoreNeverReplacesAWorkingIdentitySilently() = runTest {
        val e = engine()
        assertTrue(e.createIdentity(backgroundScope, null))
        runCurrent()
        val code = crypto.nsec(crypto.generate().secret)
        assertEquals(RestoreIdentity.Result.AlreadySharing, e.restore(backgroundScope, code, null))
        e.stop()
    }

    @Test fun anUnreadableIdentityIsABlockingStateAndCreateIsRefused() = runTest {
        val secure = InMemorySecureStore().also { it.write(OwnerIdentityStore.NAME, "garbage".encodeToByteArray()) }
        val e = OwnerEngine(ctx, OwnerIdentityStore(secure), relay, NoHub, { _, _ -> true }, InMemoryTreatmentsRepository(), FakeClock(), StandardTestDispatcher(testScheduler))
        e.start(backgroundScope)
        runCurrent()
        assertEquals(OwnerShareState.IdentityUnreadable(transient = false), e.state.value)
        assertEquals(false, e.createIdentity(backgroundScope, null))
        e.stop()
    }

    @Test fun theTreatmentLogFollowsEveryNewEpochAndWritersGetASubscription() = runTest {
        val repo = InMemoryTreatmentsRepository()
        repo.upsertIfNewer(
            TreatmentRecord(Treatment("a0000000-0000-4000-8000-00000000000a", TreatmentKind.Carbs, 20.0, Instant.ofEpochSecond(NOW - 600), modifiedAt = Instant.ofEpochSecond(NOW - 600), origin = TreatmentOrigin.Phone)),
        )
        val ana = crypto.generate().public.hex
        val bob = crypto.generate().public.hex
        store.save(CreateIdentity(ctx)(null, NOW).state.copy(followers = listOf(FollowerRecord(ana, "Ana", NOW), FollowerRecord(bob, "Bob", NOW))))
        val e = OwnerEngine(ctx, store, relay, NoHub, { _, _ -> true }, repo, FakeClock(Instant.ofEpochSecond(NOW)), StandardTestDispatcher(testScheduler))
        e.start(backgroundScope)
        runCurrent()
        fun treatmentGks() = relay.published.filter { it.tag("c") != null && it.kind == app.mauri.sukrara.protocol.Wire.KIND_DATA }.map { it.tag("gk") }
        assertEquals(1, treatmentGks().size)

        e.setWriter(ana, allowed = true)
        assertEquals(listOf(ana), relay.subscriptions.getValue("writers").single().authors)
        e.remove(bob)
        runCurrent()
        // republished under the new epoch's key
        assertEquals(2, treatmentGks().distinct().size)
        e.remove(ana)
        assertTrue("writers" !in relay.subscriptions)
        e.stop()
    }

    private object NoHub : GlucoseHub {
        override val state = MutableStateFlow(GlucoseState.Initial)

        override suspend fun ingest(readings: List<Reading>, from: SourceId) = Unit

        override fun setSource(status: SourceStatus) = Unit

        override fun setSensor(sensor: SensorState) = Unit

        override fun setAlerts(alerts: AlertUiState) = Unit

        override fun setLink(link: LinkState?) = Unit
    }
}
