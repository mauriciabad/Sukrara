// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.integrations.localserver

import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.SourceId
import app.mauri.sukrara.core.model.Treatment
import app.mauri.sukrara.integrations.nightscout.NsDocs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale

/**
 * The Nightscout read API (v1 subset) other apps read glucose from: xDrip+'s Nightscout follower, GlucoDataHandler,
 * watch faces, clocks and Home Assistant. Pure: a request and a [Snapshot] in, an answer out.
 *
 * Every data route needs the API secret ([authorized]); only `status` answers without it, and then with nothing about
 * the person (no glucose, no name, no thresholds). Entries are newest first with integer-ms `date`, a `delta` that is
 * never null, numeric `trend` matching `direction`, 24-hex `_id`, `/api/v3` → 404 so clients fall back to v1. Each
 * entry's `device` says where it came from ([device]) so a downstream app can tell a followed person's or an untested
 * sensor's data from a Libre 3 read by this phone; a reading from any other source is never served.
 */
object NightscoutApi {
    /** What the API serves: [readings] newest first, [treatments] not deleted. */
    data class Snapshot(
        val readings: List<Reading>,
        val treatments: List<Treatment>,
        val now: Instant,
        val zone: ZoneId,
    )

    const val PORT = 17581

    /** At most this many entries / treatments per request (7 days of 1-minute readings). */
    const val MAX_COUNT = 10_080

    private const val VERSION = "15.0.8"

    /** The sources served for [role]: the wearer's own sensor, or the followed person's feed. */
    fun sources(role: Role): Set<SourceId> =
        when (role) {
            Role.Wearer -> SourceId.Wearer
            Role.Follower -> setOf(SourceId.Relay, SourceId.Nearby)
        }

    fun route(
        req: HttpRequest,
        secret: String,
        snapshot: () -> Snapshot,
    ): HttpAnswer {
        val path = req.path.trimEnd('/').removeSuffix(".json")
        if (path == "api/v1/status") return ok(status(snapshot().now, authorized(secret, req)))
        if (path.startsWith("api/v3/") || path == "api/v3") return HttpAnswer("404 Not Found", "{\"status\":404}")
        if (!known(path)) return HttpAnswer.NOT_FOUND
        if (!authorized(secret, req)) return UNAUTHORIZED
        val s = snapshot()
        val q = req.query
        val served = s.readings.filter { device(it.source) != null }
        return when (path) {
            "api/v1/entries", "api/v1/entries/sgv" -> ok(entries(q, served))
            "api/v1/entries/current" -> ok(JsonArray(served.take(1).map { entry(it, served) }).toString())
            "api/v1/treatments" -> ok(treatments(q, s))
            "api/v1/verifyauth" -> ok(VERIFIED)
            else -> if (path.startsWith("api/v2/properties")) ok(properties(served)) else ok("[]")
        }
    }

    private fun known(path: String) =
        path in
            setOf(
                "api/v1/entries",
                "api/v1/entries/sgv",
                "api/v1/entries/current",
                "api/v1/treatments",
                "api/v1/verifyauth",
                "api/v1/devicestatus",
                "api/v1/profile",
                "api/v1/profiles",
                "api/v1/food",
                "api/v2/properties",
            ) || path.startsWith("api/v2/properties/")

    private fun ok(body: String) = HttpAnswer("200 OK", body)

    // MARK: entries

    private fun entries(
        q: Map<String, String>,
        all: List<Reading>,
    ): String {
        val type = q["find[type][\$eq]"] ?: q["find[type]"] ?: "sgv"
        if (type != "sgv" || q["find[type][\$ne]"] == "sgv") return "[]"
        val count = count(q, 10)
        val gt = find(q, "\$gt")
        val gte = find(q, "\$gte")
        val lt = find(q, "\$lt")
        val lte = find(q, "\$lte")
        val rows =
            all
                .asSequence()
                .filter { r ->
                    val ms = r.t.toEpochMilli()
                    (gt == null || ms > gt) && (gte == null || ms >= gte) && (lt == null || ms < lt) && (lte == null || ms <= lte)
                }.take(count)
                .toList()
        return JsonArray(rows.map { entry(it, all) }).toString()
    }

