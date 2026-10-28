// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.systemui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * [HookCode.VERSION] is the fingerprint of the code System UI runs: the module's files and the renderer / settings
 * files it calls, by path and content. A change there without a new fingerprint would leave System UI on the old code
 * with no restart asked for; app-only files (the controller, the daemon, the receivers) never ask for one.
 */
class HookCodeTest {
    private val systemUi = "src/main/kotlin/app/mauri/sukrara/monitor/systemui"
    private val graph = "../core/graph/src/main/kotlin/app/mauri/sukrara/graph"

    /** What runs inside System UI. A new file the module uses goes here. */
    private val files =
        listOf(
            "$systemUi/SystemUiHook.kt",
            "$systemUi/HookText.kt",
            "$systemUi/HookState.kt",
            "$systemUi/IconFit.kt",
            "$systemUi/SlotIcons.kt",
            "$graph/Freshness.kt",
            "$graph/GlucoseCard.kt",
            "$graph/ValueGlyphs.kt",
            "$graph/CardStyle.kt",
            "$graph/CardModel.kt",
            "$graph/SensorBadge.kt",
            "$graph/stats/Bands.kt",
            "../core/data/src/main/kotlin/app/mauri/sukrara/core/data/settings/SurfaceSettings.kt",
        )

    @Test fun `the fingerprint matches the System UI code`() {
        val md = MessageDigest.getInstance("SHA-256")
        for (path in files) {
            val f = File(path)
            assertTrue("missing $path", f.isFile)
            md.update((path + "\n").toByteArray())
            // line endings never change the code
            md.update(f.readText().replace("\r\n", "\n").toByteArray())
        }
        val code = md.digest().joinToString("") { "%02x".format(it) }.take(16)
        assertEquals("System UI's code changed: set HookCode.VERSION to \"$code\"", code, HookCode.VERSION)
    }
}
