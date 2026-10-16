// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Per-role log: logcat (tag `Sukrara.owner` / `Sukrara.follower`, used as test evidence) + the last lines for the
 * UI. Never log secret keys: only public keys, gk tags, counts and decisions.
 */
class EventLog(role: String) {
    val tag = "Sukrara.$role"
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun i(msg: String) {
        Log.i(tag, msg)
        val line = "${synchronized(fmt) { fmt.format(Date()) }} $msg"
        _lines.update { (listOf(line) + it).take(60) }
    }
}
