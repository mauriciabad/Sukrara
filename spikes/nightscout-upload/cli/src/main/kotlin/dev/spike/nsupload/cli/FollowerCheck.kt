// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.nsupload.cli

import dev.spike.nsupload.HttpRequest
import dev.spike.nsupload.NsDocs
import dev.spike.nsupload.UrlConnectionHttp
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Locale
import java.util.TimeZone

/**
 * Replays each follower's own request against the server and applies its decoder's rules (from source, docs/25 §2;
 * the real Swift decoders of Trio and LoopFollow run in `followers/run.sh`). Each line is one assertion.
 */
class FollowerCheck(private val url: String, private val hours: Int) {
    private val http = UrlConnectionHttp()
    private val secret = System.getenv("NS_API_SECRET")
    private val readToken = System.getenv("NS_READ_TOKEN")
    private var fails = 0
    private var passes = 0
    private val since = System.currentTimeMillis() - hours * 3_600_000L

    private val nsDirections = setOf("NONE", "DoubleUp", "SingleUp", "FortyFiveUp", "Flat", "FortyFiveDown", "SingleDown", "DoubleDown", "NOT COMPUTABLE", "RATE OUT OF RANGE")
    private val trioDirections = nsDirections + setOf("TripleUp", "TripleDown")

    fun run(): Int {
        loopFollow(); nightguard(); trio(); homeAssistant()
        println("\n$passes passed, $fails failed")
        return if (fails == 0) 0 else 1
    }

    private fun ok(cond: Boolean, what: String) { if (cond) passes++ else fails++; println("  ${if (cond) "PASS" else "FAIL"} $what") }

    private fun get(path: String, headers: Map<String, String> = emptyMap()): String {
        val r = http.send(HttpRequest("GET", url.trimEnd('/') + path, headers))
        check(r.code == 200) { "GET $path → ${r.code} ${r.body.take(120)}" }
        return r.body
    }

