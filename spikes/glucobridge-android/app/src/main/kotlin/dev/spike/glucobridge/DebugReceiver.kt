// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.glucobridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Lets the spike's experiments be driven from `adb shell` (the receiver requires DUMP, which the shell
 * has and ordinary apps can't get). Logs a JSON line per command to logcat tag GlucoBridge.
 *
 *   am broadcast -n dev.spike.glucobridge/.DebugReceiver -a dev.spike.glucobridge.ADD --es kind rapid --es amount 0.5 --es note "spike"
 *   … -a dev.spike.glucobridge.DELETE --es id <uuid>
 *   … -a dev.spike.glucobridge.CONFIG --ez upload true --ez glucose false --ez takeover false [--el glucoseFrom <epoch s>]
 *   … -a dev.spike.glucobridge.DUMP     (prints every treatment)
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Spike.init(context)
        val store = Spike.store
        val up = Spike.uploader
        when (intent.action?.substringAfterLast('.')) {
            "ADD" -> {
                val tr = store.add(intent.getStringExtra("kind") ?: "note", intent.getStringExtra("amount")?.toDoubleOrNull() ?: 0.0,
                    intent.getStringExtra("t")?.toLongOrNull() ?: (System.currentTimeMillis() / 1000), intent.getStringExtra("note") ?: "")
                Log.i("GlucoBridge", "debug add ${tr.id}")
            }
            "EDIT" -> {
                val id = intent.getStringExtra("id") ?: return
                val cur = store.all().firstOrNull { it.id == id } ?: return
                store.merge(cur.copy(a = intent.getStringExtra("amount")?.toDoubleOrNull() ?: cur.a, n = intent.getStringExtra("note") ?: cur.n,
                    u = maxOf(System.currentTimeMillis(), cur.u + 1), o = store.origin))
            }
            "DELETE" -> intent.getStringExtra("id")?.let { store.delete(it) }
            "CONFIG" -> up.configure(
                upload = if (intent.hasExtra("upload")) intent.getBooleanExtra("upload", false) else null,
                glucose = if (intent.hasExtra("glucose")) intent.getBooleanExtra("glucose", false) else null,
                takeover = if (intent.hasExtra("takeover")) intent.getBooleanExtra("takeover", false) else null,
                glucoseFrom = if (intent.hasExtra("glucoseFrom")) intent.getLongExtra("glucoseFrom", 0) else null)
            "DUMP" -> {
                store.all().forEach { Log.i("GlucoBridge", "debug row ${dev.spike.glucobridge.treatments.TreatmentJson.encodeToString(dev.spike.glucobridge.treatments.Treatment.serializer(), it)}") }
                Log.i("GlucoBridge", "debug status ${up.status.value} seq=${store.seq} device=${up.deviceId}")
            }
        }
        up.kick()
    }
}
