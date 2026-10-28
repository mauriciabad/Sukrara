// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.systemui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.mauri.sukrara.monitor.monitorHost
import app.mauri.sukrara.monitor.runAsync

/**
 * System UI (re)started with Sukrara's module and asks for the state ([SystemUiHook]'s hello). Exported, but only a
 * sender holding `STATUS_BAR_SERVICE` (System UI) reaches it (manifest); it reads only the code and one flag.
 */
class HookHelloReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != SystemUi.actionHello(context.packageName)) return
        val code = intent.getStringExtra(SystemUi.EXTRA_CODE)?.take(32)
        val iconOrder = intent.getBooleanExtra(SystemUi.EXTRA_ICON_ORDER, false)
        runAsync("hello") {
            val host = context.monitorHost
            host.awaitReady()
            host.monitor.rootStatusBar.onHello(code, iconOrder)
        }
    }
}
