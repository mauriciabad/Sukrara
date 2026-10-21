// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import android.content.Context
import app.mauri.sukrara.R

/**
 * What the screens say when something fails: a sentence in the app's language, never an exception's class name or
 * raw message (validation 21: the home panel printed "Juggluco unreachable — ConnectException: Failed to connect to
 * /127.0.0.1:17580"). The technical text stays in the log and the DebugReceiver's state.
 */
object UserErrors {
    /** The glucose source's problem (OwnerEngine / Live `source` + `sourceError`), or null when there is none. */
    fun source(ctx: Context, state: String, error: String?): String? {
        if (error == null) return null
        val s = state.removePrefix("direct: ")
        return ctx.getString(when {
            s == "Juggluco unreachable" -> R.string.source_problem_juggluco
            s == "live stream ended" -> R.string.source_problem_juggluco_stream
            s == "waiting for Bluetooth" -> R.string.source_problem_bluetooth_off
            s == "no sensor" -> R.string.source_problem_no_sensor
            s == "sensor ended" -> R.string.source_problem_sensor_ended
            s == "unsupported sensor" -> R.string.source_problem_unsupported
            s == "reconnecting" -> R.string.source_problem_reconnecting
            error.contains("Nearby devices") -> R.string.source_problem_permission
            else -> R.string.source_problem_other
        })
    }

    /** A failure as one sentence: the network's usual problems by kind; our own messages as they are; else generic. */
    fun of(ctx: Context, e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> ctx.getString(R.string.error_no_server)
        is java.net.SocketTimeoutException -> ctx.getString(R.string.error_timeout)
        is java.net.ConnectException, is java.net.NoRouteToHostException -> ctx.getString(R.string.error_cannot_connect)
        is javax.net.ssl.SSLException -> ctx.getString(R.string.error_secure_connection)
        is org.json.JSONException -> ctx.getString(R.string.error_unexpected_answer)
        is java.io.IOException -> ctx.getString(R.string.error_network)
        else -> e.message?.takeIf { readable(it) } ?: ctx.getString(R.string.error_generic)
    }

    /** A message written for people (ours), not a library's technical text ("Failed to connect to /127.0.0.1…"). */
    internal fun readable(m: String): Boolean = m.isNotBlank() && m.length <= 160 &&
        !Regex("""\w+(Exception|Error)\b|\bjava\.|\bandroid\.|/\d{1,3}\.\d|:\d{2,5}\b|\bat [\w.$]+\(""").containsMatchIn(m)
}
