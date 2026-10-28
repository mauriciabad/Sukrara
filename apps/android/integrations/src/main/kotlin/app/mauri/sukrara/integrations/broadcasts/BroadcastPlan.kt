// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.integrations.broadcasts

import app.mauri.sukrara.core.model.GlucoseRange
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.integrations.CurrentSensor
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant

/** One app the person allowed: its package and the SHA-256 (hex) of the signing certificate it had then. */
@Serializable
data class AllowedApp(
    val pkg: String,
    val signer: String,
)

/** The schema group `apps.broadcasts`: the allowed apps (none by default). */
@Serializable
data class BcSettings(
    val apps: List<AllowedApp> = emptyList(),
)

/**
 * What the sender remembers across restarts: the last sent reading's time (the throttle), its sensor serial and life
 * count (never the same reading twice, even after a restart or a clock change), and the sensor serial each package got
 * SENSOR_ACTIVATE for.
 */
@Serializable
data class BcState(
    val lastSentMs: Long = 0,
    val lastSerial: String = "",
    val lastLifeCount: Int = -1,
    val activated: Map<String, String> = emptyMap(),
)

/**
 * The Libre 3 driver's live reading: mg/dL, mg/dL/min, time (when the phone got it), its sensor's serial and life
 * count (minutes since the sensor started: the sensor's own clock).
 */
data class LiveReading(
    val mgdl: Int,
    val rate: Double?,
    val t: Instant,
    val serial: String,
    val lifeCount: Int,
)

/** Why a live reading wasn't sent. */
enum class Hold { Follower, Stale, Future, OutOfRange, TooSoon, Repeated, NoApp }

/** For one reading: either why nothing goes out, or who gets which actions. */
sealed interface BcDecision {
    data class Wait(val why: Hold) : BcDecision

    data class Send(val out: BroadcastPayloads.Out, val actions: Map<String, List<String>>) : BcDecision
}

/**
 * The broadcast rules, pure. A closed loop (AAPS) may dose insulin from these, so only the safest data goes out:
 * the wearer's own Libre 3 read by this phone (the input is the Libre 3 driver's live reading only: never history,
 * never Juggluco, an untested family, a follower feed or debug data), at most [MAX_AGE] old, never in the future,
 * never LO / HI (no number), at most one per [MIN_INTERVAL] and never the same or an older reading twice (by the
 * sensor's life count, which the phone's clock can't move). Each allowed app whose signer still matches gets ONE
 * format, the first it listens for in [PREFERENCE]: an app that listens for several (GlucoDataHandler) would
 * otherwise get the same reading two or three times, and a loop behind it would see doubled readings.
 * SENSOR_ACTIVATE goes with the LibreLink format only, needs the sensor's real start, and goes once per package and
 * serial.
 */
object BroadcastPlan {
    val MIN_INTERVAL: Duration = Duration.ofSeconds(55)
    val MAX_AGE: Duration = Duration.ofMinutes(5)
    val MAX_AHEAD: Duration = Duration.ofMinutes(1)

    /** One format per app, in this order. */
    val PREFERENCE = listOf(BroadcastPayloads.XDRIP, BroadcastPayloads.LIBRE, BroadcastPayloads.GLUCODATA)

    fun decide(
        role: Role?,
        live: LiveReading,
        now: Instant,
        state: BcState,
        sensor: CurrentSensor?,
        /** Allowed packages whose signer still matches → the actions each declares a receiver for. */
        receivers: Map<String, Set<String>>,
    ): BcDecision {
        if (role != Role.Wearer) return BcDecision.Wait(Hold.Follower)
        if (live.t > now + MAX_AHEAD) return BcDecision.Wait(Hold.Future)
        if (Duration.between(live.t, now) > MAX_AGE) return BcDecision.Wait(Hold.Stale)
        if (GlucoseRange.isLo(live.mgdl) || GlucoseRange.isHi(live.mgdl)) return BcDecision.Wait(Hold.OutOfRange)
        if (live.serial == state.lastSerial && live.lifeCount <= state.lastLifeCount) return BcDecision.Wait(Hold.Repeated)
        val timeMs = live.t.toEpochMilli()
        // a last-sent time ahead of the clock (the clock was moved back) would hold every reading: the life count
        // above still keeps a reading from going twice
        val last = state.lastSentMs.takeIf { it <= (now + MAX_AHEAD).toEpochMilli() } ?: 0L
        if (timeMs <= last || timeMs - last < MIN_INTERVAL.toMillis()) return BcDecision.Wait(Hold.TooSoon)
        val start = sensor?.takeIf { it.serial == live.serial }?.start?.toEpochMilli() ?: 0L
        val actions =
            receivers
                .mapValues { (pkg, declared) ->
                    when (val format = PREFERENCE.firstOrNull { it in declared }) {
                        null -> {
                            emptyList()
                        }

                        BroadcastPayloads.LIBRE -> {
                            val activate =
                                BroadcastPayloads.LIBRE_ACTIVATE in declared && start > 0 && state.activated[pkg] != live.serial
                            if (activate) listOf(BroadcastPayloads.LIBRE_ACTIVATE, format) else listOf(format)
                        }

                        else -> {
                            listOf(format)
                        }
                    }
                }.filterValues { it.isNotEmpty() }
        if (actions.isEmpty()) return BcDecision.Wait(Hold.NoApp)
        return BcDecision.Send(BroadcastPayloads.Out(live.mgdl, live.rate, timeMs, live.serial, start), actions)
    }

    /** The state after [sent] went out: the throttle moves, and each package that got SENSOR_ACTIVATE is recorded. */
    fun after(
        state: BcState,
        sent: BcDecision.Send,
        lifeCount: Int,
        delivered: Map<String, List<String>>,
    ): BcState =
        state.copy(
            lastSentMs = sent.out.timeMs,
            lastSerial = sent.out.serial,
            lastLifeCount = lifeCount,
            activated =
                state.activated +
                    delivered.filterValues { BroadcastPayloads.LIBRE_ACTIVATE in it }.mapValues { sent.out.serial },
        )
}
