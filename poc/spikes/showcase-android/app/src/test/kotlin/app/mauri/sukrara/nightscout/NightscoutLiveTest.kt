// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.nightscout

import app.mauri.sukrara.core.Reading
import app.mauri.sukrara.core.SensorInfo
import app.mauri.sukrara.treatments.Treatment
import java.net.URLEncoder
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * End-to-end against a **local, throwaway** Nightscout (docs/25 "In Sukrara"; `spikes/nightscout-upload/verify/
 * nightscout.sh up`), synthetic data only, through the app's own planner, sender and HTTP client. Skipped unless
 * `NS_E2E_URL` (must be http://localhost or 127.0.0.1), `NS_E2E_SECRET`, `NS_E2E_TOKEN` (the sukrara-uploader role) and
 * `NS_E2E_READ_TOKEN` are set; never in CI, never against a hosted site. Prints counts only, no credential.
 */
class NightscoutLiveTest {
    private val zone = TimeZone.getTimeZone("America/New_York")
    private val log = { m: String -> println("[nightscout-e2e] $m") }

    @Test fun uploadThirtyDaysThenEditsOutageAndV3() {
        val url = System.getenv("NS_E2E_URL")
        assumeTrue("set NS_E2E_URL, NS_E2E_SECRET, NS_E2E_TOKEN, NS_E2E_READ_TOKEN to run", url != null)
        val site = NsSite.parse(url!!)
        assertTrue("local sites only", NsSite.isLoopback(java.net.URI(site.root).host))
        val read = System.getenv("NS_E2E_READ_TOKEN")!!
        val http = UrlConnectionHttp()
        fun get(path: String): JSONArray = JSONArray(http.send(HttpRequest("GET", "${site.root}$path${if ('?' in path) "&" else "?"}token=" +
            URLEncoder.encode(read, "UTF-8"))).body)

        val nowS = System.currentTimeMillis() / 1000 / 60 * 60
        val readings = (0 until 31 * 1440).map { i ->
            val t = nowS - i * 60L
            val h = (t / 3600.0) % 24
            Reading(t, (130 + 50 * sin(2 * PI * (h - 9) / 24) + 3 * sin(t / 97.0)).roundToInt(), 50 * 2 * PI / 24 / 60 * kotlin.math.cos(2 * PI * (h - 9) / 24))
        }.filter { it.t !in (nowS - 50 * 60)..(nowS - 31 * 60) }.sortedBy { it.t }            // a 20-min BLE gap
        val treatments = linkedMapOf(
            "c" to Treatment("6b0e7a8e-0000-4000-8000-00000000000c", "carbs", 45.0, nowS - 95 * 60, "synthetic breakfast", 1, o = "phone"),
            "r" to Treatment("6b0e7a8e-0000-4000-8000-000000000001", "rapid", 4.5, nowS - 100 * 60, "", 1, o = "phone"),
            "l" to Treatment("6b0e7a8e-0000-4000-8000-00000000000d", "long", 14.0, nowS - 13 * 3600, "", 1, o = "phone"),
            "n" to Treatment("6b0e7a8e-0000-4000-8000-00000000000e", "note", 0.0, nowS - 5 * 3600, "synthetic walk", 1, o = "phone"),
        )
        val sensor = SensorInfo("SYNTH0001", nowS - 3 * 86_400, nowS + 12 * 86_400)
        var state = NightscoutQueue.State(from = nowS - NightscoutQueue.WINDOW_S)
        val save = { s: NightscoutQueue.State -> state = s }

        fun cycle(cfg: NsConfig, rs: List<Reading>): NightscoutSync.Result {
            val c = NsClient(cfg, http)
            val t = System.currentTimeMillis()
            save(NightscoutSync.reconcile(c, cfg, state, nowS * 1000))
            val plan = NightscoutQueue.plan(rs, treatments.values.toList(), sensor, state, NightscoutQueue.Options(), nowS)
            val r = NightscoutSync.send(c, cfg, plan, state, rs, zone, nowS, save)
            NightscoutSync.battery(c, cfg, state, 64, false, nowS * 1000, zone)?.let(save)
            save(NightscoutQueue.ok(state))
            log("${cfg.api} ${cfg.auth.javaClass.simpleName}: sent ${r.sent}, dropped ${r.dropped} in ${System.currentTimeMillis() - t} ms")
            return r
        }

        // 1. first cycle, API v1 + API secret: 30 days backfilled, treatments, both sensor events, battery
        val secret = NsConfig(site, NsAuth.ApiSecret(System.getenv("NS_E2E_SECRET")!!))
        val first = cycle(secret, readings)
        val days30 = readings.count { it.t >= nowS - NightscoutQueue.WINDOW_S }
        assertEquals(days30 + 4 + 2, first.sent)
        assertEquals(0, cycle(secret, readings).sent)

        // 2. the gap's readings arrive late (sensor history)
        val full = readings + (31..50).map { m -> Reading(nowS - m * 60L, 120, 0.0) }
        assertEquals(20, cycle(secret, full.sortedBy { it.t }).sent)
        val stored = get("/api/v1/entries/sgv.json?count=50000&find[date][\$gte]=${(nowS - NightscoutQueue.WINDOW_S) * 1000}")
        val dates = (0 until stored.length()).map { stored.getJSONObject(it).getLong("date") }
        val gaps = dates.sorted().zipWithNext().count { (a, b) -> b - a > 60_000 }
        log("site has ${dates.size} entries in 30 days, ${gaps} gaps > 1 min, ${dates.size - dates.toSet().size} duplicates")
        assertEquals(0, gaps); assertEquals(dates.size, dates.toSet().size)
        val newest = stored.getJSONObject(0)
        log("newest: sgv ${newest.getInt("sgv")} ${newest.getString("direction")} Δ${newest.opt("delta")} dateString ${newest.getString("dateString")} utcOffset ${newest.getInt("utcOffset")}")
        assertTrue(newest.getString("dateString").endsWith("Z")); assertEquals(-240, newest.getInt("utcOffset"))

        // 3. treatments as the readers see them
        val tr = get("/api/v1/treatments.json?count=50")
        val byType = (0 until tr.length()).map { tr.getJSONObject(it) }.groupBy { it.getString("eventType") }
        log("treatments: " + byType.mapValues { it.value.size })
        assertFalse("long-acting must not feed IOB", byType["Note"]!!.any { it.opt("insulin") is Number })
        assertEquals(2, byType["Note"]!!.size)
        assertEquals(1, byType["Sensor Start"]?.size); assertEquals(1, byType["Sensor Change"]?.size)
        val ds = get("/api/v1/devicestatus.json?count=1").getJSONObject(0)
        assertEquals(64, ds.getJSONObject("uploader").getInt("battery"))

        // 4. edit + move + delete through API v3 with the least-privilege token (same site: no duplicates across APIs)
        state = state.copy(reconciledMs = 0)
        val v3 = NsConfig(site, NsAuth.AccessToken(System.getenv("NS_E2E_TOKEN")!!), NsApi.V3)
        assertEquals(0, cycle(v3, full.sortedBy { it.t }).sent)
        treatments["c"] = treatments["c"]!!.copy(a = 55.0, t = nowS - 120 * 60, u = 2)
        treatments["n"] = treatments["n"]!!.copy(del = true, u = 2)
        assertEquals(2, cycle(v3, full.sortedBy { it.t }).sent)
        val c = get("/api/v1/treatments.json?find[identifier]=${treatments["c"]!!.id}&count=5")
        assertEquals(1, c.length()); assertEquals(55.0, c.getJSONObject(0).getDouble("carbs"), 0.0)
        assertEquals(NsDocs.isoUtc((nowS - 120 * 60) * 1000), c.getJSONObject(0).getString("created_at"))
        assertEquals(0, get("/api/v1/treatments.json?find[identifier]=${treatments["n"]!!.id}&count=5").length())
        log("v3: carbs edited + moved (1 document), a note deleted (0 documents, permanent)")
    }
}
