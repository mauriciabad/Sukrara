// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.mauri.sukrara.feature.sharing.setup.ShowcaseDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ShowcaseDetectorTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `the Showcase is detected only while installed`() {
        val d = ShowcaseDetector(app)
        assertFalse(d.installed())
        shadowOf(app.packageManager).installPackage(
            android.content.pm.PackageInfo().apply { packageName = ShowcaseDetector.PACKAGE },
        )
        assertTrue(d.installed())
        assertEquals("package:${ShowcaseDetector.PACKAGE}", d.uninstallIntent().data.toString())
    }
}
