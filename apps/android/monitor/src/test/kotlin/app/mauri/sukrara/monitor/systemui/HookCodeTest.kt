// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.systemui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * [HookCode.VERSION] is the fingerprint of the code System UI runs: every source file of this package except
 * HookCode.kt, by relative path and content. A change there without a new fingerprint would leave System UI on the old
 * code with no restart asked for; a new fingerprint asks for one (home card, Settings › Status bar).
 */
class HookCodeTest {
    @Test fun `the fingerprint matches the System UI code`() {
        val dir = File("src/main/kotlin/app/mauri/sukrara/monitor/systemui")
        val files = dir.listFiles { f -> f.extension == "kt" && f.name != "HookCode.kt" }!!.sortedBy { it.name }
        val md = MessageDigest.getInstance("SHA-256")
        for (f in files) {
            md.update((f.name + "\n").toByteArray())
            // line endings never change the code
            md.update(f.readText().replace("\r\n", "\n").toByteArray())
        }
        val code = md.digest().joinToString("") { "%02x".format(it) }.take(16)
        assertEquals("System UI's code changed: set HookCode.VERSION to \"$code\"", code, HookCode.VERSION)
    }
}
