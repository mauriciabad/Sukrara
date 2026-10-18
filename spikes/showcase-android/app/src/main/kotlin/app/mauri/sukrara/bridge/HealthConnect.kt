// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.bridge

import android.content.Context
import android.content.pm.PackageManager
import android.health.connect.HealthConnectException
import android.health.connect.HealthConnectManager
import android.health.connect.HealthPermissions
import android.health.connect.InsertRecordsResponse
import android.health.connect.datatypes.BloodGlucoseRecord
import android.health.connect.datatypes.Device
import android.health.connect.datatypes.MealType
import android.health.connect.datatypes.Metadata
import android.health.connect.datatypes.units.BloodGlucose
import android.os.OutcomeReceiver
import android.util.Log
import app.mauri.sukrara.core.Reading
import app.mauri.sukrara.owner.OwnerDb
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Investigation 22: the wearer's readings as Health Connect blood glucose (interstitial fluid), through the platform
 * `android.health.connect` API (built into Android 14+, so no Jetpack client on minSdk 36). Opt-in: the switch asks
 * Health Connect's own permission sheet; on, the stored history (≤ 30 days) is written once, then every reading.
 *
 * No duplicates: each record's clientRecordId is the reading's minute ([clientId]), so writing the same minute again
 * (a reconnect backfill, the switch turned off and on) updates that record instead of adding one. Checked on the
 * emulator: the second insert of a minute returns the same record id (docs/22, "In Sukrara").
 */
object HealthConnect {
    private const val TAG = "Sukrara.health"
    private const val PREFS = "health_connect"
    const val PERMISSION = HealthPermissions.WRITE_BLOOD_GLUCOSE
    private const val MGDL_PER_MMOL = 18.0   // Health Connect's own factor (Jetpack's milligramsPerDeciliter)
    private const val BATCH = 500

    private val _on = MutableStateFlow(false)
    /** The user's switch; writing also needs [granted]. */
    val on: StateFlow<Boolean> = _on
    @Volatile private var loaded = false
    private val io by lazy { Executors.newSingleThreadExecutor() }

    fun init(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            _on.value = prefs(ctx).getBoolean("on", false)
            loaded = true
        }
    }

    fun available(ctx: Context) = ctx.getSystemService(HealthConnectManager::class.java) != null
    fun granted(ctx: Context) = ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** On (after the permission was granted): writes the stored history once. Off: stops writing; records stay. */
    fun setOn(ctx: Context, on: Boolean) {
        init(ctx)
        prefs(ctx).edit().putBoolean("on", on).apply()
        _on.value = on
        if (on) io.execute { backfill(ctx) }
    }

    private fun active(ctx: Context): Boolean { init(ctx); return _on.value && granted(ctx) }

    fun onReading(ctx: Context, r: Reading) { if (active(ctx)) write(ctx, listOf(r)) }
    fun onHistory(ctx: Context, list: List<Reading>) { if (active(ctx)) list.chunked(BATCH).forEach { write(ctx, it) } }

    private fun backfill(ctx: Context) {
        if (!active(ctx)) return
        val now = System.currentTimeMillis() / 1000
        val rows = runCatching { OwnerDb(ctx.applicationContext).use { it.range(now - 30 * 86_400L, now + 60) } }.getOrDefault(emptyList())
        Log.i(TAG, "backfill ${rows.size} readings")
        rows.chunked(BATCH).forEach { write(ctx, it) }
    }

    /** The record id Health Connect dedupes on: one per minute of glucose. */
    fun clientId(t: Long) = "sukrara-glucose-${t / 60}"

    private val device by lazy {
        Device.Builder()
            .setType(Device.DEVICE_TYPE_UNKNOWN)   // Health Connect has no CGM device type
            .setManufacturer("Abbott")
            .setModel("FreeStyle Libre 3")
            .build()
    }

    private fun record(r: Reading): BloodGlucoseRecord {
        val time = Instant.ofEpochSecond(r.t)
        val meta = Metadata.Builder()
            .setDevice(device)
            .setRecordingMethod(Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED)
            .setClientRecordId(clientId(r.t))
            .setClientRecordVersion(1)
            .build()
        return BloodGlucoseRecord.Builder(
            meta, time,
            BloodGlucoseRecord.SpecimenSource.SPECIMEN_SOURCE_INTERSTITIAL_FLUID,
            BloodGlucose.fromMillimolesPerLiter(r.sgv / MGDL_PER_MMOL),
            BloodGlucoseRecord.RelationToMealType.RELATION_TO_MEAL_UNKNOWN,
            MealType.MEAL_TYPE_UNKNOWN,
        ).setZoneOffset(ZoneId.systemDefault().rules.getOffset(time)).build()
    }

    private fun write(ctx: Context, list: List<Reading>) {
        val hc = ctx.getSystemService(HealthConnectManager::class.java) ?: return
        hc.insertRecords(list.map(::record), io, object : OutcomeReceiver<InsertRecordsResponse, HealthConnectException> {
            override fun onResult(result: InsertRecordsResponse) {
                val ids = result.records.joinToString(limit = 3) { it.metadata.id }
                Log.i(TAG, "wrote ${result.records.size} (${list.first().t}…${list.last().t}) ids $ids")
            }
            override fun onError(error: HealthConnectException) = Log.w(TAG, "insert ${list.size}: ${error.errorCode} ${error.message}").let { }
        })
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
