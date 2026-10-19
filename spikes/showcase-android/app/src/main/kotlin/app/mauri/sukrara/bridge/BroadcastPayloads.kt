// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.bridge

/**
 * Investigation 22: the local broadcasts other diabetes apps listen for, as plain key → value maps (Double, Long, Int,
 * Float, String or a nested map for a Bundle) so they are unit-tested on the JVM; [AppBroadcasts] turns them into
 * Bundles. Shapes are taken from the receivers (AAPS `XdripSourcePlugin`, GlucoDataHandler `XDripBroadcastReceiver` /
 * `LibrePatchedReceiver` / `GlucoseDataReceiver`, xDrip+ `LibreReceiver`; file:line refs in docs/22). A wrong type is
 * silent on Android (`getDouble` on a Long extra returns 0), so the types matter as much as the keys.
 */
object BroadcastPayloads {
    /**
     * One reading to send: mg/dL, rate mg/dL/min (null = unknown), epoch ms, the sensor serial and start (epoch ms, 0 =
     * unknown), and [sourceInfo] = the family's `SensorProfile.broadcastLabel` (only a verified family has one).
     */
    data class Out(val mgdl: Int, val rate: Double?, val timeMs: Long, val serial: String, val sensorStartMs: Long, val sourceInfo: String)

    // xDrip+ "BgEstimate": AAPS (xDrip+ source), GlucoDataHandler (→ Wear OS, Android Auto, WatchDrip+, Tasker…).
    const val XDRIP = "com.eveningoutpost.dexdrip.BgEstimate"
    const val X_BG = "com.eveningoutpost.dexdrip.Extras.BgEstimate"
    const val X_SLOPE = "com.eveningoutpost.dexdrip.Extras.BgSlope"
    const val X_SLOPE_NAME = "com.eveningoutpost.dexdrip.Extras.BgSlopeName"
    const val X_TIME = "com.eveningoutpost.dexdrip.Extras.Time"
    const val X_SENSOR_STARTED = "com.eveningoutpost.dexdrip.Extras.SensorStartedAt"
    const val X_SOURCE_INFO = "com.eveningoutpost.dexdrip.Extras.SourceInfo"
    const val X_SOURCE_DESC = "com.eveningoutpost.dexdrip.Extras.SourceDesc"

    // Patched-LibreLink broadcast: xDrip+ "Libre2 (patched App)" collector, GlucoDataHandler. Not read by AAPS.
    const val LIBRE = "com.librelink.app.ThirdPartyIntegration.GLUCOSE_READING"
    const val LIBRE_ACTIVATE = "com.librelink.app.ThirdPartyIntegration.SENSOR_ACTIVATE"

    // Juggluco's own broadcast: GlucoDataHandler / GlucoDataAuto ("Juggluco" source).
    const val GLUCODATA = "glucodata.Minute"

    /** Every action Sukrara can send; the manifest's `<queries>` lists the same. */
    val ACTIONS = listOf(XDRIP, LIBRE, LIBRE_ACTIVATE, GLUCODATA)

    /** xDrip+ trend names with xDrip+'s / Juggluco's thresholds in mg/dL/min; "" = unknown. */
    fun trendName(rate: Double?): String = when {
        rate == null || rate.isNaN() -> ""
        rate >= 3.5 -> "DoubleUp"
        rate >= 2.0 -> "SingleUp"
        rate >= 1.0 -> "FortyFiveUp"
        rate > -1.0 -> "Flat"
        rate > -2.0 -> "FortyFiveDown"
        rate > -3.5 -> "SingleDown"
        else -> "DoubleDown"
    }

    fun xdrip(r: Out): Map<String, Any> = buildMap {
        put(X_BG, r.mgdl.toDouble())
        put(X_TIME, r.timeMs)
        put(X_SLOPE_NAME, trendName(r.rate))
        // xDrip+'s slope is mg/dL per millisecond; GlucoDataHandler multiplies it by 60000 again
        r.rate?.takeIf { !it.isNaN() }?.let { put(X_SLOPE, it / 60_000.0) }
        if (r.sensorStartMs > 0) put(X_SENSOR_STARTED, r.sensorStartMs)
        // AAPS maps it with SourceSensor.fromString ("Libre3" → advanced filtering: SMB always / after carbs), so it
        // must be the real sensor's: the profile's broadcastLabel, which only a verified family has (docs/22 §5)
        put(X_SOURCE_INFO, r.sourceInfo)
        put(X_SOURCE_DESC, "Sukrara")
    }

    fun libre(r: Out): Map<String, Any> = buildMap {
        put("glucose", r.mgdl.toDouble())
        put("timestamp", r.timeMs)
        put("bleManager", mapOf("sensorSerial" to r.serial))
        // xDrip+ and GlucoDataHandler both read the sensor start from sas.currentSensor.sensorStartTime
        if (r.sensorStartMs > 0) put("sas", mapOf("currentSensor" to mapOf("sensorStartTime" to r.sensorStartMs)))
    }

    fun libreActivate(r: Out): Map<String, Any> = mapOf(
        "sensor" to mapOf("sensorStartTime" to r.sensorStartMs),
        "bleManager" to mapOf("sensorSerial" to r.serial),
    )

    fun glucodata(r: Out): Map<String, Any> = buildMap {
        put("glucodata.Minute.SerialNumber", r.serial)
        put("glucodata.Minute.mgdl", r.mgdl)
        put("glucodata.Minute.glucose", r.mgdl.toFloat())   // the value in the receiver's unit field: mg/dL
        put("glucodata.Minute.Rate", (r.rate ?: Double.NaN).toFloat())
        put("glucodata.Minute.Alarm", 0)
        put("glucodata.Minute.Time", r.timeMs)
    }

    /**
     * Who gets what for one reading: each allowed package gets every action it declares a receiver for (no switch per
     * format, like Juggluco). [declared] = package → the actions it receives. Nothing at all unless this phone is the
     * **wearer** reading the sensor itself ([wearer], [direct]): a follower's copy must never reach AAPS as if it were a
     * local sensor, and in Juggluco mode Juggluco broadcasts itself. SENSOR_ACTIVATE only with [newSensor]. And nothing
     * for a reading whose family has no broadcast label ([sourceInfo] null: every untested family, `SensorOutputs`): a
     * closed loop must never consume untested data.
     */
    fun plan(wearer: Boolean, direct: Boolean, declared: Map<String, Set<String>>, allowed: Set<String>,
             newSensor: Boolean, sourceInfo: String?): Map<String, List<String>> {
        if (!wearer || !direct || sourceInfo == null) return emptyMap()
        return declared.filterKeys { it in allowed }.mapValues { (_, acts) ->
            ACTIONS.filter { it in acts && (it != LIBRE_ACTIVATE || newSensor) }
        }.filterValues { it.isNotEmpty() }
    }

    /** Juggluco's throttle: at most one broadcast per 55 s, and never the same or an older reading twice. */
    fun due(lastSentMs: Long, timeMs: Long) = timeMs > lastSentMs && timeMs - lastSentMs >= MIN_INTERVAL_MS

    const val MIN_INTERVAL_MS = 55_000L
}
