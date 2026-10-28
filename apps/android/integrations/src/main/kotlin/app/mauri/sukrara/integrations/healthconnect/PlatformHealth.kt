// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.integrations.healthconnect

import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.ZoneId
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [HealthStore] over the platform `android.health.connect` API (part of Android 14+, so no client library on minSdk
 * 36). Glucose only: Health Connect's platform API has no insulin record.
 */
class PlatformHealth(
    context: Context,
) : HealthStore {
    private val context = context.applicationContext
    private val manager: HealthConnectManager? get() = context.getSystemService(HealthConnectManager::class.java)

    override fun available(): Boolean = manager != null

    override fun granted(): Boolean = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    override suspend fun insert(records: List<HcGlucose>) {
        val hc = manager ?: throw HealthWriteException(HealthConnectException.ERROR_UNSUPPORTED_OPERATION)
        suspendCancellableCoroutine { cont ->
            hc.insertRecords(
                records.map(::record),
                Runnable::run,
                object : OutcomeReceiver<InsertRecordsResponse, HealthConnectException> {
                    override fun onResult(result: InsertRecordsResponse) {
                        cont.resume(Unit)
                    }

                    override fun onError(error: HealthConnectException) {
                        cont.resumeWithException(HealthWriteException(error.errorCode))
                    }
                },
            )
        }
    }

    private fun record(g: HcGlucose): BloodGlucoseRecord {
        val device =
            Device
                .Builder()
                // Health Connect has no CGM device type
                .setType(Device.DEVICE_TYPE_UNKNOWN)
                .apply {
                    g.manufacturer?.let(::setManufacturer)
                    g.model?.let(::setModel)
                }.build()
        val meta =
            Metadata
                .Builder()
                .setDevice(device)
                .setRecordingMethod(Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED)
                .setClientRecordId(g.clientId)
                .setClientRecordVersion(1)
                .build()
        return BloodGlucoseRecord
            .Builder(
                meta,
                g.t,
                BloodGlucoseRecord.SpecimenSource.SPECIMEN_SOURCE_INTERSTITIAL_FLUID,
                BloodGlucose.fromMillimolesPerLiter(g.mgdl / MGDL_PER_MMOL),
                BloodGlucoseRecord.RelationToMealType.RELATION_TO_MEAL_UNKNOWN,
                MealType.MEAL_TYPE_UNKNOWN,
            ).setZoneOffset(ZoneId.systemDefault().rules.getOffset(g.t))
            .build()
    }

    companion object {
        const val PERMISSION = HealthPermissions.WRITE_BLOOD_GLUCOSE

        /** Health Connect's own factor (Jetpack's `milligramsPerDeciliter`). */
        private const val MGDL_PER_MMOL = 18.0

        /** Health Connect's page for this app's permissions (allow again, or see what it can write). */
        fun manageIntent(context: Context): Intent =
            Intent(HealthConnectManager.ACTION_MANAGE_HEALTH_PERMISSIONS)
                .putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
