// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core

import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.util.Log

/**
 * Debug builds only: StrictMode (disk and network on the main thread → logcat `StrictMode`) and a watchdog that logs
 * the main thread's stack (`SukraraBlock`) whenever it stays busy for more than 1 s, to find what an ANR waits on
 * without root (inv. 29 round: emulator ANRs in OwnerService / onStartJob).
 */
object MainWatchdog {
    fun start() {
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog().build())
        val main = Looper.getMainLooper().thread
        val handler = Handler(Looper.getMainLooper())
        Thread({
            while (true) {
                val done = java.util.concurrent.atomic.AtomicBoolean(false)
                val posted = System.currentTimeMillis()
                handler.post { done.set(true) }
                Thread.sleep(1000)
                var reported = false
                while (!done.get()) {
                    if (!reported) {
                        Log.w("SukraraBlock", "main thread busy > ${System.currentTimeMillis() - posted} ms:\n" +
                            main.stackTrace.take(40).joinToString("\n") { "  at $it" })
                        reported = true
                    }
                    Thread.sleep(250)
                }
            }
        }, "main-watchdog").apply { isDaemon = true }.start()
    }
}
