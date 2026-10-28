// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core.data

import android.content.Context
import androidx.room.Room
import app.mauri.sukrara.core.data.db.QuarantiningOpenHelperFactory
import app.mauri.sukrara.core.data.db.RoomReadingsRepository
import app.mauri.sukrara.core.data.db.RoomTreatmentsRepository
import app.mauri.sukrara.core.data.db.SukraraDatabase
import app.mauri.sukrara.core.data.diag.DiagLog
import app.mauri.sukrara.core.data.files.FileCodec
import app.mauri.sukrara.core.data.files.FileStore
import app.mauri.sukrara.core.data.json.SukraraJson
import app.mauri.sukrara.core.data.net.Http
import app.mauri.sukrara.core.data.secure.KeystoreSecretBox
import app.mauri.sukrara.core.data.secure.KeystoreSecureStore
import app.mauri.sukrara.core.data.secure.SecretBox
import app.mauri.sukrara.core.data.settings.DataStoreSettingsRepository
import app.mauri.sukrara.core.data.settings.PreferenceStores
import app.mauri.sukrara.core.data.settings.SettingsTransfer
import app.mauri.sukrara.core.data.state.TypedStore
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.DispatcherProvider
import app.mauri.sukrara.core.model.storage.ReadingsRepository
import app.mauri.sukrara.core.model.storage.SecureStore
import app.mauri.sukrara.core.model.storage.SettingsRepository
import app.mauri.sukrara.core.model.storage.TreatmentsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.time.Duration

/** How long history is kept. */
data class Retention(
    val readings: Duration = Duration.ofDays(90),
    val seenWraps: Duration = Duration.ofDays(14),
    val rejects: Duration = Duration.ofDays(14),
    val sentOutbox: Duration = Duration.ofDays(14),
)

/**
 * Storage and network for the whole app (docs/ARCHITECTURE.md "Storage"). `AppGraph` builds it on the main thread,
 * so construction does no I/O: every member is lazy and [ready] opens the database on the IO dispatcher.
 *
 * Layout under the app's private dir (none of it in backups):
 * - `datastore/<store>.preferences_pb`: settings, one file per schema store;
 * - `files/state/<name>.json`: typed state ([typedStore]);
 * - `files/secure/<name>.bin`: secrets ([secureStore]);
 * - `databases/sukrara.db`: readings, treatments, sharing ledger;
 * - `files/diag/`: the diagnostic log.
 */
class DataModule(
    private val context: Context,
    private val appVersion: String,
    private val dispatchers: DispatcherProvider,
    private val clock: Clock,
    private val retention: Retention = Retention(),
    private val secretBox: SecretBox = KeystoreSecretBox(),
) {
    /** Lives as long as the process: DataStore instances must never be closed and reopened on the same file. */
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val typedStores = mutableMapOf<String, TypedStore<*>>()

    /** Every damaged file found; non-empty = the home screen shows the problem. */
    val corruption = CorruptionLog()

    val json: Json = SukraraJson

    private val datastoreDir: File get() = File(context.filesDir.parentFile, "datastore")

    val http: OkHttpClient by lazy { Http.client(appVersion) }

    val diagLog: DiagLog by lazy { DiagLog(File(context.filesDir, "diag"), clock, dispatchers.io, scope) }

    private val preferenceStores by lazy { PreferenceStores(datastoreDir, corruption, clock, scope) }

    val settings: SettingsRepository by lazy { DataStoreSettingsRepository(preferenceStores) }

    /** Settings › Advanced's settings file; [normalize] checks JSON values by `store:key` (the alert settings). */
    fun settingsTransfer(
        normalize: Map<String, (String) -> String?>,
    ): SettingsTransfer = SettingsTransfer(preferenceStores, appVersion, normalize)

    val secureStore: SecureStore by lazy {
        KeystoreSecureStore(File(context.filesDir, "secure"), secretBox, corruption, clock, dispatchers.io)
    }

    val database: SukraraDatabase by lazy {
        Room
            .databaseBuilder(context, SukraraDatabase::class.java, SukraraDatabase.FILE)
            .openHelperFactory(QuarantiningOpenHelperFactory(::quarantineDatabase))
            .setQueryCoroutineContext(dispatchers.io)
            .build()
    }

    val readings: ReadingsRepository by lazy { RoomReadingsRepository(database.readings()) }

    val treatments: TreatmentsRepository by lazy { RoomTreatmentsRepository(database.treatments()) }

    /**
     * The typed state file `files/state/<name>.json`. [whenCorrupt] is what the store continues from if the file is
     * damaged (it is also reported); choose it on purpose. One call per name for the process.
     */
    @Synchronized
    fun <T> typedStore(
        name: String,
        serializer: KSerializer<T>,
        initial: T,
        whenCorrupt: T,
    ): TypedStore<T> {
        require(name !in typedStores) { "typed store '$name' already exists: share the instance" }
        val store = TypedStore(File(context.filesDir, "state/$name.json"), serializer, initial, whenCorrupt, json, corruption, clock, scope)
        typedStores[name] = store
        return store
    }

    /** A plain file under `files/` with atomic writes and quarantine on damage (for formats DataStore doesn't fit). */
    fun <T> fileStore(
        relativePath: String,
        codec: FileCodec<T>,
    ): FileStore<T> = FileStore(File(context.filesDir, relativePath), codec, corruption, clock, dispatchers.io)

    /**
     * Re-reports damaged files earlier runs kept and nobody acknowledged, opens the database and applies retention,
     * off the main thread. Call from `AppGraph.ready()`.
     */
    suspend fun ready() =
        withContext(dispatchers.io) {
            corruption.rescan(listOf(context.filesDir, datastoreDir, context.getDatabasePath(SukraraDatabase.FILE).parentFile!!))
            database.openHelper.writableDatabase
            prune()
        }

    /** SQLite found the database damaged and is about to recreate it: keep a copy (and its WAL) and report it. */
    private fun quarantineDatabase(db: File) {
        val now = clock.now()
        val kept =
            listOf(db, File(db.path + "-wal"))
                .filter { it.exists() }
                .map { it.copyTo(FileStore.quarantineTarget(it, now.toEpochMilli()), overwrite = true) }
        corruption.report(CorruptFile("database:${db.name}", kept.firstOrNull(), now, "sqlite corruption"))
    }

    /** Deletes history older than [retention]; pending outbox entries are never deleted. */
    suspend fun prune() {
        val now = clock.now()
        database.readings().deleteBefore((now - retention.readings).toEpochMilli())
        database.seenWraps().deleteBefore((now - retention.seenWraps).toEpochMilli())
        database.rejects().deleteBefore((now - retention.rejects).toEpochMilli())
        database.outbox().deleteSentBefore((now - retention.sentOutbox).toEpochMilli())
    }
}
