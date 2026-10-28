// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.integrations.tidepool

import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.SourceId
import app.mauri.sukrara.core.model.Treatment
import app.mauri.sukrara.core.model.TreatmentKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.TreeMap
import java.util.UUID

/**
 * Sukrara's data in Tidepool's platform data model.
 *
 * - One upload record (data set) per account, install and role: `continuous`, deduplicated by `origin.id`
 *   (`org.tidepool.deduplicator.dataset.delete.origin`: a datum with an origin id the data set already has replaces
 *   it), so sending a slot or an entry again is an update, never a duplicate.
 * - `cbg`: one reading per 5-minute UTC slot (the earliest in it), so Tidepool's time in range counts each sample once.
 *   Origin id = the slot. A LO / HI goes up as 39 / 401 mg/dL with no trend.
 * - Entries: carbs → `food` (net carbohydrate, grams); rapid / long insulin → `insulin` with
 *   `formulation.simple.actingType` (injected insulin: `bolus` and `basal` describe pump delivery); an entry's note goes
 *   in the datum's `notes`; a note on its own is a Tidepool note (message service), see [note].
 * - Every datum carries `time` (UTC), `deviceTime` (local wall clock), `timezoneOffset` (minutes, from the zone's
 *   rules at that instant, so the hour repeated when the clocks go back stays apart) and `timezone` (IANA).
 */
object TidepoolData {
    const val CLIENT_NAME = "app.mauri.sukrara"
    const val SLOT_S = 300L
    const val DEDUPLICATOR = "org.tidepool.deduplicator.dataset.delete.origin"
    const val MGDL = "mg/dL"
    private const val MAX_AMOUNT = 1000.0
    private val DEVICE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    fun slot(t: Instant): Long = Math.floorDiv(t.epochSecond, SLOT_S)

    fun cbgOrigin(slot: Long) = "sukrara-cbg-$slot"

    fun treatmentOrigin(id: String) = "sukrara-tr-$id".take(100)

    /** The sensor's model as other services show it; an untested family says so. Null: not this phone's sensor. */
    fun sensorModel(source: SourceId): String? =
        when (source) {
            SourceId.Libre3 -> "FreeStyle Libre 3"

            SourceId.Juggluco -> "FreeStyle Libre (Juggluco)"

            SourceId.DexcomG7 -> "Dexcom G7 / ONE+ (untested)"

            SourceId.DexcomG6 -> "Dexcom G6 / ONE (untested)"

            SourceId.SmartGuide -> "Accu-Chek SmartGuide (untested)"

            SourceId.AidexX -> "Aidex X / LinX (untested)"

            // a follower's readings are the followed person's: their sensor isn't known here
            else -> null
        }

    /** One reading per 5-minute slot, the earliest one in it (a later backfill of the same slot can replace it). */
    fun perSlot(readings: List<Reading>): Map<Long, Reading> {
        val out = TreeMap<Long, Reading>()
        for (r in readings) {
            if (r.mgdl !in 1..1000) continue
            val s = slot(r.t)
            val cur = out[s]
            if (cur == null || r.t < cur.t) out[s] = r
        }
        return out
    }

    private fun JsonObjectBuilder.times(
        t: Instant,
        zone: ZoneId,
    ) {
        put("time", t.toString())
        put("deviceTime", LocalDateTime.ofInstant(t, zone).format(DEVICE_TIME))
        put("timezoneOffset", zone.rules.getOffset(t).totalSeconds / 60)
        put("timezone", zone.id)
        put("conversionOffset", 0)
        put("clockDriftOffset", 0)
    }

    private fun JsonObjectBuilder.base(
        type: String,
        t: Instant,
        zone: ZoneId,
        deviceId: String,
        originId: String,
        version: String,
    ) {
        put("type", type)
        times(t, zone)
        put("deviceId", deviceId)
        putJsonObject("origin") {
            put("id", originId)
            put("name", CLIENT_NAME)
            put("version", version)
            put("type", "application")
        }
    }

