// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.home

import app.mauri.sukrara.core.model.AlertReadiness
import app.mauri.sukrara.core.model.AlertRequirement
import app.mauri.sukrara.core.model.GlucoseState
import app.mauri.sukrara.core.model.storage.StateStore
import app.mauri.sukrara.core.model.storage.TreatmentRecord
import app.mauri.sukrara.core.testing.FakeClock
import app.mauri.sukrara.core.testing.InMemoryStateStore
import app.mauri.sukrara.core.testing.InMemoryTreatmentsRepository
import app.mauri.sukrara.feature.home.banners.BannerKind
import app.mauri.sukrara.feature.home.banners.BannerMemory
import app.mauri.sukrara.feature.home.banners.HomeEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private class Deps : HomeDeps {
        override val clock = FakeClock(Samples.NOW)
        override val glucose = MutableStateFlow(Samples.wearer())
        override val treatments = InMemoryTreatmentsRepository()
        override val readiness: StateFlow<AlertReadiness?> = MutableStateFlow(AlertReadiness.Ready)
        override val environment = MutableStateFlow(HomeEnvironment())
        override val dataProblems = MutableStateFlow<List<DataProblem>>(emptyList())
        override val bannerMemory: StateStore<BannerMemory> = InMemoryStateStore(BannerMemory())
        var unmuted = 0

        override fun acknowledge(problem: DataProblem) {
            dataProblems.value -= problem
        }

        override fun resumeMonitoring() = Unit

        override fun unmute() {
            unmuted++
        }

        override fun fix(requirement: AlertRequirement) = Unit
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `state follows the hub and says why when there is no value`() =
        runTest(dispatcher) {
            val deps = Deps()
            val vm = HomeViewModel(deps)
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            assertNull(vm.state.value.reason)
            deps.glucose.value = Samples.noSensorYet()
            runCurrent()
            assertEquals(NoDataReason.NoSensorYet(follower = false), vm.state.value.reason)
            deps.glucose.value = GlucoseState.Initial
            runCurrent()
            assertEquals(NoDataReason.SetupIncomplete, vm.state.value.reason)
        }

    @Test fun `damaged data shows the banner until acknowledged`() =
        runTest(dispatcher) {
            val deps = Deps()
            val vm = HomeViewModel(deps)
            backgroundScope.launch { vm.state.collect {} }
            val problem = DataProblem("settings", "SerializationException", "settings.corrupt-1.json", Samples.NOW)
            deps.dataProblems.value = listOf(problem)
            runCurrent()
            assertEquals(BannerKind.DataProblem, vm.state.value.banner)
            vm.acknowledge(problem)
            runCurrent()
            assertNull(vm.state.value.banner)
        }

    @Test fun `delete leaves a tombstone, undo brings it back as a newer version`() =
        runTest(dispatcher) {
            val deps = Deps()
            val t = Samples.treatment()
            deps.treatments.upsertIfNewer(TreatmentRecord(t))
            val vm = HomeViewModel(deps)
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            assertEquals(listOf(t.id), vm.state.value.treatments.map { it.id })

            vm.delete(t)
            runCurrent()
            assertTrue(deps.treatments.get(t.id)!!.treatment.deleted)
            assertEquals(t, vm.state.value.undo)
            assertTrue(vm.state.value.treatments.isEmpty())

            vm.undoDelete()
            runCurrent()
            val restored = deps.treatments.get(t.id)!!.treatment
            assertEquals(false, restored.deleted)
            assertTrue(restored.modifiedAt > t.modifiedAt)
            assertNull(vm.state.value.undo)
            assertEquals(listOf(t.id), vm.state.value.treatments.map { it.id })
        }

    @Test fun `the undo offer expires after 5 s`() =
        runTest(dispatcher) {
            val deps = Deps()
            val t = Samples.treatment()
            deps.treatments.upsertIfNewer(TreatmentRecord(t))
            val vm = HomeViewModel(deps)
            backgroundScope.launch { vm.state.collect {} }
            vm.delete(t)
            runCurrent()
            assertEquals(t, vm.state.value.undo)
            advanceTimeBy(HomeViewModel.UNDO_WINDOW.toMillis() + 1)
            runCurrent()
            assertNull(vm.state.value.undo)
        }

    @Test fun `safety banners ignore dismiss`() =
        runTest(dispatcher) {
            val deps = Deps()
            deps.environment.value = HomeEnvironment(showcaseInstalled = true)
            val vm = HomeViewModel(deps)
            backgroundScope.launch { vm.state.collect {} }
            vm.dismiss(BannerKind.TurnOffShowcase)
            runCurrent()
            assertEquals(BannerKind.TurnOffShowcase, vm.state.value.banner)
        }
}
