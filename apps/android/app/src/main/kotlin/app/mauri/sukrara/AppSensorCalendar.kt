// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara

import android.content.Context
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.storage.SettingsRepository
import app.mauri.sukrara.core.model.storage.StateStore
import app.mauri.sukrara.feature.settings.sensorend.CalendarChoice
import app.mauri.sukrara.feature.settings.sensorend.SensorCalendarPort
import app.mauri.sukrara.feature.settings.sensorend.SensorCalendarState
import app.mauri.sukrara.monitor.sensorend.CalendarRecord
import app.mauri.sukrara.monitor.sensorend.SensorCalendar
import app.mauri.sukrara.sensor.SensorInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * *Sensor end in calendar* between the settings page and the calendar writer: the wearer's phone keeps one event per
 * sensor in line with the setting, the chosen calendar and the current sensor ([keepInLine]). A follower is never
 * offered it and never writes (it has no sensor of its own).
 */
class AppSensorCalendar(
    context: Context,
    private val calendar: SensorCalendar,
    private val record: StateStore<CalendarRecord>,
    private val settings: SettingsRepository,
    private val role: Flow<Role?>,
    private val sensorInfo: Flow<SensorInfo?>,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) : SensorCalendarPort {
    private val googleCalendar =
        runCatching {
            context.packageManager.getLaunchIntentForPackage(SensorCalendar.GOOGLE_CALENDAR) != null
        }.getOrDefault(false)

    override val state: Flow<SensorCalendarState?> =
        combine(role, settings.flow(SensorCalendar.On), settings.flow(SensorCalendar.Target), record.data) { r, on, target, rec ->
            if (r != Role.Wearer) {
                null
            } else {
                val own = target == SensorCalendar.OWN
                SensorCalendarState(
                    on = on && calendar.granted(),
                    own = own,
                    name = if (own) "" else rec.targetName,
                    id = target.toLongOrNull() ?: -1,
                    googleCalendar = googleCalendar,
                )
            }
        }

    override val permissions: Array<String> get() = SensorCalendar.PERMISSIONS

    override fun granted() = calendar.granted()

    override fun setOn(on: Boolean) {
        scope.launch {
            settings.set(SensorCalendar.On, on)
            if (!on) withContext(io) { calendar.removeAll(settings.flow(SensorCalendar.Target).first()) }
        }
    }

    override suspend fun choices(): List<CalendarChoice> =
        withContext(io) { calendar.choices().map { CalendarChoice(it.id, it.name, it.account, it.synced) } }

    override fun choose(choice: CalendarChoice?) {
        scope.launch {
            val was = settings.flow(SensorCalendar.Target).first()
            val target = choice?.id?.toString() ?: SensorCalendar.OWN
            if (was == target) return@launch
            withContext(io) { calendar.removeAll(was) }
            record.update { it.copy(targetName = choice?.name.orEmpty()) }
            // the writer follows the setting and writes the current sensor's event in the new place
            settings.set(SensorCalendar.Target, target)
        }
    }

    override fun recheck() {
        // a permission revoked in Android's settings: off (nothing can be written or removed now)
        if (!calendar.granted()) scope.launch { if (settings.flow(SensorCalendar.On).first()) settings.set(SensorCalendar.On, false) }
    }

    /** Runs for the app's life: every change of role, setting, calendar or sensor rewrites the event. */
    suspend fun keepInLine() {
        combine(role, settings.flow(SensorCalendar.On), settings.flow(SensorCalendar.Target), sensorInfo) { r, on, target, info ->
            Triple(r == Role.Wearer && on, target, info?.let { SensorCalendar.Sensor(it.serial, it.startEpoch, it.endEpoch) })
        }.collectLatest { (on, target, sensor) ->
            if (!on) return@collectLatest
            val ok = withContext(io) { calendar.sync(true, target, sensor, clock.now().toEpochMilli()) }
            // the picked calendar (or its account) is gone: back to Sukrara's own
            if (!ok) {
                record.update { it.copy(targetName = "", picked = emptyMap()) }
                settings.set(SensorCalendar.Target, SensorCalendar.OWN)
            }
        }
    }
}
