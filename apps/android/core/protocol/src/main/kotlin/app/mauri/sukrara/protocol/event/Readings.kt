// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.protocol.event

import app.mauri.sukrara.protocol.Wire
import app.mauri.sukrara.protocol.event.Payload.long
import app.mauri.sukrara.protocol.event.Payload.longOrNull
import app.mauri.sukrara.protocol.event.Payload.numberOrNull
import app.mauri.sukrara.protocol.event.Payload.requireV
import app.mauri.sukrara.protocol.event.Payload.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One glucose reading on the wire: [t] unix s, [sgv] mg/dL, [rate] mg/dL/min (null = unknown), [dev] its source. */
data class WireReading(val t: Long, val sgv: Int, val rate: Double?, val dev: String = "")

/** The wearer's current sensor (unix s): start, end, warm-up end. */
data class SensorLife(val start: Long, val end: Long, val warmupEnd: Long)

/** A live event's content: a reading, the sensor, both, or neither (`sensor == null` clears the sensor). */
data class LivePayload(val reading: WireReading?, val sensor: SensorLife?)

/** An hour chunk's content: the hour start and its readings in time order. */
data class ChunkPayload(val hour: Long, val readings: List<WireReading>)

/** Live (kind 21078) and hour chunk (kind 30078) content codecs (PROTOCOL-v2 §Events, §Sensor life). */
object ReadingsCodec {
    private const val SGV_MIN = 1
    private const val SGV_MAX = 1000
    private const val DEFAULT_WARMUP_S = 3600L

    fun encodeLive(p: LivePayload): String =
        buildJsonObject {
            put("v", Wire.V)
            p.reading?.let {
                put("t", it.t)
                put("sgv", it.sgv)
                put("r", Payload.rate(it.rate))
                put("dev", it.dev)
            }
            p.sensor?.let {
                put("ss", it.start)
                put("se", it.end)
                put("sw", it.warmupEnd)
            }
        }.toString()

    fun decodeLive(json: String): LivePayload {
        val o = Payload.parse(json)
        o.requireV(Wire.V)
        val reading = if ("t" in o || "sgv" in o) {
            WireReading(o.long("t"), sgv(o.long("sgv")), o.numberOrNull("r"), o.string("dev").orEmpty())
        } else {
            null
        }
        val sensor = if ("se" in o) {
            val ss = o.longOrNull("ss") ?: 0L
            SensorLife(ss, o.long("se"), o.longOrNull("sw") ?: (ss + DEFAULT_WARMUP_S))
        } else {
            null
        }
        return LivePayload(reading, sensor)
    }

    /** Rows of [readings] inside the hour starting at [hour], time-ordered; `dev` = the newest row's source. */
    fun encodeChunk(hour: Long, readings: Collection<WireReading>): String {
        val inHour = readings.filter { it.t >= hour && it.t < hour + Wire.HOUR }.sortedBy { it.t }
        return buildJsonObject {
            put("v", Wire.V)
            put("h", hour)
            put("dev", inHour.lastOrNull()?.dev.orEmpty())
            put(
                "r",
                buildJsonArray {
                    inHour.forEach {
                        add(
                            buildJsonArray {
                                add(JsonPrimitive(it.t - hour))
                                add(JsonPrimitive(it.sgv))
                                add(it.rate?.let { r -> JsonPrimitive(Math.round(r * 100)) } ?: JsonNull)
                            },
                        )
                    }
                },
            )
        }.toString()
    }

    fun decodeChunk(json: String): ChunkPayload {
        val o = Payload.parse(json)
        o.requireV(Wire.V)
        val hour = o.long("h")
        if (Math.floorMod(hour, Wire.HOUR) != 0L) throw InvalidPayload("h is not an hour start")
        val rows = o["r"] as? JsonArray ?: throw InvalidPayload("r must be an array")
        val dev = o.string("dev").orEmpty()
        val readings = rows.map { row ->
            val cells = row as? JsonArray ?: throw InvalidPayload("bad row")
            if (cells.size < 2) throw InvalidPayload("bad row")
            val dt = Payload.asLong(cells[0], "dt") ?: throw InvalidPayload("bad row")
            val sgv = Payload.asLong(cells[1], "sgv") ?: throw InvalidPayload("bad row")
            if (dt !in 0 until Wire.HOUR) throw InvalidPayload("row out of range")
            val rate100 = cells.getOrNull(2)?.let { Payload.asLong(it, "rate") }
            WireReading(hour + dt, sgv(sgv), rate100?.let { it / 100.0 }, dev)
        }
        return ChunkPayload(hour, readings)
    }

    private fun sgv(v: Long): Int = if (v in SGV_MIN..SGV_MAX) v.toInt() else throw InvalidPayload("sgv out of range")

    internal fun JsonObject.has(key: String) = key in this
}
