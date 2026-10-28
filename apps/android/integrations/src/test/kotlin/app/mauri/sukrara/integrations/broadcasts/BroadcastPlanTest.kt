// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.integrations.broadcasts

import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.testing.FakeClock
import app.mauri.sukrara.core.testing.InMemorySettingsRepository
import app.mauri.sukrara.core.testing.InMemoryStateStore
import app.mauri.sukrara.integrations.CurrentSensor
import app.mauri.sukrara.integrations.broadcasts.BroadcastPayloads.GLUCODATA
import app.mauri.sukrara.integrations.broadcasts.BroadcastPayloads.LIBRE
import app.mauri.sukrara.integrations.broadcasts.BroadcastPayloads.LIBRE_ACTIVATE
import app.mauri.sukrara.integrations.broadcasts.BroadcastPayloads.XDRIP
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** What goes out to other apps (a closed loop may dose from it) and what never does. */
class BroadcastPlanTest {
    private val now = Instant.parse("2026-10-28T12:00:00Z")
    private val serial = "3MH0012345"
    private val sensor = CurrentSensor(serial, now - Duration.ofDays(3), now + Duration.ofDays(12))
    private val aaps = mapOf("app.aaps" to setOf(XDRIP))

    private fun live(
        mgdl: Int = 120,
        ago: Duration = Duration.ZERO,
        lifeCount: Int = 4320,
        serial: String = this.serial,
    ) = LiveReading(mgdl, 1.2, now - ago, serial, lifeCount)

    private fun decide(
        r: LiveReading = live(),
        role: Role? = Role.Wearer,
        state: BcState = BcState(),
        receivers: Map<String, Set<String>> = aaps,
        sensor: CurrentSensor? = this.sensor,
    ) = BroadcastPlan.decide(role, r, now, state, sensor, receivers)

    @Test fun `the wearer's fresh Libre 3 reading goes to the allowed app`() {
        val d = decide() as BcDecision.Send
        assertEquals(mapOf("app.aaps" to listOf(XDRIP)), d.actions)
        assertEquals(120, d.out.mgdl)
        assertEquals(sensor.start.toEpochMilli(), d.out.sensorStartMs)
    }

    @Test fun `nothing from a follower or before a role is known`() {
        assertEquals(BcDecision.Wait(Hold.Follower), decide(role = Role.Follower))
        assertEquals(BcDecision.Wait(Hold.Follower), decide(role = null))
    }

    @Test fun `old, future and LO HI readings are held`() {
        assertEquals(BcDecision.Wait(Hold.Stale), decide(live(ago = Duration.ofMinutes(6))))
        assertTrue(decide(live(ago = Duration.ofMinutes(5))) is BcDecision.Send)
        assertEquals(BcDecision.Wait(Hold.Future), decide(live(ago = Duration.ofMinutes(-2))))
        assertEquals(BcDecision.Wait(Hold.OutOfRange), decide(live(mgdl = 39)))
        assertEquals(BcDecision.Wait(Hold.OutOfRange), decide(live(mgdl = 401)))
    }

    @Test fun `at most one per 55 s and never the same reading twice`() {
        val sent = decide() as BcDecision.Send
        val st = BroadcastPlan.after(BcState(), sent, 4320, sent.actions)
        // a restarted driver reporting the same life count again, even with a later arrival time
        val again = LiveReading(121, null, now + Duration.ofSeconds(70), serial, 4320)
        assertEquals(
            BcDecision.Wait(Hold.Repeated),
            BroadcastPlan.decide(Role.Wearer, again, now + Duration.ofSeconds(70), st, sensor, aaps),
        )
        val tooSoon = LiveReading(121, null, now + Duration.ofSeconds(30), serial, 4321)
        assertEquals(
            BcDecision.Wait(Hold.TooSoon),
            BroadcastPlan.decide(Role.Wearer, tooSoon, now + Duration.ofSeconds(30), st, sensor, aaps),
        )
        val next = LiveReading(121, null, now + Duration.ofSeconds(60), serial, 4321)
        assertTrue(BroadcastPlan.decide(Role.Wearer, next, now + Duration.ofSeconds(60), st, sensor, aaps) is BcDecision.Send)
    }

    @Test fun `a clock moved back doesn't stop sending forever`() {
        val st = BcState(lastSentMs = (now + Duration.ofHours(2)).toEpochMilli(), lastSerial = serial, lastLifeCount = 4300)
        assertTrue(decide(state = st) is BcDecision.Send)
    }

    @Test fun `one format per app, the first in the preference order`() {
        val gdh = mapOf("de.michelinside.glucodatahandler" to setOf(XDRIP, LIBRE, LIBRE_ACTIVATE, GLUCODATA))
        assertEquals(listOf(XDRIP), (decide(receivers = gdh) as BcDecision.Send).actions.values.single())
        val glucodataOnly = mapOf("p" to setOf(GLUCODATA))
        assertEquals(listOf(GLUCODATA), (decide(receivers = glucodataOnly) as BcDecision.Send).actions.values.single())
    }