    fun cbg(
        r: Reading,
        zone: ZoneId,
        deviceId: String,
        version: String,
    ): JsonObject =
        buildJsonObject {
            base("cbg", r.t, zone, deviceId, cbgOrigin(slot(r.t)), version)
            put("units", MGDL)
            put("value", r.mgdl)
            put("sampleInterval", SLOT_S * 1000)
            r.rateMgdlPerMin?.takeIf { it.isFinite() && it in -100.0..100.0 && !r.isLo && !r.isHi }?.let {
                put("trendRate", Math.round(it * 10) / 10.0)
                put("trendRateUnits", "mg/dL/minute")
            }
            // which sensor measured it (the data set's device is Sukrara, the uploader): free text in the payload
            sensorModel(r.source)?.let { putJsonObject("payload") { put("sensor", it) } }
        }

    /** True for the kinds that are data (food / insulin); a note on its own is a Tidepool note instead. */
    fun isDatum(t: Treatment) = t.kind != TreatmentKind.Note

    /** Whether [t] is a datum Tidepool accepts (an amount in range). */
    fun validDatum(t: Treatment) = isDatum(t) && t.amount > 0 && t.amount <= MAX_AMOUNT

    /** The datum for a carbs / insulin entry; null for a note or an entry Tidepool would refuse (no amount). */
    fun treatment(
        t: Treatment,
        zone: ZoneId,
        deviceId: String,
        version: String,
    ): JsonObject? {
        if (!validDatum(t)) return null
        return buildJsonObject {
            when (t.kind) {
                TreatmentKind.Carbs -> {
                    base("food", t.at, zone, deviceId, treatmentOrigin(t.id), version)
                    putJsonObject("nutrition") {
                        putJsonObject("carbohydrate") {
                            put("net", t.amount)
                            put("units", "grams")
                        }
                    }
                }

                TreatmentKind.RapidInsulin, TreatmentKind.LongInsulin -> {
                    base("insulin", t.at, zone, deviceId, treatmentOrigin(t.id), version)
                    putJsonObject("dose") {
                        put("total", t.amount)
                        put("units", "Units")
                    }
                    putJsonObject("formulation") {
                        putJsonObject("simple") { put("actingType", if (t.kind == TreatmentKind.LongInsulin) "long" else "rapid") }
                    }
                }

                TreatmentKind.Note -> {
                    return null
                }
            }
            if (t.note.isNotBlank()) putJsonArray("notes") { add(JsonPrimitive(t.note.take(1000))) }
        }
    }

    /** A Tidepool note (message service `POST /message/send/{userId}`) for a note entry; shown on the daily view. */
    fun note(
        t: Treatment,
        userId: String,
    ): JsonObject =
        buildJsonObject {
            putJsonObject("message") {
                // message-api needs a guid: stable per entry
                put("guid", UUID.nameUUIDFromBytes("sukrara-note-${t.id}".toByteArray()).toString())
                put("userid", userId)
                put("groupid", userId)
                put("timestamp", t.at.toString())
                put("messagetext", t.note.take(1000))
            }
        }

    fun noteEdit(t: Treatment): JsonObject =
        buildJsonObject {
            putJsonObject("message") {
                put("timestamp", t.at.toString())
                put("messagetext", t.note.take(1000))
            }
        }

    /** `DELETE /v1/data_sets/{id}/data` body: the data with these origin ids. */
    fun selectors(originIds: List<String>): JsonArray =
        buildJsonArray {
            for (id in originIds) add(buildJsonObject { putJsonObject("origin") { put("id", id) } })
        }

    /** The upload record. [deviceModel] tells a wearer's data set from a follower's. */
    fun dataSet(
        deviceId: String,
        deviceModel: String,
        version: String,
        zone: ZoneId,
        now: Instant,
    ): JsonObject =
        buildJsonObject {
            put("type", "upload")
            putJsonObject("client") {
                put("name", CLIENT_NAME)
                put("version", version)
            }
            put("dataSetType", "continuous")
            putJsonObject("deduplicator") { put("name", DEDUPLICATOR) }
            put("deviceId", deviceId)
            putJsonArray("deviceManufacturers") { add(JsonPrimitive("Sukrara")) }
            put("deviceModel", deviceModel)
            putJsonArray("deviceTags") { add(JsonPrimitive("cgm")) }
            put("time", now.toString())
            put("computerTime", LocalDateTime.ofInstant(now, zone).format(DEVICE_TIME))
            put("timeProcessing", "none")
            put("timezone", zone.id)
            put("timezoneOffset", zone.rules.getOffset(now).totalSeconds / 60)
            put("version", version)
        }
}
