// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.powerlab

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** The plan's alarm. Snapshots take ~5 s and scenario switches up to ~1 min, so hold a wakelock meanwhile. */
class StepReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val pending = goAsync()
        val wl = Lab.wakeLock(c).apply { acquire(3 * 60_000L) }
        Thread {
            try { Lab.step(c) } catch (t: Throwable) { Lab.log(c, "step failed: $t") } finally { wl.release(); pending.finish() }
        }.start()
    }
}

/**
 * adb control (DUMP permission: only the shell can send these):
 *   am broadcast -n dev.spike.powerlab/.ControlReceiver --es cmd start --ei settle 3 --ei measure 30 --ei delay 60
 *   am broadcast -n dev.spike.powerlab/.ControlReceiver --es cmd stop|status
 */
class ControlReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val pending = goAsync()
        Thread {
            try {
                when (i.getStringExtra("cmd")) {
                    "start" -> Lab.start(c, i.getIntExtra("settle", 3), i.getIntExtra("measure", 30), i.getIntExtra("delay", 60))
                    "stop" -> Lab.stop(c)
                }
                pending.resultData = Lab.status(c)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
