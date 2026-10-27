// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core.data

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
    /** Where the damaged bytes were moved; null when they couldn't be moved (they stay in place, untouched). */
    val quarantinedTo: File?,
    val at: Instant,
    /** The decoder's error type, never the content. */
    val reason: String,
)

/**
 * Every corrupt file found since the process started. `AppGraph.startupState` reads it and the home screen shows a
 * visible problem with "Copy report" while it is not empty: data loss is never silent.
 */
class CorruptionLog {
    private val found = MutableStateFlow<List<CorruptFile>>(emptyList())
    val files: StateFlow<List<CorruptFile>> = found.asStateFlow()

    fun report(file: CorruptFile) {
        found.update { it + file }
    }

    /** The person has seen and copied the report. */
    fun acknowledge(file: CorruptFile) {
        found.update { it - file }
    }
}
