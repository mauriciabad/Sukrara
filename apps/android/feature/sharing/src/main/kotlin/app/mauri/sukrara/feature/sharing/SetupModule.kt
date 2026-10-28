// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing

import android.content.Context
import app.mauri.sukrara.core.data.settings.SchemaSettings
import app.mauri.sukrara.core.model.AlertReadinessSource
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.GlucoseHub
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.storage.SettingsRepository
import app.mauri.sukrara.core.model.storage.StateStore
import app.mauri.sukrara.feature.sharing.setup.AlertSettingsAccess
import app.mauri.sukrara.feature.sharing.setup.AlertThresholds
import app.mauri.sukrara.feature.sharing.setup.FinishItem
import app.mauri.sukrara.feature.sharing.setup.RestoreGate
import app.mauri.sukrara.feature.sharing.setup.SetupFacts
import app.mauri.sukrara.feature.sharing.setup.SetupMachine
import app.mauri.sukrara.feature.sharing.setup.SetupRecord
import app.mauri.sukrara.feature.sharing.setup.ThresholdField
import app.mauri.sukrara.feature.sharing.share.LaterRequests
import app.mauri.sukrara.share.owner.OwnerShareState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant

/** The phone's role (`AppGraph`'s own `role` store, read by the hub and the alert runtime). */
interface RoleStore {
    val role: Flow<Role?>

    suspend fun set(role: Role)
}

/**
 * What the setup flow and the sharing screens need from the app: `AppGraph` implements it by returning its
 * [SetupModule]. Navigation to other features (the sensor flow) goes through callbacks the app passes to the screens.
 */
interface SharingDeps {
    val setup: SetupModule
}

/**
 * The first-run setup and sharing graph (A4: one line in `AppGraph`). Nothing is read from disk in the constructor.
 *
 * - [setupStore]: typed store `setup` ([SetupRecord]); a damaged file only shows skipped steps again (the role is
 *   in [roles]).
 * - [resetAlerts]: the alert runtime's `AlertsModule.reset()`, called when the role is chosen or changed — before any
 *   monitoring — so no phantom no-data alarm survives. Not on identity "Start fresh": by then the sensor runs, and a
 *   reset stops alerts until the hub reports a role again.
 * - [readiness]: THE alert checklist, the same instance home's reliability row reads.
 */
class SetupModule(
    context: Context,
    val setupStore: StateStore<SetupRecord>,
    val roles: RoleStore,
    val hub: GlucoseHub,
    val sharing: OwnerSharing,
    val alertSettings: AlertSettingsAccess,
    /** The app's schema settings: setup records `alerts.thresholdsConfirmedAt` (shared with Settings › Alerts). */
    private val settings: SettingsRepository,
    val resetAlerts: suspend () -> Unit,
    /** Starts monitoring once the role is stored (the monitor decides whether a source exists yet). */
    val startMonitoring: () -> Unit,
    val readiness: AlertReadinessSource,
    val clock: Clock,
    /** The follower role (join, stop following). */
    val follower: FollowerSharing = FollowerSharing.None,
) {
    /** An invite link opened on this phone before it follows anyone: setup's join step starts with it. */
    val pendingInvite = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** When the alert thresholds were confirmed (setup or Settings › Alerts), null = never. */
    val alertsConfirmedAt: Flow<Instant?> by lazy {
        settings.flow(THRESHOLDS_CONFIRMED_AT).map { text ->
            text.takeIf { it.isNotEmpty() }?.let { runCatching { Instant.parse(it) }.getOrNull() }
        }
    }

    /**
     * P4: the shown thresholds become the runtime's (merged into the current settings, so nothing changed meanwhile in
     * Settings › Alerts is lost), then the confirmation is recorded.
     */
    suspend fun confirmAlerts(shown: AlertThresholds) {
        val current = alertSettings.settings.first()
        val merged =
            ThresholdField.entries.fold(current) { s, f -> s.withRule(f.kind, s.rule(f.kind).copy(threshold = shown.value(f))) }
        alertSettings.save(merged)
        settings.set(THRESHOLDS_CONFIRMED_AT, clock.now().toString())
    }

    /** Access requests put off with Later in the approve dialog (process memory). */
    val laterRequests = LaterRequests()

    /** Home and setup call this on resume: permissions change in the system's settings. */
    fun refresh() {
        readiness.refresh()
    }

    /** The home "Finish setup" card's items ([FinishSetupCard]); empty before a role and once nothing is left. */
    val finishItems: Flow<List<FinishItem>> by lazy {
        combine(
            setupStore.data,
            roles.role,
            hub.state,
            combine(sharing.state, follower.state, ::Pair),
            combine(alertsConfirmedAt, readiness.readiness, ::Pair),
        ) {
            record,
            role,
            glucose,
            (share, follow),
            (confirmed, checklist),
            ->
            val facts =
                SetupFacts(
                    role = role,
                    following = follow.following,
                    ownSensorLive = RestoreGate.ownSensorLive(glucose, clock.now()),
                    hasIdentity = share is OwnerShareState.Sharing || share is OwnerShareState.AnotherAppSharingAsYou,
                    alertsConfirmed = confirmed != null,
                    alertsReady = checklist.ready,
                )
            SetupMachine.finishItems(record, facts)
        }
    }

    private companion object {
        val THRESHOLDS_CONFIRMED_AT = SchemaSettings.text("alerts.thresholdsConfirmedAt", default = "")
    }
}
