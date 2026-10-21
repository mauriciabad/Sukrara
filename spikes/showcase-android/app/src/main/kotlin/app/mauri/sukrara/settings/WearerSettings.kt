// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.settings

import android.content.Context
import android.content.SharedPreferences
import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.alerts.Alerts
import app.mauri.sukrara.core.SensorLife
import app.mauri.sukrara.graph.GraphConfig
import app.mauri.sukrara.graph.GraphSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
 * "mgdl"|"rate","repeatMin"}},"muteAfterInsulin","muteFallingHigh","muteAfterCarbs"}}` ([MirroredSettings]).
 *
 * Effective settings: on the wearer's role every store's effective value is its own; on a follower the mirrored fields
 * come from [mode] — [DEFAULT]: the defaults, [WEARER]: the wearer's content (defaults until it arrives), [CUSTOM]:
 * this device's own. [GraphSettings.state], [Alerts.settings] and [SensorLife.countdown] are the effective values;
 * [GraphSettings.own], [Alerts.own] and [SensorLife.ownCountdown] are this device's (what the editors change).
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
    private val _follower = MutableStateFlow(false)
    /** Whether this install follows someone (role "follower"): only then does [mode] apply. */
    val follower: StateFlow<Boolean> = _follower

    /** The wearer's own settings as last published ([outgoing]): the mirrored body and when it last changed. */
    private var sentBody: String? = null
    private var sentU = 0L

    @Synchronized fun init(c: Context) {
        if (prefs != null) return
        val p = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _mode.value = p.getString("settingsFrom", WEARER)?.takeIf { it in MODES } ?: WEARER
        _wearer.value = p.getString("content", null)?.let { runCatching { parse(JSONObject(it), p.getLong("at", 0)) }.getOrNull() }
        _follower.value = c.applicationContext.getSharedPreferences("role", Context.MODE_PRIVATE).getString("role", null) == "follower"
        sentBody = p.getString("sentBody", null)
        sentU = p.getLong("sentU", 0)
        changed()
    }

    fun setMode(m: String) {
        if (m !in MODES) return
        prefs?.edit()?.putString("settingsFrom", m)?.apply()
        _mode.value = m
        changed()
    }

    /** The install's role changed (App.setRole). */
    fun setFollower(on: Boolean) {
        if (_follower.value == on) return
        _follower.value = on
        changed()
    }

    /** Forget the wearer's settings (the follower left; the next wearer's may carry an older `u`). */
    @Synchronized fun forgetWearer() {
        prefs?.edit()?.remove("content")?.remove("at")?.apply()
        _wearer.value = null
        changed()
    }

    /** The decrypted content of a wearer's settings event; kept when newer than the one held. Returns whether it was. */
    @Synchronized fun received(content: JSONObject, atMs: Long = System.currentTimeMillis()): Boolean {
        if (content.optString("type") != "settings") return false
        val r = runCatching { parse(content, atMs) }.getOrNull() ?: return false
        val cur = _wearer.value
        if (cur != null && r.u <= cur.u) return false
        prefs?.edit()?.putString("content", content.toString())?.putLong("at", atMs)?.apply()
        _wearer.value = r
        changed()
        return true
    }

    // ------------------------------------------------------------------------------------------- effective settings

    /** Where a follower's mirrored fields come from: a `{"graph","alerts"}` body, or null = this device's own. */
    fun source(): JSONObject? = when {
        !_follower.value || _mode.value == CUSTOM -> null
        _mode.value == WEARER -> _wearer.value?.let { JSONObject().put("graph", it.graph).put("alerts", it.alerts) } ?: MirroredSettings.defaults()
        else -> MirroredSettings.defaults()
    }

    fun effectiveGraph(own: GraphConfig): GraphConfig = source()?.let { MirroredSettings.graph(own, it.optJSONObject("graph")) } ?: own

    /** The effective alert settings; `source` is derived from [mode] ([alertSource]). */
    fun effectiveAlerts(own: AlertSettings): AlertSettings =
        (source()?.let { MirroredSettings.alerts(own, it.optJSONObject("alerts")) } ?: own).copy(source = alertSource())

    fun effectiveCountdown(own: Int): Int = source()?.let { MirroredSettings.countdown(it.optJSONObject("graph")) } ?: own

    /** SPEC §3 `source`: "wearer" (mirror the wearer's alert events) in [WEARER] mode, else "own" (rules run here). */
    fun alertSource(): String = if (_mode.value == WEARER) "wearer" else "own"

    /**
     * "Copy the wearer's settings": the wearer's mirrored fields into this device's own (graph, alerts, countdown);
     * everything else stays. False (nothing changed) while no wearer settings have arrived.
     */
    fun copyWearerToCustom(ctx: Context): Boolean {
        val w = _wearer.value ?: return false
        writeOwn(ctx, w.graph, w.alerts)
        return true
    }

    /** "Reset to default": the mirrored fields of this device's own settings back to the defaults; the rest stays. */
    fun resetCustomToDefault(ctx: Context) {
        val d = MirroredSettings.defaults()
        writeOwn(ctx, d.getJSONObject("graph"), d.getJSONObject("alerts"))
    }

    private fun writeOwn(ctx: Context, graph: JSONObject, alerts: JSONObject) {
        GraphSettings.update { MirroredSettings.graph(it, graph) }
        Alerts.update(ctx) { MirroredSettings.alerts(it, alerts) }
        SensorLife.setCountdownHours(ctx, MirroredSettings.countdown(graph))
    }

    /** Every store's effective value again. */
    private fun changed() {
        GraphSettings.recompute()
        Alerts.recompute()
        SensorLife.recompute()
    }

    // ------------------------------------------------------------------------------------------- the wearer's side

    /** This device's mirrored body (as text) whenever it changes: what the wearer's phone publishes (OwnerEngine). */
    fun ownBodies(): Flow<String> = combine(GraphSettings.own, Alerts.own, SensorLife.ownCountdown) { g, a, c ->
        MirroredSettings.body(g, c, a).toString()
    }.distinctUntilChanged()

    /**
     * The content of the wearer's settings event (PROTOCOL-v2 "Settings") from its own settings: `u` = when the mirrored
     * body last changed (now when it differs from the one published before, else unchanged).
     */
    @Synchronized fun outgoing(graph: GraphConfig = GraphSettings.own.value, countdownHours: Int = SensorLife.ownCountdown.value,
                               alerts: AlertSettings = Alerts.own.value, nowS: Long = System.currentTimeMillis() / 1000): JSONObject {
        val body = MirroredSettings.body(graph, countdownHours, alerts)
        val text = body.toString()
        if (text != sentBody) {
            sentU = maxOf(nowS, sentU + 1)
            sentBody = text
            prefs?.edit()?.putString("sentBody", text)?.putLong("sentU", sentU)?.apply()
        }
        return MirroredSettings.content(body, sentU)
    }

    private fun parse(o: JSONObject, at: Long) =
        Received(o.optJSONObject("graph") ?: JSONObject(), o.optJSONObject("alerts") ?: JSONObject(), o.optLong("u"), at)

    /** For tests: forget everything. */
    @Synchronized fun resetForTest() {
        prefs = null; _mode.value = WEARER; _wearer.value = null; _follower.value = false; sentBody = null; sentU = 0
    }
}
