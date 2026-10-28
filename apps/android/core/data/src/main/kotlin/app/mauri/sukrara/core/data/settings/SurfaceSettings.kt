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

    /** The value drawn in the status bar with root: system icons (root) or the System UI module's text. */
    val RootValue = SchemaSettings.bool("statusbar.hook", default = false)

    /** Where the root value goes ([HookPosition]). */
    val RootPosition = SchemaSettings.text("statusbar.hookPosition", HookPosition.DEFAULT, HookPosition.ALL.toSet())

    /** The root value in its range colour (module). */
    val RootColor = SchemaSettings.bool("statusbar.hookColor", default = true)

    /** Drop Sukrara's notification icon from the status bar while the root value shows (module). */
    val HideNotificationIcon = SchemaSettings.bool("statusbar.hideNotifIcon", default = true)

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

/**
 * Where the root status-bar value goes (`statusbar.hookPosition`). `slot:` placements are system icons set through
 * the root slot daemon: they show with root alone (first among the system icons), and the System UI module moves them
 * next to the named icon (`slot:wifi` right after Wi-Fi, `slot:<mobile` right before the mobile signal). The clock and
 * `icons:` placements are a text the module draws, so they show nothing without it.
 */
object HookPosition {
    const val AFTER_CLOCK = "after_clock"
    const val BEFORE_CLOCK = "before_clock"
    const val ICONS_PREFIX = "icons:"
    const val LEFT_OF_ICONS = "${ICONS_PREFIX}0"
    const val SLOT_PREFIX = "slot:"

    /** Marks a `slot:` target as "right before" instead of "right after". */
    const val BEFORE = "<"

    /** System icons right before the mobile signal: left of the more permanent icons. */
    const val DEFAULT = "$SLOT_PREFIX${BEFORE}mobile"

    /** The choices, in the order the placement dialog lists them. */
    val ALL =
        listOf(
            AFTER_CLOCK,
            BEFORE_CLOCK,
            LEFT_OF_ICONS,
            SLOT_PREFIX,
            "${SLOT_PREFIX}vpn",
            "${SLOT_PREFIX}bluetooth",
            DEFAULT,
            "${SLOT_PREFIX}mobile",
            "${SLOT_PREFIX}wifi",
        )

    /** Shown as system icons (the root slot daemon runs). */
    fun isSystemIcons(pos: String) = pos.startsWith(SLOT_PREFIX)

    /** Shows exactly as chosen only with the System UI module ("With the system icons" needs root alone). */
    fun needsModule(pos: String) = pos != SLOT_PREFIX

    /** The slot icon a `slot:` placement sits next to, and whether before it; null = no target. */
    fun target(pos: String): Pair<String, Boolean>? =
        pos
            .takeIf { isSystemIcons(it) }
            ?.removePrefix(SLOT_PREFIX)
            ?.takeIf { it.isNotEmpty() }
            ?.let { if (it.startsWith(BEFORE)) it.removePrefix(BEFORE) to true else it to false }
}
