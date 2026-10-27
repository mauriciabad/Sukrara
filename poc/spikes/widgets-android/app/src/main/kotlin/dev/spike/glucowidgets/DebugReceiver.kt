// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.glucowidgets

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * adb-only (protected by android.permission.DUMP, which only the shell holds):
 *
 *   adb shell am broadcast -n dev.spike.glucowidgets/.DebugReceiver --es cmd stats
 *   … --es cmd source --es value synthetic|juggluco
 *   … --es cmd scenario --ei phaseMin 40 --ei freezeAgeMin 12      (synthetic; freezeAgeMin > 0 = stale)
 *   … --es cmd refresh        (forced render)
 *   … --es cmd previews       (publish generated previews, Android 15+)
 *   … --es cmd screenOffSkip --ez value false
 *   … --es cmd start | stop   (live service)
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val result = try {
                run(c.applicationContext, intent)
            } catch (e: Exception) {
                "error: $e"
            }
            pending.resultData = result
            pending.finish()
        }
    }

    private suspend fun run(c: Context, i: Intent): String {
        when (i.getStringExtra("cmd")) {
            "source" -> {
                Config.load(c).copy(source = i.getStringExtra("value") ?: Config.SOURCE_JUGGLUCO).save(c)
                LiveService.tryStart(c, "debug", restart = true)
            }
            "scenario" -> {
                Config(Config.SOURCE_SYNTHETIC, i.getIntExtra("phaseMin", 0), i.getIntExtra("freezeAgeMin", 0)).save(c)
                LiveService.tryStart(c, "debug", restart = true)
            }
            "refresh" -> WidgetUpdater.refresh(c, "debug", force = true)
            "previews" -> return WidgetUpdater.publishPreviews(c)
            "screenOffSkip" -> WidgetUpdater.skipWhileScreenOff = i.getBooleanExtra("value", true)
            "start" -> LiveService.tryStart(c, "debug")
            "stop" -> LiveService.stop(c)
        }
        val s = WidgetUpdater.stats.value
        val last = GlucoseStore.history.value.lastOrNull()
        return "config=${Config.load(c)} service=${LiveService.running.value} status=${LiveService.status.value} " +
            "widgets=${LiveService.widgetCount(c)} last=$last renders=${s.renders} skippedSame=${s.skippedSame} " +
            "skippedScreenOff=${s.skippedScreenOff} lastReason=${s.lastReason} lastRenderMs=${s.lastRenderMs} " +
            "screenOffSkip=${WidgetUpdater.skipWhileScreenOff}"
    }
}
