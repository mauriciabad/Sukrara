// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor.brand

import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.sensor.SensorProfile
import app.mauri.sukrara.sensor.ble.BleLink
import app.mauri.sukrara.sensor.ble.FailReason
import app.mauri.sukrara.sensor.ble.LinkStatus
import app.mauri.sukrara.sensor.ble.NoValueReason
import app.mauri.sukrara.sensor.ble.SensorDriver
import app.mauri.sukrara.sensor.ble.SensorEvents
import app.mauri.sukrara.sensor.pairing.Pairing
import java.time.Instant
import java.util.UUID
import kotlin.math.abs

/** What a scan saw of a device. [bonded]: this phone already has a Bluetooth bond with it. */
internal data class Advert(
    val address: String,
    val name: String? = null,
    val serviceUuids: List<UUID> = emptyList(),
    val bonded: Boolean = false,
)

/** One scan filter: a service the device advertises and/or its exact name (Android's `ScanFilter`). */
internal data class ScanSpec(val service: UUID? = null, val name: String? = null)

/**
 * What the scanned families (Dexcom G7 / G6, Accu-Chek SmartGuide, Aidex X) share on top of [SensorDriver]: how they
 * are found ([scan], [matches]), the session start, the reading rules and the ways a session stops. None of them was
 * ever tested on a real sensor: the decoding is pinned by open-source recorded frames.
 *
 * Reading rules (the person acts on these values):
 * - a value is only ever the sensor's own number, in its [validMgdl] range; below it the reading is the flagged LO,
 *   above it HI ([Reading.lo] / [Reading.hi]: the card says "LO" / "HI", the alerts treat it as beyond every
 *   threshold), never a made-up number; 0 (no number at all) is no value;
 * - its time comes from the sensor's own clock; one in the future is dropped, one older than [LIVE_S] is history
 *   (never the current value the alerts read).
 */
internal abstract class BrandDriver(
    protected val pairing: Pairing,
    protected val events: SensorEvents,
    private val clockMs: () -> Long,
    protected val log: (String) -> Unit,
) : SensorDriver {
    val profile: SensorProfile get() = pairing.profile

    /** What the scanner filters on (never an unfiltered scan). */
    abstract val scan: List<ScanSpec>

    /** Should the source connect to this device? */
    abstract fun matches(advert: Advert): Boolean

    /** A scan result that passed [scan], before [matches]: [record] is the raw advertising data (Aidex X carries values). */
    open fun onAdvertisement(
        advert: Advert,
        record: ByteArray,
    ) {}

    /** The range the family's sensor reports numbers in (mg/dL). */
    protected open val validMgdl: IntRange = 39..501

    /**
     * Numbers that are a measurement at all: outside, the field holds a code or garbage, not glucose (no value, never
     * a LO / HI that would sound the very low alarm). Inside but outside [validMgdl]: LO / HI.
     */
    protected open val plausibleMgdl: IntRange = 1..Int.MAX_VALUE

    protected var link: BleLink? = null

    /** The session start (epoch s) as saved with the pairing or reported since; null until the sensor said. */
    protected var knownStart: Long? = pairing.startEpoch.takeIf { it > 0 }
        private set

    /** Epoch seconds. */
    protected fun now() = clockMs() / 1000

    /**
     * The sensor's session start, derived from its own clock. Reported (and kept) when it is new or moved by more than
     * [START_JITTER_S]; else the known one stays, so the readings' times don't wobble with the phone's clock.
     * [authoritative] = false: derived from a value record that the sensor might repeat (Aidex X); once known such a
     * start never moves, so a repeated record keeps its old time instead of looking current.
     */
    protected fun sessionStart(
        startEpoch: Long,
        authoritative: Boolean = true,
    ): Long {
        val known = knownStart
        if (known != null && (!authoritative || abs(known - startEpoch) <= START_JITTER_S)) return known
        knownStart = startEpoch
        events.onSessionStart(startEpoch)
        return startEpoch
    }

    /** A value from the sensor measured at [epochSec]: live when recent, else history; out of range → LO / HI. */
    protected fun reading(
        epochSec: Long,
        mgdl: Int,
        rate: Double?,
    ) {
        val r = toReading(epochSec, mgdl, rate) ?: return events.onStatus(LinkStatus.NoValue(NoValueReason.Missing))
        deliver(r)
    }

    /** The sensor's own "below / above my range" flag at [epochSec] (no number): a LO / HI reading. */
    protected fun outOfRange(
        epochSec: Long,
        high: Boolean,
    ) {
        val t = Instant.ofEpochSecond(epochSec)
        deliver(if (high) Reading.hi(t, profile.source) else Reading.lo(t, profile.source))
    }

    private fun deliver(r: Reading) {
        val now = now()
        val epochSec = r.t.epochSecond
        if (epochSec > now + FUTURE_SLACK_S) return log("dropping a reading ${epochSec - now} s in the future")
        if (now - epochSec <= LIVE_S) {
            events.onStatus(LinkStatus.Live)
            events.onReading(r)
        } else {
            events.onHistory(listOf(r))
        }
    }

    /**
     * Stored values (backfill): history only, numbers in [validMgdl] (a stored record carries no LO / HI flag of its
     * own, so an out-of-range number there is left out rather than guessed into one).
     */
    protected fun history(values: List<Triple<Long, Int, Double?>>) {
        val now = now()
        val ok =
            values
                .filter { (t, mgdl, _) -> mgdl in validMgdl && t <= now + FUTURE_SLACK_S }
                .map { (t, mgdl, rate) -> Reading(Instant.ofEpochSecond(t), mgdl, rate, profile.source) }
        if (ok.isNotEmpty()) events.onHistory(ok)
    }

    /** The sensor's number as a reading: below [validMgdl] the flagged LO, above it HI; outside [plausibleMgdl] none. */
    private fun toReading(
        epochSec: Long,
        mgdl: Int,
        rate: Double?,
    ): Reading? {
        val t = Instant.ofEpochSecond(epochSec)
        return when {
            mgdl !in plausibleMgdl -> null
            mgdl < validMgdl.first -> Reading.lo(t, profile.source)
            mgdl > validMgdl.last -> Reading.hi(t, profile.source)
            else -> Reading(t, mgdl, rate, profile.source)
        }
    }

    /** The sensor said its session is over for good: no more connections until a new sensor is paired. */
    protected fun ended(why: String) {
        log("sensor ${pairing.label} ended ($why): not reconnecting")
        events.onSensorEnded(pairing.label)
        events.onEndedForGood(pairing.label)
        link?.close()
        events.onStatus(LinkStatus.SensorEnded)
    }

    /** Something the person has to fix (wrong id, refused PIN): give the device up, say why. */
    protected fun fail(
        reason: FailReason,
        why: String,
    ) {
        log("sensor: $why")
        link?.close()
        events.onStatus(LinkStatus.Failed(reason))
    }

    /** A transient problem: drop the connection, the transport reconnects. */
    protected fun retry(why: String) {
        log("sensor: $why")
        events.onStatus(LinkStatus.Reconnecting(why))
        link?.disconnect()
    }

    override fun onLinkFailed(why: String) = retry(why)

    companion object {
        /** A reading at most this old (s) is the current value; older ones are history. */
        const val LIVE_S = 180L

        /** A reading this far ahead of the phone's clock (s) is still accepted (clock skew); further is dropped. */
        const val FUTURE_SLACK_S = 60L

        /** A newly derived session start within this of the known one (s) is the same session (below [FUTURE_SLACK_S]). */
        const val START_JITTER_S = 30L
    }
}
