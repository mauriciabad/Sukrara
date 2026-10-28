// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core.data.settings

import app.mauri.sukrara.core.model.storage.SettingSpec
import app.mauri.sukrara.core.model.storage.SettingsRepository
import app.mauri.sukrara.settings.SettingsSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * A follower's *Settings from* (schema `follower.settingsFrom`): where the settings it can mirror (the schema rows with
 * `"mirror": true`) come from. [Wearer]: the wearer's own, from their settings event (the defaults until it arrives);
 * [Default]: Sukrara's defaults; [Custom]: this phone's own. Everything else is always this phone's.
 */
enum class SettingsFrom(val wire: String) {
    Wearer("wearer"),
    Default("default"),
    Custom("custom"),
    ;

    companion object {
        val Stored = SchemaSettings.text("follower.settingsFrom", Wearer.wire, entries.map { it.wire }.toSet())

        fun fromWire(s: String): SettingsFrom = entries.firstOrNull { it.wire == s } ?: Wearer
    }
}

/**
 * The settings as a follower uses them: a [SettingsRepository] over this phone's own ([own]) whose mirrored graph rows
 * (schema `"mirror": true` with a `graph.*` wire key: ranges, time shown, rings, slots, lines, treatments on the graph,
 * the sensor countdown) read per [from] — [SettingsFrom.Custom] this phone's value, [SettingsFrom.Default] the
 * setting's default, [SettingsFrom.Wearer] the wearer's value at its wire key ([graph] = the settings event's
 * `graph` object, null = none received), the default when missing or of the wrong type, coerced like a stored value.
 * Every other setting, and every write, goes to [own] (the pages edit this phone's values). The wearer's phone passes
 * [SettingsFrom.Custom].
 */
class FollowerSettingsRepository(
    private val own: SettingsRepository,
    private val from: Flow<SettingsFrom>,
    private val graph: Flow<JsonObject?>,
) : SettingsRepository {
    override fun <T> flow(spec: SettingSpec<T>): Flow<T> {
        val setting = spec as? Setting<*>
        val wire = setting?.let { MirroredGraph.wireKey(it.id) } ?: return own.flow(spec)
        val mine: Flow<T> = own.flow(spec)
        return combine(from, graph, mine) { f: SettingsFrom, g: JsonObject?, m: T -> MirroredGraph.value(spec, wire, f, g, m) }
            .distinctUntilChanged()
    }

    override suspend fun <T> set(
        spec: SettingSpec<T>,
        value: T,
    ) = own.set(spec, value)

    override suspend fun <T> update(
        spec: SettingSpec<T>,
        transform: (T) -> T,
    ) = own.update(spec, transform)
}

/** The mirrored graph rows of THE schema and how a value is taken from the wearer's `graph` object. */
object MirroredGraph {
    /** schema id → key inside the settings event's `graph` object (`graph.band.low` → `low`). */
    private val wireKeys: Map<String, String> =
        SettingsSpec.SETTINGS
            .filter { it.mirror && it.android.isNotEmpty() && it.wire?.startsWith(PREFIX) == true }
            .associate { it.id to it.wire!!.removePrefix(PREFIX) }

    fun wireKey(id: String): String? = wireKeys[id]

    /** Every mirrored graph setting's schema id. */
    val ids: Set<String> get() = wireKeys.keys

    /**
     * The wearer's side: [own]'s values of [specs] as the settings event's `graph` object (wire key → value), updated on
     * every change. Whole numbers go out without a fraction (`54`, as the Mac and the contracts write them).
     */
    fun graphObject(
        own: SettingsRepository,
        specs: List<Setting<*>>,
    ): Flow<JsonObject> {
        val rows: List<Flow<Pair<String, JsonPrimitive?>>> =
            specs.mapNotNull { spec -> wireKey(spec.id)?.let { wire -> own.flow(spec).map { wire to encode(it) } } }
        return combine(rows) { pairs -> JsonObject(pairs.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()) }
            .distinctUntilChanged()
    }

    /** A stored value as it goes out (null: a type the wire doesn't carry). */
    fun encode(value: Any?): JsonPrimitive? =
        when (value) {
            is Double -> if (value % 1.0 == 0.0 && kotlin.math.abs(value) < 1e15) JsonPrimitive(value.toLong()) else JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is String -> JsonPrimitive(value)
            else -> null
        }

    /** The value [spec] has for [from]: [mine] (Custom), its default (Default) or the wearer's at [wire] (Wearer). */
    fun <T> value(
        spec: SettingSpec<T>,
        wire: String,
        from: SettingsFrom,
        graph: JsonObject?,
        mine: T,
    ): T =
        when (from) {
            SettingsFrom.Custom -> mine
            SettingsFrom.Default -> spec.default
            SettingsFrom.Wearer -> graph?.get(wire)?.let { decode(spec, it) } ?: spec.default
        }

    /**
     * Writes [from]'s values into this phone's own settings ("Copy the wearer's settings" with [SettingsFrom.Wearer],
     * "Reset to default" with [SettingsFrom.Default]); [specs] = the mirrored settings to write.
     */
    suspend fun copyInto(
        own: SettingsRepository,
        specs: List<Setting<*>>,
        from: SettingsFrom,
        graph: JsonObject?,
    ) {
        for (spec in specs) write(own, spec, from, graph)
    }

    private suspend fun <T : Any> write(
        own: SettingsRepository,
        spec: Setting<T>,
        from: SettingsFrom,
        graph: JsonObject?,
    ) {
        val wire = wireKey(spec.id) ?: return
        own.update(spec) { mine -> value(spec, wire, from, graph, mine) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> decode(
        spec: SettingSpec<T>,
        e: JsonElement,
    ): T? {
        val p = e as? JsonPrimitive ?: return null
        val raw: Any? =
            when (spec.default) {
                is Double -> if (p.isString) null else p.doubleOrNull
                is Boolean -> if (p.isString) null else p.booleanOrNull
                is String -> if (p.isString) p.content else null
                else -> null
            }
        if (raw == null) return null
        val setting = spec as? Setting<Any> ?: return raw as T
        return setting.sanitize(raw) as T
    }

    private const val PREFIX = "graph."
}