    private fun count(
        q: Map<String, String>,
        default: Int,
    ) = (q["count"]?.toDoubleOrNull()?.toInt() ?: default).coerceIn(1, MAX_COUNT)

    // find[date][op]=ms or find[dateString][op]=ISO (op = $gt, $gte, $lt, $lte) → epoch ms
    private fun find(
        q: Map<String, String>,
        op: String,
    ): Long? {
        for (field in listOf("date", "dateString", "created_at")) {
            val v = q["find[$field][$op]"] ?: continue
            return instantMs(v) ?: continue
        }
        return null
    }

    private fun instantMs(v: String): Long? =
        v.toDoubleOrNull()?.toLong()
            ?: runCatching { Instant.parse(v).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(v).toInstant().toEpochMilli() }.getOrNull()

    /**
     * Where an entry came from, or null for a source this API never serves: the Libre 3 this phone reads, Juggluco,
     * the other sensor families marked untested, and a followed person's readings marked as such.
     */
    fun device(source: SourceId): String? =
        when (source) {
            SourceId.Libre3 -> "Sukrara · FreeStyle Libre 3"
            SourceId.Juggluco -> "Sukrara · Juggluco"
            SourceId.DexcomG7 -> "Sukrara · Dexcom G7 (untested)"
            SourceId.DexcomG6 -> "Sukrara · Dexcom G6 (untested)"
            SourceId.SmartGuide -> "Sukrara · Accu-Chek SmartGuide (untested)"
            SourceId.AidexX -> "Sukrara · Aidex X (untested)"
            SourceId.Relay, SourceId.Nearby -> "Sukrara (following)"
            else -> null
        }

    fun entry(
        r: Reading,
        all: List<Reading>,
    ): JsonObject {
        val d = delta(r, all)
        val dir = if (d == null) "NONE" else XdripEndpoint.direction(d)
        val ms = r.t.toEpochMilli()
        val iso = r.t.toString()
        return buildJsonObject {
            put("_id", String.format(Locale.US, "%024x", r.t.epochSecond))
            put("device", device(r.source))
            put("type", "sgv")
            put("date", ms)
            put("dateString", iso)
            put("sysTime", iso)
            put("sgv", r.mgdl)
            put("delta", Math.round((d ?: 0.0) * 1000) / 1000.0)
            put("direction", dir)
            put("trend", TRENDS.indexOf(dir).coerceAtLeast(0))
            put("noise", 1)
            put("utcOffset", 0)
            put("mills", ms)
        }
    }

    /**
     * mg/dL per 5 min: the sensor's rate, else the slope to a reading 2–15 min earlier; null for a LO / HI reading or
     * next to one (no number to take a slope from).
     */
    private fun delta(
        r: Reading,
        all: List<Reading>,
    ): Double? {
        if (r.isLo || r.isHi) return null
        r.rateMgdlPerMin?.let { return it * 5 }
        val prev = previous(r, all) ?: return null
        val dt = r.t.epochSecond - prev.t.epochSecond
        if (dt > 15 * 60 || prev.isLo || prev.isHi) return null
        return (r.mgdl - prev.mgdl) * 300.0 / dt
    }

