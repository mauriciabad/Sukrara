// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.integrations.broadcasts

/**
 * The broadcasts other diabetes apps listen for, as plain key → value maps (Double, Long, Int, Float, String, or a
 * nested map for a Bundle) so they are tested on the JVM; [Broadcaster] turns them into Bundles. Shapes are the
 * receivers' (AAPS `XdripSourcePlugin`, GlucoDataHandler, xDrip+ `LibreReceiver`). A wrong type is silent on Android
 * (`getDouble` on a Long extra returns 0), so the types matter as much as the keys.
 */
object BroadcastPayloads {
    /**
     * One reading to send: mg/dL, rate mg/dL/min (null = unknown), epoch ms, the sensor's serial and start (epoch ms,
     * 0 = unknown).
     */
    data class Out(
        val mgdl: Int,
        val rate: Double?,
        val timeMs: Long,
        val serial: String,
        val sensorStartMs: Long,
    )

    // xDrip+ "BgEstimate": AAPS (xDrip+ source), GlucoDataHandler (→ Wear OS, Android Auto, WatchDrip+, Tasker…).
    const val XDRIP = "com.eveningoutpost.dexdrip.BgEstimate"
    const val X_BG = "com.eveningoutpost.dexdrip.Extras.BgEstimate"
    const val X_SLOPE = "com.eveningoutpost.dexdrip.Extras.BgSlope"
    const val X_SLOPE_NAME = "com.eveningoutpost.dexdrip.Extras.BgSlopeName"
    const val X_TIME = "com.eveningoutpost.dexdrip.Extras.Time"
    const val X_SENSOR_STARTED = "com.eveningoutpost.dexdrip.Extras.SensorStartedAt"
    const val X_SOURCE_INFO = "com.eveningoutpost.dexdrip.Extras.SourceInfo"
    const val X_SOURCE_DESC = "com.eveningoutpost.dexdrip.Extras.SourceDesc"

    /**
     * What AAPS's `SourceSensor.fromString` reads from [X_SOURCE_INFO]: "Libre3" turns on its advanced filtering (SMB
     * always / after carbs). It is what Juggluco sends for a Libre 3, and only a Libre 3 read by this phone is ever
     * sent ([BroadcastPlan]).
     */
    const val LIBRE3_SOURCE = "Libre3"

    // Patched-LibreLink broadcast: xDrip+ "Libre2 (patched App)" collector, GlucoDataHandler. Not read by AAPS.
    const val LIBRE = "com.librelink.app.ThirdPartyIntegration.GLUCOSE_READING"
    const val LIBRE_ACTIVATE = "com.librelink.app.ThirdPartyIntegration.SENSOR_ACTIVATE"

    // Juggluco's own broadcast: GlucoDataHandler / GlucoDataAuto ("Juggluco" source).
    const val GLUCODATA = "glucodata.Minute"

    /** Every action Sukrara can send; the app manifest's `<queries>` lists the same. */
    val ACTIONS = listOf(XDRIP, LIBRE, LIBRE_ACTIVATE, GLUCODATA)

    /** xDrip+ trend names with xDrip+'s / Juggluco's thresholds in mg/dL/min; "" = unknown. */
    fun trendName(rate: Double?): String =
        when {
            rate == null || rate.isNaN() -> ""
            rate >= 3.5 -> "DoubleUp"
            rate >= 2.0 -> "SingleUp"
            rate >= 1.0 -> "FortyFiveUp"
            rate > -1.0 -> "Flat"
            rate > -2.0 -> "FortyFiveDown"
            rate > -3.5 -> "SingleDown"
            else -> "DoubleDown"
        }

    fun xdrip(r: Out): Map<String, Any> =
        buildMap {
            put(X_BG, r.mgdl.toDouble())
            put(X_TIME, r.timeMs)
            put(X_SLOPE_NAME, trendName(r.rate))
            // xDrip+'s slope is mg/dL per millisecond; GlucoDataHandler multiplies it by 60000 again
            r.rate?.takeIf { !it.isNaN() }?.let { put(X_SLOPE, it / 60_000.0) }
            if (r.sensorStartMs > 0) put(X_SENSOR_STARTED, r.sensorStartMs)
            put(X_SOURCE_INFO, LIBRE3_SOURCE)
            put(X_SOURCE_DESC, "Sukrara")
        }

    fun libre(r: Out): Map<String, Any> =
        buildMap {
            put("glucose", r.mgdl.toDouble())
            put("timestamp", r.timeMs)
            put("bleManager", mapOf("sensorSerial" to r.serial))
            // xDrip+ and GlucoDataHandler both read the sensor start from sas.currentSensor.sensorStartTime
            if (r.sensorStartMs > 0) put("sas", mapOf("currentSensor" to mapOf("sensorStartTime" to r.sensorStartMs)))
        }

    fun libreActivate(r: Out): Map<String, Any> =
        mapOf(
            "sensor" to mapOf("sensorStartTime" to r.sensorStartMs),
            "bleManager" to mapOf("sensorSerial" to r.serial),
        )

    fun glucodata(r: Out): Map<String, Any> =
        buildMap {
            put("glucodata.Minute.SerialNumber", r.serial)
            put("glucodata.Minute.mgdl", r.mgdl)
            // the value in the receiver's unit field: mg/dL
            put("glucodata.Minute.glucose", r.mgdl.toFloat())
            put("glucodata.Minute.Rate", (r.rate ?: Double.NaN).toFloat())
            put("glucodata.Minute.Alarm", 0)
            put("glucodata.Minute.Time", r.timeMs)
        }

    fun extras(
        action: String,
        r: Out,
    ): Map<String, Any> =
        when (action) {
            XDRIP -> xdrip(r)
            LIBRE -> libre(r)
            LIBRE_ACTIVATE -> libreActivate(r)
            else -> glucodata(r)
        }
}
