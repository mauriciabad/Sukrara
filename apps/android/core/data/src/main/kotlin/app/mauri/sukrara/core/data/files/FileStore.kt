// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core.data.files

import app.mauri.sukrara.core.data.CorruptFile
import app.mauri.sukrara.core.data.CorruptionLog
import app.mauri.sukrara.core.model.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** The result of reading a stored file. */
sealed interface FileLoad<out T> {
    data class Loaded<T>(val value: T) : FileLoad<T>

    data object Missing : FileLoad<Nothing>

    /**
     * The file couldn't be decoded. It stays in place (every later load says Corrupt again, never Missing) and a copy
     * was kept at [quarantinedTo] and reported. Only a deliberate [FileStore.save] or [FileStore.delete] replaces it.
     */
    data class Corrupt(val quarantinedTo: File, val reason: String) : FileLoad<Nothing>
}

/** Encodes and decodes one file's content. [decode] throws (anything but [IOException]) on damaged content. */
interface FileCodec<T> {
    fun encode(value: T): ByteArray

    fun decode(bytes: ByteArray): T
}

/**
 * One file written atomically (temp file, fsync, rename): a crash mid-write leaves the old file. A file that can't be
 * decoded is copied to `<name>.corrupt-<epochMs>` (once per process) and reported to [corruption]; it stays in place,
 * so it never reads as Missing and is never overwritten with empty data by accident.
 * Real I/O errors (disk full, permissions) are thrown as [IOException] and change nothing.
 */
class FileStore<T>(
    val file: File,
    private val codec: FileCodec<T>,
    private val corruption: CorruptionLog,
    private val clock: Clock,
    private val io: CoroutineDispatcher,
) {
    private val lock = Mutex()
    private var quarantined: FileLoad.Corrupt? = null

    suspend fun load(): FileLoad<T> =
        withContext(io) {
            lock.withLock {
                if (!file.exists()) return@withLock FileLoad.Missing
                when (val decoded = decode(file.readBytes())) {
                    is Decoded.Ok -> FileLoad.Loaded(decoded.value)
                    is Decoded.Damaged -> quarantine(decoded.reason)
                }
            }
        }

    suspend fun save(value: T) =
        withContext(io) {
            lock.withLock {
                writeAtomically(file, codec.encode(value))
                quarantined = null
            }
        }

    suspend fun delete() =
        withContext(io) {
            lock.withLock {
                if (file.exists() && !file.delete()) throw IOException("can't delete ${file.name}")
                quarantined = null
            }
        }

    private sealed interface Decoded<out T> {
        data class Ok<T>(val value: T) : Decoded<T>

        data class Damaged(val reason: String) : Decoded<Nothing>
    }

    @Suppress("TooGenericExceptionCaught") // a decoder may throw any parse error; IOException is rethrown
    private fun decode(bytes: ByteArray): Decoded<T> =
        try {
            Decoded.Ok(codec.decode(bytes))
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            Decoded.Damaged(e.javaClass.simpleName)
        }

    private fun quarantine(reason: String): FileLoad.Corrupt {
        quarantined?.let { return it }
        val now = clock.now()
        val copy = file.copyTo(quarantineTarget(file, now.toEpochMilli()), overwrite = true)
        corruption.report(CorruptFile(store = file.name, quarantinedTo = copy, at = now, reason = reason))
        return FileLoad.Corrupt(copy, reason).also { quarantined = it }
    }

    companion object {
        /** Marks a kept copy of a damaged file; `DataModule.ready` re-reports copies the person hasn't acknowledged. */
        const val QUARANTINE_MARK = ".corrupt-"

        /** Where a damaged [file]'s copy is kept: `<name>.corrupt-<epochMs>`. */
        fun quarantineTarget(
            file: File,
            epochMs: Long,
        ): File = File(file.parentFile, "${file.name}$QUARANTINE_MARK$epochMs")

        /** Writes [bytes] to a temp file next to [target], syncs it to disk and renames it over [target]. */
        fun writeAtomically(
            target: File,
            bytes: ByteArray,
        ) {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.tmp")
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw IOException("can't rename ${tmp.name} to ${target.name}")
            }
        }
    }
}
