// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.nsupload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone
import kotlin.random.Random

class UploaderTest {
    private val min = 60_000L
    private var clock = 1_792_000_000_000L
    private val utc = TimeZone.getTimeZone("UTC")

    private class Local : LocalData {
        val readings = sortedMapOf<Long, Reading>()
        val treatments = HashMap<String, Treatment>()
        override fun readings(fromMs: Long, toMs: Long) = readings.subMap(fromMs, toMs + 1).values.toList()
        override fun treatment(id: String) = treatments[id]
    }

    private val local = Local()
    private val ns = FakeNightscout()
    private val store = MemoryStateStore()

    private fun cfg(api: NsApi = NsApi.V1, auth: NsAuth = NsAuth.ApiSecret("spike-secret-0123"), relay: String? = null) =
        NsConfig("https://ns.example", auth, api, relayPerson = relay)

    private fun uploader(c: NsConfig = cfg()) = Uploader(c, local, store, ns, { clock }, utc, Random(1))

    private fun fill(minutes: Int, end: Long = clock) {
        for (i in 0 until minutes) { val t = end - i * min; local.readings[t] = Reading(t, 100 + i % 50, 0.5) }
    }

    @Test fun firstRunBackfillsInBatches() {
        fill(1200)
        val r = uploader().sync()
        assertEquals(Health.OK, r.health)
        assertEquals(1200, r.backfilled)
        assertEquals(1200, ns.entries.size)
        assertEquals(3, ns.posts("/api/v1/entries"))   // 500 + 500 + 200
        assertEquals(Health.IDLE, uploader().sync().health)
    }

    @Test fun lostResponseNeverDuplicates() {
        fill(30)
        val up = uploader()
        ns.applyThenFail = true; ns.failures += 500   // the server stored the batch but the answer was a 500
        assertEquals(Health.BACKING_OFF, up.sync().health)
        ns.applyThenFail = false
        clock += 60_000
        assertEquals(Health.OK, up.sync().health)
        assertEquals(30, ns.entries.size)
    }

    @Test fun backoffDoublesWithJitterAndHonoursRetryAfter() {
        fill(5)
        val up = uploader()
        ns.failures.addAll(listOf(0, 0, 503))
        val w1 = up.sync().nextAttemptMs - clock
        assertTrue("first wait ~30 s: $w1", w1 in 24_000..36_000)
        assertEquals(Health.BACKING_OFF, up.sync().health)   // too early: no request
        clock += w1
        val w2 = up.sync().nextAttemptMs - clock
        assertTrue("second wait ~60 s: $w2", w2 in 48_000..72_000)
        clock += w2
        ns.retryAfterSec = 600
        val w3 = up.sync().nextAttemptMs - clock
        assertTrue("Retry-After 600 s wins: $w3", w3 >= 600_000)
        clock += w3
        assertEquals(Health.OK, up.sync().health)
        assertEquals(5, ns.entries.size)
    }

    @Test fun backoffIsCappedAt15Minutes() {
        fill(1)
        val up = uploader()
        repeat(12) { ns.failures += 500 }
        var last = 0L
        repeat(12) { last = up.sync().nextAttemptMs - clock; clock += last }
        assertTrue("cap: $last", last <= 18 * min)
    }

    @Test fun authFailureStopsUntilReset() {
        fill(3)
        val up = Uploader(cfg(auth = NsAuth.ApiSecret("wrong-secret-xxxx")), local, store, ns, { clock }, utc)
        assertEquals(Health.AUTH_FAILED, up.sync().health)
        val n = ns.requests.size
        clock += 3_600_000
        assertEquals(Health.AUTH_FAILED, up.sync().health)
        assertEquals("no requests while the secret is wrong", n, ns.requests.size)
        up.resetAuth()
        assertEquals(Health.AUTH_FAILED, up.sync().health)   // still wrong, but it tried again
        assertTrue(ns.requests.size > n)
    }

    @Test fun rejectedBatchIsDroppedNotRetriedForever() {
        fill(3)
        val up = uploader()
        up.sync()   // reconcile + upload ok
        local.readings[clock + min] = Reading(clock + min, 150)
        up.onReadings(listOf(local.readings[clock + min]!!))
        ns.failures += 400
        val r = up.sync()
        assertEquals(1, r.dropped)
        assertEquals(0, r.pending)
    }

    @Test fun lateReadingsAndOutageHealByDiff() {
        fill(120)
        val up = uploader()
        up.sync()
        // 20 server entries lost (DB restore) + Nightscout down while 10 new readings arrive
        ns.entries.entries.removeIf { it.value.getLong("date") in (clock - 100 * min)..(clock - 81 * min) }
        ns.failures.addAll(listOf(0, 0))
        repeat(10) { clock += min; local.readings[clock] = Reading(clock, 120); up.onReadings(listOf(local.readings[clock]!!)) }
        assertEquals(Health.BACKING_OFF, up.sync().health)
        clock += 40_000
        assertEquals(Health.BACKING_OFF, up.sync().health)
        clock += 90_000
        val r = up.sync()
        assertEquals(Health.OK, r.health)
        assertEquals(20, r.backfilled)
        assertEquals(130, ns.entries.size)
    }

