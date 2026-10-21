// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * Followers (round f28): where the settings a follower can mirror come from — schema `follower.settingsFrom`
 * (spikes/settings/settings.json, the entries with `"mirror": true`): [DEFAULT] (the schema's defaults), [WEARER]
 * (the wearer's own, from their settings event, PROTOCOL-v2 "Settings"; the default) or [CUSTOM] (this device's).
 * Everything else (sounds, like an alarm, the notification's Mute / Slow down, no data, per-place windows and sensor
 * time, the alerts main switch) is always this device's.
 *
 * Wire content of the wearer's event (`"graph"` = GraphConfig JSON keys, `"alerts"` = SPEC §3 keys), only the mirrored
 * keys: `{"v":2,"type":"settings","u":unix_s,"graph":{"veryLow",…,"countdownHours"},"alerts":{"alerts":{kind:{"on",
 * "mgdl"|"rate","repeatMin"}},"muteAfterInsulin","muteFallingHigh","muteAfterCarbs"}}`.
 */
object WearerSettings {
    const val DEFAULT = "default"
    const val WEARER = "wearer"
    const val CUSTOM = "custom"
    val MODES = listOf(WEARER, DEFAULT, CUSTOM)

    /** The wearer's latest settings ([u] = when the wearer changed them, unix s). */
    data class Received(val graph: JSONObject, val alerts: JSONObject, val u: Long, val at: Long)

    private const val PREFS = "wearer-settings"
    private var prefs: SharedPreferences? = null
    private val _mode = MutableStateFlow(WEARER)
    val mode: StateFlow<String> = _mode
    private val _wearer = MutableStateFlow<Received?>(null)
    val wearer: StateFlow<Received?> = _wearer

    @Synchronized fun init(c: Context) {
        if (prefs != null) return
        val p = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _mode.value = p.getString("settingsFrom", WEARER)?.takeIf { it in MODES } ?: WEARER
        _wearer.value = p.getString("content", null)?.let { runCatching { parse(JSONObject(it), p.getLong("at", 0)) }.getOrNull() }
    }

    fun setMode(m: String) {
        if (m !in MODES) return
        prefs?.edit()?.putString("settingsFrom", m)?.apply()
        _mode.value = m
    }

    /** The decrypted content of a wearer's settings event; kept when newer than the one held. Returns whether it was. */
    @Synchronized fun received(content: JSONObject, atMs: Long = System.currentTimeMillis()): Boolean {
        if (content.optString("type") != "settings") return false
        val r = runCatching { parse(content, atMs) }.getOrNull() ?: return false
        val cur = _wearer.value
        if (cur != null && r.u <= cur.u) return false
        prefs?.edit()?.putString("content", content.toString())?.putLong("at", atMs)?.apply()
        _wearer.value = r
        return true
    }

    private fun parse(o: JSONObject, at: Long) =
        Received(o.optJSONObject("graph") ?: JSONObject(), o.optJSONObject("alerts") ?: JSONObject(), o.optLong("u"), at)

    /** For tests: forget everything. */
    @Synchronized fun resetForTest() { prefs = null; _mode.value = WEARER; _wearer.value = null }
}
