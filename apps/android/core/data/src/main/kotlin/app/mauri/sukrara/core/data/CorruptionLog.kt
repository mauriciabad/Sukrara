// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core.data

import app.mauri.sukrara.core.data.files.FileStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.time.Instant

/** A stored file that couldn't be read. The damaged bytes are kept at [quarantinedTo] for the report. */
data class CorruptFile(
    /** Which store: a file name, a settings store, a secret name. */
    val store: String,
    /** The kept copy of the damaged bytes; null when the damaged file was already gone. */
    val quarantinedTo: File?,
    val at: Instant,
    /** The decoder's error type, never the content. */
    val reason: String,
)

/**
 * Every corrupt file not yet acknowledged. `AppGraph.startupState` reads it and the home screen shows a visible
 * problem with "Copy report" while it is not empty: data loss is never silent. Reports survive the process: the kept
 * copies (`*.corrupt-<epochMs>`) are found again by [rescan] at every start until the person acknowledges them.
 */
class CorruptionLog {
    private val found = MutableStateFlow<List<CorruptFile>>(emptyList())
    val files: StateFlow<List<CorruptFile>> = found.asStateFlow()

    fun report(file: CorruptFile) {
        found.update { current ->
            if (file.quarantinedTo != null && current.any { it.quarantinedTo == file.quarantinedTo }) current else current + file
        }
    }

    /** Reports every kept copy under [roots] (a previous process found them; nobody acknowledged them yet). */
    fun rescan(roots: List<File>) {
        roots
            .filter { it.isDirectory }
            .flatMap { root -> root.walkTopDown().filter { it.isFile && QUARANTINE_MARK in it.name } }
            .forEach { copy ->
                val store = copy.name.substringBefore(QUARANTINE_MARK)
                val at =
                    copy.name
                        .substringAfter(QUARANTINE_MARK)
                        .toLongOrNull()
                        ?.let(Instant::ofEpochMilli) ?: Instant.EPOCH
                report(CorruptFile(store, copy, at, "found at start"))
            }
    }

    /** The person has seen and copied the report: the copy is renamed `*.seen-<epochMs>` (kept, not re-reported). */
    fun acknowledge(file: CorruptFile) {
        file.quarantinedTo?.let { copy -> copy.renameTo(File(copy.parentFile, copy.name.replace(QUARANTINE_MARK, SEEN_MARK))) }
        found.update { it - file }
    }

    private companion object {
        const val QUARANTINE_MARK = FileStore.QUARANTINE_MARK
        const val SEEN_MARK = ".seen-"
    }
}
