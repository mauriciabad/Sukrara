// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core.data.secure

import app.mauri.sukrara.core.data.CorruptFile
import app.mauri.sukrara.core.data.CorruptionLog
import app.mauri.sukrara.core.data.files.FileStore
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.storage.SecretUnreadableException
import app.mauri.sukrara.core.model.storage.SecureStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** The secrets the app keeps, one Keystore key (alias `sukrara.<name>`) and one file each. */
object SecretName {
    const val IDENTITY_OWNER = "identity.owner"
    const val IDENTITY_FOLLOWER = "identity.follower"
    const val NIGHTSCOUT = "nightscout"
    const val TIDEPOOL = "tidepool"
    const val LIBREVIEW = "libreview"
    const val SENSOR = "sensor"

    val ALL = setOf(IDENTITY_OWNER, IDENTITY_FOLLOWER, NIGHTSCOUT, TIDEPOOL, LIBREVIEW, SENSOR)

    fun alias(name: String) = "sukrara.$name"
}

/**
 * [SecureStore] in `files/secure/<name>.bin` = format byte ‖ [SecretBox] output. Unknown names are refused, so every
 * secret is listed in [SecretName] (and docs/ARCHITECTURE.md "Storage").
 *
 * A file that can't be opened is copied aside (`<name>.bin.corrupt-<epochMs>`) and reported, and [read] throws
 * [SecretUnreadableException] — now and on every later read, because the file stays in place: an unreadable identity
 * never turns into "no identity". Only a deliberate [write] (restore) or [delete] by the person replaces it. A
 * transient Keystore error throws too, without a report. Keys are never deleted: one key per purpose is reused.
 */
class KeystoreSecureStore(
    private val dir: File,
    private val box: SecretBox,
    private val corruption: CorruptionLog,
    private val clock: Clock,
    private val io: CoroutineDispatcher,
) : SecureStore {
    private val lock = Mutex()
    private val reported = mutableSetOf<String>()

    override suspend fun read(name: String): ByteArray? = withContext(io) {
        lock.withLock {
            val file = fileOf(name)
            if (!file.exists()) return@withLock null
            val bytes = file.readBytes()
            if (bytes.isEmpty() || bytes[0] != FORMAT) {
                throw unreadable(name, file, SecretOpenException(transient = false, "unknown format"))
            }
            try {
                box.open(SecretName.alias(name), bytes.copyOfRange(1, bytes.size), name.encodeToByteArray())
            } catch (e: SecretOpenException) {
                throw unreadable(name, file, e)
            }
        }
    }

    override suspend fun write(
        name: String,
        bytes: ByteArray,
    ): Unit = withContext(io) {
        lock.withLock {
            val sealed = box.seal(SecretName.alias(name), bytes, name.encodeToByteArray())
            FileStore.writeAtomically(fileOf(name), byteArrayOf(FORMAT) + sealed)
            reported.remove(name)
        }
    }

    override suspend fun delete(name: String): Unit = withContext(io) {
        lock.withLock {
            val file = fileOf(name)
            if (file.exists() && !file.delete()) throw IOException("can't delete secret $name")
            reported.remove(name)
        }
    }

    private fun fileOf(name: String): File {
        require(name in SecretName.ALL) { "unknown secret '$name': add it to SecretName" }
        return File(dir, "$name.bin")
    }

    private fun unreadable(
        name: String,
        file: File,
        error: SecretOpenException,
    ): SecretUnreadableException {
        if (!error.transient && name !in reported) {
            val now = clock.now()
            val copy = file.copyTo(FileStore.quarantineTarget(file, now.toEpochMilli()), overwrite = true)
            reported.add(name)
            corruption.report(CorruptFile(store = "secret:$name", quarantinedTo = copy, at = now, reason = error.message.orEmpty()))
        }
        return SecretUnreadableException(name, error.transient, error)
    }

    private companion object {
        const val FORMAT: Byte = 1
    }
}
