// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder

/*
 * The monitoring runtime's Android components, declared in this module's manifest. They are skeletons until chunk 10
 * (monitor) fills them; chunk 11's alert components are in their own files; they stay enabled so a reboot or an update always reaches them
 * (ManifestTest keeps them enabled).
 */

/** The one foreground service (type connectedDevice) that keeps monitoring alive for either role. Chunk 10. */
class MonitoringService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}

/** Restarts monitoring after a reboot or an app update. Chunk 10. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) = Unit
}

/** Every-15-minutes exact alarm that restarts the service if it died. Chunk 10. */
class HealthCheckReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) = Unit
}
