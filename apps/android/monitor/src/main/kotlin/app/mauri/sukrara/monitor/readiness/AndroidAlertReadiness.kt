// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.readiness

import android.Manifest
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import app.mauri.sukrara.alerts.AlertKind
import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.core.model.AlertReadiness
import app.mauri.sukrara.core.model.AlertReadinessSource
import app.mauri.sukrara.core.model.AlertRequirement
import app.mauri.sukrara.core.model.DispatcherProvider
import app.mauri.sukrara.monitor.alerts.AlertChannels
import app.mauri.sukrara.monitor.notify.Channels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/**
 * THE alert checklist of the app (CHANGES P5): home's reliability row, the settings Reliability page and the setup
 * permission step all render this one instance (`AppGraph.alerts.readiness`). It re-reads the phone when a screen
 * asks ([refresh], on every resume), when the alert settings or the role's source change, and while someone collects
 * it, on the system broadcasts that flip an item without leaving the app (Bluetooth, Do Not Disturb, a blocked
 * channel, the exact-alarm grant).
 *
 * Items: notifications on; the full-screen intent when a kind rings like an alarm; exact alarms; the battery
 * exemption (and not "Restricted"); the alert channels in use not blocked or silenced, and Do Not Disturb not
 * silencing the alarm-level ones (a non-alarm alert in Do Not Disturb is the person's choice: "Like an alarm" is the
 * way through); Bluetooth on and allowed when [needsBluetooth].
 */
class AndroidAlertReadiness(
    context: Context,
    scope: CoroutineScope,
    dispatchers: DispatcherProvider,
    private val alertSettings: Flow<AlertSettings>,
    private val needsBluetooth: Flow<Boolean>,
) : AlertReadinessSource {
    private val ctx = context.applicationContext
    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private val refreshes = MutableStateFlow(0L)

    @Volatile private var last = Inputs(AlertSettings(), needsBluetooth = false)

    override val readiness: StateFlow<AlertReadiness> by lazy {
        combine(alertSettings, needsBluetooth, refreshes, systemChanges()) { settings, bluetooth, _, _ ->
            last = Inputs(settings, bluetooth)
            facts(last).readiness()
        }.flowOn(dispatchers.default)
            .stateIn(scope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), facts(last).readiness())
    }

    override fun refresh() {
        refreshes.value++
    }

    override fun fix(requirement: AlertRequirement) {
        val pkg = "package:${ctx.packageName}".toUri()
        val intent =
            when (requirement) {
                AlertRequirement.Notifications -> appNotificationSettings()
                AlertRequirement.FullScreenIntent -> Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg)
                AlertRequirement.ExactAlarm -> Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg)
                AlertRequirement.BatteryExemption ->
                    if (ctx.getSystemService(ActivityManager::class.java).isBackgroundRestricted) {
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
                    } else {
                        // REQUEST_IGNORE_BATTERY_OPTIMIZATIONS is declared: a CGM's alerts are the accepted use case
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
                    }
                // the channel page holds both the importance and "Override Do Not Disturb"
                AlertRequirement.AlertChannel ->
                    channelsInUse(last.settings).firstOrNull { it.silenced() || it.blockedByDnd() }?.let { channelSettings(it.id) }
                        ?: appNotificationSettings()
                AlertRequirement.Bluetooth ->
                    if (bluetoothPermitted()) Intent(Settings.ACTION_BLUETOOTH_SETTINGS) else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
            }
        start(intent) || start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
    }

    private data class Inputs(
        val settings: AlertSettings,
        val needsBluetooth: Boolean,
    )

    private fun facts(inputs: Inputs): ReadinessFacts {
        val channels = channelsInUse(inputs.settings)
        val alertGroupBlocked =
            listOf(Channels.Group.ALERTS, Channels.Group.ALERTS_ALARM).any { nm.getNotificationChannelGroup(it.id)?.isBlocked == true }
        val bluetooth = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        return ReadinessFacts(
            notificationsEnabled = nm.areNotificationsEnabled(),
            anyAlarmKind = alarmKinds(inputs.settings).isNotEmpty(),
            fullScreenAllowed = nm.canUseFullScreenIntent(),
            exactAlarmAllowed = ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
            batteryExempt = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName),
            backgroundRestricted = ctx.getSystemService(ActivityManager::class.java).isBackgroundRestricted,
            alertChannelSilenced = alertGroupBlocked || channels.any { it.silenced() },
            dndSilencesAlarms = channels.any { it.blockedByDnd() },
            needsBluetooth = inputs.needsBluetooth,
            bluetoothOn = bluetooth?.isEnabled == true,
            bluetoothPermitted = bluetoothPermitted(),
        )
    }

    private fun alarmKinds(s: AlertSettings) = if (s.on) AlertKind.entries.filter { s.rule(it).on && s.rule(it).alarm } else emptyList()

    /** The channel each enabled kind posts on (a channel not created yet gets our defaults: nothing to warn about). */
    private fun channelsInUse(s: AlertSettings): List<NotificationChannel> =
        if (!s.on) {
            emptyList()
        } else {
            AlertKind.entries.filter { s.rule(it).on }.mapNotNull { nm.getNotificationChannel(AlertChannels.id(it, s.rule(it).alarm)) }
        }

    private fun NotificationChannel.silenced() = importance < NotificationManager.IMPORTANCE_DEFAULT

    private fun NotificationChannel.blockedByDnd(): Boolean {
        if (id !in ALARM_CHANNELS) return false
        return when (nm.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_NONE -> true
            NotificationManager.INTERRUPTION_FILTER_PRIORITY ->
                !canBypassDnd() && nm.notificationPolicy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS == 0
            else -> false
        }
    }

    private fun bluetoothPermitted() = ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** One emission at start and one per broadcast that can flip an item while the app stays open. */
    private fun systemChanges(): Flow<Unit> =
        callbackFlow {
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context,
                        intent: Intent,
                    ) {
                        trySend(Unit)
                    }
                }
            val filter =
                IntentFilter().apply {
                    addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                    addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
                    addAction(NotificationManager.ACTION_NOTIFICATION_POLICY_CHANGED)
                    addAction(NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED)
                    addAction(NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED)
                    addAction(NotificationManager.ACTION_NOTIFICATION_CHANNEL_GROUP_BLOCK_STATE_CHANGED)
                    addAction(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)
                }
            // system broadcasts reach a not-exported receiver
            ContextCompat.registerReceiver(ctx, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            awaitClose { ctx.unregisterReceiver(receiver) }
        }.onStart { emit(Unit) }

    private fun appNotificationSettings() = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)

    private fun channelSettings(id: String) =
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, id)

    private fun start(intent: Intent): Boolean =
        try {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            Log.w("Sukrara.readiness", "no screen for ${intent.action}: ${e.javaClass.simpleName}")
            false
        }

    private companion object {
        const val STOP_AFTER_MS = 5_000L
        val ALARM_CHANNELS: Set<String> = AlertKind.entries.map { AlertChannels.id(it, alarm = true) }.toSet()
    }
}
