// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.sensors.harness

import dev.spike.sensors.BleLink
import dev.spike.sensors.DriverStatus
import dev.spike.sensors.Reading
import dev.spike.sensors.SensorEvents
import dev.spike.sensors.Session
import dev.spike.sensors.aidex.AidexX
import dev.spike.sensors.aidex.AidexXDriver
import dev.spike.sensors.cgms.CgmService
import dev.spike.sensors.cgms.SmartGuideDriver
import dev.spike.sensors.dexcom.Dexcom
import dev.spike.sensors.dexcom.G6Messages
import dev.spike.sensors.dexcom.G7Driver
import dev.spike.sensors.dexcom.G7Messages
import dev.spike.sensors.hex
import java.io.File
import java.util.UUID

/**
 * Decoder harness: replay a recorded trace through a driver and print what it decodes, with no Bluetooth and no
 * Android. This is how a new driver is checked before any hardware exists.
 *
 *   ./gradlew :core:run --args="g7 core/src/test/resources/traces/g7-session.txt"
 *   ./gradlew :core:run --args="smartguide"      # the bundled trace of that name
 *   ./gradlew :core:run --args="aidex"
 *   ./gradlew :core:run --args="g6"
 */
fun main(args: Array<String>) {
    val which = args.firstOrNull() ?: "all"
    val file = args.getOrNull(1)?.let { File(it) }
    fun trace(name: String) = if (file != null) Trace.load(file) else Trace.resource(name)

    when (which) {
        "g7" -> g7(trace("g7-session"))
        "g6" -> g6(trace("g6-session"))
        "smartguide" -> smartGuide(trace("smartguide"))
        "aidex" -> aidex(trace("aidex-x"))
        "all" -> { g7(Trace.resource("g7-session")); g6(Trace.resource("g6-session"))
                   smartGuide(Trace.resource("smartguide")); aidex(Trace.resource("aidex-x")) }
        else -> error("unknown driver '$which' (g7, g6, smartguide, aidex)")
    }
}

private class Printer(val tag: String) : SensorEvents {
    override fun onReading(reading: Reading) = say("reading ${reading.mgdl} mg/dL" +
        (reading.trendMgdlPerMin?.let { " %+.1f mg/dL/min".format(it) } ?: "") +
        (reading.sensorState?.let { " [$it]" } ?: "") + " index=${reading.index}")
    override fun onHistory(readings: List<Reading>) = say("history ${readings.size} values: " +
        readings.joinToString(", ") { "${it.mgdl}@${it.index}" })
    override fun onSession(session: Session) = say("session start=${session.startMs} state=${session.state}")
    override fun onStatus(status: DriverStatus) = say("status $status")
    override fun onPairingState(state: ByteArray) = say("pairing state ${state.hex()}")
    private fun say(line: String) = println("[$tag] $line")
}

private fun header(name: String) = println("\n=== $name".let { it + " " + "=".repeat(maxOf(0, 60 - it.length)) })

private fun g7(trace: Trace) {
    header("Dexcom G7 / ONE+ (observer mode)")
    val events = Printer("g7")
    val driver = G7Driver(events, mode = G7Driver.Mode.OBSERVE, now = { 1_760_000_000_000 })
    // The official app's session going live is what starts us; then the sensor notifies everyone.
    driver.onConnected(Silent)
    driver.onNotification(Dexcom.AUTHENTICATION, byteArrayOf(0x05, 0x01, 0x01))
    println("[g7] phase after the app's session went live: ${driver.phase}")
    for (entry in trace.entries.filter { it.label.startsWith("egv") || it.label.startsWith("life") }) {
        val egv = entry.bytes
        val parsed = G7Messages.Egv.parse(egv) ?: continue
        println("[g7] ${entry.label} ${egv.hex()}")
        println("[g7]   t=${parsed.secondsSinceStart}s seq=${parsed.sequence} age=${parsed.ageSeconds}s " +
            "glucose=${parsed.mgdl ?: "none"} state=${Dexcom.stateName(parsed.state)} trend=${parsed.trend} " +
            "predicted=${parsed.predictedMgdl ?: "none"} displayOnly=${parsed.displayOnly}")
        driver.onEgv(parsed)
    }
    trace.all("backfill-record").forEach { driver.onBackfillRecords(it) }
    driver.flushBackfill()
}

