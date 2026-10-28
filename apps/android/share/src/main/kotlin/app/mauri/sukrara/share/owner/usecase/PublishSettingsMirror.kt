// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.owner.usecase

import app.mauri.sukrara.protocol.Wire
import app.mauri.sukrara.protocol.event.SettingsPayload
import app.mauri.sukrara.settings.SettingsSpec
import app.mauri.sukrara.share.owner.OwnerState
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The wearer's own settings as the settings layer keeps them: [graph] = the graph settings under their wire keys
 * (PROTOCOL-v2 §Settings), [alerts] = the whole alert settings in the alerts SPEC §3 shape. What goes out is chosen by
 * [SettingsMirror.select], never here.
 */
data class WearerSettings(val graph: JsonObject, val alerts: JsonObject)

/** The wearer's settings, updated on every change (the app's settings layer provides it). */
fun interface MirroredSettingsSource {
    fun settings(): Flow<WearerSettings>

    companion object {
        /** No settings layer composed: nothing is published (followers keep their defaults, never made-up values). */
        val None = MirroredSettingsSource { kotlinx.coroutines.flow.emptyFlow() }
    }
}

/**
 * Which settings followers mirror: exactly the rows of THE schema (contracts/settings/settings.json) with
 * `"mirror": true`, at their `wire` path; `{kind}` = the alert kinds other than `no_data`. Everything else (sounds,
 * the notification actions, the alerts main switch, no data) stays on the wearer's phone.
 */
object SettingsMirror {
    /** contracts/alerts/SPEC.md kinds, without `no_data` (per device, never mirrored). */
    val MIRRORED_ALERT_KINDS = listOf("very_low", "low", "high", "very_high", "extremely_high", "falling_fast", "rising_fast")

    /** Wire paths (`graph.low`, `alerts.alerts.low.mgdl`, …) of the mirrored rows, in schema order. */
    val paths: List<List<String>> = SettingsSpec.SETTINGS.filter { it.mirror }.mapNotNull { it.wire }.flatMap { wire ->
        if ("{kind}" in wire) MIRRORED_ALERT_KINDS.map { wire.replace("{kind}", it) } else listOf(wire)
    }.distinct().map { it.split('.') }

    fun select(s: WearerSettings): WearerSettings {
        val root = JsonObject(mapOf("graph" to s.graph, "alerts" to s.alerts))
        var out = JsonObject(mapOf("graph" to JsonObject(emptyMap()), "alerts" to JsonObject(emptyMap())))
        for (path in paths) at(root, path)?.let { out = put(out, path, it) }
        return WearerSettings(out.getValue("graph").jsonObject, out.getValue("alerts").jsonObject)
    }

    private fun at(o: JsonObject, path: List<String>): JsonElement? =
        path.dropLast(1).fold(o as JsonObject?) { cur, key -> cur?.get(key) as? JsonObject }?.get(path.last())

    private fun put(o: JsonObject, path: List<String>, value: JsonElement): JsonObject {
        val key = path.first()
        val next = if (path.size == 1) value else put(o[key] as? JsonObject ?: JsonObject(emptyMap()), path.drop(1), value)
        return JsonObject(o + (key to next))
    }
}

/**
 * The settings event (PROTOCOL-v2 §Settings), signed by D under the current epoch. `u` moves only when the mirrored
 * content changed (followers take the newest `u`); start, a new epoch and the weekly refresh send the same content
 * with the same `u`. `created_at` strictly increases per publish (replaceable event).
 */
class PublishSettingsMirror(private val ctx: OwnerContext) {
    /** The wearer's settings changed (debounced by the engine): an event only when the mirrored part changed. */
    fun onChange(s: OwnerState, settings: WearerSettings, now: Long): Step {
        val selected = SettingsMirror.select(settings)
        val json = JsonObject(mapOf("graph" to selected.graph, "alerts" to selected.alerts)).toString()
        if (json == s.settingsJson) return Step(s)
        return event(s.copy(settingsJson = json, settingsU = maxOf(now, s.settingsU + 1)), now)
    }

    /** The last published content again (start, new epoch, weekly); nothing before the first change. */
    fun republish(s: OwnerState, now: Long): Step = if (s.settingsJson == null) Step(s) else event(s, now)

    fun republishDue(s: OwnerState, now: Long): Boolean = s.settingsJson != null && now - s.settingsPublishedAt >= 7 * Wire.DAY

    private fun event(s: OwnerState, now: Long): Step {
        val content = Json.parseToJsonElement(s.settingsJson!!).jsonObject
        val payload = SettingsPayload(s.settingsU, content.getValue("graph").jsonObject, content.getValue("alerts").jsonObject)
        val at = maxOf(now, s.settingsPublishedAt + 1)
        val ev = ctx.events.settings(s.device(ctx.crypto), s.readKey(ctx.crypto).public, s.sid, payload, at)
        return Step(s.copy(settingsPublishedAt = at), listOf(ev))
    }
}
