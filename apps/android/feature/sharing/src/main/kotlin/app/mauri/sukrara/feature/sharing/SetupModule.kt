// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing

import android.content.Context
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.GlucoseHub
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.storage.StateStore
import app.mauri.sukrara.feature.sharing.setup.AlertSettingsAccess
import app.mauri.sukrara.feature.sharing.setup.AndroidAlertReadiness
import app.mauri.sukrara.feature.sharing.setup.PhoneFacts
import app.mauri.sukrara.feature.sharing.setup.SetupRecord
import app.mauri.sukrara.feature.sharing.setup.ShowcaseDetector
import app.mauri.sukrara.feature.sharing.setup.FinishItem
import app.mauri.sukrara.feature.sharing.setup.RestoreGate
import app.mauri.sukrara.feature.sharing.setup.SetupFacts
import app.mauri.sukrara.feature.sharing.setup.SetupMachine
import app.mauri.sukrara.share.owner.OwnerShareState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

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
 * - [resetAlerts]: chunk 11's `AlertsModule.reset()`, called when the role is chosen or changed — before any
 *   monitoring — so no phantom no-data alarm survives. Not on identity "Start fresh": by then the sensor runs, and a
 *   reset stops alerts until the hub reports a role again.
 * - [alertChannels]: chunk 11's alert channel ids, for the readiness check.
 */
class SetupModule(
    context: Context,
    val setupStore: StateStore<SetupRecord>,
    val roles: RoleStore,
    val hub: GlucoseHub,
    val sharing: OwnerSharing,
    val alertSettings: AlertSettingsAccess,
    val resetAlerts: suspend () -> Unit,
    alertChannels: () -> List<String>,
    val clock: Clock,
    /** Following on Android (chunk 25) is in this build. */
    val followerAvailable: Boolean = false,
) {
    private val ctx = context.applicationContext

    val showcase by lazy { ShowcaseDetector(ctx) }

    /** THE alert checklist: also given to home's reliability row (CHANGES P5). */
    val readiness by lazy { AndroidAlertReadiness(ctx, alertChannels, wearer = { hub.state.value.role != Role.Follower }) }

    private val showcaseInstalled = MutableStateFlow(false)

    fun phoneNow(): PhoneFacts {
        readiness.refresh()
        return PhoneFacts(showcase.installed().also { showcaseInstalled.value = it }, readiness.readiness.value)
    }

    /** Home calls this on resume: re-reads the checklist and whether the Showcase is still installed. */
    fun refresh() {
        phoneNow()
    }

    /** The home "Finish setup" card's items ([FinishSetupCard]); empty before a role and once nothing is left. */
    val finishItems: Flow<List<FinishItem>> by lazy {
        combine(setupStore.data, roles.role, hub.state, sharing.state, showcaseInstalled) { record, role, glucose, share, installed ->
            val facts =
                SetupFacts(
                    role = role,
                    showcaseInstalled = installed,
                    followerAvailable = followerAvailable,
                    ownSensorLive = RestoreGate.ownSensorLive(glucose, clock.now()),
                    hasIdentity = share is OwnerShareState.Sharing || share is OwnerShareState.AnotherAppSharingAsYou,
                )
            SetupMachine.finishItems(record, facts)
        }
    }
}
