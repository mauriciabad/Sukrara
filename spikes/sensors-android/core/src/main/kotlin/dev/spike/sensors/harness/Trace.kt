// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.sensors.harness

import dev.spike.sensors.unhex
import java.io.File

/**
 * A recorded trace: `# comment` lines, then `<label> <hex…>` lines (spaces inside the hex are ignored).
 * Where every fixture came from is in `core/src/test/resources/traces/README.md`.
 */
class Trace(val entries: List<Entry>) {
    data class Entry(val label: String, val bytes: ByteArray) {
        val text get() = String(bytes, Charsets.US_ASCII)
    }

    operator fun get(label: String): ByteArray =
        entries.firstOrNull { it.label == label }?.bytes ?: error("no '$label' in this trace")

    fun all(label: String): List<ByteArray> = entries.filter { it.label == label }.map { it.bytes }

    companion object {
        fun parse(text: String): Trace = Trace(
            text.lineSequence().map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { line ->
                    val label = line.substringBefore(' ')
                    val rest = line.substringAfter(' ').trim()
                    // A value that is not hex (e.g. a serial number) is kept as its ASCII bytes.
                    val bytes = if (rest.replace(" ", "").matches(Regex("[0-9a-fA-F]{2,}")) && rest.count { !it.isWhitespace() } % 2 == 0)
                        rest.unhex() else rest.toByteArray(Charsets.US_ASCII)
                    Entry(label, bytes)
                }.toList()
        )

        fun load(file: File): Trace = parse(file.readText())

        /** Load from the test resources (`traces/<name>.txt`) — used by the unit tests and the harness. */
        fun resource(name: String): Trace {
            val stream = Trace::class.java.classLoader.getResourceAsStream("traces/$name.txt")
                ?: error("no traces/$name.txt on the classpath")
            return parse(stream.reader().readText())
        }
    }
}
