// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import app.mauri.sukrara.core.model.DurationWords
import app.mauri.sukrara.core.model.Durations
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale

/**
 * THE duration formatter ([Durations], :core:model) against contracts/settings/durations.json, with the real
 * `duration_*` strings of this module (en + es), so the words and the rules are tested together.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DurationsTest {
    private val contract: JsonObject by lazy {
        val dir = System.getProperty("sukrara.contracts") ?: error("sukrara.contracts not set: run through Gradle")
        Json.parseToJsonElement(File(dir, "settings/durations.json").readText()).jsonObject
    }

    private fun check(
        cases: JsonArray,
        name: String,
        format: (Long) -> String,
    ) {
        check(cases.isNotEmpty()) { "no $name cases" }
        for (case in cases) {
            val (min, expected) = case.jsonArray
            assertEquals("$name(${min.jsonPrimitive.long})", expected.jsonPrimitive.content, format(min.jsonPrimitive.long))
        }
    }

    private fun checkFormats(
        cases: JsonObject,
        words: DurationWords,
        lang: String,
    ) {
        check(cases.getValue("text").jsonArray, "$lang text") { Durations.text(it, words) }
        check(cases.getValue("compact").jsonArray, "$lang compact") { Durations.compact(it, words) }
        check(cases.getValue("whole").jsonArray, "$lang whole") { Durations.whole(it, words) }
    }

    @Test
    fun formatsInEnglish() = checkFormats(contract, Scenes.words().durations, "en")

    @Test
    fun formatsInSpanish() = checkFormats(contract.getValue("es").jsonObject, Scenes.words(Locale.forLanguageTag("es")).durations, "es")

    @Test
    fun parsesTypedMinutes() {
        val cases = contract.getValue("parse").jsonArray
        check(cases.isNotEmpty())
        for (case in cases) {
            val (input, expected) = case.jsonArray
            val want = if (expected is JsonNull) null else expected.jsonPrimitive.int
            assertEquals("parse(\"${input.jsonPrimitive.content}\")", want, Durations.parse(input.jsonPrimitive.content))
        }
    }

    @Test
    fun pluralQuantityIsTheShownNumber() {
        assertEquals(45, Durations.compactQuantity(45))
        assertEquals(90, Durations.compactQuantity(90))
        assertEquals(6, Durations.compactQuantity(400))
        assertEquals(2, Durations.compactQuantity(2880))
        assertEquals(23, Durations.wholeQuantity(1439))
        assertEquals(1, Durations.wholeQuantity(1440))
        assertEquals(2, Durations.textQuantity(120))
        assertEquals(64, Durations.textQuantity(64))
    }
}
