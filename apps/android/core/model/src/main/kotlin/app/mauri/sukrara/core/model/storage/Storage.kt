// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core.model.storage

import app.mauri.sukrara.core.model.Reading
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/*
 * Storage ports. They live in this JVM module so pure engines and the in-memory fakes (`:core:testing`) can use
 * them; `:core:data` implements them on Android (DataStore, Keystore, Room).
 */

/** One setting of THE schema (`contracts/settings/settings.json`); the generated `SettingsSpec` lists them all. */
interface SettingSpec<T> {
    /** The schema key, also the Preferences DataStore key. */
    val key: String
    val default: T
}

/** Settings in Preferences DataStore, keyed by the schema. Unset settings read as their default. */
interface SettingsRepository {
    fun <T> flow(spec: SettingSpec<T>): Flow<T>

    suspend fun <T> set(
        spec: SettingSpec<T>,
        value: T,
    )
}

/** One typed state file (typed DataStore with kotlinx.serialization). Updates are atomic. */
interface StateStore<T> {
    val data: Flow<T>

    suspend fun update(transform: (T) -> T)
}

/**
 * Small secrets (identity key, sensor pairing secrets), AES-GCM encrypted with an Android Keystore key
 * (alias `sukrara.<name>`) in `files/secure/<name>.bin`. Never in backups, never logged.
 */
interface SecureStore {
    suspend fun read(name: String): ByteArray?

    suspend fun write(
        name: String,
        bytes: ByteArray,
    )

    suspend fun delete(name: String)
}

/** Glucose history (Room). Upserting the same timestamp and source replaces the value. */
interface ReadingsRepository {
    /** Readings at or after [from], oldest first, updated as new ones arrive. */
    fun window(from: Instant): Flow<List<Reading>>

    suspend fun upsert(readings: List<Reading>)
}
