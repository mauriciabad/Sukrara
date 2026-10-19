// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.tidepool

import app.mauri.sukrara.treatments.Treatment
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Sukrara's data in Tidepool's platform data model (docs/27). Pure Kotlin, so the JVM tests and the end-to-end harness
 * build exactly what the app sends.
 *
 * - One upload record (data set) per account and install: `continuous`, deduplicated by `origin.id`
 *   (`org.tidepool.deduplicator.dataset.delete.origin`: a datum with an origin id already in the data set replaces it),
 *   so sending a slot or a treatment again is an update, never a duplicate.
 * - `cbg`: one reading per 5-minute UTC slot (the earliest in the slot), like the CSV export's 5-minute rows, so
 *   Tidepool's time in range counts each sample once; `sampleInterval` says 5 minutes. Origin id = the slot.
 * - Treatments: carbs → `food` (net carbohydrate, grams); rapid / long insulin → `insulin` with `formulation.simple.actingType`
 *   (Tidepool's type for injected insulin: `bolus` and `basal` describe pump delivery); a note on an entry goes in the
 *   datum's `notes`; a note on its own is a Tidepool note (message service), see [note].
 * - Every datum carries `time` (UTC), `deviceTime` (local wall clock, no offset), `timezoneOffset` (minutes, from the
 *   zone's rules at that instant, so the hour repeated when the clocks go back stays apart) and `timezone` (IANA).
 */
object TidepoolData {
    const val CLIENT_NAME = "app.mauri.sukrara"
    const val SLOT_S = 300L
    const val DEDUPLICATOR = "org.tidepool.deduplicator.dataset.delete.origin"
    const val MGDL = "mg/dL"
    private val DEVICE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    /** A glucose reading (mg/dL, trend in mg/dL/min). */
    data class Glucose(val t: Long, val sgv: Int, val rate: Double? = null)

    fun slot(t: Long): Long = Math.floorDiv(t, SLOT_S)
    fun cbgOrigin(slot: Long) = "sukrara-cbg-$slot"
    fun treatmentOrigin(id: String) = "sukrara-tr-$id".take(100)

    /** One reading per 5-minute slot, the earliest one in it (a later backfill of the same slot can replace it). */
    fun perSlot(readings: List<Glucose>): Map<Long, Glucose> {
        val out = java.util.TreeMap<Long, Glucose>()
        for (r in readings) {
            if (r.sgv !in 1..1000) continue
            val s = slot(r.t)
            val cur = out[s]
            if (cur == null || r.t < cur.t) out[s] = r
        }
        return out
    }

    /** The time fields every datum carries. */
    fun times(t: Long, zone: ZoneId): Map<String, Any> {
        val instant = Instant.ofEpochSecond(t)
        val offsetMin = zone.rules.getOffset(instant).totalSeconds / 60
        return linkedMapOf(
            "time" to instant.toString(),
            "deviceTime" to LocalDateTime.ofInstant(instant, zone).format(DEVICE_TIME),
            "timezoneOffset" to offsetMin,
            "timezone" to zone.id,
            "conversionOffset" to 0,
            "clockDriftOffset" to 0,
        )
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.base(type: String, t: Long, zone: ZoneId, deviceId: String, originId: String, version: String) {
        put("type", type)
        for ((k, v) in times(t, zone)) when (v) { is String -> put(k, v); is Int -> put(k, v) }
        put("deviceId", deviceId)
        putJsonObject("origin") { put("id", originId); put("name", CLIENT_NAME); put("version", version); put("type", "application") }
    }

    fun cbg(r: Glucose, zone: ZoneId, deviceId: String, version: String): JsonObject = buildJsonObject {
        base("cbg", r.t, zone, deviceId, cbgOrigin(slot(r.t)), version)
        put("units", MGDL)
        put("value", r.sgv)
        put("sampleInterval", SLOT_S * 1000)
        r.rate?.takeIf { it.isFinite() && it in -100.0..100.0 }?.let {
            put("trendRate", Math.round(it * 10) / 10.0); put("trendRateUnits", "mg/dL/minute")
        }
    }

    /** True for the kinds that are data (food / insulin); a note on its own is a Tidepool note instead. */
    fun isDatum(tr: Treatment) = tr.k != "note"

    /** The datum for a carbs / insulin entry; null for a note or an entry Tidepool would reject (no amount). */
    fun treatment(tr: Treatment, zone: ZoneId, deviceId: String, version: String): JsonObject? {
        if (!isDatum(tr) || !(tr.a > 0) || tr.a > 1000) return null
        return buildJsonObject {
            when (tr.k) {
                "carbs" -> {
                    base("food", tr.t, zone, deviceId, treatmentOrigin(tr.id), version)
                    putJsonObject("nutrition") { putJsonObject("carbohydrate") { put("net", tr.a); put("units", "grams") } }
                }
                "rapid", "long" -> {
                    base("insulin", tr.t, zone, deviceId, treatmentOrigin(tr.id), version)
                    putJsonObject("dose") { put("total", tr.a); put("units", "Units") }
                    putJsonObject("formulation") { putJsonObject("simple") { put("actingType", tr.k) } }
                }
                else -> return null
            }
            if (tr.n.isNotBlank()) putJsonArray("notes") { add(kotlinx.serialization.json.JsonPrimitive(tr.n.take(1000))) }
        }
    }

    /** A Tidepool note (message service `POST /message/send/{userId}`) for a note entry; shown on the daily view. */
    fun note(tr: Treatment, userId: String): JsonObject = buildJsonObject {
        putJsonObject("message") {
            put("userid", userId); put("groupid", userId)
            put("timestamp", Instant.ofEpochSecond(tr.t).toString())
            put("messagetext", tr.n.ifBlank { "·" })
        }
    }

    fun noteEdit(tr: Treatment): JsonObject = buildJsonObject {
        putJsonObject("message") { put("timestamp", Instant.ofEpochSecond(tr.t).toString()); put("messagetext", tr.n.ifBlank { "·" }) }
    }

    /** `DELETE /v1/data_sets/{id}/data` body: the data with these origin ids. */
    fun selectors(originIds: List<String>): JsonArray = buildJsonArray {
        for (id in originIds) add(buildJsonObject { putJsonObject("origin") { put("id", id) } })
    }

    /** The upload record. [deviceModel] tells a wearer's data set from a follower's. */
    fun dataSet(deviceId: String, deviceModel: String, version: String, zone: ZoneId, now: Long): JsonObject = buildJsonObject {
        put("type", "upload")
        putJsonObject("client") { put("name", CLIENT_NAME); put("version", version) }
        put("dataSetType", "continuous")
        putJsonObject("deduplicator") { put("name", DEDUPLICATOR) }
        put("deviceId", deviceId)
        putJsonArray("deviceManufacturers") { add(kotlinx.serialization.json.JsonPrimitive("Sukrara")) }
        put("deviceModel", deviceModel)
        putJsonArray("deviceTags") { add(kotlinx.serialization.json.JsonPrimitive("cgm")) }
        val instant = Instant.ofEpochSecond(now)
        put("time", instant.toString())
        put("computerTime", LocalDateTime.ofInstant(instant, zone).format(DEVICE_TIME))
        put("timeProcessing", "none")
        put("timezone", zone.id)
        put("timezoneOffset", zone.rules.getOffset(instant).totalSeconds / 60)
        put("version", version)
    }
}
