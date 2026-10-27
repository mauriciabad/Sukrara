// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import app.mauri.sukrara.core.model.AlertRequirement
import app.mauri.sukrara.feature.sharing.setup.AndroidAlertReadiness
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
class PermissionsChecklistTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `missing permissions are reported and come back on refresh`() {
        val r = AndroidAlertReadiness(app, alertChannels = { emptyList() }, wearer = { true })
        assertTrue(AlertRequirement.Notifications in r.readiness.value.missing)
        assertTrue(AlertRequirement.Bluetooth in r.readiness.value.missing)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        r.refresh()
        assertFalse(AlertRequirement.Notifications in r.readiness.value.missing)
    }

    @Test
    fun `bluetooth isn't asked of a follower`() {
        val r = AndroidAlertReadiness(app, alertChannels = { emptyList() }, wearer = { false })
        assertFalse(AlertRequirement.Bluetooth in r.readiness.value.missing)
    }

    @Test
    fun `a silenced alert channel is a problem and opens that channel's settings`() {
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("alert.low", "Low", NotificationManager.IMPORTANCE_LOW))
        val r = AndroidAlertReadiness(app, alertChannels = { listOf("alert.low", "not-created-yet") }, wearer = { true })
        assertTrue(AlertRequirement.AlertChannel in r.readiness.value.missing)
        val fix = r.fixIntent(AlertRequirement.AlertChannel)
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, fix.action)
        assertEquals("alert.low", fix.getStringExtra(Settings.EXTRA_CHANNEL_ID))
    }

    @Test
    fun `channels not created yet are not a problem`() {
        val r = AndroidAlertReadiness(app, alertChannels = { listOf("not-created-yet") }, wearer = { true })
        assertFalse(AlertRequirement.AlertChannel in r.readiness.value.missing)
    }

    @Test
    fun `every requirement has a settings page`() {
        val r = AndroidAlertReadiness(app, alertChannels = { emptyList() }, wearer = { true })
        for (req in AlertRequirement.entries) assertTrue(req.name, r.fixIntent(req).action != null)
    }

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
