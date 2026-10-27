// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.nightscout

import app.mauri.sukrara.core.Reading
import app.mauri.sukrara.core.SensorInfo
import app.mauri.sukrara.treatments.Treatment
import java.util.TimeZone
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's Nightscout upload (docs/25) against an in-memory Nightscout with 15.0.8's rules (and a 14.x mode): one
 * cycle = reconcile + plan + send, exactly as `Nightscout.cycle` runs them, with the phone's stores as the queue.
 */
class NightscoutUploadTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private var nowS = 1_792_000_000L
    private val ns = FakeNightscout()
    private val readings = sortedMapOf<Long, Reading>()
    private val treatments = LinkedHashMap<String, Treatment>()
    private var sensor: SensorInfo? = null
    private var state = NightscoutQueue.State()
    private val secret = NsAuth.ApiSecret("spike-secret-0123")
    private val token = NsAuth.AccessToken("sukrarapho-0123456789abcdef")

    private fun cfg(auth: NsAuth = secret, api: NsApi = NsApi.V1) = NsConfig(NsSite.parse("https://ns.example"), auth, api)

    private fun fill(minutes: Int, end: Long = nowS) { for (i in 0 until minutes) { val t = end - i * 60; readings[t] = Reading(t, 100 + i % 50, 0.5) } }

    /** One app cycle; throws [NsFailure] like the app's cycle does. */
    private fun cycle(c: NsConfig = cfg(), client: NsClient = NsClient(c, ns) { nowS * 1000 }, o: NightscoutQueue.Options = NightscoutQueue.Options()): NightscoutSync.Result {
        if (state.from == 0L) state = state.copy(from = nowS - NightscoutQueue.WINDOW_S)
        val save = { s: NightscoutQueue.State -> state = s }
        try {
            save(NightscoutSync.reconcile(client, c, state, nowS * 1000))
            val all = readings.values.toList()
            val plan = NightscoutQueue.plan(all, treatments.values.toList(), sensor, state, o, nowS)
            val r = NightscoutSync.send(client, c, plan, state, all, utc, nowS, save)
            save(NightscoutQueue.ok(state))
            return r
        } catch (e: NsFailure) {
            save(NightscoutQueue.failed(state, nowS * 1000, (e.outcome as? Outcome.Retry)?.afterMs, Random(1)))
            throw e
        }
    }

    private fun failing(f: () -> Unit): NsFailure = runCatching(f).exceptionOrNull() as? NsFailure ?: error("expected NsFailure")

    @Test fun backfillsThirtyDaysInBatchesAndNothingTwice() {
        fill(31 * 1440)
        val r = cycle()
        assertEquals(30 * 1440 + 1, r.sent)
        assertEquals(30 * 1440 + 1, ns.entries.size)
        assertEquals(87, ns.posts("/api/v1/entries"))   // 43 201 / 500
        assertEquals(0, cycle().sent)
    }

    @Test fun lateReadingsAndALostDatabaseHeal() {
        fill(120)
        readings.keys.filter { it in (nowS - 50 * 60)..(nowS - 31 * 60) }.toList().forEach { readings.remove(it) }   // a BLE gap
        cycle()
        assertEquals(100, ns.entries.size)
        fill(120)   // the sensor's history fills the gap
        assertEquals(20, cycle().sent)
        // the site loses 30 entries; Nightscout down for a while
        ns.entries.entries.removeIf { it.value.getLong("date") / 1000 in (nowS - 100 * 60)..(nowS - 71 * 60) }
        ns.failures += 0
        nowS += 60; fill(1)
        assertTrue(failing { cycle() }.outcome is Outcome.Retry)
        nowS += 600
        cycle()
        assertEquals(121, ns.entries.size)
        assertEquals(0, state.failures)
    }

    @Test fun lostResponseNeverDuplicates() {
        fill(30)
        ns.applyThenFail = true; ns.failures += 500
        failing { cycle() }
        ns.applyThenFail = false
        nowS += 120
        cycle()
        assertEquals(30, ns.entries.size)
    }

    @Test fun backoffDoublesCapsAndHonoursRetryAfter() {
        fill(3)
        ns.failures.addAll(List(12) { 503 })
        val waits = (1..12).map { failing { cycle() }; (state.nextAttemptMs - nowS * 1000).also { nowS += it / 1000 + 1 } }
        assertTrue(waits.first() in 24_000L..36_000L)
        assertTrue(waits.last() in 12 * 60_000L..18 * 60_000L)
        ns.retryAfterSec = 3600; ns.failures += 503
        failing { cycle() }
        assertTrue(state.nextAttemptMs - nowS * 1000 >= 3_600_000)
    }

    @Test fun refusedCredentialIsAnAuthFailure() {
        fill(3)
        val f = failing { cycle(cfg(NsAuth.ApiSecret("wrong-secret-xxxx"))) }
        assertTrue(f.outcome is Outcome.AuthFailed)
        val g = failing { cycle(cfg(NsAuth.AccessToken("nope-0000000000000000"), NsApi.V3)) }
        assertTrue(g.outcome is Outcome.AuthFailed)
    }

    @Test fun secondUploaderPausesReadingsNotTreatments() {
        fill(10)
        val iso = NsDocs.isoUtc((nowS - 120) * 1000)
        ns.entries["$iso|sgv"] = org.json.JSONObject().put("date", (nowS - 120) * 1000).put("sysTime", iso).put("type", "sgv").put("sgv", 99).put("device", "xDrip-LibreReceiver")
        treatments["a"] = Treatment("9f0c3c39-2a43-4f7e-9d8e-2b1f3c0a00aa", "carbs", 30.0, nowS - 600, "", 1, o = "phone").let { it.copy(id = "9f0c3c39-2a43-4f7e-9d8e-2b1f3c0a00aa") }
        cycle()
        assertEquals("xDrip-LibreReceiver", state.conflict)
        assertEquals(1, ns.entries.size)
        assertEquals(1, ns.treatments.size)
        state = state.copy(conflictAccepted = true)   // "Upload anyway"
        cycle()
        assertEquals(10, ns.entries.size)   // ours replaced the other one's minute: 9 + its own at the same minute
    }

    @Test fun treatmentEditMoveDeleteOnV1V3AndNightscout14() {
        for ((auth, api, legacy) in listOf(Triple(secret, NsApi.V1, false), Triple(token, NsApi.V3, false), Triple(secret, NsApi.V1, true))) {
            val fake = FakeNightscout().apply { legacyV1 = legacy }
            state = NightscoutQueue.State(); treatments.clear()
            val c = cfg(auth, api); val client = NsClient(c, fake) { nowS * 1000 }
            val id = "9f0c3c39-2a43-4f7e-9d8e-2b1f3c0a0001"
            treatments[id] = Treatment(id, "carbs", 40.0, nowS - 1800, "pasta", 1, o = "phone"); cycle(c, client)
            treatments[id] = treatments[id]!!.copy(a = 55.0, t = nowS - 3000, u = 2); cycle(c, client)
            assertEquals("$api legacy=$legacy: edit + move keep one document", 1, fake.treatments.size)
            assertEquals(55.0, fake.treatment(id)!!.getDouble("carbs"), 0.0)
            assertEquals(NsDocs.isoUtc((nowS - 3000) * 1000), fake.treatment(id)!!.getString("created_at"))
            treatments[id] = treatments[id]!!.copy(del = true, u = 3); cycle(c, client)
            assertTrue(fake.treatments.isEmpty())
        }
    }

    @Test fun sensorStartGoesUpOnceAsBothEvents() {
        sensor = SensorInfo("3MH001SYNTH", nowS - 3 * 86_400, nowS + 12 * 86_400)
        cycle(); cycle()
        assertEquals(setOf("Sensor Change", "Sensor Start"), ns.treatments.values.map { it.getString("eventType") }.toSet())
        assertEquals(2, ns.treatments.size)
    }

    @Test fun switchesLimitWhatGoesUp() {
        fill(5); sensor = SensorInfo("S", nowS - 60, nowS + 86_400)
        treatments["x"] = Treatment("9f0c3c39-2a43-4f7e-9d8e-2b1f3c0a0003", "rapid", 2.0, nowS, "", 1, o = "phone")
        cycle(o = NightscoutQueue.Options(readings = false, treatments = false, sensor = false, battery = false))
        assertTrue(ns.entries.isEmpty() && ns.treatments.isEmpty())
    }

    @Test fun v3SendsNewestFirstWithinItsBudget() {
        fill(300)
        val c = cfg(token, NsApi.V3); val client = NsClient(c, ns) { nowS * 1000 }
        assertEquals(240, cycle(c, client).sent)
        assertTrue(ns.entries.values.any { it.getLong("date") == nowS * 1000 })
        assertEquals(60, cycle(c, client).sent)
    }

    @Test fun batteryOnChangeOrEvery30Minutes() {
        val c = cfg(); val client = NsClient(c, ns) { nowS * 1000 }
        for ((p, dt) in listOf(80 to 0L, 80 to 300L, 79 to 60L, 79 to 2400L)) {
            nowS += dt
            NightscoutSync.battery(client, c, state, p, false, nowS * 1000, utc)?.let { state = it }
        }
        assertEquals(listOf(80, 79, 79), ns.deviceStatus.map { it.getJSONObject("uploader").getInt("battery") })
    }

    @Test fun documentsFollowTheReadersRules() {
        val e = NsDocs.entry(Reading(1_792_359_960, 155, null), Reading(1_792_359_900, 150, null), "Sukrara", utc, NsApi.V3)
        assertEquals("b019d3b3-b827-5605-9403-1cb50d8a495e", e.getString("identifier"))   // = the server's uuid.v5
        assertEquals("2026-10-18T21:46:00.000Z", e.getString("dateString"))
        assertEquals("DoubleUp", e.getString("direction"))
        assertEquals(25.0, e.getDouble("delta"), 0.0)
        assertEquals("NONE", NsDocs.entry(Reading(1, 100, null), null, "S", utc, NsApi.V1).getString("direction"))
        val ny = TimeZone.getTimeZone("America/New_York")
        val v1 = NsDocs.entry(Reading(1_792_359_960, 155, 0.0), null, "Sukrara", ny, NsApi.V1)
        assertEquals("2026-10-18T17:46:00.000-04:00", v1.getString("dateString"))
        assertEquals(-240, v1.getInt("utcOffset"))
        val long = NsDocs.treatment(Treatment("x", "long", 14.0, 0, "", 0, o = "phone"), "Sukrara", utc)
        assertFalse(long.has("insulin"))
        assertEquals("Long-acting insulin 14u", long.getString("notes"))
    }

    @Test fun onlyHttpsOrTheHomeNetwork() {
        assertNull(NsSite.parse("https://my.ns.example/").problem)
        assertEquals("https://my.ns.example", NsSite.parse("https://my.ns.example/").root)
        for (ok in listOf("http://localhost:1337", "http://127.0.0.1:1337", "http://[::1]:1337")) {
            assertNull(ok, NsSite.parse(ok).problem); assertFalse(ok, NsSite.parse(ok).insecure)
        }
        for (home in listOf("http://192.168.1.20:1337", "http://10.0.0.5", "http://172.16.0.1", "http://172.31.255.1",
                "http://169.254.3.4", "http://nightscout.local", "http://[fe80::1]:1337", "http://[fd12::5]")) {
            assertNull(home, NsSite.parse(home).problem); assertTrue(home, NsSite.parse(home).insecure)
        }
        for (bad in listOf("http://my.ns.example", "http://8.8.8.8", "http://172.32.0.1", "http://192.168.1.1.nip.io",
                "http://10.0.0.5.example.com", "http://local", "http://[2001:db8::1]")) {
            assertEquals(bad, "https", NsSite.parse(bad).problem)
        }
        for (junk in listOf("", "my.ns.example", "ftp://x", "https://user:pw@x.example", "https://x.example/?token=abc"))
            assertEquals(junk, "url", NsSite.parse(junk).problem)
        assertTrue(runCatching { NsConfig(NsSite.parse("https://x.example"), secret, NsApi.V3) }.isFailure)
        assertFalse(secret.toString().contains("spike"))
    }
}
