// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.mauri.sukrara.core.data.CorruptFile
import app.mauri.sukrara.core.data.CorruptionLog
import app.mauri.sukrara.core.data.files.FileStore
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.storage.SettingSpec
import app.mauri.sukrara.core.model.storage.SettingsRepository
import app.mauri.sukrara.settings.SettingsSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * The Preferences DataStore files, one per schema store (`SettingsSpec.Stores`), each created once (DataStore allows
 * one instance per file). A damaged file is copied aside and reported, then its settings read as defaults.
 */
class PreferenceStores(
    private val dir: File,
    private val corruption: CorruptionLog,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {
    private val known: Set<String> = schemaStores()
    private val opened = mutableMapOf<String, DataStore<Preferences>>()

    @Synchronized
    fun store(name: String): DataStore<Preferences> {
        require(name in known) { "'$name' is not a settings store of the schema" }
        return opened.getOrPut(name) {
            val file = File(dir, "$name.preferences_pb")
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { error ->
                    val now = clock.now()
                    val copy = file.takeIf { it.exists() }?.copyTo(FileStore.quarantineTarget(file, now.toEpochMilli()), overwrite = true)
                    corruption.report(CorruptFile("settings:$name", copy, now, error.message.orEmpty()))
                    emptyPreferences()
                },
                scope = scope,
                produceFile = { file },
            )
        }
    }
}

/** The Android stores the schema names (`store:key` of every Android setting). */
fun schemaStores(): Set<String> =
    SettingsSpec.SETTINGS.mapNotNull { info -> info.android.substringBefore(':').takeIf { it.isNotEmpty() } }.toSet()

/** [SettingsRepository] on [PreferenceStores]. Accepts only [Setting]s (store and key from the schema). */
class DataStoreSettingsRepository(private val stores: PreferenceStores) : SettingsRepository {
    override fun <T> flow(spec: SettingSpec<T>): Flow<T> {
        val setting = spec.asSetting()
        return stores.store(setting.store).data
            .map { prefs -> prefs[setting.preferenceKey]?.let(setting.sanitize) ?: setting.default }
            .distinctUntilChanged()
    }

    override suspend fun <T> set(
        spec: SettingSpec<T>,
        value: T,
    ) {
        val setting = spec.asSetting()
        stores.store(setting.store).updateData { prefs ->
            prefs.toMutablePreferences().apply { this[setting.preferenceKey] = setting.sanitize(requireNotNull(value)) }
        }
    }

    override suspend fun <T> update(
        spec: SettingSpec<T>,
        transform: (T) -> T,
    ) {
        val setting = spec.asSetting()
        stores.store(setting.store).updateData { prefs ->
            val current = prefs[setting.preferenceKey]?.let(setting.sanitize) ?: setting.default
            prefs.toMutablePreferences().apply { this[setting.preferenceKey] = setting.sanitize(requireNotNull(transform(current))) }
        }
    }

    /** Back to the default (the key is removed, so a later default change applies). */
    suspend fun <T> reset(spec: SettingSpec<T>) {
        val setting = spec.asSetting()
        stores.store(setting.store).updateData { prefs ->
            prefs.toMutablePreferences().apply { remove(setting.preferenceKey) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> SettingSpec<T>.asSetting(): Setting<T & Any> =
        requireNotNull(this as? Setting<*>) { "$this is not a schema Setting: build it with SchemaSettings" } as Setting<T & Any>
}
