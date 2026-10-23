// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.pen

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** Sukrara's pen engine, OpenNov reader and scenario driver are the investigation's files with the package renamed. */
class PenCopyTest {
    private fun root() = listOf(File("../.."), File("../../.."), File(".")).first { File(it, "novopen-android/scenarios.json").isFile }

    private fun same(spike: String, app: String, from: String, to: String) {
        val r = root()
        val a = File(r, "novopen-android/src/$spike").readText().replace(from, to)
        assertEquals("$app differs from the spike's $spike (copy it over, package renamed)", a, File(r, "showcase-android/app/src/$app").readText())
    }

    @Test fun engine() = same("main/kotlin/app/mauri/novopen/engine/PenEngine.kt", "main/kotlin/app/mauri/sukrara/pen/PenEngine.kt",
        "package app.mauri.novopen.engine", "package app.mauri.sukrara.pen")

    @Test fun scenarioDriver() = same("test/kotlin/app/mauri/novopen/engine/PenScenarioTest.kt", "test/kotlin/app/mauri/sukrara/pen/PenScenarioTest.kt",
        "package app.mauri.novopen.engine", "package app.mauri.sukrara.pen")

    @Test fun reader() {
        val r = root()
        val spike = File(r, "novopen-android/src/main/kotlin/app/mauri/novopen/opennov")
        spike.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(spike).path
            assertEquals("pen/opennov/$rel differs from the spike", f.readText().replace("app.mauri.novopen.opennov", "app.mauri.sukrara.pen.opennov"),
                File(r, "showcase-android/app/src/main/kotlin/app/mauri/sukrara/pen/opennov/$rel").readText())
        }
    }
}
