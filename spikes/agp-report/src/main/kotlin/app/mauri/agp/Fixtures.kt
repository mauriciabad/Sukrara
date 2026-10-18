// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.agp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The shared fixtures (`fixtures/<name>.json`): synthetic readings + the period + what every implementation must
 * compute from them (`expect`). Kotlin (this engine, `FixtureTest`), the Swift port (`mac/`, `agp-mac selftest`) and
 * the independent numpy/pandas check (`check/independent.py`) all read the same files. Numbers are rounded to 4
 * decimals; implementations agree within [TOLERANCE].
 */
object Fixtures {
    const val TOLERANCE = 1e-3
    private val json = Json { prettyPrint = false }

    data class Fixture(val name: String, val note: String, val period: Period, val bands: Bands, val options: AgpOptions,
                       val readings: List<Reading>, val expect: JsonObject)

    private fun r4(x: Double) = JsonPrimitive((x * 10000).roundToLong() / 10000.0)
    private fun r4(x: Double?) = x?.let { r4(it) } ?: JsonNull

    fun expect(r: AgpReport): JsonObject = buildJsonObject {
        val m = r.metrics
        put("metrics", buildJsonObject {
            put("veryLow", r4(m.veryLow)); put("low", r4(m.low)); put("tight", r4(m.tight)); put("target", r4(m.target))
            put("high", r4(m.high)); put("veryHigh", r4(m.veryHigh)); put("active", r4(m.active))
            put("mean", r4(m.mean)); put("sd", r4(m.sd)); put("cv", r4(m.cv)); put("gmi", r4(m.gmi)); put("readings", m.readings)
        })
        val s = r.sufficiency
        put("sufficiency", buildJsonObject {
            put("hours", r4(s.hours)); put("daysWithData", s.daysWithData); put("best7Active", r4(s.best7Active))
            put("report", s.report); put("profile", s.profile); put("consensus", s.consensus)
        })
        fun bins(bs: List<AgpBin>) = buildJsonArray { bs.forEach { b -> add(buildJsonArray { add(JsonPrimitive(b.n)); b.values().forEach { add(r4(it)) } }) } }
        put("rawProfile", bins(r.rawProfile))
        put("profile", bins(r.profile))
        put("days", buildJsonArray {
            r.days.forEach { d ->
                add(buildJsonObject {
                    put("date", d.date.toString()); put("hours", r4(d.hours)); put("active", r4(d.active)); put("inRange", r4(d.inRange))
                    put("segments", d.segments.size); put("points", d.segments.sumOf { it.size }); put("noon", r4(d.noon))
                })
            }
        })
    }

    fun write(file: File, s: Scenario, readings: List<Reading>, r: AgpReport) {
        val o = buildJsonObject {
            put("name", s.name); put("note", s.note); put("zone", s.zone.id); put("first", s.first.toString()); put("days", s.days)
            put("bands", buildJsonObject { r.bands.let { put("veryLow", it.veryLow); put("low", it.low); put("tight", it.tight); put("high", it.high); put("veryHigh", it.veryHigh) } })
            put("options", buildJsonObject { put("gridS", r.options.gridS); put("binMinutes", r.options.binMinutes); put("maxGapS", r.options.maxGapS) })
            put("expect", expect(r))
        }
        // readings one compact array per line keeps diffs and the file readable
        val head = json.encodeToString(JsonObject.serializer(), o).dropLast(1)
        file.writeText(head + ",\"readings\":[\n" + readings.joinToString(",\n") { "[${it.t},${it.sgv}]" } + "\n]}\n")
    }

    fun read(file: File): Fixture {
        val o = json.parseToJsonElement(file.readText()).jsonObject
        val b = o["bands"]!!.jsonObject
        val op = o["options"]!!.jsonObject
        return Fixture(
            o["name"]!!.jsonPrimitive.content, o["note"]!!.jsonPrimitive.content,
            Period(ZoneId.of(o["zone"]!!.jsonPrimitive.content), LocalDate.parse(o["first"]!!.jsonPrimitive.content), o["days"]!!.jsonPrimitive.int),
            Bands(b.i("veryLow"), b.i("low"), b.i("tight"), b.i("high"), b.i("veryHigh")),
            AgpOptions(op["gridS"]!!.jsonPrimitive.long, op.i("binMinutes"), op["maxGapS"]!!.jsonPrimitive.long),
            o["readings"]!!.jsonArray.map { val a = it.jsonArray; Reading(a[0].jsonPrimitive.long, a[1].jsonPrimitive.int) },
            o["expect"]!!.jsonObject,
        )
    }

    private fun JsonObject.i(k: String) = this[k]!!.jsonPrimitive.int

    /** Differences between two `expect` trees, as "path: a ≠ b" lines (numbers within [TOLERANCE] are equal). */
    fun diff(a: JsonElement, b: JsonElement, path: String = ""): List<String> = when {
        a is JsonObject && b is JsonObject -> (a.keys + b.keys).flatMap { k ->
            val x = a[k]; val y = b[k]
            if (x == null || y == null) listOf("$path.$k: missing") else diff(x, y, "$path.$k")
        }
        a is JsonArray && b is JsonArray ->
            if (a.size != b.size) listOf("$path: size ${a.size} ≠ ${b.size}") else a.indices.flatMap { diff(a[it], b[it], "$path[$it]") }
        a is JsonPrimitive && b is JsonPrimitive && !a.isString && !b.isString && a !is JsonNull && b !is JsonNull &&
            a.content.toDoubleOrNull() != null && b.content.toDoubleOrNull() != null ->
            if (abs(a.double - b.double) <= TOLERANCE) emptyList() else listOf("$path: ${a.content} ≠ ${b.content}")
        else -> if (a == b) emptyList() else listOf("$path: $a ≠ $b")
    }
}
