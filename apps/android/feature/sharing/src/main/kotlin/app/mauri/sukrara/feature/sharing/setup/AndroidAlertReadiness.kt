// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing.setup

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri
import app.mauri.sukrara.core.model.AlertReadiness
import app.mauri.sukrara.core.model.AlertReadinessSource
import app.mauri.sukrara.core.model.AlertRequirement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The phone's answers to the alert checklist (CHANGES P5), read from the system: the setup permission step and the home
 * reliability row show this one source. [alertChannels] = the alert channel ids the runtime posts on (chunk 11); a
 * channel not created yet isn't a problem (the runtime creates it with the right importance). [wearer] adds Bluetooth.
 */
class AndroidAlertReadiness(
    context: Context,
    private val alertChannels: () -> List<String>,
    private val wearer: () -> Boolean,
) : AlertReadinessSource {
    private val ctx = context.applicationContext
    private val notifications = ctx.getSystemService(NotificationManager::class.java)
    private val _readiness = MutableStateFlow(read())
    override val readiness: StateFlow<AlertReadiness> = _readiness.asStateFlow()

    override fun refresh() {
        _readiness.value = read()
    }

    private fun read(): AlertReadiness =
        AlertReadiness(AlertRequirement.entries.filterNot(::met).toSet())

    private fun met(r: AlertRequirement): Boolean =
        when (r) {
            AlertRequirement.Notifications -> notifications.areNotificationsEnabled() && granted(Manifest.permission.POST_NOTIFICATIONS)
            AlertRequirement.FullScreenIntent -> notifications.canUseFullScreenIntent()
            AlertRequirement.ExactAlarm -> ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
            AlertRequirement.BatteryExemption -> ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
            AlertRequirement.AlertChannel ->
                alertChannels().mapNotNull(notifications::getNotificationChannel).all { it.importance >= NotificationManager.IMPORTANCE_HIGH }
            AlertRequirement.Bluetooth ->
                !wearer() ||
                    (granted(Manifest.permission.BLUETOOTH_CONNECT) && ctx.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true)
        }

    private fun granted(permission: String) = ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** The system screen that fixes [requirement]; runtime permissions are asked in context by the screen first. */
    fun fixIntent(requirement: AlertRequirement): Intent {
        val pkg = "package:${ctx.packageName}".toUri()
        return when (requirement) {
            AlertRequirement.Notifications ->
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            AlertRequirement.FullScreenIntent -> Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg)
            AlertRequirement.ExactAlarm -> Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg)
            // CGM: Doze would delay the no-data alarm, an accepted reason for the direct request (manifest permission in :monitor)
            AlertRequirement.BatteryExemption -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
            AlertRequirement.AlertChannel ->
                alertChannels().firstOrNull { id ->
                    notifications.getNotificationChannel(id)?.let { it.importance < NotificationManager.IMPORTANCE_HIGH } == true
                }?.let { id ->
                    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, id)
                } ?: Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            AlertRequirement.Bluetooth ->
                if (granted(Manifest.permission.BLUETOOTH_CONNECT)) {
                    Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                } else {
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
                }
        }
    }

    override fun fix(requirement: AlertRequirement) {
        ctx.startActivity(fixIntent(requirement).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
