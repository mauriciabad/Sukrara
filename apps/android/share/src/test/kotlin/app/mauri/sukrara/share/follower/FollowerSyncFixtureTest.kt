// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.follower

import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.testing.Contracts
import app.mauri.sukrara.core.testing.FakeClock
import app.mauri.sukrara.core.testing.InMemoryReadingsRepository
import app.mauri.sukrara.core.testing.InMemorySecureStore
import app.mauri.sukrara.core.testing.InMemoryTreatmentsRepository
import app.mauri.sukrara.protocol.KeyPair
import app.mauri.sukrara.protocol.PublicKeyHex
import app.mauri.sukrara.protocol.SecretKey
import app.mauri.sukrara.protocol.SignedEvent
import app.mauri.sukrara.protocol.follow.FollowerState
import app.mauri.sukrara.protocol.testing.ContractFiles
import app.mauri.sukrara.share.FakeRelayTransport
import app.mauri.sukrara.share.OwnerFixtures.crypto
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * The follower sequences in contracts/protocol/sync through the real [FollowerEngine] (not only `StreamFollower`, which
 * `SyncFixturesTest` pins): a seeded follower record, every step's event delivered by a fake relay, `{"restart"}` =
 * a new engine on the same SecureStore (what a process restart keeps). The engine must leave the fixture's expected
 * status, keys, devices, writers, readings, sensor, treatments and settings — what the person sees.
 */
class FollowerSyncFixtureTest {
    private val files =
        java.io
            .File(Contracts.root, "protocol/sync")
            .listFiles { f -> f.extension == "json" }!!
            .map { it.nameWithoutExtension }
            .filter { it != "owner-conflict" } // the wearer's side (IdentityConflictTest)
            .sorted()

    @Test fun everyFollowerSequenceLeavesTheExpectedState() {
        assertEquals(listOf("join-readings", "revoke-reapprove", "rotation", "settings-mirror", "treatments-writer"), files)
        files.forEach { replay(it) }
    }

    private fun replay(name: String) =
        runTest {
            val fx = ContractFiles.json("protocol/sync/$name.json")
            val keys = fx.getValue("keys").jsonObject
            val follower = pair(keys.getValue(fx.str("follower")).jsonObject)
            val events = fx.getValue("events").jsonObject.mapValues { (_, v) -> SignedEvent.parse(v.toString())!! }
            val clock = FakeClock(Instant.ofEpochSecond(fx.getValue("now").jsonPrimitive.long))
            val secure = InMemorySecureStore()
            val store = FollowerStore(secure, crypto)
            val treatments = InMemoryTreatmentsRepository()
            val readings = InMemoryReadingsRepository()
            store.save(
                FollowerRecord(
                    stream = FollowerState(follower = follower, identity = PublicKeyHex(fx.str("identity"))),
                    token = "t",
                    code = "0000",
                    me = null,
                    wearer = null,
                    joinedAt = clock.now().epochSecond,
                ),
            )
            val received = mutableListOf<Reading>()
            val relay = FakeRelayTransport()

            fun TestScope.start(): Pair<FollowerEngine, Job> {
                val e = FollowerEngine(crypto, store, { _, _ -> relay }, readings, treatments, clock, StandardTestDispatcher(testScheduler))
                val job = backgroundScope.launch { e.readings.collect { received += it } }
                runCurrent()
                return e to job
            }

            var (engine, job) = start()
            for (step in fx.getValue("steps").jsonArray.map { it.jsonObject }) {
                if (step["restart"]?.jsonPrimitive?.booleanOrNull == true) {
                    job.cancel()
                    runCurrent()
                    start().let { (e, j) ->
                        engine = e
                        job = j
                    }
                    continue
                }
                relay.deliver("sync", events.getValue(step.str("event")))
                runCurrent()
            }
            job.cancel()
            runCurrent()

            val expected = fx.getValue("expected").jsonObject
            val record = (store.load() as FollowerStore.Load.Loaded).record
            val s = record.stream
            assertEquals("$name status", expected.str("status"), s.status.name.lowercase())
            assertEquals(
                "$name epochs",
                expected.longs("epochs"),
                s.epochs.values
                    .map { it.n.toLong() }
                    .sorted(),
            )
            assertEquals("$name devices", expected.strs("devices"), s.devices)
            assertEquals("$name writers", expected.strs("writers"), s.writers)
            val firstPerT = received.groupBy { it.t.epochSecond }.mapValues { it.value.first().mgdl }.toSortedMap()
            val want =
                expected.getValue("readings").jsonArray.associate { r ->
                    r.jsonArray[0].jsonPrimitive.long to
                        r.jsonArray[1]
                            .jsonPrimitive.long
                            .toInt()
                }
            assertEquals("$name readings", want, firstPerT)
            val sensor = expected["sensor"]?.takeIf { it != JsonNull }?.jsonObject
            assertEquals(
                "$name sensor",
                sensor?.let {
                    listOf(
                        it.getValue("ss").jsonPrimitive.long,
                        it.getValue("se").jsonPrimitive.long,
                        it.getValue("sw").jsonPrimitive.long,
                    )
                },
                record.sensor?.let { listOf(it.start, it.end, it.warmupEnd) },
            )
            val stored = treatments.changedSince(Instant.EPOCH).map { it.treatment }.sortedBy { it.id }
            val wantTreatments = expected.getValue("treatments").jsonArray.map { it.jsonObject }
            assertEquals(
                "$name treatments",
                wantTreatments.map { it.str("id") to it.getValue("del").jsonPrimitive.booleanOrNull },
                stored.map {
                    it.id to
                        it.deleted
                },
            )
            assertEquals(
                "$name treatment versions",
                wantTreatments.map { it.getValue("u").jsonPrimitive.long },
                stored.map { it.modifiedAt.toEpochMilli() },
            )
            val settings = expected["settings"]?.takeIf { it != JsonNull }?.jsonObject
            assertEquals("$name settings", settings?.getValue("u")?.jsonPrimitive?.long, record.settings?.u)
        }

    private fun pair(o: JsonObject) = KeyPair(SecretKey.ofHex(o.str("sec"))!!, PublicKeyHex(o.str("pub")))

    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content

    private fun JsonObject.strs(k: String) = (getValue(k) as JsonArray).map { it.jsonPrimitive.content }

    private fun JsonObject.longs(k: String) = (getValue(k) as JsonArray).map { it.jsonPrimitive.long }.sorted()
}
