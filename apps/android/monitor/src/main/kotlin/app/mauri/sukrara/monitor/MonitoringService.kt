// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.util.Log
import app.mauri.sukrara.core.model.ServiceState
import app.mauri.sukrara.monitor.notify.Channels
import app.mauri.sukrara.monitor.notify.NotificationIds
import app.mauri.sukrara.monitor.service.bluetoothPower
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

/**
 * The one monitoring foreground service, for both roles (`connectedDevice`: the sensor / the nearby wearer over
 * Bluetooth, relays over the network; not `dataSync`, capped at 6 h a day). It hosts the role's reading source as the
 * hub's input, and its notification IS the glucose notification. START_STICKY: Android recreates it after killing it;
 * the health check covers the cases it doesn't.
 *
 * `onCreate` does no I/O: it goes to the foreground at once with what the hub has in memory, then waits for the app's
 * storage in its own scope.
 */
class MonitoringService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val monitor: MonitorModule get() = monitorHost.monitor
    private var bluetoothReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        if (!goForeground()) return
        isRunning = true
        scope.launch { run() }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // every startForegroundService must be answered with startForeground, also for a running service; a service
        // refused in onCreate is already stopping (and has said so once)
        if (isRunning) goForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        bluetoothReceiver?.let { runCatching { unregisterReceiver(it) } }
        bluetoothReceiver = null
        scope.cancel()
        // a post still waiting in the gate must not bring back a notification nothing updates any more
        monitor.notification.cancelPending()
        monitor.hubImpl.setService(ServiceState.NotRunning)
        super.onDestroy()
    }

    /** Foreground with the glucose notification; on failure the person sees "Monitoring stopped" and it stops. */
    private fun goForeground(): Boolean {
        val m = monitor
        return try {
            // only the channels this needs: alert channels get their sounds once, from MonitorModule.ready
            Channels.createCore(this)
            val now = m.clock.now()
            val notification = m.notification.build(m.notification.content(m.hubImpl.state.value, now), now)
            startForeground(NotificationIds.GLUCOSE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            true
        } catch (e: IllegalStateException) {
            refused(e)
        } catch (e: SecurityException) {
            refused(e)
        }
    }

    private fun refused(e: Exception): Boolean {
        Log.w(TAG, "foreground refused: ${e.javaClass.simpleName}")
        monitor.starter.denied()
        stopSelf()
        return false
    }

    private suspend fun run() {
        monitorHost.awaitReady()
        val m = monitor
        m.hubImpl.setService(ServiceState.Running)
        m.healthCheck.schedule()
        watchBluetooth(m)
        scope.launch {
            combine(m.hubImpl.state, m.freshnessTicks.onStart { emit(Unit) }) { state, _ -> state }.collect { state ->
                val now = m.clock.now()
                m.freshnessTicker.plan(state.latest?.t, now)
                m.notification.post(m.notification.content(state, now), now)
            }
        }
        // the role's source, swapped when the role changes (wearer ↔ follower)
        m.hubImpl.state.map { it.role }.distinctUntilChanged().collectLatest { role ->
            val source = role?.let { m.source(it) }
            if (source == null) {
                // nothing to monitor (setup not done, no sensor, not following): no service; the app shows why
                stopSelf()
            } else {
                m.hubImpl.attach(source)
            }
        }
    }

    private fun watchBluetooth(m: MonitorModule) {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        m.hubImpl.onBluetoothChanged(adapter?.isEnabled ?: true)
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    bluetoothPower(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR))
                        ?.let(m.hubImpl::onBluetoothChanged)
                }
            }
        registerReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), RECEIVER_NOT_EXPORTED)
        bluetoothReceiver = receiver
    }

    companion object {
        private const val TAG = "Sukrara.monitor"

        /** Whether the service runs in this process (false after the process died). */
        @Volatile
        var isRunning = false
            private set
    }
}
