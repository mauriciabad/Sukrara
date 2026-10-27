// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File

/** The shared contracts/graph fixtures (the `sukrara.contracts` system property set by the build). */
internal object Fixtures {
    private val root: File by lazy {
        val dir = System.getProperty("sukrara.contracts") ?: error("sukrara.contracts not set: run through Gradle")
        File(dir, "graph").also { check(it.isDirectory) { "no graph fixtures at $it" } }
    }

    fun read(path: String): JsonObject = Json.parseToJsonElement(File(root, path).readText()) as JsonObject

    fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

    fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull

    fun JsonElement?.double(): Double? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull

    fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.booleanOrNull
}
