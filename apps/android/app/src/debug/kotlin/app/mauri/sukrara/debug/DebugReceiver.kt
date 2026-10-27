// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * adb commands for debug builds (this source set doesn't exist in release, so nothing here can fake data there):
 * `adb shell am broadcast -n app.mauri.sukrara/.debug.DebugReceiver -a <action> [extras]`.
 * Commands that replace the sensor or show made-up data go through [DebugFakes]; reading state changes nothing.
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action ?: return
        val handled = DebugFakes.run(context, action, intent)
        if (!handled) Log.w(TAG, "unknown debug action $action")
    }

    private companion object {
        const val TAG = "SukraraDebug"
    }
}

/** The registry of fake-data commands (synthetic source, test alerts, previews). Later chunks add entries. */
object DebugFakes {
    private val commands = mapOf<String, (Context, Intent) -> Unit>()

    /** Runs [action] when it is a known fake; false otherwise. */
    fun run(
        context: Context,
        action: String,
        intent: Intent,
    ): Boolean {
        val command = commands[action] ?: return false
        command(context, intent)
        return true
    }
}
