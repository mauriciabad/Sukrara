// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.glucowidgets

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Fallback when the live service isn't running (e.g. a background FGS start was refused): every 15 min (the
 * WorkManager minimum) pull the history and refresh. Cheap, but the widget can be up to 15 min old.
 */
class RefreshWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result {
        if (LiveService.running.value) return Result.success()
        val cfg = Config.load(applicationContext)
        runCatching {
            if (cfg.source == Config.SOURCE_SYNTHETIC) {
                val src = SyntheticSource(cfg.phaseMin, cfg.freezeAgeMin)
                val last = (nowSec() / 60 - cfg.freezeAgeMin) * 60
                GlucoseStore.add(applicationContext, (0 until 30).map { src.at(last - it * 60L) })
            } else {
                GlucoseStore.add(applicationContext, JugglucoSource.history(30))
            }
        }
        WidgetUpdater.refresh(applicationContext, "worker")
        return Result.success()
    }

    companion object {
        private const val NAME = "widget-refresh"

        fun schedule(c: Context) = WorkManager.getInstance(c).enqueueUniquePeriodicWork(NAME,
            ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES).build())

        fun cancel(c: Context) = WorkManager.getInstance(c).cancelUniqueWork(NAME)
    }
}
