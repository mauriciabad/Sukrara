// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara

import org.junit.Test
import java.io.File

/** Release builds never carry a made-up source: the release variant returns none and main code never reaches debug. */
class ReleaseVariantTest {
    private val src = File(System.getProperty("user.dir"), "src")

    @Test fun releaseHasNoTestSource() {
        val release = File(src, "release/kotlin/app/mauri/sukrara/BuildVariant.kt").readText()
        check("wearerSource(graph: AppGraph): ReadingSource? = null" in release) { "release BuildVariant must return no source" }
        check(
            "Synthetic" !in release && "debug" !in release.substringAfter("internal object"),
        ) { "release BuildVariant names a test source" }
    }

    @Test fun mainNeverImportsDebugTools() {
        val offenders =
            File(src, "main").walkTopDown().filter { it.isFile && it.extension == "kt" }.filter { file ->
                file.readLines().any { it.startsWith("import app.mauri.sukrara.debug") }
            }
        check(offenders.none()) { "main imports debug tools: ${offenders.toList()}" }
    }
}
