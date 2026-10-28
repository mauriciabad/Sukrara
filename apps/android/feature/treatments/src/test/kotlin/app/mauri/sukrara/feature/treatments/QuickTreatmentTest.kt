// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.treatments

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.mauri.sukrara.core.model.AlertUiState
import app.mauri.sukrara.core.model.GlucoseState
import app.mauri.sukrara.core.model.NavTargets
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.testing.FakeClock
import app.mauri.sukrara.core.testing.InMemorySettingsRepository
import app.mauri.sukrara.core.testing.InMemoryTreatmentsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The app's Application stand-in: gives the quick dialog its module, like `App` does. */
class TestTreatmentsApp :
    Application(),
    TreatmentsHost {
    val glucose = MutableStateFlow(GlucoseState.Initial)
    var low = false
    val hints =
        object : AlertHints {
            override fun lowNow() = low

            override val quiet = kotlinx.coroutines.flow.flowOf(QuietRules(afterInsulinMin = 60, afterCarbsMin = 15))
        }

    override val treatments by lazy {
        TreatmentsModule(
            InMemoryTreatmentsRepository(),
            LocalTreatmentSync(),
            FakeClock(),
            glucose,
            InMemorySettingsRepository(),
            CoroutineScope(Dispatchers.Unconfined),
            hints,
        )
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = TestTreatmentsApp::class)
class QuickTreatmentTest {
    private val app = ApplicationProvider.getApplicationContext<TestTreatmentsApp>()

    @Test fun widgetAndNotificationIntentsResolveToTheQuickDialog() {
        for (source in QuickSource.entries) {
            val intent = QuickTreatmentIntents.open(app, source, focusCarbs = true)
            val resolved = app.packageManager.resolveActivity(intent, 0)
            assertEquals(QuickTreatmentActivity::class.java.name, resolved?.activityInfo?.name)
            assertTrue(QuickTreatmentIntents.focusCarbs(intent))
        }
        // the monitor's notification posts the action with the package only
        val notification = Intent(NavTargets.ACTION_QUICK_TREATMENT).setPackage(app.packageName)
        assertEquals(
            QuickTreatmentActivity::class.java.name,
            app.packageManager
                .resolveActivity(notification, 0)
                ?.activityInfo
                ?.name,
        )
    }

    @Test fun opensTheDialogWhenThisPhoneCanAdd() {
        app.glucose.value = GlucoseState.Initial.copy(role = Role.Wearer)
        val activity =
            Robolectric
                .buildActivity(
                    QuickTreatmentActivity::class.java,
                    QuickTreatmentIntents.open(app, QuickSource.Notification),
                ).setup()
                .get()
        assertFalse(activity.isFinishing)
    }

    @Test fun opensTheAppInsteadWhenThisPhoneCannotAdd() {
        app.glucose.value = GlucoseState.Initial.copy(role = Role.Follower)
        val activity =
            Robolectric
                .buildActivity(
                    QuickTreatmentActivity::class.java,
                    QuickTreatmentIntents.open(app, QuickSource.Widget),
                ).setup()
                .get()
        assertTrue(activity.isFinishing)
    }

    @Test fun carbsFirstWhileTheLatestReadingIsLow() {
        app.low = true
        assertTrue(app.treatments.lowNow())
        app.low = false
        assertFalse(app.treatments.lowNow())
        assertFalse(QuickTreatmentIntents.focusCarbs(Intent()))
    }
}
