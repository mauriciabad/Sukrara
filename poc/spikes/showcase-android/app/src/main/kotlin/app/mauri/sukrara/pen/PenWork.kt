// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.pen

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.mauri.sukrara.treatments.Treat
import app.mauri.sukrara.treatments.Treatment
import java.util.concurrent.TimeUnit

/**
 * The pen's one timer: when the newest small doses stop waiting for their injection (PenEngine.nextWake → a question)
 * and when a pen entry's session closes (Treatment.heldUntil → the uploads that waited for it go out).
 */
class PenWork(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        Pens.refresh(applicationContext)
        Treat.uploader.kick()
        runCatching { app.mauri.sukrara.nightscout.Nightscout.kick() }
        runCatching { app.mauri.sukrara.tidepool.Tidepool.kick() }
        schedule(applicationContext)
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val nowS = System.currentTimeMillis() / 1000
            val wake = PenEngine.nextWake(Pens.ledger.value, Pens.rules())
            val settle = Treat.store.all().mapNotNull { it.heldUntil(nowS) }.minOrNull()
            val at = listOfNotNull(wake, settle).minOrNull() ?: return
            val req = OneTimeWorkRequestBuilder<PenWork>().setInitialDelay(maxOf(0, at - nowS + 5), TimeUnit.SECONDS).build()
            runCatching { WorkManager.getInstance(ctx).enqueueUniqueWork("pen", ExistingWorkPolicy.REPLACE, req) }
        }
    }
}