    @Test fun `SENSOR_ACTIVATE goes once per app and sensor, only with a real start`() {
        val libre = mapOf("xdrip" to setOf(LIBRE, LIBRE_ACTIVATE), "gdh" to setOf(LIBRE, LIBRE_ACTIVATE))
        val first = decide(receivers = libre) as BcDecision.Send
        assertEquals(listOf(LIBRE_ACTIVATE, LIBRE), first.actions["xdrip"])
        // only gdh received it (xdrip's send failed): xdrip gets it again next time
        val st = BroadcastPlan.after(BcState(), first, 4320, mapOf("gdh" to listOf(LIBRE_ACTIVATE, LIBRE), "xdrip" to listOf(LIBRE)))
        val next = LiveReading(121, null, now + Duration.ofMinutes(1), serial, 4321)
        val second = BroadcastPlan.decide(Role.Wearer, next, now + Duration.ofMinutes(1), st, sensor, libre) as BcDecision.Send
        assertEquals(listOf(LIBRE_ACTIVATE, LIBRE), second.actions["xdrip"])
        assertEquals(listOf(LIBRE), second.actions["gdh"])
        // the sensor's start unknown (or another sensor's): no activation, no start in the reading
        val unknown = decide(receivers = libre, sensor = null) as BcDecision.Send
        assertEquals(listOf(LIBRE), unknown.actions["xdrip"])
        assertEquals(0L, unknown.out.sensorStartMs)
        val other = decide(receivers = libre, sensor = sensor.copy(serial = "OTHER")) as BcDecision.Send
        assertEquals(listOf(LIBRE), other.actions["xdrip"])
    }

    @Test fun `no allowed app that listens means nothing goes out`() {
        assertEquals(BcDecision.Wait(Hold.NoApp), decide(receivers = emptyMap()))
        assertEquals(BcDecision.Wait(Hold.NoApp), decide(receivers = mapOf("x" to emptySet())))
    }

    @Test fun `xDrip+ payload has AAPS's keys and types, and the Libre 3 source Juggluco sends`() {
        val out = BroadcastPayloads.Out(120, 1.2, 1_000L, serial, 500L)
        val x = BroadcastPayloads.xdrip(out)
        assertEquals(120.0, x[BroadcastPayloads.X_BG])
        assertEquals(1_000L, x[BroadcastPayloads.X_TIME])
        assertEquals(1.2 / 60_000.0, x[BroadcastPayloads.X_SLOPE] as Double, 1e-12)
        assertEquals("FortyFiveUp", x[BroadcastPayloads.X_SLOPE_NAME])
        assertEquals("Libre3", x[BroadcastPayloads.X_SOURCE_INFO])
        assertEquals(500L, x[BroadcastPayloads.X_SENSOR_STARTED])
        val g = BroadcastPayloads.glucodata(out)
        assertEquals(120, g["glucodata.Minute.mgdl"])
        assertEquals(120f, g["glucodata.Minute.glucose"])
        assertEquals(1_000L, g["glucodata.Minute.Time"])
        val l = BroadcastPayloads.libre(out)
        assertEquals(120.0, l["glucose"])
        assertEquals(mapOf("sensorSerial" to serial), l["bleManager"])
        assertFalse(BroadcastPayloads.xdrip(out.copy(rate = null)).containsKey(BroadcastPayloads.X_SLOPE))
    }

    @Test fun `the sender checks the signer before each send and records what went out`() =
        runBlocking {
            val settings = InMemorySettingsRepository()
            val store = InMemoryStateStore(BcState())
            val clock = FakeClock(now)
            val apps = FakeApps(receivers = mapOf("app.aaps" to setOf(XDRIP), "evil" to setOf(XDRIP)), signers = mapOf("app.aaps" to "aa"))
            val b =
                Broadcaster(
                    settings,
                    store,
                    emptyFlow(),
                    flowOf(sensor),
                    flowOf(Role.Wearer),
                    apps,
                    clock,
                    Dispatchers.Unconfined,
                ) {}
            // nothing allowed: nothing sent
            b.onLive(live())
            assertTrue(apps.sent.isEmpty())
            assertTrue(b.allow("app.aaps"))
            assertFalse(b.allow("not-installed"))
            b.onLive(live())
            assertEquals(listOf("app.aaps" to XDRIP), apps.sent)
            assertEquals(4320, store.data.first().lastLifeCount)
            // reinstalled with another key: nothing until allowed again
            apps.signers["app.aaps"] = "bb"
            clock.advance(Duration.ofMinutes(10))
            val later = live(lifeCount = 4330).copy(t = clock.now())
            b.onLive(later)
            assertEquals(1, apps.sent.size)
            // allowed again: the new signer is pinned and the next reading goes out
            assertTrue(b.allow("app.aaps"))
            b.onLive(later)
            assertEquals(2, apps.sent.size)
        }

    private class FakeApps(
        val receivers: Map<String, Set<String>>,
        signers: Map<String, String>,
    ) : AppDirectory {
        val signers = signers.toMutableMap()
        val sent = ArrayList<Pair<String, String>>()

        override fun receivers() = receivers

        override fun label(pkg: String) = pkg

        override fun signer(pkg: String) = signers[pkg]

        override fun signedBy(
            pkg: String,
            sha256: String,
        ) = signers[pkg] == sha256

        override fun send(
            pkg: String,
            action: String,
            extras: Map<String, Any>,
        ): Boolean {
            sent += pkg to action
            return true
        }
    }
}
