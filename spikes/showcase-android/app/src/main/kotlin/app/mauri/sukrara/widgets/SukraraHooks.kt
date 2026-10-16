// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.widgets

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.mauri.sukrara.App
import app.mauri.sukrara.core.Reading as CoreReading
import app.mauri.sukrara.owner.OwnerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Glue between investigation 12's widgets and Sukrara: readings from the wearer pipeline go into the widgets'
 * small persisted store ([GlucoseStore], last 8 h) and trigger [WidgetUpdater.refresh] (deduped; skipped while the
 * screen is off). Widget receivers make sure the wearer service runs.
 */
object SukraraHooks {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun onReadings(c: Context, list: List<CoreReading>, reason: String) {
        if (list.isEmpty()) return
        val changed = GlucoseStore.add(c, list.map { Reading(it.t, it.sgv, it.rate) })
        if (changed) refresh(c, reason)
    }

    fun refresh(c: Context, reason: String, force: Boolean = false) {
        scope.launch { runCatching { WidgetUpdater.refresh(c.applicationContext, reason, force) }.onFailure { Log.w("Sukrara.widgets", "refresh: $it") } }
    }

    /** Android 15+ picker previews; once per app version (the system rate-limits the call). */
    fun publishPreviewsOnce(c: Context) {
        val p = c.getSharedPreferences("widgets", Context.MODE_PRIVATE)
        val v = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode }.getOrDefault(0)
        if (p.getLong("previewsFor", -1) == v) return
        scope.launch {
            val r = runCatching { WidgetUpdater.publishPreviews(c.applicationContext) }.getOrElse { it.message ?: "?" }
            Log.i("Sukrara.widgets", "previews: $r")
            p.edit().putLong("previewsFor", v).apply()
        }
    }

    /** A widget was added/updated: the wearer service feeds it, so make sure it runs (wearer role only). */
    fun ensureService(c: Context) {
        runCatching {
            if (App.role(c) == "owner" && App.owner(c).hasIdentity()) c.startForegroundService(Intent(c, OwnerService::class.java))
        }.onFailure { Log.w("Sukrara.widgets", "service not started from widget: $it") }
        refresh(c, "widget", force = true)
    }
}

/** Restart the wearer service after a reboot or an app update (both are allowed to start a foreground service). */
class StartReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED && i.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        runCatching {
            if (App.role(c) == "owner" && App.owner(c).hasIdentity()) c.startForegroundService(Intent(c, OwnerService::class.java))
        }.onFailure { Log.w("Sukrara.start", "service not started on ${i.action}: $it") }
    }
}
