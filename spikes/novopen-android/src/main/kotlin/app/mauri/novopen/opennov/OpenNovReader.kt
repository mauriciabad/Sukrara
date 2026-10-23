// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
// Ported to Kotlin from xDrip (insulin/opennov/OpenNov, GPL-3.0), original by JamOrHam.
package app.mauri.novopen.opennov

import app.mauri.novopen.opennov.ll.PHDllHelper
import app.mauri.novopen.opennov.ll.T4Transceiver
import app.mauri.novopen.opennov.mt.InsulinDose
import java.io.IOException
import kotlin.math.roundToLong

/**
 * One ISO-DEP exchange (Android: `IsoDep.transceive`, already connected, timeout ≥ 3000 ms). Throws on a lost tag.
 */
fun interface Transceiver {
    fun transceive(cmd: ByteArray): ByteArray
}

/**
 * One dose record. [relTime] is the pen's own clock (seconds since it was made / reset), stable across scans;
 * [time] is placed on the phone's clock at the read ([PenRead.scanTime] − ([PenRead.penRelNow] − [relTime])), so it can
 * move by a second or two between scans. [units] has 0.1 u resolution. [flags] is the raw 32-bit status word
 * (`0x08000000` on every normal dose seen so far, see docs/29-novopen.md).
 */
data class PenDose(val relTime: Long, val time: Long, val units: Double, val flags: Long) {
    /** A status bit in the flags' low byte (normal doses read `0x08000000`): taken as "incomplete" (docs/29: unverified). */
    val incomplete get() = (flags and 0xFFL) != 0L
}

/**
 * A finished read. [doses] are the insulin records in time order (oldest first); records that aren't doses (no
 * `FF 00` units marker or other flags, e.g. the rel-time-0 record) are dropped. [stored] is how many records the pen
 * says its store holds (dose or not); [complete] is false only when [OpenNovReader.read] stopped at known history
 * ([doses] is then the newest part only).
 */
data class PenRead(
    val serial: String,
    val model: String,
    val scanTime: Long,
    val penRelNow: Long,
    val doses: List<PenDose>,
    val stored: Int = doses.size,
    val complete: Boolean = true,
    val part: String = "",      // the pen's part number (specification), how its model is told apart (docs/29)
)

object OpenNovReader {
    private const val MAX_ERRORS = 3
    private const val MAX_TRANSACTIONS = 200 // a full 800-dose pen is ~45 chunks

    /**
     * Reads the pen's identity and its whole dose history. [stopBefore] (epoch s), when set, ends the transfer once a
     * chunk reaches a dose older than it (the pen sends newest first) — a rescan then takes one chunk instead of ~45.
     * [progress] gets the share of the records transferred so far (0–1) after each message, once the pen said how
     * many it holds (the read card's ring). [stopBeforeFor] does the same with a limit chosen from the pen's serial (null = read everything).
     * Throws [IOException] when the pen can't be selected, the session breaks off, or no serial arrived.
     */
    fun read(t: Transceiver, nowEpochS: Long = System.currentTimeMillis() / 1000, log: (String) -> Unit = {}, stopBefore: Long? = null,
             stopBeforeFor: ((serial: String) -> Long?)? = null, progress: (Float) -> Unit = {}): PenRead =
        OpenNovLog.with(log) {
            val started = System.nanoTime()
            val now = { nowEpochS + (System.nanoTime() - started) / 1_000_000_000L }
            val ts = T4Transceiver(t)
            val ph = PHDllHelper(ts)
            if (!ts.doNeededSelection()) throw IOException("not an OpenNov pen (Type 4 PHD selection failed)")
            // [stopBeforeFor]: the limit for the serial the pen just reported (a pen seen before reads only its new part)
            val limitFor = { c: OpContext -> stopBefore ?: c.specification?.serial?.let { s -> stopBeforeFor?.invoke(s) } }
            val stop = if (stopBefore == null && stopBeforeFor == null) null
                else { c: OpContext -> limitFor(c)?.let { limit -> c.doses.any { it.units > 0 && it.absoluteTime < limit } } == true }
            val machine = Machine(now, stop)
            var fsa = FSA.read()
            var errors = 0
            var transactions = 0
            while (fsa.doRead() && errors < MAX_ERRORS && transactions < MAX_TRANSACTIONS) {
                transactions++
                val payload = ph.extractInnerPacket(ts.readFromLinkLayer(), true)
                if (payload == null) { errors++; OpenNovLog.d { "Read cycle got null, errors $errors" }; continue }
                fsa = machine.processPayload(payload)
                // the share of the pen's records transferred so far (once the pen said how many it holds)
                if (machine.stored > 0) progress((machine.context.doses.size.toFloat() / machine.stored).coerceIn(0f, 1f))
                if (fsa.action == FSA.Action.WRITE_READ) {
                    val out = fsa.payload ?: throw IOException("no reply to build in state ${machine.state}")
                    if (!ph.writeInnerPacket(out)) throw IOException("write failed in state ${machine.state}")
                }
            }
            val c = machine.context
            val serial = c.specification?.serial?.takeIf { it.length >= 4 } ?: throw IOException("no pen serial")
            if (!machine.complete) throw IOException("transfer incomplete in state ${machine.state} after $transactions transactions")
            val doses = c.doses.filter { isDose(it) }
                .map { PenDose(it.relativeTime, it.absoluteTime, (it.units * 10).roundToLong() / 10.0, it.flags) }
                .distinctBy { it.relTime to it.units }
                .sortedBy { it.relTime }
            c.doses.filterNot { isDose(it) }.forEach { d -> OpenNovLog.d { "not a dose: rel ${d.relativeTime} units ${Hex.of(longBytes(d.unitsRaw))} flags ${Hex.of(longBytes(d.flags))}" } }
            val relNow = if (c.relativeTime >= 0) c.relativeTime else c.eventReport?.relativeTime ?: -1
            PenRead(serial, c.model, nowEpochS, relNow, doses, part = c.specification?.partNumber?.trim().orEmpty(), stored = maxOf(machine.stored, 0L).toInt(), complete = !machine.stoppedEarly)
        }

    /**
     * A record with the `FF 00` units marker and flags `0x080000xx`. xDrip keeps only flags == `0x08000000`; Juggluco
     * keeps any low byte (and logs it as a message), which we follow so a dose with a status bit isn't lost.
     */
    private fun isDose(d: InsulinDose) = d.units > 0 && (d.flags and 0xFFFFFF00L) == 0x08000000L

    private fun longBytes(v: Long) = java.nio.ByteBuffer.allocate(4).putInt(v.toInt()).array()
}