    /**
     * The newest reading at least 2 min before [r] in the newest-first [all], searched from [r]'s own place (binary
     * search on time), so a 7-day answer stays linear.
     */
    private fun previous(
        r: Reading,
        all: List<Reading>,
    ): Reading? {
        val limit = r.t.epochSecond - 120
        var lo = 0
        var hi = all.size
        // first index whose time is <= limit (times descend along the list)
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (all[mid].t.epochSecond <= limit) hi = mid else lo = mid + 1
        }
        return all.getOrNull(lo)
    }

    /** `bgnow` and `delta` only: no insulin / carbs on board, pump or loop data (this phone has none of it). */
    private fun properties(all: List<Reading>): String {
        val r = all.firstOrNull() ?: return "{}"
        val d = delta(r, all)
        return buildJsonObject {
            putJsonObject("bgnow") {
                put("mean", r.mgdl)
                put("last", r.mgdl)
                put("mills", r.t.toEpochMilli())
            }
            if (d != null) {
                putJsonObject("delta") {
                    val rounded = Math.round(d).toDouble()
                    put("absolute", rounded)
                    put("elapsedMins", 5)
                    put("interpolated", false)
                    put("mgdl", rounded)
                    put("scaled", rounded)
                    put("display", String.format(Locale.US, "%+.0f", rounded))
                }
            }
            putJsonObject("direction") {
                val dir = if (d == null) "NONE" else XdripEndpoint.direction(d)
                put("value", dir)
                put("mills", r.t.toEpochMilli())
            }
        }.toString()
    }

    // MARK: status

    /** Server facts only: no person's name, no thresholds (Sukrara's ranges aren't Nightscout's), no data. */
    private fun status(
        now: Instant,
        authorized: Boolean,
    ): String =
        buildJsonObject {
            put("status", "ok")
            put("name", "Sukrara")
            put("version", VERSION)
            put("serverTime", now.toString())
            put("serverTimeEpoch", now.toEpochMilli())
            put("apiEnabled", true)
            put("careportalEnabled", false)
            put("boluscalcEnabled", false)
            putJsonObject("settings") {
                put("units", "mg/dl")
                put("authDefaultRoles", "denied")
            }
            put("authorized", if (authorized) VERIFIED_ROLES else JsonNull)
        }.toString()

    private val VERIFIED_ROLES: JsonElement =
        buildJsonObject {
            put("canRead", true)
            put("canWrite", false)
            put("isAdmin", false)
        }

    // MARK: treatments

    private fun treatments(
        q: Map<String, String>,
        s: Snapshot,
    ): String {
        val count = count(q, 100)
        val from =
            listOf("created_at", "date").firstNotNullOfOrNull { f ->
                (q["find[$f][\$gte]"] ?: q["find[$f][\$gt]"])?.let(::instantMs)
            }
        val rows =
            s.treatments
                .filter { !it.deleted && (from == null || it.at.toEpochMilli() >= from) }
                .sortedByDescending { it.at }
                .take(count)
        return JsonArray(rows.map { treatment(it, s.zone) }).toString()
    }

    /** The upload's mapping (long-acting insulin is a `Note` with the dose in `notes`) plus the v1 read fields. */
    fun treatment(
        t: Treatment,
        zone: ZoneId,
    ): JsonObject {
        val doc = NsDocs.treatment(t, "Sukrara", zone)
        return JsonObject(doc + mapOf("_id" to JsonPrimitive(sha1(t.id).take(24)), "mills" to JsonPrimitive(t.at.toEpochMilli())))
    }

    // MARK: auth

    /**
     * The request carries the API secret: `api-secret` = SHA-1 of it (xDrip+, Home Assistant, nightscout-clock) or the
     * secret itself (with or without `token=`), `?token=` (Nightguard, LoopFollow, watch faces) or
     * `Authorization: Bearer`. Compared in constant time; an empty secret never matches.
     */
    fun authorized(
        secret: String,
        req: HttpRequest,
    ): Boolean {
        if (secret.isEmpty()) return false
        val header = (req.headers["api-secret"] ?: req.headers["api_secret"])?.trim()?.removePrefix("token=")
        val bearer =
            req.headers["authorization"]
                ?.trim()
                ?.takeIf { it.startsWith("Bearer ") }
                ?.removePrefix("Bearer ")
                ?.trim()
        val candidates = listOfNotNull(header, req.query["token"], bearer)
        val sha = sha1(secret)
        return candidates.any { c -> same(c.lowercase(), sha) || same(c, secret) }
    }

    private fun same(
        a: String,
        b: String,
    ) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    fun sha1(s: String): String =
        MessageDigest
            .getInstance("SHA-1")
            .digest(s.toByteArray())
            .joinToString("") { String.format(Locale.US, "%02x", it) }

    /** Nightscout's numeric trends, by index (0 = none). */
    private val TRENDS = listOf("NONE", "DoubleUp", "SingleUp", "FortyFiveUp", "Flat", "FortyFiveDown", "SingleDown", "DoubleDown")

    private val UNAUTHORIZED = HttpAnswer("401 Unauthorized", "{\"status\":401,\"message\":\"Unauthorized\"}")

    private const val VERIFIED =
        "{\"status\":200,\"message\":{\"canRead\":true,\"canWrite\":false,\"isAdmin\":false,\"message\":\"OK\",\"rolefound\":\"FOUND\"}}"
}
