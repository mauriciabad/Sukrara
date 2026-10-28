// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.treatments

import app.mauri.sukrara.core.model.TreatmentKind
import app.mauri.sukrara.core.testing.Contracts
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** The form rules against contracts/treatments/treatment-form.json (the Mac runs the same cases). */
class TreatmentFormTest {
    private val fixture = Json.parseToJsonElement(Contracts.file("treatments/treatment-form.json").readText()).jsonObject

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    private val wire =
        mapOf(
            "rapid" to TreatmentKind.RapidInsulin,
            "long" to TreatmentKind.LongInsulin,
            "carbs" to TreatmentKind.Carbs,
            "note" to TreatmentKind.Note,
        )

    @Test fun limitsMatchTheContract() {
        val limits = fixture.getValue("limits").jsonObject
        assertEquals(limits.getValue("units").jsonPrimitive.double, TreatmentForm.MAX_UNITS, 0.0)
        assertEquals(limits.getValue("grams").jsonPrimitive.double, TreatmentForm.MAX_GRAMS, 0.0)
        assertEquals(limits.getValue("noteBytes").jsonPrimitive.int, TreatmentForm.NOTE_BYTES)
    }

    @Test fun forms() {
        for (case in fixture.getValue("forms").jsonArray.map { it.jsonObject }) {
            val got = TreatmentForm.entries(case.str("rapid"), case.str("long"), case.str("carbs"), case.str("note"))
            val want =
                (case.getValue("entries") as? JsonArray)?.map { e ->
                    val (k, a, n) = e.jsonArray
                    NewEntry(wire.getValue(k.jsonPrimitive.content), a.jsonPrimitive.double, n.jsonPrimitive.content)
                }
            assertEquals(case.str("name"), want, got)
        }
    }

    @Test fun amountFilters() {
        for (case in fixture.getValue("filters").jsonArray.map { it.jsonObject }) {
            assertEquals(case.str("in"), case.str("out"), TreatmentForm.filterAmount(case.str("in")))
        }
    }

    @Test fun minuteFilters() {
        for (case in fixture.getValue("minute_filters").jsonArray.map { it.jsonObject }) {
            assertEquals(case.str("in"), case.str("out"), TreatmentForm.filterMinutes(case.str("in")))
        }
    }

    @Test fun minutesAgo() {
        val now =
            LocalDate
                .of(2026, 6, 15)
                .atTime(14, 30, 20)
                .atZone(ZoneId.of("Europe/Madrid"))
                .toInstant()
        for (case in fixture.getValue("minutes").jsonArray.map { it.jsonObject }) {
            val typed = case.str("typed")
            val minutes = TreatmentForm.agoMinutes(typed)
            if (case["invalid"]?.jsonPrimitive?.booleanOrNull == true) {
                assertNull(typed, minutes)
                continue
            }
            val at = TreatmentForm.agoTime(now, checkNotNull(minutes) { typed })
            assertEquals(typed, case.getValue("ago_s").jsonPrimitive.longOrNull, at?.let { Duration.between(it, now).seconds })
        }
    }

    @Test fun pickedTimes() {
        val zone = ZoneId.of("Europe/Madrid")
        for (case in fixture.getValue("times").jsonArray.map { it.jsonObject }) {
            val now =
                LocalDate
                    .of(2026, 6, 15)
                    .atTime(LocalTime.parse(case.str("now")))
                    .atZone(zone)
                    .toInstant()
            val at = TreatmentForm.pickTime(now, zone, LocalTime.parse(case.str("pick")))
            val want = case.getValue("ago_s").let { if (it is JsonNull) null else it.jsonPrimitive.longOrNull }
            assertEquals(case.toString(), want, at?.let { Duration.between(it, now).seconds })
        }
    }
}
