// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.glucostatus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * App → SystemUI hook channel. A plain broadcast to SystemUI, guarded by a signature permission that only this
 * app holds, so no other app can feed the status bar. SystemUI says HELLO when it (re)starts so the value
 * appears immediately instead of at the next reading.
 */
object HookBridge {
    const val APP = "dev.spike.glucostatus"
    const val ACTION = "$APP.STATUS_BAR_GLUCOSE"
    const val ACTION_HELLO = "$APP.HOOK_HELLO"
    const val PERMISSION = "$APP.permission.STATUS_BAR_GLUCOSE"

    fun send(c: Context, cfg: Config, r: Reading?) {
        val i = Intent(ACTION).setPackage("com.android.systemui")
            .putExtra("enabled", cfg.hook)
            .putExtra("position", cfg.hookPosition)
        if (r != null) {
            i.putExtra("mgdl", r.mgdl).putExtra("epochSec", r.epochSec)
            r.rate?.let { i.putExtra("rate", it) }
        }
        c.sendBroadcast(i)
    }

    /** SystemUI (re)started with the hook loaded. */
    class HelloReceiver : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            Hub.note("SystemUI hook says hello")
            send(c, Config.load(c), Hub.state.value.reading)
        }
    }
}