    private fun tok() = "token=" + URLEncoder.encode(readToken ?: error("NS_READ_TOKEN not set"), "UTF-8")
    private fun utcNoFrac(ms: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(ms)

    // LoopFollow: BGData.swift:70-76, ShareClientExtension.swift:7-37, Treatments.swift, DeviceStatus.swift, SAge.swift
    private fun loopFollow() {
        println("LoopFollow (token= query, v1)")
        val e = JSONArray(get("/api/v1/entries.json?count=${hours * 60 + 10}&find[date][\$gte]=$since&find[type][\$ne]=cal&${tok()}"))
        val objs = (0 until e.length()).map { e.getJSONObject(it) }
        ok(objs.size >= hours * 60 - 5, "entries since ${hours} h: ${objs.size} (1/min)")
        ok(objs.all { it.opt("sgv") is Number && it.opt("date") is Number }, "every entry: numeric sgv + numeric date (one bad entry fails the whole fetch)")
        ok(objs.all { it.optLong("date") > 1e12 }, "date is epoch ms (LoopFollow divides by 1000)")
        ok(objs.all { it.optString("direction") in nsDirections }, "direction in LoopFollow's arrow map")
        ok(objs.map { it.getLong("date") }.toSet().size == objs.size, "no duplicate dates")
        val tr = JSONArray(get("/api/v1/treatments.json?find[created_at][\$gte]=${utcNoFrac(since - 86_400_000L * 4)}&find[created_at][\$lte]=${utcNoFrac(System.currentTimeMillis() + 60_000)}&count=200&${tok()}"))
        val ts = (0 until tr.length()).map { tr.getJSONObject(it) }
        ok(ts.any { it.optString("eventType") == "Carb Correction" && it.opt("carbs") is Number }, "Carb Correction with numeric carbs")
        ok(ts.any { it.optString("eventType") == "Correction Bolus" && it.opt("insulin") is Number }, "Correction Bolus with numeric insulin")
        ok(ts.none { it.optString("eventType") == "Note" && it.opt("insulin") is Number }, "long-acting is a Note without an insulin field (no IOB)")
        val sage = ts.firstOrNull { it.optString("eventType") == "Sensor Start" }
        ok(sage != null, "Sensor Start present (LoopFollow's SAGE)")
        sage?.let { println("    Sensor Start created_at = ${it.optString("created_at")} (server-normalised; see followers/run.sh for LoopFollow's SAge parser)") }
        val ds = JSONArray(get("/api/v1/devicestatus.json?count=1&${tok()}"))
        ok(ds.length() == 1 && ds.getJSONObject(0).optJSONObject("uploader")?.opt("battery") is Number, "devicestatus uploader.battery numeric")
    }

    // nightguard: NightscoutService.swift:419, 515, 815-820, 910-1054, 1368-1410
    private fun nightguard() {
        println("nightguard (v3 with Bearer JWT, then v1)")
        val jwt = JSONObject(get("/api/v2/authorization/request/${URLEncoder.encode(readToken ?: error("NS_READ_TOKEN not set"), "UTF-8")}")).getString("token")
        val v3 = JSONObject(get("/api/v3/entries?type\$in=sgv%7Cmbg&date\$gte=$since&sort\$desc=date&limit=500&fields=identifier,_id,date,dateString,mills,type,sgv,mbg,direction,units",
            mapOf("Authorization" to "Bearer $jwt"))).getJSONArray("result")
        val objs = (0 until v3.length()).map { v3.getJSONObject(it) }
        ok(objs.size >= hours * 60 - 5, "v3 entries: ${objs.size}")
        ok(objs.all { it.opt("date") is Number && it.opt("sgv") is Number }, "v3: date + sgv numbers")
        ok(objs.all { it.optString("direction") in nsDirections - setOf("NONE", "NOT COMPUTABLE", "RATE OUT OF RANGE") || it.optString("direction") == "NONE" }, "v3: direction arrow or NONE")
        ok(objs.all { it.optString("identifier").isNotEmpty() }, "v3: identifier on every entry")
        val sc = JSONArray(get("/api/v1/treatments.json?find[eventType]=Sensor%20Change&find[created_at][\$gte]=${utcNoFrac(System.currentTimeMillis() - 30L * 86_400_000)}&count=1&${tok()}"))
        ok(sc.length() == 1, "Sensor Change present (nightguard's SAGE)")
        if (sc.length() == 1) {
            val s = sc.getJSONObject(0).getString("created_at")
            val parsed = listOf("yyyy-MM-dd'T'HH:mm:ssZ", "yyyy-MM-dd'T'HH:mm:ss.SSSZ").any { f ->
                runCatching { SimpleDateFormat(f, Locale.US).parse(s.replace("Z", "+0000")) }.isSuccess
            }
            ok(parsed, "nightguard parses created_at '$s'")
        }
    }

    // Trio "Nightscout as CGM": NightscoutAPI.swift:68-104, BloodGlucose.swift:78-121
    private fun trio() {
        println("Trio (api-secret header, entries/sgv.json with dateString filter)")
        val h = mapOf("api-secret" to NsDocs.sha1Hex(secret ?: error("NS_API_SECRET not set")))
        val from = NsDocs.isoUtc(since)
        val e = JSONArray(get("/api/v1/entries/sgv.json?count=1600&find[dateString][\$gte]=${URLEncoder.encode(from, "UTF-8")}", h))
        val objs = (0 until e.length()).map { e.getJSONObject(it) }
        ok(objs.size >= hours * 60 - 5, "dateString string-range filter returns ${objs.size} entries")
        ok(objs.all { o -> o.optString("dateString").let { s -> runCatching { Instant.parse(s) }.isSuccess && s.endsWith("Z") } }, "dateString ISO-8601 UTC 'Z' on every entry")
        ok(objs.all { it.optString("direction") in trioDirections }, "direction decodes into Trio's enum (anything else empties the batch)")
        ok(objs.all { !it.has("noise") || it.get("noise") is Int }, "noise is an Int")
        val c = JSONArray(get("/api/v1/treatments.json?find[carbs][\$exists]=true&find[created_at][\$gt]=${URLEncoder.encode(NsDocs.isoUtc(since - 86_400_000L), "UTF-8")}", h))
        ok((0 until c.length()).any { c.getJSONObject(it).optString("enteredBy") == "Sukrara" }, "carbs found by Trio's carbs query (enteredBy 'Sukrara' is not on its exclusion list)")
    }

    // Home Assistant `nightscout` integration (py-nightscout): status.json + entries/sgv.json [0]; also run for real by ha_check.py
    private fun homeAssistant() {
        println("Home Assistant (api-secret, status.json + entries/sgv.json)")
        val h = mapOf("api-secret" to NsDocs.sha1Hex(secret ?: error("NS_API_SECRET not set")))
        val st = JSONObject(get("/api/v1/status.json", h))
        ok(st.has("settings") && st.getJSONObject("settings").has("units"), "status.json settings.units = ${st.optJSONObject("settings")?.optString("units")}")
        val e = JSONArray(get("/api/v1/entries/sgv.json", h)).getJSONObject(0)
        ok(e.opt("delta") is Number, "newest entry has a numeric delta (py-nightscout crashes on null)")
        ok(System.currentTimeMillis() - e.getLong("date") < 3 * 60_000, "newest entry is fresh: ${e.getInt("sgv")} mg/dL ${e.getString("direction")} Δ${e.get("delta")}")
    }
}
