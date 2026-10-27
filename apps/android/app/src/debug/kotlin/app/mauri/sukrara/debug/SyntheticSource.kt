// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.debug

import android.content.Context
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.ReadingSource
import app.mauri.sukrara.core.model.SensorState
import app.mauri.sukrara.core.model.SourceId
import app.mauri.sukrara.core.model.SourceStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import java.time.Duration
import java.time.Instant
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Debug builds only (emulator screenshots and QA): a made-up wearer source, switched on with the DebugFakes command
 * `SYNTHETIC`. One reading a minute on a 3-hour sine between 45 and 215 mg/dL (very low to high), with a 3-hour
 * backfill. Bluetooth off pauses it (Interrupted) like a real sensor; [frozen] stops new readings so the stale states
 * can be shot by moving the emulator's clock.
 */
class SyntheticSource(
    private val clock: Clock,
    /** Minutes added to the time before it goes into the curve (puts a low or a high "now" for a screenshot). */
    private val shiftMinutes: Long,
    private val frozen: () -> Boolean,
    /** When the made-up sensor ends (the sensor-end warning and countdown can be shot with a near end). */
    endsAt: Instant = clock.now() + Duration.ofDays(10),
) : ReadingSource {
    private val bluetooth = MutableStateFlow(true)
    private val mutableStatus = MutableStateFlow<SourceStatus>(SourceStatus.Connecting)

    override val sensor: StateFlow<SensorState> =
        MutableStateFlow<SensorState>(SensorState.Active(endsAt = endsAt)).asStateFlow()

    override val status: StateFlow<SourceStatus> = mutableStatus.asStateFlow()

    override val readings: Flow<List<Reading>> =
        flow {
            var last = clock.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
            emit((BACKFILL_MINUTES downTo 0).map { reading(last - Duration.ofMinutes(it), shiftMinutes) })
            mutableStatus.value = SourceStatus.Live(last)
            while (true) {
                val next = last + Duration.ofMinutes(1)
                delay(Duration.between(clock.now(), next).toMillis().coerceAtLeast(0))
                last = next
                if (bluetooth.value && !frozen()) {
                    emit(listOf(reading(next, shiftMinutes)))
                    mutableStatus.value = SourceStatus.Live(next)
                } else if (mutableStatus.value is SourceStatus.Live) {
                    mutableStatus.value = SourceStatus.Interrupted(next)
                }
            }
        }

    override fun onBluetoothOn() {
        bluetooth.value = true
    }

    override fun onBluetoothOff() {
        bluetooth.value = false
    }

    companion object {
        val ID = SourceId("synthetic")
        private const val BACKFILL_MINUTES = 180L
        private const val PERIOD_MINUTES = 180.0
        private const val MIDDLE = 130.0
        private const val AMPLITUDE = 85.0
        private const val PREFS = "debug-fakes"
        private const val KEY_ON = "synthetic"
        private const val KEY_FROZEN = "syntheticFrozen"
        private const val KEY_SHIFT = "syntheticShift"
        private const val KEY_ENDS_AT = "syntheticEndsAt"

        /** The curve's lowest point is this many minutes into its cycle. */
        const val TROUGH_MINUTE = 135

        fun reading(
            t: Instant,
            shiftMinutes: Long = 0,
        ): Reading {
            val phase = 2 * PI * (t.epochSecond / 60.0 + shiftMinutes) / PERIOD_MINUTES
            val rate = AMPLITUDE * 2 * PI / PERIOD_MINUTES * cos(phase)
            return Reading(t, (MIDDLE + AMPLITUDE * sin(phase)).roundToInt(), rate, ID)
        }

        fun isOn(context: Context) = prefs(context).getBoolean(KEY_ON, false)

        fun isFrozen(context: Context) = prefs(context).getBoolean(KEY_FROZEN, false)

        fun shift(context: Context) = prefs(context).getLong(KEY_SHIFT, 0)

        /** The made-up sensor's end, or null = 10 days from when the source starts. */
        fun endsAt(context: Context): Instant? = prefs(context).getLong(KEY_ENDS_AT, 0).takeIf { it > 0 }?.let(Instant::ofEpochMilli)

        /** The shift that puts [now] at [cycleMinute] of the curve's 180-minute cycle (135 = the lowest point). */
        fun shiftFor(
            now: Instant,
            cycleMinute: Int,
        ): Long = Math.floorMod(cycleMinute - now.epochSecond / 60, PERIOD_MINUTES.toLong())

        fun set(
            context: Context,
            on: Boolean,
            frozen: Boolean,
            shift: Long,
            endsAt: Instant?,
        ) = prefs(context)
            .edit()
            .putBoolean(KEY_ON, on)
            .putBoolean(KEY_FROZEN, frozen)
            .putLong(KEY_SHIFT, shift)
            .putLong(KEY_ENDS_AT, endsAt?.toEpochMilli() ?: 0)
            .commit()

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
