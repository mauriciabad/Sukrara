// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.integrations.export

import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.SourceId
import app.mauri.sukrara.core.model.Treatment
import app.mauri.sukrara.core.model.TreatmentKind
import app.mauri.sukrara.core.model.TreatmentOrigin
import app.mauri.sukrara.core.model.storage.TreatmentRecord
import app.mauri.sukrara.core.testing.Contracts
import app.mauri.sukrara.integrations.CurrentSensor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** Every case of `contracts/export/libreview-csv.json` gives the same bytes as the reference writer (and the Mac). */
class GlucoseCsvTest {
    private val cases =
        Json
            .parseToJsonElement(Contracts.file("export/libreview-csv.json").readText())
            .jsonObject["cases"]!!
            .jsonArray
            .map { it.jsonObject }

    @Test
    fun `every contract case matches byte for byte`() {
        check(cases.size >= 7)
        for (c in cases) {
            val expect = c["expect"]!!.jsonArray.joinToString("") { it.jsonPrimitive.content + "\r\n" }
            assertEquals(c.str("name"), expect, write(c))
        }
    }

    @Test
    fun `file name carries only the period`() {
        val zone = ZoneId.of("Europe/Madrid")
        val from = Instant.parse("2026-10-05T08:00:00Z")
        val to = Instant.parse("2026-10-19T08:00:00Z")
        assertEquals("sukrara-glucose-2026-10-05-to-2026-10-19.csv", GlucoseCsv.fileName(from, to, zone))
    }

    @Test
    fun `tenths round half away from zero`() {
        assertEquals("2.5", GlucoseCsv.tenths(2.45))
        assertEquals("-3.1", GlucoseCsv.tenths(-3.06))
        assertEquals("0.0", GlucoseCsv.tenths(-0.04))
    }

    private fun write(c: JsonObject): String {
        val readings =
            c["readings"]!!.jsonArray.map { r ->
                val o = r.jsonObject
                Reading(
                    Instant.ofEpochSecond(o["t"]!!.jsonPrimitive.long),
                    o["sgv"]!!.jsonPrimitive.int,
                    o["rate"]?.jsonPrimitive?.double,
                    SourceId.Libre3,
                )
            }
        val treatments = c["treatments"]!!.jsonArray.map { record(it.jsonObject) }
        val sensor =
            (c["sensor"] as? JsonObject)?.let {
                CurrentSensor(
                    it.str("serial"),
                    Instant.ofEpochSecond(it["start"]!!.jsonPrimitive.long),
                    Instant.ofEpochSecond(it["end"]!!.jsonPrimitive.long),
                )
            }
        return GlucoseCsv.write(
            readings,
            treatments,
            Instant.ofEpochSecond(c["from"]!!.jsonPrimitive.long),
            Instant.ofEpochSecond(c["to"]!!.jsonPrimitive.long),
            ZoneId.of(c.str("zone")),
            Instant.ofEpochSecond(c["generated"]!!.jsonPrimitive.long),
            sensor,
            c["mmol"]?.jsonPrimitive?.boolean ?: false,
        )
    }

    private fun record(o: JsonObject): TreatmentRecord {
        val kind =
            when (o.str("k")) {
                "rapid" -> TreatmentKind.RapidInsulin
                "long" -> TreatmentKind.LongInsulin
                "carbs" -> TreatmentKind.Carbs
                else -> TreatmentKind.Note
            }
        val origin =
            when (o.str("o")) {
                "mac" -> TreatmentOrigin.Mac
                "follower" -> TreatmentOrigin.Follower
                else -> TreatmentOrigin.Phone
            }
        val at = Instant.ofEpochSecond(o["t"]!!.jsonPrimitive.long)
        val pen =
            o["pen"]?.takeIf { it !is JsonNull }?.let { serial ->
                buildJsonObject {
                    put("pen", serial)
                    o["pr"]?.let { put("pr", it) }
                    o["pd"]?.let { put("pd", it) }
                }.toString()
            }
        return TreatmentRecord(
            Treatment(
                id = o.str("id"),
                kind = kind,
                amount = o["a"]?.jsonPrimitive?.double ?: 0.0,
                at = at,
                note = o["n"]?.jsonPrimitive?.content.orEmpty(),
                modifiedAt = at,
                deleted = o["del"]?.jsonPrimitive?.boolean ?: false,
                origin = origin,
            ),
            penJson = pen,
        )
    }

    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content
}
