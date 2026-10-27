// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sensor

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class OfficialAppsTest {
    @Test
    fun `every official app has a queries entry (Android 11+ hides packages without one)`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val queried = Regex("""<package android:name="([^"]+)"""").findAll(manifest).map { it.groupValues[1] }.toList()
        assertEquals(OfficialApps.PACKAGES.sorted(), queried.sorted())
    }
}
