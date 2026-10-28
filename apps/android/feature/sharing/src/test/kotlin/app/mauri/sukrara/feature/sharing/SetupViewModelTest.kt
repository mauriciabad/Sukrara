// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.mauri.sukrara.alerts.AlertKind
import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.core.model.AlertReadiness
import app.mauri.sukrara.core.model.AlertReadinessSource
import app.mauri.sukrara.core.model.AlertRequirement
import app.mauri.sukrara.core.model.AlertUiState
import app.mauri.sukrara.core.model.GlucoseHub
import app.mauri.sukrara.core.model.GlucoseState
import app.mauri.sukrara.core.model.LinkState
import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.SensorState
import app.mauri.sukrara.core.model.SourceId
import app.mauri.sukrara.core.model.SourceStatus
import app.mauri.sukrara.core.testing.FakeClock
import app.mauri.sukrara.core.testing.InMemorySettingsRepository
import app.mauri.sukrara.core.testing.InMemoryStateStore
import app.mauri.sukrara.feature.sharing.setup.AlertSettingsAccess
import app.mauri.sukrara.feature.sharing.setup.RestoreUi
import app.mauri.sukrara.feature.sharing.setup.SetupRecord
import app.mauri.sukrara.feature.sharing.setup.SetupStep
import app.mauri.sukrara.feature.sharing.setup.SetupViewModel
import app.mauri.sukrara.feature.sharing.setup.ThresholdField
import app.mauri.sukrara.share.owner.usecase.RestoreIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SetupViewModelTest {
    private val clock = FakeClock()
    private val hub = FakeHub()
    private val roles = FakeRoles()
    private val sharing = FakeOwnerSharing()
    private val alerts = FakeAlerts()
    private val record = InMemoryStateStore(SetupRecord())
    private val events = mutableListOf<String>()
    private val settings = InMemorySettingsRepository()
    private val readiness = FakeReadiness()

    private fun vm(): SetupViewModel {
        val module =
            SetupModule(
                ApplicationProvider.getApplicationContext<Application>(),
                record,
                roles,
                hub,
                sharing,
                alerts,
                settings,
                resetAlerts = { events += "reset" },
                startMonitoring = { events += "start" },
                readiness = readiness,
                clock = clock,
            )
        return SetupViewModel(module)
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `choosing the role resets the alert runtime before the role is stored`() =
        runTest {
            val vm = vm()
            vm.ui.launchIn(backgroundScope)
            vm.continueWelcome()
            assertEquals(SetupStep.Role, vm.ui.first { it != null }!!.step)
            vm.chooseRole(Role.Wearer)
            assertEquals(listOf("reset", "role:Wearer", "start"), events)
            assertEquals(SetupStep.Permissions, vm.ui.first { it?.step == SetupStep.Permissions }!!.step)
        }

    @Test
    fun `the checklist follows the live readiness, not a copy taken on resume`() =
        runTest {
            roles.role.value = Role.Wearer
            val vm = vm()
            vm.ui.launchIn(backgroundScope)
            assertTrue(vm.ui.first { it != null }!!.readiness.ready)
            // the monitor answers later (its own dispatcher): setup must show it without another resume
            readiness.readiness.value = AlertReadiness(setOf(AlertRequirement.Bluetooth))
            assertEquals(setOf(AlertRequirement.Bluetooth), vm.ui.first { it?.readiness?.ready == false }!!.readiness.missing)
        }

    @Test
    fun `follower isn't stored while following isn't in this build`() =
        runTest {
            val vm = vm()
            vm.ui.launchIn(backgroundScope)
            vm.ui.first { it != null }
            vm.chooseRole(Role.Follower)
            assertTrue(events.isEmpty())
        }

    @Test
    fun `confirm writes exactly the shown thresholds to the runtime's store`() =
        runTest {
            roles.role.value = Role.Wearer
            val vm = vm()
            vm.ui.launchIn(backgroundScope)
            vm.ui.first { it != null }
            vm.editThreshold(ThresholdField.Low, 75.0)
            assertEquals(70.0, alerts.settings.value.rule(AlertKind.LOW).threshold, 0.0)
            vm.confirmAlerts()
            assertEquals(75.0, alerts.settings.value.rule(AlertKind.LOW).threshold, 0.0)
            assertTrue(SetupStep.Alerts in record.data.first().completed)
            // the confirmation is recorded where Settings › Alerts reads it
            assertTrue(vm.ui.first { it?.facts?.alertsConfirmed == true }!!.facts.alertsConfirmed)
        }

    @Test
    fun `restore is refused while the gate is closed`() =
        runTest {
            roles.role.value = Role.Wearer
            val vm = vm()
            vm.ui.launchIn(backgroundScope)
            vm.ui.first { it != null }
            vm.restore("nsec1abc", null)
            assertTrue("restore" !in sharing.calls)
        }

    @Test
    fun `with a live reading the restore runs, and a wrong password stays on the step`() =
        runTest {
            roles.role.value = Role.Wearer
            hub.live(clock)
            val vm = vm()
            vm.ui.launchIn(backgroundScope)
            vm.ui.first { it != null }
            vm.restore("ncryptsec1abc", "wrong")
            assertEquals(listOf("restore"), sharing.calls)
            assertEquals(RestoreUi.Failed(RestoreIdentity.Result.WrongPassword), vm.ui.first { it?.restore is RestoreUi.Failed }!!.restore)
            assertTrue(SetupStep.Identity !in record.data.first().completed)
            assertTrue("startFresh" !in sharing.calls)
        }

    private inner class FakeRoles : RoleStore {
        override val role = MutableStateFlow<Role?>(null)

        override suspend fun set(role: Role) {
            events += "role:$role"
            this.role.value = role
        }
    }

    /** Setup renders THE alert checklist (the monitor's `AndroidAlertReadiness`); starts with everything allowed. */
    private class FakeReadiness : AlertReadinessSource {
        override val readiness = MutableStateFlow(AlertReadiness.Ready)

        override fun refresh() = Unit

        override fun fix(requirement: AlertRequirement) = Unit
    }

    private class FakeAlerts : AlertSettingsAccess {
        override val settings = MutableStateFlow(AlertSettings())

        override suspend fun save(settings: AlertSettings) {
            this.settings.value = settings
        }
    }

    private class FakeHub : GlucoseHub {
        override val state = MutableStateFlow(GlucoseState.Initial)

        fun live(clock: FakeClock) {
            state.value = state.value.copy(latest = Reading(clock.now(), 120, null, SourceId.Libre3), source = SourceStatus.Live(clock.now()))
        }

        override suspend fun ingest(
            readings: List<Reading>,
            from: SourceId,
        ) = Unit

        override fun setSource(status: SourceStatus) = Unit

        override fun setSensor(sensor: SensorState) = Unit

        override fun setAlerts(alerts: AlertUiState) = Unit

        override fun setLink(link: LinkState?) = Unit
    }
}
