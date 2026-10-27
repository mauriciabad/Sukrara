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
 * `SYNTHETIC`. One reading a minute on a 3-hour sine between 60 and 200 mg/dL (lows, in range, highs), with a 3-hour
 * backfill. Bluetooth off pauses it (Interrupted) like a real sensor; [frozen] stops new readings so the stale states
 * can be shot by moving the emulator's clock.
 */
class SyntheticSource(
    private val clock: Clock,
    private val frozen: () -> Boolean,
) : ReadingSource {
    private val bluetooth = MutableStateFlow(true)
    private val mutableStatus = MutableStateFlow<SourceStatus>(SourceStatus.Connecting)

    override val sensor: StateFlow<SensorState> =
        MutableStateFlow<SensorState>(SensorState.Active(endsAt = clock.now() + Duration.ofDays(10))).asStateFlow()

    override val status: StateFlow<SourceStatus> = mutableStatus.asStateFlow()

    override val readings: Flow<List<Reading>> =
        flow {
            var last = clock.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
            emit((BACKFILL_MINUTES downTo 0).map { reading(last - Duration.ofMinutes(it)) })
            mutableStatus.value = SourceStatus.Live(last)
            while (true) {
                val next = last + Duration.ofMinutes(1)
                delay(Duration.between(clock.now(), next).toMillis().coerceAtLeast(0))
                last = next
                if (bluetooth.value && !frozen()) {
                    emit(listOf(reading(next)))
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
        private const val AMPLITUDE = 70.0
        private const val PREFS = "debug-fakes"
        private const val KEY_ON = "synthetic"
        private const val KEY_FROZEN = "syntheticFrozen"

        fun reading(t: Instant): Reading {
            val phase = 2 * PI * (t.epochSecond / 60.0) / PERIOD_MINUTES
            val rate = AMPLITUDE * 2 * PI / PERIOD_MINUTES * cos(phase)
            return Reading(t, (MIDDLE + AMPLITUDE * sin(phase)).roundToInt(), rate, ID)
        }

        fun isOn(context: Context) = prefs(context).getBoolean(KEY_ON, false)

        fun isFrozen(context: Context) = prefs(context).getBoolean(KEY_FROZEN, false)

        fun set(
            context: Context,
            on: Boolean,
            frozen: Boolean,
        ) = prefs(context).edit().putBoolean(KEY_ON, on).putBoolean(KEY_FROZEN, frozen).commit()

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
