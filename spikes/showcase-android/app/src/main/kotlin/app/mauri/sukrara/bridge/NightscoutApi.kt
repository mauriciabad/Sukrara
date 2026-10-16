// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.bridge

import app.mauri.sukrara.treatments.Treatment
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Investigation 03's "tier 1" Nightscout read API, ported from `spikes/nostr/follower.py` (the adapter Home Assistant's
 * stock `nightscout` integration was validated against, 17/17 request shapes for HA, xDrip+, Nightguard, LoopFollow,
 * M5 NightscoutMon, nightscout-clock and Garmin). Served by [XdripServer] on its second listener (default
 * `127.0.0.1:17581`, optionally the LAN with a token), from whatever the role has: the wearer's readings or the
 * follower DB. Pure functions over a snapshot, no Android types, so it is cheap to reason about.
 *
 * Gotchas baked in (docs/03): newest first, `date` integer ms, `delta` never null, numeric `trend` matching
 * `direction`, 24-hex `_id`, root mount, `/api/v3` → 404 so Nightguard falls back to v1.
 */
object NightscoutApi {
    /** One route result: HTTP status line + JSON body, or null when the path isn't ours (caller falls back). */
    data class Answer(val status: String, val body: String)

    /**
     * [readings]: newest first. [treatments]: the wearer's shared log (empty for a follower, which doesn't receive
     * treatments yet). [name]: shown as the site title.
     */
    fun route(path: String, q: Map<String, String>, readings: List<Bridge.Reading>, treatments: List<Treatment>,
              name: String): Answer? = when (path) {
        "api/v1/entries", "api/v1/entries.json", "api/v1/entries/sgv", "api/v1/entries/sgv.json" -> ok(entries(q, readings))
        "api/v1/entries/current", "api/v1/entries/current.json" -> ok(arr(readings.take(1).map { entry(it, readings) }))
        "api/v1/status", "api/v1/status.json" -> ok(status(name))
        "api/v1/treatments", "api/v1/treatments.json" -> ok(treatmentsJson(q, treatments))
        "api/v1/devicestatus", "api/v1/devicestatus.json", "api/v1/profile", "api/v1/profile.json",
        "api/v1/profiles", "api/v1/profiles.json", "api/v1/food.json" -> ok("[]")
        "api/v1/verifyauth" -> ok(VERIFY)
        "api/v2/properties" -> ok(properties(readings))
        else -> when {
            path.startsWith("api/v2/properties/") -> ok(properties(readings))
            path.startsWith("api/v3/") -> Answer("404 Not Found", "{\"status\":404}")
            else -> null
        }
    }

    private fun ok(body: String) = Answer("200 OK", body)
    private fun arr(items: List<JSONObject>) = JSONArray(items).toString()

    // MARK: entries

    private fun entries(q: Map<String, String>, all: List<Bridge.Reading>): String {
        val type = q["find[type][\$eq]"] ?: q["find[type]"] ?: "sgv"
        if (type != "sgv" || q["find[type][\$ne]"] == "sgv") return "[]"       // we only have sgv entries
        val count = (q["count"]?.toDoubleOrNull()?.toInt() ?: 10).coerceIn(1, 10_000)
        val gt = find(q, "\$gt"); val gte = find(q, "\$gte"); val lt = find(q, "\$lt"); val lte = find(q, "\$lte")
        val rows = all.asSequence().filter { r ->
            val ms = r.epochSec * 1000
            (gt == null || ms > gt) && (gte == null || ms >= gte) && (lt == null || ms < lt) && (lte == null || ms <= lte)
        }.take(count).toList()
        return arr(rows.map { entry(it, all) })
    }

    /** `find[date][$gt]=ms` or `find[dateString][$gte]=ISO` → ms. */
    private fun find(q: Map<String, String>, op: String): Long? {
        for (field in listOf("date", "dateString")) {
            val v = q["find[$field][$op]"] ?: continue
            v.toDoubleOrNull()?.let { return it.toLong() }
            runCatching { return Instant.parse(v).toEpochMilli() }
            runCatching { return java.time.OffsetDateTime.parse(v).toInstant().toEpochMilli() }
        }
        return null
    }

    fun entry(r: Bridge.Reading, all: List<Bridge.Reading>): JSONObject {
        val d = delta(r, all)
        val dir = XdripServer.direction(d)
        val iso = Instant.ofEpochSecond(r.epochSec).toString()
        return JSONObject()
            .put("_id", String.format(Locale.US, "%024x", r.epochSec))
            .put("device", "Sukrara").put("type", "sgv")
            .put("date", r.epochSec * 1000).put("dateString", iso).put("sysTime", iso)
            .put("sgv", r.mgdl)
            .put("delta", Math.round((d ?: 0.0) * 1000) / 1000.0)
            .put("direction", dir).put("trend", XdripServer.trend(d))
            .put("noise", 1)
            .put("utcOffset", 0)
            .put("mills", r.epochSec * 1000)
    }

