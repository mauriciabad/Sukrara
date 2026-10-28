// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara

import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.alerts.AlertSettingsCodec
import app.mauri.sukrara.alerts.AlertSource
import app.mauri.sukrara.core.data.settings.FollowerSettingsRepository
import app.mauri.sukrara.core.data.settings.MirroredGraph
import app.mauri.sukrara.core.data.settings.Setting
import app.mauri.sukrara.core.data.settings.SettingsFrom
import app.mauri.sukrara.core.data.settings.SurfaceSettings
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.storage.SettingsRepository
import app.mauri.sukrara.feature.settings.follower.SettingsFromPort
import app.mauri.sukrara.feature.settings.follower.SettingsFromState
import app.mauri.sukrara.graph.GraphSettings
import app.mauri.sukrara.monitor.alerts.AlertMirror
import app.mauri.sukrara.protocol.event.SettingsPayload
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.Instant

/**
 * A follower's *Settings from* over this phone's settings ([own]), the alert settings ([alerts]: read, and an atomic
 * update) and the wearer's mirrored settings ([wearer], the follower engine's; read only while *Wearer* is chosen,
 * so the wearer's phone never waits for an engine it doesn't run). [settings] is what the cards, the notification and
 * the sensor-end reminders read; [alertMirror] what the alert runtime runs a follower's rules with; the alerts'
 * `source` follows the choice ([keepAlertSource]). On the wearer's phone everything is its own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FollowerSettingsBridge(
    private val own: SettingsRepository,
    private val alerts: AlertsAccess,
    role: Flow<Role?>,
    private val wearer: Flow<SettingsPayload?>,
    wearerName: Flow<String?>,
) : SettingsFromPort {
    /** The alert settings store (the alert runtime's). */
    interface AlertsAccess {
        val settings: Flow<AlertSettings>

        suspend fun update(transform: (AlertSettings) -> AlertSettings)
    }

    private val stored: Flow<SettingsFrom> = own.flow(SettingsFrom.Stored).map(SettingsFrom::fromWire).distinctUntilChanged()

    /** The choice in effect: the stored one on a follower, Custom (its own) on the wearer's phone. */
    private val from: Flow<SettingsFrom> =
        combine(role, stored) { r, f -> if (r == Role.Follower) f else SettingsFrom.Custom }.distinctUntilChanged()

    /** The wearer's payload while *Wearer* is in effect, null otherwise. */
    private val wearerInUse: Flow<SettingsPayload?> =
        from.flatMapLatest { f -> if (f == SettingsFrom.Wearer) wearer else flowOf(null) }

    val settings: SettingsRepository = FollowerSettingsRepository(own, from, wearerInUse.map { it?.graph })

    val alertMirror: Flow<AlertSettings?> =
        combine(from, wearerInUse) { f, p -> mirroredAlerts(f, p) }.distinctUntilChanged()

    /** What the alert pages show: the settings the alert runtime runs ([alertMirror] over this phone's own). */
    override val alertsInUse: Flow<AlertSettings?> =
        combine(alerts.settings, alertMirror) { own, mirror -> mirror?.let { AlertMirror.effective(own, it) } }.distinctUntilChanged()

    override val state: Flow<SettingsFromState?> =
        combine(role, stored, wearerOnFollower(role), wearerName) { r, f, p, name ->
            if (r != Role.Follower) null else SettingsFromState(f, name, p?.let { Instant.ofEpochSecond(it.u) })
        }.distinctUntilChanged()

    private fun wearerOnFollower(role: Flow<Role?>): Flow<SettingsPayload?> =
        role.distinctUntilChanged().flatMapLatest { r -> if (r == Role.Follower) wearer else flowOf(null) }

    override suspend fun choose(from: SettingsFrom) = own.set(SettingsFrom.Stored, from.wire)

    override suspend fun copyWearer(): Boolean {
        val p = wearer.first() ?: return false
        MirroredGraph.copyInto(own, MIRRORED_GRAPH, SettingsFrom.Wearer, p.graph)
        alerts.update { AlertMirror.effective(it, mirroredAlerts(SettingsFrom.Wearer, p)) }
        return true
    }

    override suspend fun resetToDefault() {
        MirroredGraph.copyInto(own, MIRRORED_GRAPH, SettingsFrom.Default, null)
        alerts.update { AlertMirror.effective(it, AlertSettings()) }
    }

    /**
     * Keeps the alerts' `source` (schema `alerts.source`, derived from `follower.settingsFrom`) on the stored choice:
     * *Wearer* mirrors the wearer's `alert` events, *Default* / *Custom* run the rules here. Runs for the app's life.
     */
    suspend fun keepAlertSource() {
        stored.collect { f ->
            val source = sourceOf(f)
            alerts.update { if (it.source == source) it else it.copy(source = source) }
        }
    }

    companion object {
        /** Every mirrored graph setting this phone stores (what *Copy* and *Reset* write). */
        val MIRRORED_GRAPH: List<Setting<*>> =
            GraphSettings.Bands +
                listOf(
                    GraphSettings.Hours,
                    GraphSettings.GridHours,
                    GraphSettings.RingCount,
                    GraphSettings.Side,
                    GraphSettings.ColumnCrown,
                    GraphSettings.RingMarks,
                    GraphSettings.RingRange,
                    GraphSettings.LastPlace,
                    GraphSettings.Slots,
                    GraphSettings.RingOrder,
                    GraphSettings.Lines,
                    GraphSettings.LastElse,
                    GraphSettings.Marks,
                    SurfaceSettings.CountdownHours,
                )

        fun sourceOf(from: SettingsFrom): AlertSource = if (from == SettingsFrom.Wearer) AlertSource.WEARER else AlertSource.OWN

        /**
         * The values the mirrored alert rows take ([AlertMirror.effective]): Custom = this phone's own (null);
         * Default = the defaults; Wearer = the wearer's, a missing key at its default, all defaults until they arrive.
         */
        fun mirroredAlerts(
            from: SettingsFrom,
            wearer: SettingsPayload?,
        ): AlertSettings? =
            when (from) {
                SettingsFrom.Custom -> null
                SettingsFrom.Default -> AlertSettings()
                SettingsFrom.Wearer -> wearer?.let { AlertSettingsCodec.decode(it.alerts.toString()) } ?: AlertSettings()
            }
    }
}
