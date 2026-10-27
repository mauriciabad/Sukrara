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

/**
 * The app's diagnostic log (`files/diag/diag.log` + one rotated `diag.log.1`, each at most [maxBytes]): what the
 * "Copy report" button shares. Every module logs here instead of its own files.
 *
 * Rule for callers: log events and states, never glucose values, treatment amounts, keys, tokens or serials.
 * [Redact] still scrubs what looks like a key, token or secret query parameter, as a second line of defence.
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

    private fun append(line: String) {
        dir.mkdirs()
        if (current.exists() && current.length() + line.length > maxBytes) {
            previous.delete()
            current.renameTo(previous)
        }
        current.appendText(line)
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
    )

    fun scrub(text: String): String = rules.fold(text) { acc, (pattern, replacement) -> pattern.replace(acc, replacement) }
}
