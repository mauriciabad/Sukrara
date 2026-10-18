// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.outputs

import android.content.Context
import android.health.connect.HealthConnectException
import android.health.connect.HealthConnectManager
import android.health.connect.HealthPermissions
import android.health.connect.InsertRecordsResponse
import android.health.connect.datatypes.BloodGlucoseRecord
import android.health.connect.datatypes.Device
import android.health.connect.datatypes.MealType
import android.health.connect.datatypes.Metadata
import android.health.connect.datatypes.Record
import android.health.connect.datatypes.units.BloodGlucose
import android.os.OutcomeReceiver
import java.time.Instant
import java.time.ZoneId

/**
 * Health Connect through the platform API (android.health.connect, built into Android 14+; minSdk 36 so no Jetpack
 * client is needed). One [BloodGlucoseRecord] per reading, interstitial fluid, with a clientRecordId per minute so a
 * second write of the same minute replaces the record instead of duplicating it.
 */
class HealthWriter(ctx: Context) {
    private val app = ctx.applicationContext
    private val hc = app.getSystemService(HealthConnectManager::class.java)

    val permission = HealthPermissions.WRITE_BLOOD_GLUCOSE
    fun granted() = app.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private val device = Device.Builder()
        .setType(Device.DEVICE_TYPE_UNKNOWN) // Health Connect has no CGM device type
        .setManufacturer("Synthetic")
        .setModel("Outputs spike")
        .build()

    fun record(r: Reading): BloodGlucoseRecord {
        val time = Instant.ofEpochMilli(r.timeMs)
        val meta = Metadata.Builder()
            .setDevice(device)
            .setRecordingMethod(Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED)
            .setClientRecordId("${r.serial}-${r.timeMs / 60_000}")
            .setClientRecordVersion(1)
            .build()
        return BloodGlucoseRecord.Builder(
            meta, time,
            BloodGlucoseRecord.SpecimenSource.SPECIMEN_SOURCE_INTERSTITIAL_FLUID,
            BloodGlucose.fromMillimolesPerLiter(r.mgdl / MGDL_PER_MMOL),
            BloodGlucoseRecord.RelationToMealType.RELATION_TO_MEAL_UNKNOWN,
            MealType.MEAL_TYPE_UNKNOWN,
        ).setZoneOffset(ZoneId.systemDefault().rules.getOffset(time)).build()
    }

    fun write(readings: List<Reading>, done: (String) -> Unit) {
        val records: List<Record> = readings.map(::record)
        hc.insertRecords(records, app.mainExecutor, object : OutcomeReceiver<InsertRecordsResponse, HealthConnectException> {
            override fun onResult(result: InsertRecordsResponse) = done("Health Connect: ${result.records.size} records written")
            override fun onError(error: HealthConnectException) = done("Health Connect error ${error.errorCode}: ${error.message}")
        })
    }

    companion object {
        /** Health Connect's own mg/dL factor (Jetpack `BloodGlucose.milligramsPerDeciliter` uses 18.0). */
        const val MGDL_PER_MMOL = 18.0
    }
}
