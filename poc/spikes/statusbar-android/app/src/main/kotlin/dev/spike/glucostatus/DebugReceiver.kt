// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.glucostatus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.update

/**
 * adb-driven experiments (protected by DUMP, which only the shell holds):
 *   am broadcast -n dev.spike.glucostatus/.DebugReceiver --ez chip false --es hookPosition icons:0
 *   am broadcast -n dev.spike.glucostatus/.DebugReceiver --ei mgdl 187 --ef rate -1.2
 *   am broadcast -n dev.spike.glucostatus/.DebugReceiver --ez restartSystemUi true   (root; loads hook changes)
 * Boolean extras use the [Config] field names. An injected reading is pinned for --ei pinSec (default 120 s).
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.getBooleanExtra("restartSystemUi", false)) {
            // A kill, not `am crash`: crashes count towards Android's rescue escalation.
            resultData = runCatching {
                ProcessBuilder("su", "-c", "kill $(pidof com.android.systemui)").redirectErrorStream(true).start()
                    .inputStream.bufferedReader().readText().ifEmpty { "killed" }
            }.getOrElse { "no root: ${it.message}" }
            return
        }
        var cfg = Config.load(c)
        fun b(key: String, cur: Boolean) = if (i.hasExtra(key)) i.getBooleanExtra(key, cur) else cur
        cfg = cfg.copy(demo = b("demo", cfg.demo), chip = b("chip", cfg.chip), metricStyle = b("metricStyle", cfg.metricStyle),
            yieldChip = b("yieldChip", cfg.yieldChip), dynamicIcon = b("dynamicIcon", cfg.dynamicIcon), slot = b("slot", cfg.slot),
            hook = b("hook", cfg.hook), hookPosition = i.getStringExtra("hookPosition") ?: cfg.hookPosition)
        cfg.save(c)
        if (i.hasExtra("mgdl")) {
            val r = Reading(System.currentTimeMillis() / 1000 - i.getIntExtra("ageSec", 0), i.getIntExtra("mgdl", 100),
                if (i.hasExtra("rate")) i.getFloatExtra("rate", 0f).toDouble() else null)
            Hub.state.update { it.copy(reading = r, pinnedUntilMs = System.currentTimeMillis() + i.getIntExtra("pinSec", 120) * 1000L) }
        }
        if (i.getBooleanExtra("stop", false)) StatusService.stop(c) else StatusService.start(c)
        resultData = cfg.toString()
    }
}
