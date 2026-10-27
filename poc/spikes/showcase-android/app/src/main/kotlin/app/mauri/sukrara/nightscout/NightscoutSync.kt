// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.nightscout

import app.mauri.sukrara.core.SensorOutputs
import app.mauri.sukrara.core.SensorProfile
import app.mauri.sukrara.core.Reading
import java.util.TimeZone

/** A cycle that stopped: [outcome] says whether to retry later or stop until the user fixes the credential. */
class NsFailure(val outcome: Outcome) : Exception(when (outcome) {
    is Outcome.Retry -> outcome.reason
    is Outcome.AuthFailed -> outcome.reason
    is Outcome.Rejected -> outcome.reason
    is Outcome.Ok -> "ok"
})

/**
 * Sends one [NightscoutQueue.Plan] (plain JVM: the app's cycle and the local-Nightscout test run this same code).
 * Entries go oldest first in batches, each batch remembered at once through [save] so a 30-day backfill keeps its
 * progress through a failure; then treatments, then the sensor start. A document the site refuses (a 4xx other than
 * 401/403) is remembered as sent and counted in [Result.dropped]: one bad document must not block the rest.
 */
object NightscoutSync {
    data class Result(val state: NightscoutQueue.State, val sent: Int, val dropped: Int)

    fun reconcile(client: NsClient, cfg: NsConfig, state: NightscoutQueue.State, nowMs: Long): NightscoutQueue.State {
        val windowS = NightscoutQueue.reconcileWindowS(state, nowMs) ?: return state
        val toS = nowMs / 1000; val fromS = toS - windowS
        val (server, o) = client.entriesBetween(fromS * 1000, toS * 1000, max = (windowS / 30).toInt().coerceIn(100, 100_000))
        if (server == null) throw NsFailure(o)
        return NightscoutQueue.reconciled(state, server, fromS, toS, cfg.device, nowMs)
    }

    fun send(client: NsClient, cfg: NsConfig, plan: NightscoutQueue.Plan, state: NightscoutQueue.State, readings: List<Reading>,
             zone: TimeZone, nowS: Long, save: (NightscoutQueue.State) -> Unit, sensor: SensorProfile? = null): Result {
        var st = state
        var sent = 0; var dropped = 0
        val prevOf = HashMap<Long, Reading>()
        readings.sortedBy { it.t }.zipWithNext { a, b -> prevOf[b.t] = a }
        val entries = if (cfg.api == NsApi.V3) plan.entries.takeLast(NightscoutQueue.V3_MAX_PER_CYCLE) else plan.entries
        for (chunk in entries.chunked(NightscoutQueue.BATCH)) {
            // each entry names its own sensor ("Sukrara" for the Libre 3, as always; "Sukrara · <model> (untested)" else)
            val docs = chunk.map { NsDocs.entry(it, prevOf[it.t], SensorOutputs.uploader(cfg.device, SensorOutputs.profileOf(it.dev)), zone, cfg.api) }
            when (val o = client.postEntries(docs)) {
                is Outcome.Ok -> { val bad = if (cfg.api == NsApi.V3) client.lastRejected else 0; sent += chunk.size - bad; dropped += bad }
                is Outcome.Rejected -> dropped += chunk.size
                else -> throw NsFailure(o)
            }
            st = NightscoutQueue.sentEntries(st, chunk, nowS).also(save)
        }
        for (t in plan.upserts + plan.deletes) {
            val o = if (t.del) client.deleteTreatment(t.id) else client.upsertTreatment(NsDocs.treatment(t, cfg.device, zone))
            when (o) {
                is Outcome.Ok -> sent++
                is Outcome.Rejected -> dropped++
                else -> throw NsFailure(o)
            }
            st = NightscoutQueue.sentTreatments(st, listOf(t)).also(save)
        }
        if (plan.settled.isNotEmpty()) st = NightscoutQueue.sentTreatments(st, plan.settled).also(save)
        plan.sensor?.let { s ->
            when (val o = client.postTreatments(NsDocs.sensorEvents(s, cfg.device, zone, sensor))) {
                is Outcome.Ok -> sent += 2
                is Outcome.Rejected -> dropped += 2
                else -> throw NsFailure(o)
            }
            st = NightscoutQueue.sentSensor(st, s.serial).also(save)
        }
        return Result(st, sent, dropped)
    }

    /** The phone's battery: when it changed, or every 30 min at most otherwise. */
    fun battery(client: NsClient, cfg: NsConfig, state: NightscoutQueue.State, percent: Int, charging: Boolean, nowMs: Long,
                zone: TimeZone): NightscoutQueue.State? {
        if (percent < 0 || (percent == state.batteryPercent && nowMs - state.batteryMs < NightscoutQueue.BATTERY_EVERY_MS)) return null
        when (val o = client.postDeviceStatus(NsDocs.deviceStatus(percent, charging, nowMs, cfg.device, zone))) {
            is Outcome.Ok, is Outcome.Rejected -> {}
            else -> throw NsFailure(o)
        }
        return state.copy(batteryPercent = percent, batteryMs = nowMs)
    }
}
