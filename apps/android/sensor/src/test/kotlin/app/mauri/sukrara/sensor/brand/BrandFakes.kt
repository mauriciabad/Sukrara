// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor.brand

import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.sensor.ble.BleLink
import app.mauri.sukrara.sensor.ble.LinkStatus
import app.mauri.sukrara.sensor.ble.SensorEvents
import java.util.UUID

/**
 * What the scanned families' driver tests share: the recorded traces in `src/test/resources/traces` (open-source
 * fixtures only, see its README), a scripted link and an event recorder.
 */
internal class Trace(val entries: List<Pair<String, ByteArray>>) {
    operator fun get(label: String): ByteArray = entries.firstOrNull { it.first == label }?.second ?: error("no '$label' in this trace")

    companion object {
        fun resource(name: String): Trace {
            val text =
                Trace::class.java.classLoader!!
                    .getResourceAsStream("traces/$name.txt")!!
                    .reader()
                    .readText()
            return Trace(
                text
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .map { line ->
                        val label = line.substringBefore(' ')
                        val rest = line.substringAfter(' ').trim()
                        val compact = rest.replace(" ", "")
                        label to
                            if (compact.matches(Regex("[0-9a-fA-F]{2,}")) && compact.length % 2 == 0) {
                                unhex(compact)
                            } else {
                                rest.toByteArray(Charsets.US_ASCII)
                            }
                    }.toList(),
            )
        }
    }
}

internal fun unhex(s: String): ByteArray = s.replace(Regex("[^0-9a-fA-F]"), "").hexToByteArray()

internal fun ByteArray.hex(): String = toHexString()

internal class BrandLink(override var isBonded: Boolean = true) : BleLink {
    val ops = ArrayList<String>()
    var pending: Runnable? = null
    var closed = 0
    var disconnects = 0

    override fun subscribe(characteristic: UUID) {
        ops += "sub ${short(characteristic)}"
    }

    override fun indicate(characteristic: UUID) {
        ops += "sub ${short(characteristic)} ind"
    }

    override fun write(characteristic: UUID, value: ByteArray) {
        ops += "write ${short(characteristic)} ${value.hex()}"
    }

    override fun writeNoResponse(characteristic: UUID, value: ByteArray) {
        ops += "write ${short(characteristic)} ${value.hex()}"
    }

    override fun read(characteristic: UUID) {
        ops += "read ${short(characteristic)}"
    }

    override fun bond() {
        ops += "bond"
    }

    override fun disconnect() {
        ops += "disconnect"
        disconnects++
    }

    override fun close() {
        ops += "close"
        closed++
    }

    override fun schedule(delayMs: Long, action: Runnable) {
        ops += "schedule $delayMs"
        pending = action
    }

    override fun cancel(action: Runnable) {
        if (pending === action) pending = null
    }

    /** Characteristic writes only (not subscriptions): what reading along must never do. */
    val characteristicWrites get() = ops.filter { it.startsWith("write") }

    private fun short(c: UUID) = c.toString().substring(4, 8).uppercase()
}

internal class BrandEvents : SensorEvents {
    val statuses = ArrayList<LinkStatus>()
    val readings = ArrayList<Reading>()
    val history = ArrayList<Reading>()
    val starts = ArrayList<Long>()
    val ended = ArrayList<String>()

    override fun onStatus(status: LinkStatus) {
        statuses += status
    }

    override fun onReading(reading: Reading) {
        readings += reading
    }

    override fun onHistory(readings: List<Reading>) {
        history += readings
    }

    override fun onEndedForGood(serial: String) {
        ended += serial
    }

    override fun onSessionStart(startEpoch: Long) {
        starts += startEpoch
    }

    fun last() = statuses.last()
}

internal val Reading.epochSec get() = t.epochSecond
