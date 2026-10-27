// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.direct

import app.mauri.sukrara.bridge.Bridge
import java.util.UUID

/**
 * What the untested families' driver tests share (investigation 23, steps 2–5): the recorded traces in
 * `src/test/resources/traces` (open-source fixtures only, see its README), a scripted link and an event recorder.
 */
internal class Trace(val entries: List<Pair<String, ByteArray>>) {
    operator fun get(label: String): ByteArray = entries.firstOrNull { it.first == label }?.second ?: error("no '$label' in this trace")

    companion object {
        fun resource(name: String): Trace {
            val text = Trace::class.java.classLoader!!.getResourceAsStream("traces/$name.txt")!!.reader().readText()
            return Trace(text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map { line ->
                val label = line.substringBefore(' ')
                val rest = line.substringAfter(' ').trim()
                val compact = rest.replace(" ", "")
                label to (if (compact.matches(Regex("[0-9a-fA-F]{2,}")) && compact.length % 2 == 0) unhex(compact)
                          else rest.toByteArray(Charsets.US_ASCII))
            }.toList())
        }
    }
}

internal fun unhex(s: String): ByteArray = s.replace(Regex("[^0-9a-fA-F]"), "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

internal class BrandLink(override var isBonded: Boolean = true) : BleLink {
    val ops = ArrayList<String>()
    val writes = ArrayList<Pair<UUID, ByteArray>>()
    var pending: Runnable? = null
    var closed = 0
    var disconnects = 0
    override fun subscribe(characteristic: UUID, indicate: Boolean) { ops += "sub ${short(characteristic)}" + if (indicate) " ind" else "" }
    override fun write(characteristic: UUID, value: ByteArray, withResponse: Boolean) {
        ops += "write ${short(characteristic)} ${value.hex()}"
        writes += characteristic to value
    }
    override fun read(characteristic: UUID) { ops += "read ${short(characteristic)}" }
    override fun bond() { ops += "bond" }
    override fun requestConnectionPriority(priority: Int) { ops += "priority $priority" }
    override fun disconnect() { ops += "disconnect"; disconnects++ }
    override fun close() { ops += "close"; closed++ }
    override fun schedule(delayMs: Long, action: Runnable) { ops += "schedule $delayMs"; pending = action }
    override fun cancel(action: Runnable) { if (pending === action) pending = null }
    /** Characteristic writes only (not subscriptions): what observer mode must never do. */
    val characteristicWrites get() = ops.filter { it.startsWith("write") }
    private fun short(c: UUID) = c.toString().substring(4, 8).uppercase()
}

internal class BrandEvents : SensorEvents {
    val statuses = ArrayList<Pair<String, String?>>()
    val readings = ArrayList<Bridge.Reading>()
    val history = ArrayList<Bridge.Reading>()
    val starts = ArrayList<Long>()
    val ended = ArrayList<String>()
    override fun onStatus(state: String, detail: String?) { statuses += state to detail }
    override fun onReading(reading: Bridge.Reading) { readings += reading }
    override fun onHistory(readings: List<Bridge.Reading>) { history += readings }
    override fun onSensorEnded(serial: String) { ended += serial }
    override fun onSessionStart(startEpoch: Long) { starts += startEpoch }
    fun last() = statuses.last()
}

internal class BrandPrefs : DriverPrefs {
    val map = HashMap<String, Boolean>()
    override fun getBoolean(key: String, default: Boolean) = map[key] ?: default
    override fun putBoolean(key: String, value: Boolean) { map[key] = value }
}

internal val QUIET: (Int, String) -> Unit = { _, _ -> }