    @Test fun followedReadingsNeverUploadedUnlessRelayChosen() {
        local.readings[clock] = Reading(clock, 222, 0.0, Origin.FOLLOWED, "alice")
        local.readings[clock - min] = Reading(clock - min, 101)
        val up = uploader()
        up.onReadings(local.readings.values.toList())
        up.sync()
        assertEquals(listOf(101), ns.entries.values.map { it.getInt("sgv") })

        val ns2 = FakeNightscout()
        val relay = Uploader(cfg(relay = "alice"), local, MemoryStateStore(), ns2, { clock }, utc)
        relay.sync()
        assertEquals(setOf("Sukrara relay", "Sukrara"), ns2.entries.values.map { it.getString("device") }.toSet())
        val ns3 = FakeNightscout()
        Uploader(cfg(relay = "bob"), local, MemoryStateStore(), ns3, { clock }, utc).sync()
        assertEquals("bob ≠ alice", listOf(101), ns3.entries.values.map { it.getInt("sgv") })
    }

    @Test fun anotherUploaderPausesEntriesUntilAccepted() {
        fill(10)
        val iso = NsDocs.isoUtc(clock - 2 * min)
        ns.entries["$iso|sgv"] = org.json.JSONObject().put("date", clock - 2 * min).put("sysTime", iso).put("type", "sgv").put("sgv", 99).put("device", "xDrip-LibreReceiver")
        val up = uploader()
        val r = up.sync()
        assertEquals(Health.CONFLICT, r.health)
        assertTrue(r.message.contains("xDrip-LibreReceiver"))
        assertEquals(0, ns.posts("/api/v1/entries"))
        up.acceptConflict()
        assertEquals(Health.OK, up.sync().health)
        assertEquals(10, ns.entries.size)
    }

    @Test fun treatmentsUpsertEditMoveAndDeleteV1() = treatmentRoundTrip(NsApi.V1, NsAuth.ApiSecret("spike-secret-0123"))

    @Test fun treatmentMoveOnNightscout14DoesNotDuplicate() { ns.legacyV1 = true; treatmentRoundTrip(NsApi.V1, NsAuth.ApiSecret("spike-secret-0123")) }

    @Test fun treatmentsUpsertEditMoveAndDeleteV3() = treatmentRoundTrip(NsApi.V3, NsAuth.AccessToken("sukrarapho-0123456789abcdef"))

    private fun treatmentRoundTrip(api: NsApi, auth: NsAuth) {
        val up = uploader(cfg(api, auth))
        val t = Treatment("9f0c3c39-2a43-4f7e-9d8e-2b1f3c0a0001", "carbs", 40.0, clock - 30 * min, "pasta", clock)
        local.treatments[t.id] = t; up.onTreatmentChanged(t); up.sync()
        assertEquals(40.0, ns.treatment(t.id)!!.getDouble("carbs"), 0.0)
        val moved = t.copy(amount = 55.0, epochMs = clock - 50 * min, updatedMs = clock + 1)
        local.treatments[t.id] = moved; up.onTreatmentChanged(moved); up.sync()
        assertEquals("edit + move replace the same document", 1, ns.treatments.size)
        assertEquals(55.0, ns.treatment(t.id)!!.getDouble("carbs"), 0.0)
        assertEquals(NsDocs.isoUtc(clock - 50 * min), ns.treatment(t.id)!!.getString("created_at"))
        local.treatments[t.id] = moved.copy(deleted = true, updatedMs = clock + 2); up.onTreatmentChanged(local.treatments[t.id]!!)
        assertEquals(1, up.sync().deletes)
        assertTrue(ns.treatments.isEmpty())
    }

    @Test fun accessTokenIsExchangedOnceAndRefreshedOnExpiry() {
        fill(3)
        ns.jwtExpSec = (clock / 1000) + 3600
        val up = uploader(cfg(NsApi.V3, NsAuth.AccessToken("sukrarapho-0123456789abcdef")))
        up.sync()
        assertEquals(1, ns.jwtIssued)
        assertTrue(ns.requests.filter { "/api/v3/" in it.url }.all { it.headers["Authorization"] == "Bearer jwt1" })
        clock += 2 * 3_600_000; ns.jwtExpSec = (clock / 1000) + 3600
        local.readings[clock] = Reading(clock, 140); up.onReadings(listOf(local.readings[clock]!!)); up.sync()
        assertEquals(2, ns.jwtIssued)
        assertEquals(4, ns.entries.size)
        assertFalse(ns.requests.any { it.headers.containsKey("api-secret") })
    }

