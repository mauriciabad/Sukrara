// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.nsupload.cli

import dev.spike.nsupload.LocalData
import dev.spike.nsupload.Origin
import dev.spike.nsupload.Reading
import dev.spike.nsupload.SensorStart
import dev.spike.nsupload.StateStore
import dev.spike.nsupload.Treatment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Stand-in for the phone's stores: `local.json` with synthetic readings, treatments and the sensor start. */
class LocalFile(private val file: File) : LocalData {
    val readings = sortedMapOf<Long, Reading>()
    val treatments = linkedMapOf<String, Treatment>()
    var sensor: SensorStart? = null
    /** What arrived since the last `sync` (the app calls the uploader's on… hooks as things arrive). */
    val inboxReadings = sortedSetOf<Long>()
    val inboxTreatments = linkedSetOf<String>()
    var inboxSensor = false

    init {
        if (file.exists()) {
            val j = JSONObject(file.readText())
            j.getJSONArray("readings").forEachObj {
                val r = Reading(it.getLong("t"), it.getInt("g"), if (it.has("r")) it.getDouble("r") else null,
                    Origin.valueOf(it.optString("o", "OWN_SENSOR")), it.optString("p").ifEmpty { null })
                readings[r.epochMs] = r
            }
            j.getJSONArray("treatments").forEachObj {
                val t = Treatment(it.getString("id"), it.getString("k"), it.getDouble("a"), it.getLong("t"),
                    it.optString("n"), it.getLong("u"), it.optBoolean("del"))
                treatments[t.id] = t
            }
            j.optJSONObject("sensor")?.let { sensor = SensorStart(it.getString("serial"), it.getLong("t")) }
            j.optJSONArray("inboxReadings")?.let { a -> for (i in 0 until a.length()) inboxReadings += a.getLong(i) }
            j.optJSONArray("inboxTreatments")?.let { a -> for (i in 0 until a.length()) inboxTreatments += a.getString(i) }
            inboxSensor = j.optBoolean("inboxSensor")
        }
    }

    fun save() {
        val j = JSONObject()
            .put("readings", JSONArray(readings.values.map { r ->
                JSONObject().put("t", r.epochMs).put("g", r.mgdl).apply {
                    r.rate?.let { put("r", it) }
                    if (r.origin != Origin.OWN_SENSOR) put("o", r.origin.name).put("p", r.followedPerson)
                }
            }))
            .put("treatments", JSONArray(treatments.values.map { t ->
                JSONObject().put("id", t.id).put("k", t.kind).put("a", t.amount).put("t", t.epochMs).put("n", t.note)
                    .put("u", t.updatedMs).put("del", t.deleted)
            }))
            .put("sensor", sensor?.let { JSONObject().put("serial", it.serial).put("t", it.epochMs) })
            .put("inboxReadings", JSONArray(inboxReadings.toList()))
            .put("inboxTreatments", JSONArray(inboxTreatments.toList()))
            .put("inboxSensor", inboxSensor)
        file.parentFile?.mkdirs()
        file.writeText(j.toString())
    }

    override fun readings(fromMs: Long, toMs: Long): List<Reading> = readings.subMap(fromMs, toMs + 1).values.toList()
    override fun treatment(id: String): Treatment? = treatments[id]

    /** Synthetic 1-minute Libre 3 curve: meals as bumps, a night low, sensor noise. Never user data. */
    fun synth(fromMs: Long, toMs: Long, inbox: Boolean, skip: (Long) -> Boolean = { false }): Int {
        var n = 0
        var t = fromMs - fromMs % 60_000
        var prev: Double? = null
        while (t <= toMs) {
            val rnd = Random(t / 60_000)   // same minute → same value, whichever command made it
            val h = ((t / 3_600_000.0) % 24)
            val base = 115 + 45 * sin(2 * PI * (h - 9) / 24) + 60 * bump(h, 8.5) + 75 * bump(h, 14.0) + 70 * bump(h, 21.0) - 45 * bump(h, 3.5)
            val g = (base + rnd.nextDouble(-3.0, 3.0)).roundToInt().coerceIn(40, 400)
            if (!skip(t) && t !in readings) {
                readings[t] = Reading(t, g, prev?.let { Math.round((base - it) * 100) / 100.0 })
                if (inbox) inboxReadings += t
                n++
            }
            prev = base
            t += 60_000
        }
        return n
    }

    private fun bump(h: Double, at: Double): Double { val d = h - at; return if (d < 0 || d > 3) 0.0 else sin(PI * d / 3) }

    fun addTreatment(kind: String, amount: Double, at: Long, note: String = ""): Treatment =
        Treatment(UUID.randomUUID().toString(), kind, amount, at, note, System.currentTimeMillis()).also { treatments[it.id] = it; inboxTreatments += it.id }
}

/** Uploader queue state in a file next to `local.json`. */
class FileStateStore(private val file: File) : StateStore {
    override fun load(): String? = file.takeIf { it.exists() }?.readText()
    override fun save(json: String) { file.parentFile?.mkdirs(); file.writeText(json) }
}

private fun JSONArray.forEachObj(f: (JSONObject) -> Unit) { for (i in 0 until length()) f(getJSONObject(i)) }
