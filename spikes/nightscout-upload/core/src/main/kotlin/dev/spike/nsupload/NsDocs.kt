// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.nsupload

import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.math.roundToLong

/**
 * Sukrara → Nightscout documents. Every field choice is pinned to what a reader needs (docs/25 §2):
 * - `date` integer ms (LoopFollow decodes it as a required number), `dateString` UTC with `.SSSZ` (Trio requires it
 *   and range-filters on it as a *string*), `sgv` an integer, `direction` only from Nightscout's enum (Trio's decoder
 *   rejects the whole batch on an unknown string such as `"None"`), `noise` an integer, `delta` a 5-minute delta.
 * - `identifier` = Nightscout's own v5 UUID of `device_date` for entries, so v3 never logs an identifier mismatch, and
 *   the treatment's UUID for treatments, so edits replace the same document on v1 and v3.
 * - Long-acting insulin is a `Note` with the amount in `notes`, never an `insulin` field: Nightscout's IOB plugin adds
 *   every treatment with `insulin` as a rapid bolus (`lib/plugins/iob.js:187`).
 */
object NsDocs {
    const val APP = "Sukrara"
    private val NS_NAMESPACE = "NightscoutRocks!".toByteArray(Charsets.US_ASCII)   // lib/api3/shared/operationTools.js:6

    /** Nightscout's direction enum (cgm-remote-monitor `lib/plugins/direction.js`), as Sukrara's XdripServer maps it. */
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

    /** Minutes east of UTC at [ms] in [zone], the way Nightscout stores `utcOffset`. */
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

    /** What the server would compute for a document without an identifier (`calculateIdentifier`). */
    fun serverIdentifier(device: String, date: Long, eventType: String? = null): String =
        uuidV5("${device}_$date" + (eventType?.let { "_$it" } ?: ""))

    fun sha1Hex(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /**
     * @param prev the reading before [r] (any age), for the delta when the sensor gives no rate
     */
    fun entry(r: Reading, prev: Reading?, device: String, zone: TimeZone, api: NsApi = NsApi.V3): JSONObject {
        val rate = r.rate ?: prev?.let { p ->
            val dtMin = (r.epochMs - p.epochMs) / 60_000.0
            if (dtMin in 0.5..15.0) (r.mgdl - p.mgdl) / dtMin else null
        }
        // v1 ignores `utcOffset` and derives it from `dateString`'s offset, then rewrites `dateString` to UTC
        // (lib/server/entries.js:122-128): send local time with its offset there, UTC + `utcOffset` on v3. Both
        // end up stored as UTC `…Z` + the wearer's offset, so a document is identical whichever API wrote it.
        val iso = if (api == NsApi.V1) isoLocal(r.epochMs, zone) else isoUtc(r.epochMs)
        return JSONObject()
            .put("type", "sgv")
            .put("sgv", r.mgdl)
            .put("date", r.epochMs)
            .put("dateString", iso)
            .put("utcOffset", utcOffsetMin(r.epochMs, zone))
            .put("direction", direction(rate))
            .apply { if (rate != null) put("delta", (rate * 5 * 1000).roundToLong() / 1000.0) }
            .put("noise", 1)
            .put("device", device)
            .put("app", APP)
            .put("identifier", serverIdentifier(device, r.epochMs))
    }

    fun treatment(t: Treatment, device: String, zone: TimeZone): JSONObject {
        val o = JSONObject()
            .put("identifier", t.id)
            .put("created_at", isoUtc(t.epochMs))
            .put("date", t.epochMs)
            .put("utcOffset", utcOffsetMin(t.epochMs, zone))
            .put("enteredBy", APP)
            .put("app", APP)
            .put("device", device)
        val notes = mutableListOf<String>()
        when (t.kind) {
            "rapid" -> o.put("eventType", "Correction Bolus").put("insulin", t.amount).put("insulinType", "rapid-acting")
            "long" -> {
                o.put("eventType", "Note").put("insulinType", "long-acting")
                notes += "Long-acting insulin ${fmt(t.amount)} U"
            }
            "carbs" -> o.put("eventType", "Carb Correction").put("carbs", t.amount)
            else -> o.put("eventType", "Note")
        }
        if (t.note.isNotBlank()) notes += t.note
        if (notes.isNotEmpty()) o.put("notes", notes.joinToString(" · "))
        // lossless round-trip for a Sukrara that reads its own documents back (never needed by other apps)
        o.put("sukrara", JSONObject().put("k", t.kind).put("a", t.amount).put("u", t.updatedMs))
        return o
    }

    /**
     * Sensor age: LoopFollow counts only `Sensor Start` (`SAge.swift:13`), nightguard only `Sensor Change`
     * (`NightscoutService.swift:815`), Nightscout's SAGE pill takes the newest of both. A Libre 3 is inserted and
     * started in the same tap, so both events go up with the same time and their own identifiers.
     */
    fun sensorEvents(s: SensorStart, device: String, zone: TimeZone): List<JSONObject> =
        listOf("Sensor Change", "Sensor Start").map { type ->
            JSONObject()
                .put("identifier", uuidV5("sukrara-sensor_${s.serial}_$type"))
                .put("eventType", type)
                .put("created_at", isoUtc(s.epochMs))
                .put("date", s.epochMs)
                .put("utcOffset", utcOffsetMin(s.epochMs, zone))
                .put("enteredBy", APP).put("app", APP).put("device", device)
                .put("notes", "Libre 3 ${s.serial}")
        }

    fun deviceStatus(b: UploaderBattery, device: String, zone: TimeZone): JSONObject = JSONObject()
        .put("device", device)
        .put("created_at", isoUtc(b.epochMs))
        .put("date", b.epochMs)
        .put("utcOffset", utcOffsetMin(b.epochMs, zone))
        .put("app", APP)
        .put("identifier", serverIdentifier(device, b.epochMs))
        .put("uploader", JSONObject().put("battery", b.percent).put("isCharging", b.charging))

    private fun fmt(d: Double) = if (d == Math.floor(d)) d.toLong().toString() else d.toString()
}
