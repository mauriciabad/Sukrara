// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core.testing

import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.DispatcherProvider
import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.Treatment
import app.mauri.sukrara.core.model.storage.ReadingsRepository
import app.mauri.sukrara.core.model.storage.SecureStore
import app.mauri.sukrara.core.model.storage.SettingSpec
import app.mauri.sukrara.core.model.storage.SettingsRepository
import app.mauri.sukrara.core.model.storage.StateStore
import app.mauri.sukrara.core.model.storage.TreatmentRecord
import app.mauri.sukrara.core.model.storage.TreatmentsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

/** A clock tests move by hand; the zone is settable to test travel and DST. */
class FakeClock(
    var instant: Instant = Instant.parse("2026-01-15T12:00:00Z"),
    override var zone: ZoneId = ZoneOffset.UTC,
) : Clock {
    override fun now(): Instant = instant

    fun advance(by: Duration) {
        instant += by
    }
}

/** Every dispatcher is the same test dispatcher, so `runTest` controls all of them. */
class TestDispatchers(val dispatcher: TestDispatcher = StandardTestDispatcher()) : DispatcherProvider {
    override val main: CoroutineDispatcher get() = dispatcher
    override val default: CoroutineDispatcher get() = dispatcher
    override val io: CoroutineDispatcher get() = dispatcher
}

class InMemorySettingsRepository : SettingsRepository {
    private val values = MutableStateFlow<Map<String, Any?>>(emptyMap())

    @Suppress("UNCHECKED_CAST")
    override fun <T> flow(spec: SettingSpec<T>): Flow<T> =
        values.map { if (spec.key in it) it[spec.key] as T else spec.default }

    override suspend fun <T> set(
        spec: SettingSpec<T>,
        value: T,
    ) {
        values.update { it + (spec.key to value) }
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T> update(
        spec: SettingSpec<T>,
        transform: (T) -> T,
    ) {
        values.update { it + (spec.key to transform(if (spec.key in it) it[spec.key] as T else spec.default)) }
    }
}

class InMemoryStateStore<T>(initial: T) : StateStore<T> {
    private val state = MutableStateFlow(initial)
    override val data: Flow<T> = state

    override suspend fun update(transform: (T) -> T) {
        state.update(transform)
    }
}

class InMemorySecureStore : SecureStore {
    private val entries = ConcurrentHashMap<String, ByteArray>()

    override suspend fun read(name: String): ByteArray? = entries[name]?.copyOf()

    override suspend fun write(
        name: String,
        bytes: ByteArray,
    ) {
        entries[name] = bytes.copyOf()
    }

    override suspend fun delete(name: String) {
        entries.remove(name)
    }
}

class InMemoryReadingsRepository : ReadingsRepository {
    private val readings = MutableStateFlow<List<Reading>>(emptyList())

    override fun window(from: Instant): Flow<List<Reading>> = readings.map { all -> all.filter { it.t >= from } }

    override fun latest(): Flow<Reading?> = readings.map { it.lastOrNull() }

    override suspend fun range(
        from: Instant,
        to: Instant,
    ): List<Reading> = readings.value.filter { it.t >= from && it.t < to }

    override suspend fun upsert(readings: List<Reading>) {
        this.readings.update { current ->
            (current + readings).associateBy { it.t to it.source }.values.sortedBy { it.t }
        }
    }

    override suspend fun deleteBefore(cutoff: Instant): Int {
        val before = readings.value.size
        readings.update { all -> all.filter { it.t >= cutoff } }
        return before - readings.value.size
    }
}

class InMemoryTreatmentsRepository : TreatmentsRepository {
    private val records = MutableStateFlow<Map<String, TreatmentRecord>>(emptyMap())

    override fun between(
        from: Instant,
        to: Instant,
    ): Flow<List<Treatment>> = records.map { all ->
        all.values.map { it.treatment }.filter { !it.deleted && it.at >= from && it.at < to }.sortedBy { it.at }
    }

    override suspend fun get(id: String): TreatmentRecord? = records.value[id]

    override suspend fun upsertIfNewer(record: TreatmentRecord): Boolean {
        val stored = records.value[record.treatment.id]
        if (stored != null && stored.treatment.modifiedAt >= record.treatment.modifiedAt) return false
        records.update { it + (record.treatment.id to record) }
        return true
    }

    override suspend fun changedSince(since: Instant): List<TreatmentRecord> =
        records.value.values.filter { it.treatment.modifiedAt > since }.sortedBy { it.treatment.modifiedAt }
}