    @Test fun v3SendsOneEntryPerRequestWithinTheBudget() {
        fill(300)
        val up = uploader(cfg(NsApi.V3, NsAuth.AccessToken("sukrarapho-0123456789abcdef")))
        assertEquals(240, up.sync().entries)
        assertEquals(60, up.sync().entries)
        assertEquals(300, ns.posts("/api/v3/entries"))
    }

    @Test fun v3DiffPagesPastTheThousandLimit() {
        fill(2500)
        Uploader(cfg(), local, MemoryStateStore(), ns, { clock }, utc).sync()   // v1 fills the server
        val n = ns.requests.size
        val r = uploader(cfg(NsApi.V3, NsAuth.AccessToken("sukrarapho-0123456789abcdef"))).sync()
        assertEquals(Health.IDLE, r.health)
        assertEquals("nothing missing after switching API", 0, r.backfilled)
        assertEquals("3 pages of ≤ 1000 + the token exchange", 4, ns.requests.size - n)
    }

    @Test fun queueSurvivesProcessDeath() {
        fill(10)
        val up = uploader()
        ns.failures += 0
        assertEquals(Health.BACKING_OFF, up.sync().health)
        val t = Treatment("9f0c3c39-2a43-4f7e-9d8e-2b1f3c0a0002", "rapid", 3.0, clock, "", clock)
        local.treatments[t.id] = t; up.onTreatmentChanged(t)
        up.onSensorStart(SensorStart("SER1", clock - 3_600_000))
        up.onBattery(UploaderBattery(77, true, clock))
        clock += 20 * min
        val again = uploader()   // new process, same state file
        val r = again.sync()
        assertEquals(Health.OK, r.health)
        assertEquals(10, ns.entries.size); assertEquals(1, r.treatments); assertEquals(2, r.sensorEvents); assertEquals(1, r.deviceStatus)
        assertEquals(77, ns.deviceStatus.single().getJSONObject("uploader").getInt("battery"))
    }

    @Test fun batteryOnlyWhenChangedOrEvery30Minutes() {
        val up = uploader()
        up.onBattery(UploaderBattery(80, false, clock)); up.sync()
        up.onBattery(UploaderBattery(80, false, clock + 5 * min)); up.sync()
        up.onBattery(UploaderBattery(79, false, clock + 6 * min)); up.sync()
        up.onBattery(UploaderBattery(79, false, clock + 40 * min)); up.sync()
        assertEquals(listOf(80, 79, 79), ns.deviceStatus.map { it.getJSONObject("uploader").getInt("battery") })
    }

    @Test fun configRefusesPlainHttpAndSecretOnV3() {
        assertThrows { NsConfig("http://my-ns.example", NsAuth.ApiSecret("spike-secret-0123")) }
        NsConfig("http://localhost:1337", NsAuth.ApiSecret("spike-secret-0123"))
        assertThrows { NsConfig("https://x.example", NsAuth.ApiSecret("spike-secret-0123"), NsApi.V3) }
        assertThrows { NsConfig("https://x.example", NsAuth.ApiSecret("short")) }
        assertFalse(NsAuth.ApiSecret("spike-secret-0123").toString().contains("spike"))
    }

    private fun assertThrows(f: () -> Unit) {
        val threw = runCatching(f).isFailure
        assertTrue("expected an exception", threw)
    }

    @Test fun documentsFollowTheReadersRules() {
        val e = NsDocs.entry(Reading(1_792_359_960_000, 155, null), Reading(1_792_359_900_000, 150), "Sukrara", utc)
        assertEquals("b019d3b3-b827-5605-9403-1cb50d8a495e", e.getString("identifier"))   // = server's uuid.v5 (python uuid5 too)
        assertEquals("2026-10-18T21:46:00.000Z", e.getString("dateString"))
        assertEquals("DoubleUp", e.getString("direction"))   // +5 mg/dL in 1 min
        assertEquals(25.0, e.getDouble("delta"), 0.0)
        assertTrue(e.get("sgv") is Int && e.get("noise") is Int && e.get("date") is Long)
        assertEquals("NONE", NsDocs.entry(Reading(1, 100), null, "S", utc).getString("direction"))   // never "None"
        val long = NsDocs.treatment(Treatment("x", "long", 14.0, 0, "", 0), "Sukrara", utc)
        assertFalse("long-acting must not feed IOB", long.has("insulin"))
        assertEquals("Long-acting insulin 14 U", long.getString("notes"))
        assertEquals(listOf("Sensor Change", "Sensor Start"), NsDocs.sensorEvents(SensorStart("S", 0), "Sukrara", utc).map { it.getString("eventType") })
        assertNotEquals(NsDocs.sensorEvents(SensorStart("S", 0), "x", utc)[0].getString("identifier"), NsDocs.sensorEvents(SensorStart("S", 0), "x", utc)[1].getString("identifier"))
    }
}
