// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core.data.settings

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.mauri.sukrara.core.model.storage.SettingSpec
import app.mauri.sukrara.settings.NumberSpec
import app.mauri.sukrara.settings.SettingInfo
import app.mauri.sukrara.settings.SettingsSpec

/**
 * One Android setting of THE schema: its Preferences DataStore file ([store]), [key] and [default]. Built only by
 * [SchemaSettings] from the generated `SettingsSpec`, so store and key are exactly the schema's.
 */
class Setting<T : Any> internal constructor(
    /** The schema id, e.g. `graph.hours`. */
    val id: String,
    /** The DataStore file (`datastore/<store>.preferences_pb`). */
    val store: String,
    override val key: String,
    override val default: T,
    internal val preferenceKey: Preferences.Key<T>,
    /** Read values pass through this (number settings snap to their format; others keep the value). */
    internal val sanitize: (T) -> T,
) : SettingSpec<T> {
    override fun toString() = "Setting($store:$key)"
}

/**
 * Looks settings up by schema id. Feature modules declare theirs as e.g.
 * `val GraphHours = SchemaSettings.number("graph.hours")` or `SchemaSettings.bool("sharing.nostr", default = true)`.
 * Bool / enum defaults live in code (the schema has formats only for numbers); `SettingsSchemaTest` checks every
 * id resolves.
 */
object SchemaSettings {
    /** A number setting; default and valid values from its schema format (stored units). */
    fun number(id: String): Setting<Double> {
        val info = androidInfo(id, "number")
        val format: NumberSpec = SettingsSpec.NUMBERS.getValue(checkNotNull(info.number) { "$id has no number format" })
        val (store, key) = storeKey(info)
        return Setting(id, store, key, format.default, doublePreferencesKey(key), format::coerce)
    }

    fun bool(
        id: String,
        default: Boolean,
    ): Setting<Boolean> {
        val (store, key) = storeKey(androidInfo(id, "bool"))
        return Setting(id, store, key, default, booleanPreferencesKey(key)) { it }
    }

    /**
     * A text setting: an enum (stored by name, unknown names read as [default]) or a JSON value whose decoding the
     * owning module handles. [allowed] = the accepted values for an enum, empty for free text / JSON.
     */
    fun text(
        id: String,
        default: String,
        allowed: Set<String> = emptySet(),
    ): Setting<String> {
        val (store, key) = storeKey(androidInfo(id, null))
        require(allowed.isEmpty() || default in allowed) { "$id default '$default' not in $allowed" }
        return Setting(id, store, key, default, stringPreferencesKey(key)) { value ->
            if (allowed.isEmpty() || value in allowed) value else default
        }
    }

    /**
     * One place's key of a per-place number setting (`graph.hours.place`: the schema's comma list holds one key per
     * place, in the order of its `places.android`). [index] = the place's position in that list.
     */
    fun numberAt(
        id: String,
        index: Int,
    ): Setting<Double> {
        val info = androidInfo(id, "number")
        val format: NumberSpec = SettingsSpec.NUMBERS.getValue(checkNotNull(info.number) { "$id has no number format" })
        val (store, key) = placeKey(info, index)
        return Setting(id, store, key, format.default, doublePreferencesKey(key), format::coerce)
    }

    /** One place's key of a per-place bool setting (`graph.sensorTime.place`), like [numberAt]. */
    fun boolAt(
        id: String,
        index: Int,
        default: Boolean,
    ): Setting<Boolean> {
        val (store, key) = placeKey(androidInfo(id, "bool"), index)
        return Setting(id, store, key, default, booleanPreferencesKey(key)) { it }
    }

    private fun placeKey(
        info: SettingInfo,
        index: Int,
    ): Pair<String, String> {
        val (store, keys) = info.android.split(":", limit = 2)
        val key = keys.split(',').getOrNull(index)
        requireNotNull(key) { "'${info.id}' has no key for place $index ($keys)" }
        return store to key
    }

    private fun androidInfo(
        id: String,
        type: String?,
    ): SettingInfo {
        val info = requireNotNull(SettingsSpec.SETTINGS.firstOrNull { it.id == id }) { "'$id' is not in the settings schema" }
        require(type == null || info.type == type) { "'$id' is a ${info.type}, not a $type" }
        require(info.android.isNotEmpty()) { "'$id' is not an Android setting" }
        return info
    }

    /** `store:key` of a single-key Android setting (groups with several keys are owned by their module). */
    private fun storeKey(info: SettingInfo): Pair<String, String> {
        val (store, key) = info.android.split(":", limit = 2)
        require(',' !in key) { "'${info.id}' spans several keys ($key): declare each through its owning module" }
        return store to key
    }
}
