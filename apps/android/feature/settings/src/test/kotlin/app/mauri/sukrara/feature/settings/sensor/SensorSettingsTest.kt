// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.settings.sensor

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.mauri.sukrara.core.model.SensorAction
import app.mauri.sukrara.core.model.SensorState
import app.mauri.sukrara.core.testing.FakeClock
import app.mauri.sukrara.designsystem.theme.SukraraTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneOffset

/** Settings › Sensor: the source switch asks first, every problem state shows its way out, sign-out confirms. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SensorSettingsTest {
    @get:Rule val compose = createComposeRule()

    private val now = Instant.parse("2026-10-27T14:00:00Z")
    private val sensor = SensorSummary("3MH00ABCDEF", now.minusSeconds(5 * 86_400), now.plusSeconds(10 * 86_400))
    private val working =
        SensorSettingsState(
            SourceChoice.Libre3Direct,
            SensorState.Active(sensor.endsAt),
            sensor,
            LibreViewSummary("me@example.invalid", 123456789),
        )

    private class FakePort(initial: SensorSettingsState) : SensorSettingsPort {
        override val state = MutableStateFlow(initial)
        val sources = mutableListOf<SourceChoice>()
        var signedOut = false

        override fun setSource(source: SourceChoice) {
            sources += source
            state.value = state.value.copy(source = source)
        }

        override suspend fun signOut() {
            signedOut = true
            state.value = state.value.copy(account = null)
        }
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private fun show(
        state: SensorSettingsState,
        actions: SensorSettingsActions = SensorSettingsActions(),
    ) = compose.setContent { SukraraTheme(dark = false, dynamic = false) { SensorSettingsContent(state, now, ZoneOffset.UTC, actions) {} } }

    @Test
    fun `switching to Juggluco asks first, Cancel changes nothing, confirming switches`() {
        val chosen = mutableListOf<SourceChoice>()
        show(working, SensorSettingsActions(setSource = { chosen += it }))
        compose.onNodeWithText("Juggluco").performScrollTo().performClick()
        compose.onNodeWithText("Read glucose from Juggluco?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(emptyList<SourceChoice>(), chosen)
        compose.onNodeWithText("Juggluco").performClick()
        compose.onNodeWithText("Use Juggluco").performClick()
        assertEquals(listOf(SourceChoice.Juggluco), chosen)
    }

    @Test
    fun `the current source is selected and tapping it does nothing`() {
        val chosen = mutableListOf<SourceChoice>()
        show(working, SensorSettingsActions(setSource = { chosen += it }))
        compose.onNodeWithText("This phone reads the sensor").performScrollTo().performClick()
        compose.onNodeWithText("Read the sensor directly?").assertDoesNotExist()
        assertTrue(chosen.isEmpty())
    }

    @Test
    fun `shows the sensor, when it ends and the account`() {
        show(working)
        compose.onNodeWithText("3MH00ABCDEF").assertExists()
        compose.onNodeWithText("10d", substring = true).assertExists()
        compose.onNodeWithText("me@example.invalid").performScrollTo().assertExists()
        compose.onNodeWithText("123456789").assertExists()
    }

    @Test
    fun `an ended sensor offers Pair new sensor`() {
        var paired = 0
        show(working.copy(state = SensorState.Ended), SensorSettingsActions(pairNewSensor = { paired++ }))
        compose.onNodeWithText("This sensor has ended", substring = true).assertExists()
        compose.onAllNodesWithTextFirst("Pair new sensor").performClick()
        assertEquals(1, paired)
    }

    @Test
    fun `an ended sensor shows no time left`() {
        show(working.copy(state = SensorState.Ended))
        compose.onNodeWithText(" · in ", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a silent Juggluco is never shown as working`() {
        show(working.copy(source = SourceChoice.Juggluco, link = SourceLink.JugglucoUnreachable))
        compose.onNodeWithText("Juggluco isn’t answering: no readings").assertExists()
        compose.onNodeWithText("Working", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Juggluco doesn’t answer", substring = true).assertExists()
    }

    @Test
    fun `a connecting sensor is not shown as working`() {
        show(working.copy(link = SourceLink.Connecting))
        compose.onNodeWithText("Connecting to the sensor: no readings yet").assertExists()
    }

    @Test
    fun `an untested family is named and tagged, its no-value reasons are said`() {
        show(working.copy(family = SensorFamily("Dexcom G7 / ONE+", untested = true), link = SourceLink.NoValueCalibration))
        compose.onNodeWithText("Dexcom G7 / ONE+ · untested").assertExists()
        compose.onNodeWithText("asks for a calibration", substring = true).assertExists()
        compose.onNodeWithText("Working", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a sensor error offers Pair new sensor`() {
        var paired = 0
        show(working.copy(link = SourceLink.NoValueSensorError), SensorSettingsActions(pairNewSensor = { paired++ }))
        compose.onAllNodes(hasText("the sensor reports an error", substring = true)).onFirst().assertExists()
        compose.onAllNodesWithTextFirst("Pair new sensor").performClick()
        assertEquals(1, paired)
    }

    @Test
    fun `an old sensor skipped while searching is said`() {
        show(working.copy(family = SensorFamily("Dexcom G7 / ONE+", untested = true, skippedOld = 1), link = SourceLink.Searching))
        compose.onNodeWithText("An older Dexcom G7 / ONE+ nearby has ended", substring = true).assertExists()
        compose.onNodeWithText("Looking for the sensor", substring = true).assertExists()
    }

    @Test
    fun `sign out asks first, Cancel keeps the account`() {
        var signedOut = 0
        show(working, SensorSettingsActions(signOut = { signedOut++ }))
        compose.onAllNodesWithTextFirst("Sign out").performClick()
        compose.onNodeWithText("Sign out of LibreView?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, signedOut)
        compose.onAllNodesWithTextFirst("Sign out").performClick()
        compose.onAllNodes(hasText("Sign out"))[1].performClick()
        assertEquals(1, signedOut)
    }

    @Test
    fun `signed out shows Sign in`() {
        var signIn = 0
        show(
            working.copy(state = SensorState.ActionNeeded(SensorAction.SignInLibreView), account = null),
            SensorSettingsActions(signIn = {
                signIn++
            }),
        )
        compose.onAllNodesWithTextFirst("Sign in to LibreView").performClick()
        assertEquals(1, signIn)
    }

    @Test
    fun `the view model switches through the port, signs out, and ignores the current source`() {
        val port = FakePort(working)
        val vm = SensorSettingsViewModel(port)
        compose.setContent { SensorSettingsScreen(vm, FakeClock(now), {}, {}, {}) }
        compose.waitForIdle()
        vm.setSource(SourceChoice.Libre3Direct)
        vm.setSource(SourceChoice.Juggluco)
        vm.signOut()
        compose.waitForIdle()
        assertEquals(listOf(SourceChoice.Juggluco), port.sources)
        assertTrue(port.signedOut)
        compose.onNodeWithText("Juggluco").assertIsSelected()
    }

    @Test
    fun `forget asks first, Cancel keeps the sensor, open on scan toggles`() {
        var forgot = 0
        val scan = mutableListOf<Boolean>()
        show(
            working.copy(paired = true, openOnScan = OpenOnScan(on = false)),
            SensorSettingsActions(forget = { forgot++ }, setOpenOnScan = { scan += it }),
        )
        compose.onNodeWithText("Open Sukrara when you scan a sensor").performScrollTo().performClick()
        assertEquals(listOf(true), scan)
        compose.onNodeWithText("Forget this sensor").performScrollTo().performClick()
        compose.onNodeWithText("Forget this sensor?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, forgot)
        compose.onNodeWithText("Forget this sensor").performClick()
        compose.onNodeWithText("Forget").performClick()
        assertEquals(1, forgot)
    }

    @Test
    fun `with Juggluco as the source the This phone rows are hidden`() {
        show(working.copy(source = SourceChoice.Juggluco, paired = true, openOnScan = OpenOnScan(on = true)))
        compose.onNodeWithText("Forget this sensor").assertDoesNotExist()
        compose.onNodeWithText("Open Sukrara when you scan a sensor").assertDoesNotExist()
    }

    private fun ComposeContentTestRule.onAllNodesWithTextFirst(text: String) = onAllNodes(hasText(text)).onFirst().performScrollTo()
}
