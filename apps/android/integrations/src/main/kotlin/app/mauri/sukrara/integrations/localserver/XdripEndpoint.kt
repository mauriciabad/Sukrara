// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.integrations.localserver

import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.integrations.CurrentSensor
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The xDrip+ "local web service" answers, byte-compatible with Juggluco's server (field order and number formats), which
 * is what Garmin Connect IQ glucose apps (CGM+ data fields, CGM Connect, …) poll through Garmin Connect. Pure: the
 * readings (newest first, the last 24 h) and the sensor come in, the JSON goes out.
 *
 * Paths: `sgv.json`, `api/v1/entries/sgv.json`, `api/v1/entries.json`, `api/v1/entries` (`count`, `interval`,
 * `brief_mode`, `sensor`), `pebble`, `status.json`, `api/v1/status.json`.
 */
object XdripEndpoint {
    const val PORT = 17580
    const val DEVICE = "Sukrara"
    private const val SPACING_S = 270L
    private const val STATUS = "{\"settings\":{\"units\":\"mg/dL\",\"thresholds\":{\"bgHigh\":185,\"bgLow\":65}}}"
    private val UTC = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).withZone(ZoneOffset.UTC)
    private val LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
    private val STATUS_TIME = DateTimeFormatter.ofPattern("dd-MM-yy HH:mm", Locale.US)

    fun route(
        req: HttpRequest,
        newestFirst: List<Reading>,
        sensor: CurrentSensor?,
        now: Instant,
        zone: ZoneId,
    ): HttpAnswer =
        when (req.path) {
            "sgv.json", "api/v1/entries/sgv.json", "api/v1/entries.json", "api/v1/entries" -> {
                HttpAnswer("200 OK", sgv(req.query, newestFirst, sensor, now, zone, utc = req.path.startsWith("api/")))
            }

            "pebble" -> {
                HttpAnswer("200 OK", pebble(newestFirst, now))
            }

            "status.json", "api/v1/status.json" -> {
                HttpAnswer("200 OK", STATUS)
            }

            else -> {
                HttpAnswer.NOT_FOUND
            }
        }

    /** Newest first, at least [minGap] s apart (readings jitter by a second or two: an ask for 300 allows 30 s slack). */
    private fun spaced(
        all: List<Reading>,
        count: Int,
        minGap: Long = SPACING_S,
    ): List<Reading> {
        val out = ArrayList<Reading>()
        for (r in all) {
            if (out.isNotEmpty() && out.last().t.epochSecond - r.t.epochSecond < minGap) continue
            out += r
            if (out.size >= count) break
        }
        return out
    }

    /** mg/dL per 5 minutes: the sensor's rate when it has one, else the slope to the reading before. */
    private fun delta(
        r: Reading,
        all: List<Reading>,
    ): Double? {
        r.rateMgdlPerMin?.let { return it * 5 }
        val prev = all.firstOrNull { it.t.epochSecond <= r.t.epochSecond - 120 } ?: return null
        // no slope from or to a LO / HI: its 39 / 401 is a flag, not a value
        if (r.isLo || r.isHi || prev.isLo || prev.isHi) return null
        val dt = r.t.epochSecond - prev.t.epochSecond
        if (dt > 15 * 60) return null
        return (r.mgdl - prev.mgdl) * 300.0 / dt
    }

    private fun flag(v: String?) = v?.let { it.isEmpty() || it[0] !in "fFnN0" } ?: false

    private fun sgv(
        q: Map<String, String>,
        all: List<Reading>,
        sensor: CurrentSensor?,
        now: Instant,
        zone: ZoneId,
        utc: Boolean,
    ): String {
        // Monkey C apps send floats ("count=72.0"); Juggluco's parser stops at the '.', so do the same
        val count = (q["count"]?.toDoubleOrNull()?.toInt() ?: 24).coerceIn(1, 1000)
        val minGap = q["interval"]?.toDoubleOrNull()?.toLong()?.let { (it - minOf(30, it / 10)).coerceAtLeast(0) } ?: SPACING_S
        val brief = flag(q["brief_mode"])
        val withSensor = flag(q["sensor"])
        val list = spaced(all, count, minGap)
        return list
            .mapIndexed { i, r ->
                val d = delta(r, all)
                buildString {
                    append('{')
                    if (!brief) {
                        val ds = if (utc) UTC.format(r.t) else LOCAL.format(r.t.atZone(zone))
                        append("\"_id\":\"${sensor?.serial ?: DEVICE}#${r.t.epochSecond / 60}\",\"device\":\"$DEVICE\",")
                        append("\"dateString\":\"$ds\",\"sysTime\":\"$ds\",")
                    }
                    append("\"date\":${r.t.epochSecond}000,\"sgv\":${r.mgdl},")
                    append("\"delta\":${String.format(Locale.US, "%.3f", d ?: 0.0)},\"direction\":\"${direction(d)}\",\"noise\":1")
                    if (!brief) append(",\"filtered\":${r.mgdl}000,\"unfiltered\":${r.mgdl}000,\"rssi\":100,\"type\":\"sgv\"")
                    if (i == 0) {
                        append(",\"units_hint\":\"mgdl\"")
                        if (withSensor && sensor != null) append(",\"sensor_status\":\"${sensorStatus(sensor, now, zone)}\"")
                    }
                    append('}')
                }
            }.joinToString(",\n", "[", "]\n")
    }

    private fun pebble(
        all: List<Reading>,
        now: Instant,
    ): String {
        val r = spaced(all, 1).firstOrNull()
        val bgs =
            r
                ?.let {
                    val d = delta(it, all)
                    "{\"sgv\":\"${it.mgdl}\",\"trend\":${trend(d)},\"direction\":\"${direction(d)}\",\"datetime\":${it.t.epochSecond}000," +
                        "\"bgdelta\":\"${String.format(Locale.US, "%.2f", d ?: 0.0)}\",\"iob\":\"0.00\"}"
                }.orEmpty()
        return "{\"status\":[{\"now\":${now.toEpochMilli()}}],\"bgs\":[$bgs],\"cals\":[]}"
    }

    /** "dd-mm-yy HH:MM (Xd Yh)": sensor start and age, as Juggluco reports it. */
    private fun sensorStatus(
        s: CurrentSensor,
        now: Instant,
        zone: ZoneId,
    ): String {
        val age = now.epochSecond - s.start.epochSecond
        return "${STATUS_TIME.format(s.start.atZone(zone))} (${age / 86400}d ${age % 86400 / 3600}h)"
    }

    /** Nightscout trend names from a 5-minute delta (thresholds per minute: 1, 2, 3 mg/dL). */
    fun direction(delta5: Double?): String {
        val m = (delta5 ?: return "NONE") / 5
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

    private fun trend(delta5: Double?): Int =
        when (direction(delta5)) {
            "DoubleUp" -> 1
            "SingleUp" -> 2
            "FortyFiveUp" -> 3
            "Flat" -> 4
            "FortyFiveDown" -> 5
            "SingleDown" -> 6
            "DoubleDown" -> 7
            else -> 0
        }
}
