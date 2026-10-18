// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.outputs

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * One reading as Sukrara has it after decoding the sensor: mg/dL, rate in mg/dL per minute (NaN = unknown),
 * epoch ms, the sensor serial and its start (epoch ms).
 */
data class Reading(
    val mgdl: Int,
    val rate: Float,
    val timeMs: Long,
    val serial: String,
    val sensorStartMs: Long,
)

/**
 * The broadcasts other apps listen for, as plain key → value maps (Double, Long, Int, Float, String or a nested map for
 * a Bundle), so they can be unit-tested on the JVM. [Emitter] turns them into Bundles.
 *
 * Shapes are taken from the receivers, not from senders (file:line refs in docs/22-ecosystem-outputs.md):
 * AAPS `DataReceiver` + `XdripSourcePlugin`, GlucoDataHandler `XDripBroadcastReceiver`, `LibrePatchedReceiver`,
 * `GlucoseDataReceiver`, xDrip+ `LibreReceiver`.
 */
object Payloads {
    // xDrip+ "BgEstimate": AAPS (xDrip+ source), GlucoDataHandler, Watchdrip+, many watch faces.
    const val XDRIP_ACTION = "com.eveningoutpost.dexdrip.BgEstimate"
    const val X_BG = "com.eveningoutpost.dexdrip.Extras.BgEstimate"
    const val X_SLOPE = "com.eveningoutpost.dexdrip.Extras.BgSlope"
    const val X_SLOPE_NAME = "com.eveningoutpost.dexdrip.Extras.BgSlopeName"
    const val X_TIME = "com.eveningoutpost.dexdrip.Extras.Time"
    const val X_SENSOR_STARTED = "com.eveningoutpost.dexdrip.Extras.SensorStartedAt"
    const val X_SOURCE_INFO = "com.eveningoutpost.dexdrip.Extras.SourceInfo"
    const val X_SOURCE_DESC = "com.eveningoutpost.dexdrip.Extras.SourceDesc"

    /**
     * The sensor label AAPS maps with `SourceSensor.fromString`. "Libre3" → `LIBRE_3`, which AAPS's xDrip+ source counts
     * as advanced filtering (SMB always / after carbs). Must name the real sensor: a Libre 3 Plus is "Libre3".
     */
    const val SOURCE_LIBRE3 = "Libre3"

    // Patched-LibreLink broadcast: xDrip+ "Libre2 (patched App)" source, GlucoDataHandler. Not read by AAPS.
    const val LIBRE_ACTION = "com.librelink.app.ThirdPartyIntegration.GLUCOSE_READING"
    const val LIBRE_ACTIVATE_ACTION = "com.librelink.app.ThirdPartyIntegration.SENSOR_ACTIVATE"

    // Juggluco's own "glucodata" broadcast: GlucoDataHandler / GlucoDataAuto ("Juggluco" source), Kotlin-friendly.
    const val GLUCODATA_ACTION = "glucodata.Minute"

    /** xDrip+ trend names; thresholds in mg/dL/min as xDrip+ and Juggluco use them (glucose.cpp `getdeltaname`). */
    fun trendName(rate: Float): String = when {
        rate.isNaN() -> ""
        rate >= 3.5f -> "DoubleUp"
        rate >= 2.0f -> "SingleUp"
        rate >= 1.0f -> "FortyFiveUp"
        rate > -1.0f -> "Flat"
        rate > -2.0f -> "FortyFiveDown"
        rate > -3.5f -> "SingleDown"
        else -> "DoubleDown"
    }

    fun xdrip(r: Reading): Map<String, Any> = buildMap {
        put(X_BG, r.mgdl.toDouble())
        put(X_TIME, r.timeMs)
        put(X_SLOPE_NAME, trendName(r.rate))
        // xDrip+ slope unit is mg/dL per millisecond; GlucoDataHandler multiplies by 60000 again.
        if (!r.rate.isNaN()) put(X_SLOPE, r.rate.toDouble() / 60_000.0)
        put(X_SENSOR_STARTED, r.sensorStartMs)
        put(X_SOURCE_INFO, SOURCE_LIBRE3)
        put(X_SOURCE_DESC, "Sukrara outputs spike")
    }

    fun libre(r: Reading): Map<String, Any> = mapOf(
        "glucose" to r.mgdl.toDouble(),
        "timestamp" to r.timeMs,
        "bleManager" to mapOf("sensorSerial" to r.serial),
        // xDrip+ and GlucoDataHandler both pick the sensor start from sas.currentSensor.sensorStartTime.
        "sas" to mapOf("currentSensor" to mapOf("sensorStartTime" to r.sensorStartMs)),
    )

    fun libreActivate(r: Reading): Map<String, Any> = mapOf(
        "sensor" to mapOf("sensorStartTime" to r.sensorStartMs),
        "bleManager" to mapOf("sensorSerial" to r.serial),
    )

    fun glucodata(r: Reading): Map<String, Any> = mapOf(
        "glucodata.Minute.SerialNumber" to r.serial,
        "glucodata.Minute.mgdl" to r.mgdl,
        "glucodata.Minute.glucose" to r.mgdl.toFloat(), // value in the user's unit; mg/dL here
        "glucodata.Minute.Rate" to r.rate,
        "glucodata.Minute.Alarm" to 0,
        "glucodata.Minute.Time" to r.timeMs,
    )
}

/** Synthetic readings only: a slow sine around 130 mg/dL with a 3 h period. Never a real sensor. */
object Synthetic {
    const val SERIAL = "SPIKE0000022"

    fun at(timeMs: Long, sensorStartMs: Long = timeMs - 3 * 86_400_000L): Reading {
        val minute = timeMs / 60_000.0
        val phase = 2 * PI * minute / 180.0
        val mgdl = (130 + 60 * sin(phase)).roundToInt()
        val rate = (60 * 2 * PI / 180.0 * kotlin.math.cos(phase)).toFloat() // derivative, mg/dL per minute
        return Reading(mgdl, rate, timeMs - timeMs % 60_000, SERIAL, sensorStartMs)
    }
}