private fun g6(trace: Trace) {
    header("Dexcom G6 / ONE")
    println("[g6] key for transmitter 8GXXXX: ${G6Messages.key("8GXXXX").hex()}")
    println("[g6] challenge answer for 0001020304050607: " +
        Dexcom.challengeHash(G6Messages.key("8GXXXX"), byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)).hex())
    for ((label, bytes) in trace.entries.map { it.label to it.bytes }) {
        val ok = G6Messages.crcOk(bytes)
        println("[g6] $label ${bytes.hex()} crc=${if (ok) "ok" else "BAD"}")
        G6Messages.BackfillReply.parse(bytes)?.let {
            println("[g6]   backfill ${it.startSeconds}..${it.endSeconds}s, ${it.length} bytes, payload crc 0x%04x".format(it.bufferCrc))
        }
    }
}

private fun smartGuide(trace: Trace) {
    header("Accu-Chek SmartGuide (standard CGM Service)")
    val events = Printer("accu")
    val driver = SmartGuideDriver(events, now = { 1_760_000_000_000 })
    driver.onStatusRead(trace["status"])
    for (entry in trace.entries) {
        if (entry.label == "status") continue
        val m = CgmService.Measurement.parse(entry.bytes)
        println("[accu] ${entry.label} ${entry.bytes.hex()}")
        println("[accu]   offset=${m?.offsetMinutes} glucose=${m?.mgdl ?: "none"} trend=${m?.trendMgdlPerMin} " +
            "quality=${m?.qualityPercent} belowRange=${m?.belowRange} crc=${m?.crcOk}")
        driver.measurement(entry.bytes)?.let { println("[accu]   → ${it.mgdl} mg/dL at ${it.timeMs}") }
    }
    println("[accu] SOCP 'get interval' with its E2E-CRC: ${CgmService.getCommunicationInterval().hex()}")
}

private fun aidex(trace: Trace) {
    header("Aidex X")
    val serial = trace["serial"].let { String(it, Charsets.US_ASCII) }
    val events = Printer("aidex")
    val driver = AidexXDriver(serial, events, now = { 1_760_000_000_000 })
    val iv = AidexX.iv(serial)
    println("[aidex] serial $serial iv=${iv.hex()} askKey=${AidexX.askKey(serial).hex()}")
    val session = AidexX.sessionKey(trace["notified-key"], iv, trace["session-read"])
    println("[aidex] session key ${session?.hex() ?: "CRC FAILED"}")
    if (session == null) return
    for (entry in trace.entries.filter { it.label.startsWith("rx-") }) {
        val plain = AidexX.reply(session, iv, entry.bytes)
        println("[aidex] ${entry.label} ${entry.bytes.hex()} → ${plain?.hex() ?: "CRC FAILED"}")
    }
    AidexX.Glucose.parseReply(trace["plain-glucose"].let { it.copyOf(it.size - 2) })?.let {
        println("[aidex] glucose reply: ${it.mgdl} mg/dL, ${it.minutesSinceStart} min, trend ${it.trend}, quality ${it.quality}")
        driver.onGlucose(it)
    }
    AidexX.Advertisement.parse(trace["advertisement"])?.let {
        println("[aidex] advertisement from ${it.name}: ${it.latest.mgdl} mg/dL (then ${it.previous}), crc=${it.crcOk}")
        driver.onAdvertisement(trace["advertisement"])
    }
}

/** A link that swallows every operation: the harness replays notifications, it does not talk to anything. */
private object Silent : BleLink {
    override val isBonded = true
    override fun subscribe(characteristic: UUID, indicate: Boolean) {}
    override fun write(characteristic: UUID, value: ByteArray, withResponse: Boolean) {}
    override fun bond() {}
    override fun disconnect() {}
}
