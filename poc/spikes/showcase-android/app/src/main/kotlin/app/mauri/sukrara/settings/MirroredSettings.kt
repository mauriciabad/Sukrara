// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.settings

import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.alerts.Kind
import app.mauri.sukrara.core.SensorLife
import app.mauri.sukrara.graph.GraphConfig
import org.json.JSONObject

/**
 * The settings a follower can mirror (spikes/settings/settings.json rows with `"mirror": true`, from [SettingsSpec]) and
 * their wire form in the wearer's settings event (PROTOCOL-v2 "Settings"): `"graph"` = [GraphConfig] JSON keys (plus
 * `countdownHours`, SensorLife), `"alerts"` = SPEC §3 paths. `{kind}` expands over the 8 kinds without no data.
 */
object MirroredSettings {
    /** Graph keys (`graph.X` → X), [COUNTDOWN] included. */
    val GRAPH_KEYS: List<String>
    /** Paths inside the SPEC §3 JSON (`alerts.a.b.c` → [a, b, c]). */
    val ALERT_PATHS: List<List<String>>
    const val COUNTDOWN = "countdownHours"

    init {
        val wires = SettingsSpec.SETTINGS.filter { it.mirror }.mapNotNull { it.wire }.flatMap { w ->
            if ("{kind}" in w) Kind.ALL.filter { it != Kind.NO_DATA }.map { w.replace("{kind}", it) } else listOf(w)
        }
        GRAPH_KEYS = wires.filter { it.startsWith("graph.") }.map { it.removePrefix("graph.") }
        ALERT_PATHS = wires.filter { it.startsWith("alerts.") }.map { it.removePrefix("alerts.").split(".") }
        require(GRAPH_KEYS.size + ALERT_PATHS.size == wires.size) { "a mirrored wire key outside graph./alerts.: $wires" }
    }

    /** `{"graph":{…},"alerts":{…}}` with only the mirrored keys of these settings. */
    fun body(graph: GraphConfig, countdownHours: Int, alerts: AlertSettings): JSONObject {
        val g = graph.toJson().put(COUNTDOWN, countdownHours)
        val outG = JSONObject()
        for (k in GRAPH_KEYS) if (g.has(k)) outG.put(k, g.get(k))
        val a = alerts.toJson()
        val outA = JSONObject()
        for (p in ALERT_PATHS) get(a, p)?.let { set(outA, p, it) }
        return JSONObject().put("graph", outG).put("alerts", outA)
    }

    /** [body] of the defaults ("Settings from: Default"). */
    fun defaults(): JSONObject = body(GraphConfig(), SensorLife.DEFAULT_COUNTDOWN_HOURS, AlertSettings())

    /** The settings event's content: `{"v":2,"type":"settings","u":…,"graph":{…},"alerts":{…}}`. */
    fun content(body: JSONObject, u: Long): JSONObject = JSONObject().put("v", 2).put("type", "settings").put("u", u)
        .put("graph", body.optJSONObject("graph") ?: JSONObject()).put("alerts", body.optJSONObject("alerts") ?: JSONObject())

    /** [own] with every mirrored graph field from [src] (a `"graph"` object; a missing key = the default). */
    fun graph(own: GraphConfig, src: JSONObject?): GraphConfig {
        val d = GraphConfig().toJson()
        val j = own.toJson()
        for (k in GRAPH_KEYS) if (k != COUNTDOWN) j.put(k, src?.opt(k) ?: d.get(k))
        return GraphConfig.fromJson(j)
    }

    /** The countdown hours in [src] (a `"graph"` object), or the default. */
    fun countdown(src: JSONObject?): Int =
        SensorLife.validCountdown(src?.optInt(COUNTDOWN, SensorLife.DEFAULT_COUNTDOWN_HOURS) ?: SensorLife.DEFAULT_COUNTDOWN_HOURS)

    /** [own] with every mirrored alert field from [src] (an `"alerts"` object; a missing path = the default). */
    fun alerts(own: AlertSettings, src: JSONObject?): AlertSettings {
        val d = AlertSettings().toJson()
        val j = own.toJson()
        for (p in ALERT_PATHS) set(j, p, src?.let { get(it, p) } ?: get(d, p)!!)
        return AlertSettings.fromJson(j)
    }

    private fun get(o: JSONObject, p: List<String>): Any? {
        var cur: Any = o
        for (k in p) cur = (cur as? JSONObject)?.opt(k) ?: return null
        return cur
    }

    private fun set(o: JSONObject, p: List<String>, v: Any) {
        var cur = o
        for (k in p.dropLast(1)) cur = cur.optJSONObject(k) ?: JSONObject().also { cur.put(k, it) }
        cur.put(p.last(), v)
    }
}
