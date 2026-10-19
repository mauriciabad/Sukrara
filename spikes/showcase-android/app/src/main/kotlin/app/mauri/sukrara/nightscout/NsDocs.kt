// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.nightscout

import app.mauri.sukrara.core.Reading
import app.mauri.sukrara.core.SensorInfo
import app.mauri.sukrara.treatments.Treatment
import app.mauri.sukrara.treatments.TreatmentMarks
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.math.roundToLong
import org.json.JSONObject

/**
 * Sukrara → Nightscout documents (docs/25 §3), pinned to what the readers need: `date` integer ms (LoopFollow), stored
 * `dateString` UTC (Trio filters it as a string), `sgv`/`noise` integers, `direction` only from Nightscout's enum
 * (`NONE` when unknown: Trio drops a whole batch on an unknown string), a 5-minute `delta` (Home Assistant).
 * `identifier` = Nightscout's own v5 UUID of `device_date` for entries; the treatment's UUID for treatments. The phone's
 * read API (`bridge/NightscoutApi`) serves its treatments from [treatment] too.
 */
object NsDocs {
    const val APP = "Sukrara"
    private val NS_NAMESPACE = "NightscoutRocks!".toByteArray(Charsets.US_ASCII)   // cgm-remote-monitor operationTools.js:6

    /** The phone's trend thresholds (`bridge/XdripServer.direction`) on a per-minute rate. */
    fun direction(ratePerMin: Double?): String {
        val m = ratePerMin ?: return "NONE"
        return when {
            m > 3 -> "DoubleUp"
            m > 2 -> "SingleUp"
            m > 1 -> "FortyFiveUp"
            m >= -1 -> "Flat"
            m >= -2 -> "FortyFiveDown"
            m >= -3 -> "SingleDown"
            else -> "DoubleDown"
        }
    }

    fun isoUtc(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    fun isoLocal(ms: Long, zone: TimeZone): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
        .apply { timeZone = zone }.format(Date(ms))

    fun utcOffsetMin(ms: Long, zone: TimeZone): Int = zone.getOffset(ms) / 60_000

    /** RFC 4122 v5 UUID, as node's `uuid.v5(name, namespace)`. */
    fun uuidV5(name: String, namespace: ByteArray = NS_NAMESPACE): String {
        val h = MessageDigest.getInstance("SHA-1").digest(namespace + name.toByteArray(Charsets.UTF_8))
        h[6] = ((h[6].toInt() and 0x0f) or 0x50).toByte()
        h[8] = ((h[8].toInt() and 0x3f) or 0x80).toByte()
        var msb = 0L; var lsb = 0L
        for (i in 0 until 8) msb = (msb shl 8) or (h[i].toLong() and 0xff)
        for (i in 8 until 16) lsb = (lsb shl 8) or (h[i].toLong() and 0xff)
        return UUID(msb, lsb).toString()
    }

    /** What the server computes for a document without an identifier (`calculateIdentifier`). */
    fun serverIdentifier(device: String, date: Long, eventType: String? = null): String =
        uuidV5("${device}_$date" + (eventType?.let { "_$it" } ?: ""))

    fun sha1Hex(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /**
     * [prev]: the reading before [r], for the rate when the sensor gives none. v1 derives `utcOffset` from
     * `dateString`'s offset and stores it as UTC, v3 takes `utcOffset` as given: so local time on v1, UTC on v3, and
     * the stored document is the same whichever API wrote it.
     */
    fun entry(r: Reading, prev: Reading?, device: String, zone: TimeZone, api: NsApi): JSONObject {
        val ms = r.t * 1000
        val rate = r.rate ?: prev?.let { p ->
            val dtMin = (r.t - p.t) / 60.0
            if (dtMin in 0.5..15.0) (r.sgv - p.sgv) / dtMin else null
        }
        return JSONObject()
            .put("type", "sgv")
            .put("sgv", r.sgv)
            .put("date", ms)
            .put("dateString", if (api == NsApi.V1) isoLocal(ms, zone) else isoUtc(ms))
            .put("utcOffset", utcOffsetMin(ms, zone))
            .put("direction", direction(rate))
            .apply { if (rate != null) put("delta", (rate * 5 * 1000).roundToLong() / 1000.0) }
            .put("noise", 1)
            .put("device", device)
            .put("app", APP)
            .put("identifier", serverIdentifier(device, ms))
    }

    /**
     * rapid → `Correction Bolus` + `insulin`; carbs → `Carb Correction` + `carbs`; note → `Note`. Long-acting → `Note`
     * with the dose only in `notes`: Nightscout's IOB and LoopFollow count every `insulin` field as a bolus.
     */
    fun treatment(t: Treatment, device: String, zone: TimeZone, enteredBy: String = APP): JSONObject {
        val ms = t.t * 1000
        val o = JSONObject()
            .put("identifier", t.id)
            .put("created_at", isoUtc(ms))
            .put("date", ms)
            .put("utcOffset", utcOffsetMin(ms, zone))
            .put("enteredBy", enteredBy)
            .put("app", APP)
            .put("device", device)
        when (t.k) {
            "rapid" -> o.put("eventType", "Correction Bolus").put("insulin", t.a).put("insulinType", "rapid-acting")
            "long" -> o.put("eventType", "Note").put("insulinType", "long-acting")
            "carbs" -> o.put("eventType", "Carb Correction").put("carbs", t.a)
            else -> o.put("eventType", "Note")
        }
        val notes = listOfNotNull(if (t.k == "long") "Long-acting insulin ${TreatmentMarks.amount(t)}" else null, t.n.ifBlank { null })
        if (notes.isNotEmpty()) o.put("notes", notes.joinToString(" · "))
        return o
    }

    /**
     * Sensor age: LoopFollow counts only `Sensor Start`, nightguard only `Sensor Change`, Nightscout's SAGE the newest of
     * both. A Libre 3 is inserted and started in one tap: both go up with the same time and their own identifiers.
     */
    fun sensorEvents(s: SensorInfo, device: String, zone: TimeZone): List<JSONObject> =
        listOf("Sensor Change", "Sensor Start").map { type ->
            val ms = s.startEpoch * 1000
            JSONObject()
                .put("identifier", uuidV5("sukrara-sensor_${s.serial}_$type"))
                .put("eventType", type)
                .put("created_at", isoUtc(ms))
                .put("date", ms)
                .put("utcOffset", utcOffsetMin(ms, zone))
                .put("enteredBy", APP).put("app", APP).put("device", device)
                .put("notes", "Libre 3 ${s.serial}")
        }

    fun deviceStatus(percent: Int, charging: Boolean, ms: Long, device: String, zone: TimeZone): JSONObject = JSONObject()
        .put("device", device)
        .put("created_at", isoUtc(ms))
        .put("date", ms)
        .put("utcOffset", utcOffsetMin(ms, zone))
        .put("app", APP)
        .put("identifier", serverIdentifier(device, ms))
        .put("uploader", JSONObject().put("battery", percent).put("isCharging", charging))
}
