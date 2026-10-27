// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core.data

import app.mauri.sukrara.core.data.json.SukraraJson
import app.mauri.sukrara.core.data.state.TypedStore
import app.mauri.sukrara.core.testing.FakeClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TypedStoreCorruptionTest {
    @get:Rule val tmp = TemporaryFolder()

    @Serializable
    data class Pairing(val serial: String, val epoch: Int)

    private companion object {
        /** What the owner chose to continue from when the file is damaged (distinct from "never set"). */
        val LOST = Pairing("lost", -1)
    }

    private val log = CorruptionLog()
    private val clock = FakeClock()
    private val file by lazy { File(tmp.root, "state/pairing.json") }

    private fun store(scope: CoroutineScope) =
        TypedStore<Pairing?>(file, Pairing.serializer().nullable, initial = null, whenCorrupt = LOST, SukraraJson, log, clock, scope)

    @Test
    fun `missing file starts at initial, updates persist`() = runTest {
        val store = store(backgroundScope)
        assertNull(store.data.first())
        store.update { Pairing("ABC", 1) }
        assertEquals(Pairing("ABC", 1), store.data.first())
        assertTrue(file.readText().contains("\"serial\":\"ABC\""))
        assertNull(store.corruption.value)
    }

    @Test
    fun `a damaged file is kept aside and reported, then the chosen value applies`() = runTest {
        file.parentFile!!.mkdirs()
        file.writeText("{\"serial\": tru")
        val store = store(backgroundScope)

        assertEquals(LOST, store.data.first())

        val report = checkNotNull(store.corruption.value)
        assertEquals("{\"serial\": tru", report.quarantinedTo!!.readText())
        assertEquals(listOf(report), log.files.value)
    }

    @Test
    fun `unknown fields from a newer version are ignored, not corruption`() = runTest {
        file.parentFile!!.mkdirs()
        file.writeText("{\"serial\":\"X\",\"epoch\":3,\"future\":true}")
        val store = store(backgroundScope)
        assertEquals(Pairing("X", 3), store.data.first())
        assertNull(store.corruption.value)
    }
}
