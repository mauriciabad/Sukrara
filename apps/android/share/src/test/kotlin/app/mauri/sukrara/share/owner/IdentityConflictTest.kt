// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.owner

import app.mauri.sukrara.core.model.GlucoseHub
import app.mauri.sukrara.core.model.GlucoseState
import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.SourceId
import app.mauri.sukrara.core.model.SourceStatus
import app.mauri.sukrara.core.testing.Contracts
import app.mauri.sukrara.core.testing.FakeClock
import app.mauri.sukrara.core.testing.InMemorySecureStore
import app.mauri.sukrara.protocol.SignedEvent
import app.mauri.sukrara.protocol.owner.IdentityCheck
import app.mauri.sukrara.protocol.owner.IdentityConflict
import app.mauri.sukrara.share.FakeRelayTransport
import app.mauri.sukrara.share.OwnerFixtures
import app.mauri.sukrara.share.OwnerFixtures.crypto
import app.mauri.sukrara.share.owner.usecase.IdentityConflictDetector
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A3 through the engine, on contracts/protocol/sync/owner-conflict.json (the same file 06 / Mac replay): the
 * wearer's phone after a restore (device D2, epochs 1–2) sees roster / backup events signed by its identity.
 */
class IdentityConflictTest {
    private val fixture = Json.parseToJsonElement(Contracts.file("protocol/sync/owner-conflict.json").readText()).jsonObject
    private val keys = fixture["keys"]!!.jsonObject.mapValues { it.value.jsonObject["sec"]!!.jsonPrimitive.content }
    private val marks = fixture["marks"]!!.jsonObject
    private val events = fixture["events"]!!.jsonObject.mapValues { SignedEvent.parse(it.value.toString())!! }

    /** The wearer's OwnerState matching the fixture's `marks`. */
    private val state = OwnerState(
        identityHex = keys.getValue(marks["identity"]!!.jsonPrimitive.content),
        deviceHex = keys.getValue(marks["device"]!!.jsonPrimitive.content),
        sid = fixture["sid"]!!.jsonPrimitive.content,
        epochs = (marks["epochs"] as JsonObject).map { (n, k) -> EpochKey(n.toInt(), keys.getValue(k.jsonPrimitive.content)) },
        rosterPublishedAt = marks["lastRosterAt"]!!.jsonPrimitive.long,
        backupPublishedAt = marks["lastBackupAt"]!!.jsonPrimitive.long,
    )
    private val detector = IdentityConflictDetector(OwnerFixtures.context())

    @Test fun everyFixtureCheckGivesTheExpectedVerdict() {
        fixture["checks"]!!.jsonArray.forEach { c ->
            val name = c.jsonObject["event"]!!.jsonPrimitive.content
            val expect = c.jsonObject["expect"]!!.jsonPrimitive.content
            val got = detector.check(state, events.getValue(name), NOW)?.conflict?.reason?.let { "conflict:${it.name}" } ?: "fine"
            assertEquals(name, expect, got)
        }
    }

    @Test fun retryReplacesTheOtherAppsRosterSoItsStaleEventIsFineAfterwards() {
        val showcase = events.getValue("showcase-roster")
        val blocked = detector.check(state, showcase, NOW)!!
        val retried = detector.retry(blocked, NOW)
        assertNull(retried.state.conflict)
        assertTrue(retried.state.rosterPublishedAt > showcase.createdAt)
        assertEquals(IdentityCheck.Fine, IdentityConflict.check(crypto, retried.state.marks(crypto), showcase))
    }

    @Test fun engineBlocksControlPublishingButKeepsReadingsGoing() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val relay = FakeRelayTransport()
        val secure = InMemorySecureStore()
        val store = OwnerIdentityStore(secure)
        store.save(state.copy(rosterPublishedAt = NOW, backupPublishedAt = NOW))
        val hub = FakeHub()
        val clock = FakeClock(Instant.ofEpochSecond(NOW))
        val engine = OwnerEngine(OwnerFixtures.context(), store, relay, hub, { _, _ -> true }, clock, dispatcher)
        engine.start(backgroundScope)
        runCurrent()
        assertTrue(engine.state.value is OwnerShareState.Sharing)

        relay.deliver("own", events.getValue("showcase-roster"))
        runCurrent()
        val blocked = engine.state.value as OwnerShareState.AnotherAppSharingAsYou
        assertEquals(IdentityCheck.Reason.RosterFromOtherDevice, blocked.reason)
        assertTrue(blocked.followersReceiving)
        // persisted: a restart is still blocked
        assertTrue((store.load() as OwnerIdentityStore.Load.Loaded).state.conflict != null)
        // follower changes refused, no roster / backup goes out
        val before = relay.published.size
        assertNull(engine.createInvite("x"))
        assertEquals(before, relay.published.size)
        // readings continue
        hub.push(Reading(Instant.ofEpochSecond(NOW - 30), 123, 0.5, SourceId.Libre3))
        runCurrent()
        assertTrue(relay.published.drop(before).any { it.kind == app.mauri.sukrara.protocol.Wire.KIND_LIVE })

        engine.retryConflict()
        runCurrent()
        assertTrue(engine.state.value is OwnerShareState.Sharing)
        engine.stop()
    }

    /** The hub as a wearer's phone sees it; readings appear in `recent`. */
    private class FakeHub : GlucoseHub {
        override val state = MutableStateFlow(GlucoseState.Initial.copy(role = app.mauri.sukrara.core.model.Role.Wearer))

        fun push(r: Reading) {
            state.value = state.value.copy(latest = r, recent = state.value.recent + r)
        }

        override suspend fun ingest(readings: List<Reading>, from: SourceId) = Unit

        override fun setSource(status: SourceStatus) = Unit

        override fun setSensor(sensor: app.mauri.sukrara.core.model.SensorState) = Unit

        override fun setAlerts(alerts: app.mauri.sukrara.core.model.AlertUiState) = Unit

        override fun setLink(link: app.mauri.sukrara.core.model.LinkState?) = Unit
    }

    private companion object {
        val NOW = 1_792_087_200L
    }
}
