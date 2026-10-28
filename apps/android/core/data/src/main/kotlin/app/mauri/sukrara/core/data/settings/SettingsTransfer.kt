// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core.data.settings

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.mauri.sukrara.settings.SettingsSpec
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** What [SettingsTransfer.import] did. */
sealed interface ImportResult {
    /** [count] settings were applied (the file's others were unknown or invalid and left as they were). */
    data class Done(val count: Int) : ImportResult

    /** Not a Sukrara settings file (or not JSON): nothing changed. */
    data object NotSettings : ImportResult
}

/**
 * Settings › Advanced › Settings: the person's options as one JSON file (export / import) and back to the defaults
 * (reset), driven by THE schema (`SettingsSpec.SETTINGS`): every Android setting is carried unless [KEPT] names it with
 * the reason. Identity, followers, the sensor pairing and the accounts are not settings of the schema and are never
 * touched. Each value goes through the same checks as reading it (a number snaps to its format, an unknown enum reads
 * as its default); [normalize] lets the owning module check a JSON value (the alert settings) before it is written.
 */
class SettingsTransfer(
    private val stores: PreferenceStores,
    private val appVersion: String,
    /** `store:key` → its accepted form, or null to refuse it (e.g. the alert settings through their codec). */
    private val normalize: Map<String, (String) -> String?> = emptyMap(),
) {
    /** One stored key of a carried setting. */
    internal data class Entry(val id: String, val type: String, val store: String, val key: String)

    /** Writes the file's text. */
    suspend fun export(): String {
        val stored =
            entries().groupBy { it.store }.toSortedMap().mapValues { (store, list) ->
                val prefs =
                    stores
                        .store(store)
                        .data
                        .first()
                        .asMap()
                        .mapKeys { it.key.name }
                list.mapNotNull { e -> prefs[e.key]?.let { e.key to it } }
            }
        val root =
            buildJsonObject {
                put("kind", KIND)
                put("v", VERSION)
                put("app", appVersion)
                putJsonObject("settings") {
                    for ((store, values) in stored) {
                        if (values.isEmpty()) continue
                        putJsonObject(store) { for ((key, v) in values) put(key, toJson(v)) }
                    }
                }
            }
        return pretty.encodeToString(JsonObject.serializer(), root)
    }

    /**
     * Applies a file from [export]: each setting it has replaces the current one; settings it doesn't have keep
     * their values. A new alert settings value also clears "thresholds confirmed" so the review is asked again.
     */
    suspend fun import(text: String): ImportResult {
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return ImportResult.NotSettings
        if ((root["kind"] as? JsonPrimitive)?.content != KIND) return ImportResult.NotSettings
        val settings = runCatching { root["settings"]?.jsonObject }.getOrNull() ?: return ImportResult.Done(0)
        var count = 0
        var alertsChanged = false
        for ((store, list) in entries().groupBy { it.store }) {
            val values = runCatching { settings[store]?.jsonObject }.getOrNull() ?: continue
            stores.store(store).updateData { prefs ->
                prefs.toMutablePreferences().apply {
                    for (e in list) {
                        val value = values[e.key] ?: continue
                        if (write(this, e, value)) {
                            count++
                            if (e.store == ALERTS_STORE && e.key == ALERTS_KEY) alertsChanged = true
                        }
                    }
                }
            }
        }
        if (alertsChanged) clearConfirmation()
        return ImportResult.Done(count)
    }

    /** Every carried setting back to its default (its key is removed, so a later default change applies). */
    suspend fun reset() {
        for ((store, list) in entries().groupBy { it.store }) {
            stores.store(store).updateData { prefs ->
                prefs.toMutablePreferences().apply { list.forEach { e -> asMap().keys.filter { it.name == e.key }.forEach { remove(it) } } }
            }
        }
        clearConfirmation()
    }

    private fun write(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        e: Entry,
        value: JsonElement,
    ): Boolean {
        when (e.type) {
            "number" -> {
                val d = (value as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return false
                if (!d.isFinite()) return false
                val format =
                    SettingsSpec.SETTINGS
                        .first { it.id == e.id }
                        .number
                        ?.let(SettingsSpec.NUMBERS::get)
                prefs[doublePreferencesKey(e.key)] = format?.coerce(d) ?: d
            }

            "bool" -> {
                val b = (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: return false
                prefs[booleanPreferencesKey(e.key)] = b
            }

            else -> {
                val raw = if (value is JsonPrimitive && value.isString) value.content else value.toString()
                val accepted = normalize["${e.store}:${e.key}"]?.let { check -> check(raw) ?: return false } ?: raw
                prefs[stringPreferencesKey(e.key)] = accepted
            }
        }
        return true
    }

    private suspend fun clearConfirmation() {
        val (store, key) = storeKey(CONFIRMED_ID) ?: return
        stores.store(store).updateData { prefs ->
            prefs.toMutablePreferences().apply { asMap().keys.filter { it.name == key }.forEach { remove(it) } }
        }
    }

    private fun toJson(v: Any): JsonElement =
        when (v) {
            is Boolean -> {
                JsonPrimitive(v)
            }

            is Number -> {
                JsonPrimitive(v)
            }

            is String -> {
                // a JSON value (the alert settings) stays readable in the file
                v
                    .takeIf { it.startsWith("{") || it.startsWith("[") }
                    ?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } ?: JsonPrimitive(v)
            }

            else -> {
                JsonPrimitive(v.toString())
            }
        }

    companion object {
        const val KIND = "sukrara-settings"
        const val VERSION = 1
        const val MIME = "application/json"

        private const val ALERTS_STORE = "alerts"
        private const val ALERTS_KEY = "alerts"
        private const val CONFIRMED_ID = "alerts.thresholdsConfirmedAt"

        /** Android settings the file doesn't carry and reset doesn't touch, with why. */
        val KEPT: Map<String, String> =
            mapOf(
                "source.source" to "where glucose comes from is the setup: switching it would drop the sensor",
                CONFIRMED_ID to "a record of the person's review, not an option (cleared when the alert settings change)",
                "home.uploadBanner" to "a closed tip, not an option (Settings › Show closed tips)",
                "sharing.nostr" to "who receives the data: changed only on the Sharing page, with the followers in view",
                "sharing.bleFollowers" to "who receives the data: changed only on the Sharing page, with the followers in view",
                "apps.xdrip" to "a connection to another app: set up on its page",
                "apps.nightscout" to "a connection to another app: set up on its page",
                "apps.nightscoutLan" to "opens the phone to the home network: set up on its page",
                "apps.libreview" to "an account (the clinic's copy): set up on its page",
                "apps.tidepool" to "an account: set up on its page",
                "apps.nightscoutUpload" to "an account: set up on its page",
                "apps.broadcasts" to "which apps on this phone may read glucose: chosen per app",
                "sensorCalendar.on" to "needs this phone's calendar permission and calendar",
                "sensorCalendar.calendarId" to "a calendar of this phone",
                "healthConnect.on" to "needs this phone's Health Connect permissions",
                "widget.overrides" to "per widget placed on this phone's home screen",
                "pens.pen" to "the pens this phone has read",
            )

        /** The stored keys the file carries (one per key of a per-place setting; settings sharing a key once). */
        internal fun entries(): List<Entry> =
            SettingsSpec.SETTINGS
                .filter { it.android.isNotEmpty() && it.id !in KEPT && it.type != "state" }
                .flatMap { info ->
                    val (store, keys) = info.android.split(":", limit = 2)
                    keys.split(',').map { Entry(info.id, info.type, store, it) }
                }.groupBy { it.store to it.key }
                // several settings in one key (the alert settings) are one JSON value
                .map { (_, same) -> same.singleOrNull() ?: same.first().copy(type = "json") }

        private fun storeKey(id: String): Pair<String, String>? =
            SettingsSpec.SETTINGS
                .firstOrNull { it.id == id }
                ?.android
                ?.split(":", limit = 2)
                ?.takeIf { it.size == 2 }
                ?.let { it[0] to it[1] }

        private val pretty = Json { prettyPrint = true }
    }
}
