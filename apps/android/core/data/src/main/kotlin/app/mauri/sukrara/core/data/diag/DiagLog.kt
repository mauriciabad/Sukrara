// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core.data.diag

import app.mauri.sukrara.core.model.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * The app's diagnostic log (`files/diag/diag.log` + one rotated `diag.log.1`, each at most [maxBytes]): what the
 * "Copy report" button shares. Every module logs here instead of its own files.
 *
 * Rule for callers: log events and states, never glucose values, treatment amounts, keys, tokens or serials (free
 * text can't be scrubbed of those reliably: a number may be a glucose value or a retry count). [Redact] still scrubs
 * what looks like a key, token, secret query parameter or sensor serial, as a second line of defence.
 */
class DiagLog(
    private val dir: File,
    private val clock: Clock,
    io: CoroutineDispatcher,
    private val scope: CoroutineScope,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    /** One writer at a time, in call order. */
    private val writer = io.limitedParallelism(1)
    private val current = File(dir, NAME)
    private val previous = File(dir, "$NAME.1")

    /** Appends a line off the caller's thread. */
    fun log(
        tag: String,
        message: String,
    ) {
        val line = "${clock.now()} $tag ${Redact.scrub(message)}\n"
        scope.launch(writer) { append(line) }
    }

    /** Both files, oldest first (for "Copy report"). */
    suspend fun snapshot(): String = withContext(writer) {
        listOf(previous, current).filter { it.exists() }.joinToString("") { it.readText() }
    }

    /** A failing write (disk full) loses the line, never crashes the app. */
    private fun append(line: String) {
        val bytes = line.encodeToByteArray()
        try {
            dir.mkdirs()
            if (current.exists() && current.length() + bytes.size > maxBytes) {
                previous.delete()
                current.renameTo(previous)
            }
            current.appendBytes(bytes)
        } catch (_: IOException) {
            // nowhere left to report it; the next line tries again
        }
    }

    private companion object {
        const val NAME = "diag.log"
        const val DEFAULT_MAX_BYTES = 512L * 1024
    }
}

/** Removes secrets from free text before it is logged. */
object Redact {
    private val rules: List<Pair<Regex, String>> = listOf(
        // Nostr keys and other bech32 entities
        Regex("\\b(nsec|npub|nprofile|ncryptsec)1[02-9ac-hj-np-z]{20,}\\b") to "$1…",
        // credentials in URLs and headers
        Regex("(?i)\\b(token|secret|api-secret|password|pass|key|auth)=([^&\\s]+)") to "$1=…",
        Regex("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]+") to "$1 …",
        // long hex (keys, hashes, event ids) and long opaque tokens (JWT, base64)
        Regex("\\b[0-9a-fA-F]{16,}\\b") to "<hex>",
        Regex("\\b[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b") to "<token>",
        Regex("[A-Za-z0-9+/]{32,}={0,2}") to "<token>",
        // sensor serials (Libre: 9–11 upper-case letters and digits with at least two of each)
        Regex("\\b(?=[A-Z0-9]*[0-9][A-Z0-9]*[0-9])(?=[A-Z0-9]*[A-Z][A-Z0-9]*[A-Z])[A-Z0-9]{9,11}\\b") to "<serial>",
    )

    fun scrub(text: String): String = rules.fold(text) { acc, (pattern, replacement) -> pattern.replace(acc, replacement) }
}
