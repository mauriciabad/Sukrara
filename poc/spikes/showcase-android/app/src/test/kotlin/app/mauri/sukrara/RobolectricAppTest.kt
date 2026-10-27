// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara

import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Guard for src/test/resources/robolectric.properties: Robolectric tests never run App.onCreate. Its background work
 * (widget previews, sensor-end re-plans) outlives the test that started it and made other tests fail at random.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RobolectricAppTest {
    @Test fun testsDontStartTheAppsBackgroundWork() {
        assertFalse("robolectric.properties must keep application=android.app.Application", RuntimeEnvironment.getApplication() is App)
    }
}