    /** mg/dL per 5 min: the sensor's rate when present, else the slope to a reading 2–15 min earlier. */
    private fun delta(r: Bridge.Reading, all: List<Bridge.Reading>): Double? {
        r.rate?.let { return it * 5 }
        val prev = all.firstOrNull { it.epochSec <= r.epochSec - 120 } ?: return null   // newest-first list
        val dt = r.epochSec - prev.epochSec
        if (dt > 15 * 60) return null
        return (r.mgdl - prev.mgdl) * 300.0 / dt
    }

    private fun properties(all: List<Bridge.Reading>): String {
        val r = all.firstOrNull() ?: return "{}"
        val d = Math.round(delta(r, all) ?: 0.0).toDouble()
        val prev = all.firstOrNull { it.epochSec <= r.epochSec - 240 }?.mgdl ?: r.mgdl
        return JSONObject().put("delta", JSONObject()
            .put("absolute", d).put("elapsedMins", 5).put("interpolated", false).put("mean5MinsAgo", prev)
            .put("mgdl", d).put("scaled", d).put("display", String.format(Locale.US, "%+.0f", d))).toString()
    }

    // MARK: status

    private fun status(name: String): String {
        val now = System.currentTimeMillis()
        return JSONObject()
            .put("status", "ok").put("name", "Sukrara").put("version", "15.0.8")
            .put("serverTime", Instant.ofEpochMilli(now).toString()).put("serverTimeEpoch", now)
            .put("apiEnabled", true).put("careportalEnabled", false).put("boluscalcEnabled", false)
            .put("head", "sukrara-poc")
            .put("settings", JSONObject()
                .put("units", "mg/dl").put("timeFormat", 24).put("customTitle", name).put("theme", "default")
                .put("alarmTypes", JSONArray().put("predict")).put("language", "en").put("showPlugins", "")
                .put("enable", JSONArray().put("careportal"))
                .put("thresholds", JSONObject().put("bgHigh", 185).put("bgTargetTop", 145)
                    .put("bgTargetBottom", 65).put("bgLow", 55))
                .put("DEFAULT_FEATURES", JSONArray()).put("authDefaultRoles", "readable"))
            .put("extendedSettings", JSONObject().put("devicestatus", JSONObject().put("advanced", true)))
            .put("authorized", JSONObject.NULL)
            .toString()
    }

    // MARK: treatments (investigation 04's log → Nightscout careportal entries)

    private fun treatmentsJson(q: Map<String, String>, all: List<Treatment>): String {
        val count = (q["count"]?.toDoubleOrNull()?.toInt() ?: 100).coerceIn(1, 10_000)
        val from = listOf("created_at", "date").firstNotNullOfOrNull { f ->
            (q["find[$f][\$gte]"] ?: q["find[$f][\$gt]"])?.let { v ->
                v.toDoubleOrNull()?.toLong() ?: runCatching { Instant.parse(v).toEpochMilli() }.getOrNull()
            }
        }
        val rows = all.filter { !it.del && (from == null || it.t * 1000 >= from) }.sortedByDescending { it.t }.take(count)
        return arr(rows.map(::treatment))
    }

    fun treatment(t: Treatment): JSONObject {
        val iso = Instant.ofEpochSecond(t.t).toString()
        val o = JSONObject()
            .put("_id", sha1(t.id).take(24))
            .put("identifier", t.id)
            .put("created_at", iso).put("date", t.t * 1000).put("mills", t.t * 1000)
            .put("enteredBy", "Sukrara (${t.o})").put("utcOffset", 0)
        when (t.k) {
            "rapid" -> o.put("eventType", "Correction Bolus").put("insulin", t.a)
            "long" -> o.put("eventType", "Note").put("insulin", t.a).put("insulinType", "long-acting")
            "carbs" -> o.put("eventType", "Carb Correction").put("carbs", t.a)
            else -> o.put("eventType", "Note")
        }
        val notes = listOfNotNull(if (t.k == "long") "Long-acting insulin ${t.a} U" else null, t.n.ifBlank { null })
        if (notes.isNotEmpty()) o.put("notes", notes.joinToString(" · "))
        return o
    }

    // MARK: auth

    /**
     * Nightscout clients authenticate with `api-secret: sha1(secret)` (xDrip+, HA, nightscout-clock), `?token=`
     * (Nightguard, LoopFollow, M5, Garmin), `?secret=sha1` or `Authorization: Bearer`. Loopback callers never need it.
     */
    fun authorized(secret: String, headers: Map<String, String>, q: Map<String, String>): Boolean {
        val sha = sha1(secret)
        val h = (headers["api-secret"] ?: headers["api_secret"] ?: "").removePrefix("token=").lowercase()
        return h == sha || h == secret.lowercase() || q["token"] == secret || q["secret"]?.lowercase() == sha ||
            headers["authorization"] == "Bearer $secret"
    }

    fun sha1(s: String): String = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        .joinToString("") { String.format(Locale.US, "%02x", it) }

    private const val VERIFY = "{\"status\":200,\"message\":{\"canRead\":true,\"canWrite\":false,\"isAdmin\":false," +
        "\"message\":\"OK\",\"rolefound\":\"FOUND\"}}"
}
