// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core.data.settings

import app.mauri.sukrara.core.model.SensorEndConfig
import app.mauri.sukrara.core.model.SensorReminder
import app.mauri.sukrara.core.model.storage.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The status bar's and the sensor end's settings, declared once: the monitor (glucose notification, sensor-end
 * notifier) and the settings pages read the same objects, so a page and a notification can't disagree on a default.
 */
object SurfaceSettings {
    /** The glucose notification is a promoted Live Update (the status-bar chip). */
    val StatusChip = SchemaSettings.bool("statusbar.chip", default = true)

    /** Without the chip, the notification's small icon is the number itself. */
    val NumberIcon = SchemaSettings.bool("statusbar.icon", default = true)

    /** Settings › Sensor end as JSON ([SensorEndConfig]); empty = the defaults. */
    val SensorReminders = SchemaSettings.text("sensor.reminders", default = "")

    /** The end countdown's hours (also the card's countdown ring). */
    val CountdownHours = SchemaSettings.number("sensor.countdownHours")

    fun sensorEnd(settings: SettingsRepository): Flow<SensorEndConfig> =
        settings.flow(SensorReminders).map(::decodeSensorEnd).distinctUntilChanged()

    suspend fun setSensorEnd(
        settings: SettingsRepository,
        config: SensorEndConfig,
    ) = settings.set(SensorReminders, encodeSensorEnd(config))

    private val json = Json { ignoreUnknownKeys = true }

    /** Invalid reminders are dropped; a missing field keeps its default; an invalid night window: the default one. */
    fun decodeSensorEnd(text: String): SensorEndConfig {
        if (text.isBlank()) return SensorEndConfig()
        val w = runCatching { json.decodeFromString(Wire.serializer(), text) }.getOrNull() ?: return SensorEndConfig()
        val d = SensorEndConfig()
        val reminders =
            w.reminders?.mapNotNull { r -> runCatching { SensorReminder(r.days, r.minute) }.getOrNull() } ?: d.reminders
        val ns = w.nightStart ?: d.nightStart
        val ne = w.nightEnd ?: d.nightEnd
        val night = runCatching { d.copy(nightStart = ns, nightEnd = ne) }.getOrDefault(d)
        return night
            .copy(
                reminders = reminders,
                countdown = w.countdown ?: d.countdown,
                warmup = w.warmup ?: d.warmup,
                unlock = w.unlock ?: d.unlock,
                evening = w.evening ?: d.evening,
            ).normalized()
    }

    fun encodeSensorEnd(c: SensorEndConfig): String =
        json.encodeToString(
            Wire.serializer(),
            Wire(
                c.reminders.map { WireReminder(it.daysBefore, it.minuteOfDay) },
                c.countdown,
                c.warmup,
                c.unlock,
                c.evening,
                c.nightStart,
                c.nightEnd,
            ),
        )

    @Serializable
    private data class WireReminder(
        val days: Int,
        val minute: Int,
    )

    @Serializable
    private data class Wire(
        val reminders: List<WireReminder>? = null,
        val countdown: Boolean? = null,
        val warmup: Boolean? = null,
        val unlock: Boolean? = null,
        val evening: Boolean? = null,
        val nightStart: Int? = null,
        val nightEnd: Int? = null,
    )
}
