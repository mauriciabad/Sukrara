// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.owner

import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.SourceId
import app.mauri.sukrara.core.model.Treatment
import app.mauri.sukrara.core.model.TreatmentKind
import app.mauri.sukrara.core.model.TreatmentOrigin
import app.mauri.sukrara.core.model.storage.TreatmentRecord
import app.mauri.sukrara.core.testing.FakeClock
import app.mauri.sukrara.core.testing.InMemorySecureStore
import app.mauri.sukrara.core.testing.InMemoryTreatmentsRepository
import app.mauri.sukrara.protocol.SecretKey
import app.mauri.sukrara.protocol.SignedEvent
import app.mauri.sukrara.protocol.Wire
import app.mauri.sukrara.protocol.testing.ContractFiles
import app.mauri.sukrara.share.FakeHub
import app.mauri.sukrara.share.FakeRelayTransport
import app.mauri.sukrara.share.OwnerFixtures
import app.mauri.sukrara.share.OwnerFixtures.crypto
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's sharing wiring (AppGraph: `ownerEngine.followRole(role)`, hub, treatment log): the engine runs only while
 * the phone is the wearer, publishes the hub's readings and the log's changes, merges and echoes a writer's entry
 * from relays, and stops for a follower.
 */
class OwnerWiringTest {
    private val fx = ContractFiles.json("protocol/sync/treatments-writer.json")
    private val now = fx.getValue("now").jsonPrimitive.content.toLong()

    private fun key(name: String) =
        crypto.keyPair(SecretKey.ofHex(fx.getValue("keys").jsonObject.getValue(name).jsonObject.getValue("sec").jsonPrimitive.content)!!)!!

    @Test fun sharingFollowsTheRoleAndCarriesReadingsTreatmentsAndWriterEntries() = runTest {
        val w = key("W")
        val store = OwnerIdentityStore(InMemorySecureStore())
        store.save(
            OwnerState(
                identityHex = key("I").secret.revealHex(),
                deviceHex = key("D").secret.revealHex(),
                sid = fx.getValue("sid").jsonPrimitive.content,
                epochs = listOf(EpochKey(1, key("G1").secret.revealHex())),
                followers = listOf(FollowerRecord(w.public.hex, "Ana", now - 1000)),
                writers = setOf(w.public.hex),
                rosterPublishedAt = now,
                backupPublishedAt = now,
            ),
        )
        val relay = FakeRelayTransport()
        val hub = FakeHub()
        val log = InMemoryTreatmentsRepository()
        val engine = OwnerEngine(
            OwnerFixtures.context(), store, relay, hub, { _, _ -> true }, log, FakeClock(Instant.ofEpochSecond(now)), StandardTestDispatcher(testScheduler),
        )
        val role = MutableStateFlow<Role?>(null)
        backgroundScope.launch { engine.followRole(role, backgroundScope) }
        runCurrent()
        assertFalse(engine.running)

        role.value = Role.Wearer
        runCurrent()
        assertTrue(engine.running)
        hub.push(Reading(Instant.ofEpochSecond(now - 30), 123, 0.5, SourceId.Libre3))
        runCurrent()
        assertTrue(relay.published.any { it.kind == Wire.KIND_LIVE })

        val carbs = Treatment("c0000000-0000-4000-8000-00000000000c", TreatmentKind.Carbs, 20.0, Instant.ofEpochSecond(now - 60), modifiedAt = Instant.ofEpochSecond(now - 60), origin = TreatmentOrigin.Phone)
        engine.publishTreatment(TreatmentRecord(carbs))
        assertEquals(1, relay.published.count { it.tag("d") == Wire.treatmentD(fx.getValue("sid").jsonPrimitive.content, carbs.id) })

        val writerEvent = SignedEvent.parse(fx.getValue("events").jsonObject.getValue("writer-rapid").toString())!!
        relay.deliver("writers", writerEvent)
        runCurrent()
        val echo = relay.published.last()
        assertEquals(key("D").public.hex, echo.pubkey)
        assertEquals(writerEvent.tag("d"), echo.tag("d"))
        assertEquals(w.public.hex, log.get("b0000000-0000-4000-8000-00000000000b")!!.creator)

        role.value = Role.Follower
        runCurrent()
        assertFalse(engine.running)
        assertFalse(relay.started)
    }
}
