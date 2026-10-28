// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor

import android.content.BroadcastReceiver
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs [block] off the main thread within the broadcast's time ([BroadcastReceiver.goAsync]: 10 s), then finishes it.
 * A failure or a timeout is logged under [what] (an action name, never data) and never takes the process (and the
 * monitoring service) down. The one helper of every monitor and alert receiver.
 */
internal fun BroadcastReceiver.runAsync(
    what: String,
    block: suspend () -> Unit,
) {
    val pending = goAsync()
    receiverScope.launch {
        try {
            withTimeoutOrNull(RECEIVER_BUDGET_MS) { block() } ?: Log.w(RECEIVER_TAG, "$what timed out")
        } catch (e: Exception) {
            Log.w(RECEIVER_TAG, "$what failed: ${e.javaClass.simpleName}")
        } finally {
            pending.finish()
        }
    }
}

/** The alert check's share of [RECEIVER_BUDGET_MS] (alarm, boot): the service start after it always gets the rest. */
internal const val ALERT_CHECK_BUDGET_MS = 6_000L

/** Below the 10 s a receiver may run after goAsync. */
private const val RECEIVER_BUDGET_MS = 9_000L
private const val RECEIVER_TAG = "Sukrara.receiver"

/**
 * The receivers' process-wide scope. It can't come from the app graph's dispatchers: a receiver is often the first
 * thing a cold process runs, and it must be able to start before the graph is ready.
 */
private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
