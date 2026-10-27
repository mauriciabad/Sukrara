// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.cgmnostr

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** One glucose reading. [t] unix seconds, [sgv] mg/dL, [rate] mg/dL/min (null = unknown). */
data class Reading(val t: Long, val sgv: Int, val rate: Double?)

data class RelayStats(
    val url: String,
    val connected: Boolean = false,
    val ok: Int = 0,
    val fail: Int = 0,
    val dropped: Int = 0,
    val lastError: String? = null,
    val pausedUntilMs: Long = 0,
    val lastLiveOkMs: Long? = null,   // reading-arrival → relay OK latency of the last live event
    val connects: Int = 0,
    val queued: Int = 0,
)

data class AppStatus(
    val juggluco: String = "not started",
    val jugglucoError: String? = null,
    val last: Reading? = null,
    val lastReceivedMs: Long = 0,
    val readingsKnown: Int = 0,
    val liveSent: Int = 0,
    val chunksSent: Int = 0,
    val relays: Map<String, RelayStats> = emptyMap(),
    val npub: String? = null,
    val followerLink: String? = null,
    val ncryptsec: String? = null,
    val log: List<String> = emptyList(),
)

/** Process-wide status shared by the service (writer) and the activity (reader). */
object Status {
    const val TAG = "CgmNostr"
    private val _state = MutableStateFlow(AppStatus())
    val state: StateFlow<AppStatus> = _state

    fun update(f: (AppStatus) -> AppStatus) = _state.update(f)

    fun relay(url: String, f: (RelayStats) -> RelayStats) =
        _state.update { s -> s.copy(relays = s.relays + (url to f(s.relays[url] ?: RelayStats(url)))) }

    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun log(msg: String) {
        Log.i(TAG, msg)
        val line = "${synchronized(fmt) { fmt.format(Date()) }} $msg"
        _state.update { it.copy(log = (listOf(line) + it.log).take(40)) }
    }
}
