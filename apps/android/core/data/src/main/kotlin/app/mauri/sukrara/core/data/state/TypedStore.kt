// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core.data.state

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import app.mauri.sukrara.core.data.CorruptFile
import app.mauri.sukrara.core.data.CorruptionLog
import app.mauri.sukrara.core.data.files.FileStore
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.storage.StateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * One typed state file (`files/state/<name>.json`): a DataStore with a kotlinx.serialization serializer.
 *
 * - Missing file: [data] starts at `initial`.
 * - Damaged file: it is copied to `<name>.json.corrupt-<epochMs>`, reported to the [CorruptionLog] and to this
 *   store's [corruption], and the store continues from `whenCorrupt` — a value the owning code chooses on purpose
 *   (there is no default), so it can tell "never set" from "lost" by watching [corruption].
 *
 * Built only through `DataModule.typedStore` (one instance per file, a DataStore rule).
 */
class TypedStore<T> internal constructor(
    val file: File,
    serializer: KSerializer<T>,
    initial: T,
    whenCorrupt: T,
    json: Json,
    private val log: CorruptionLog,
    private val clock: Clock,
    scope: CoroutineScope,
) : StateStore<T> {
    private val corrupted = MutableStateFlow<CorruptFile?>(null)

    /** Set when this store's file was found damaged in this process (the report the UI shows). */
    val corruption: StateFlow<CorruptFile?> = corrupted.asStateFlow()

    private val store: DataStore<T> = DataStoreFactory.create(
        serializer = JsonSerializer(serializer, initial, json),
        corruptionHandler = ReplaceFileCorruptionHandler { error ->
            quarantine(error)
            whenCorrupt
        },
        scope = scope,
        produceFile = { file },
    )

    override val data: Flow<T> = store.data

    override suspend fun update(transform: (T) -> T) {
        store.updateData { transform(it) }
    }

    private fun quarantine(error: CorruptionException) {
        val now = clock.now()
        val aside = FileStore.quarantineTarget(file, now.toEpochMilli())
        // copy, not move: DataStore replaces the file itself right after this handler
        val kept = file.takeIf { it.exists() }?.copyTo(aside, overwrite = true)
        val report = CorruptFile(store = file.name, quarantinedTo = kept, at = now, reason = error.message.orEmpty())
        corrupted.value = report
        log.report(report)
    }
}

/** kotlinx JSON <-> DataStore. Undecodable content is a [CorruptionException]; real I/O errors pass through. */
internal class JsonSerializer<T>(
    private val serializer: KSerializer<T>,
    override val defaultValue: T,
    private val json: Json,
) : Serializer<T> {
    override suspend fun readFrom(input: InputStream): T {
        val text = input.readBytes().decodeToString()
        return try {
            json.decodeFromString(serializer, text)
        } catch (e: SerializationException) {
            throw CorruptionException("undecodable ${serializer.descriptor.serialName}", e)
        } catch (e: IllegalArgumentException) {
            throw CorruptionException("invalid ${serializer.descriptor.serialName}", e)
        }
    }

    override suspend fun writeTo(
        t: T,
        output: OutputStream,
    ) {
        output.write(json.encodeToString(serializer, t).encodeToByteArray())
    }
}
