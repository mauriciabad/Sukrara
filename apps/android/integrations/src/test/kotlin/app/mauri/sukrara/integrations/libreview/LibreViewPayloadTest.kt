// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.integrations.libreview

import app.mauri.sukrara.core.testing.Contracts
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

/** `contracts/treatments/libreview-fixture.json`: the record numbers and the exact upload bytes. */
class LibreViewPayloadTest {
    private val fx = Json.parseToJsonElement(Contracts.file("treatments/libreview-fixture.json").readText()).jsonObject
    private val entries =
        fx["treatments"]!!.jsonArray.map { e ->
            val o = e.jsonObject
            LvEntry(
                o["id"]!!.jsonPrimitive.content,
                o["k"]!!.jsonPrimitive.content,
                o["a"]!!.jsonPrimitive.double,
                o["t"]!!.jsonPrimitive.long,
                o["n"]!!.jsonPrimitive.content,
                o["u"]!!.jsonPrimitive.long,
                o["del"]!!.jsonPrimitive.boolean,
                o["lv"]!!.jsonPrimitive.long,
            )
        }

    @Test
    fun `record numbers match the contract`() {
        val kinds = entries.associate { it.id to it.k }
        for (r in fx["recordNumbers"]!!.jsonArray) {
            val (id, v, rn) = r.jsonArray.map { it.jsonPrimitive }
            assertEquals(id.content, rn.long, LibreViewPayload.recordNumber(id.content, kinds.getValue(id.content), v.long))
        }
    }

    @Test
    fun `payload matches the contract byte for byte`() {
        val body =
            LibreViewPayload.payload(
                entries,
                fx["deviceId"]!!.jsonPrimitive.content,
                fx["token"]!!.jsonPrimitive.content,
                fx["now"]!!.jsonPrimitive.long,
                ZoneId.of(fx["zone"]!!.jsonPrimitive.content),
            )
        assertEquals(fx["expectedPayload"]!!.jsonPrimitive.content, body)
    }

    @Test
    fun `glucose record numbers come from the serial and the life count`() {
        val h = LibreViewPayload.histor("3MH000ABCD")
        val body =
            LibreViewPayload.payload(
                emptyList(),
                "d",
                "t",
                1_792_083_600,
                ZoneId.of("UTC"),
                LvSensor(
                    "3MH000ABCD",
                    1_792_000_000,
                    LvGlucose(1400, 123, 1_792_083_600, 0.5),
                    listOf(LvGlucose(1395, 120, 1_792_083_300)),
                ),
            )
        assertEquals(true, "\"recordNumber\":${h or 1400L}," in body)
        assertEquals(true, "\"recordNumber\":${h or 1395L}," in body)
        assertEquals(true, "\"trendArrow\":\"Stable\"" in body)
    }
}
