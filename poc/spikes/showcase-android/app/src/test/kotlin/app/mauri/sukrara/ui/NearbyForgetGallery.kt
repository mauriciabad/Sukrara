// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import app.mauri.sukrara.R
import app.mauri.sukrara.core.BleOwner
import app.mauri.sukrara.features.Features
import app.mauri.sukrara.graph.RoundedFont
import app.mauri.sukrara.owner.Follower
import app.mauri.sukrara.owner.OwnerUi
import com.android.settingslib.spa.framework.theme.SettingsTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.lang.reflect.Proxy

/**
 * Round f33 evidence + check: Settings → Share with people → Nearby devices → a device → *Forget* → *Forget device?*
 * forgets exactly that follower's device. Synthetic followers on the JVM. Out: `gallery.out`/nearby/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h1500dp-xhdpi")
class NearbyForgetGallery {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val ctx = RuntimeEnvironment.getApplication()
    private val out = File(System.getProperty("gallery.out") ?: "build/gallery", "nearby").also { it.mkdirs() }
    private val forgotten = mutableListOf<String>()

    /** Only forget does something; every other action is unused here. */
    private val actions: OwnerActions = Proxy.newProxyInstance(OwnerActions::class.java.classLoader, arrayOf(OwnerActions::class.java)) { p, m, args ->
        when (m.name) {
            "forget" -> { forgotten += args!![0] as String; null }
            "equals" -> p === args!![0]
            "hashCode" -> System.identityHashCode(p)
            "toString" -> "FakeOwnerActions"
            else -> null
        }
    } as OwnerActions

    @Before fun setup() {
        RoundedFont.init(ctx)
        Features.init(ctx)
        Features.update { it.copy(bleFollowers = true) }
    }

    private fun save(name: String, dialog: Boolean) {
        compose.waitForIdle()
        val node = if (dialog) compose.onNode(isDialog()) else compose.onRoot()
        File(out, "$name.png").outputStream().use { node.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun forgetANearbyDevice() {
        val now = System.currentTimeMillis() / 1000
        val sam = Follower("s", "Sam", now - 86_400 * 3)
        val robin = Follower("r", "Robin", now - 86_400 * 9)
        val u = OwnerUi(hasIdentity = true, followers = listOf(sam, robin), nearbyPaired = setOf("s", "r"), bleOn = true,
            bleLinks = listOf(BleOwner.Link("AA:BB", now - 600, "s", "mac")))
        compose.setContent { SettingsTheme { SharingSettings(u, actions) {} } }
        save("android-nearby-devices", dialog = false)
        compose.onNode(hasText("Sam · Mac") and hasClickAction()).performClick()
        save("android-nearby-device", dialog = true)
        compose.onNode(hasText(ctx.getString(R.string.people_nearby_forget)) and hasClickAction()).performClick()
        save("android-nearby-forget-confirm", dialog = true)
        compose.onNode(hasText(ctx.getString(R.string.people_nearby_forget_confirm)) and hasClickAction()).performClick()
        compose.waitForIdle()
        assertEquals(listOf("s"), forgotten)
    }

    @Test fun cancelForgetsNothing() {
        val now = System.currentTimeMillis() / 1000
        val u = OwnerUi(hasIdentity = true, followers = listOf(Follower("r", "Robin", now - 3600)), nearbyPaired = setOf("r"), bleOn = true)
        compose.setContent { SettingsTheme { SharingSettings(u, actions) {} } }
        compose.onNode(hasText("Robin") and hasClickAction() and hasText(ctx.getString(R.string.people_nearby_disconnected))).performClick()
        compose.onNode(hasText(ctx.getString(R.string.people_nearby_forget)) and hasClickAction()).performClick()
        compose.onNode(hasText(ctx.getString(R.string.settings_dialog_cancel)) and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNode(isDialog()).assertDoesNotExist()
        assertEquals(emptyList<String>(), forgotten)
    }
}
