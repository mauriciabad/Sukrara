// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core.data

import app.mauri.sukrara.core.data.files.FileCodec
import app.mauri.sukrara.core.data.files.FileLoad
import app.mauri.sukrara.core.data.files.FileStore
import app.mauri.sukrara.core.testing.FakeClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Integers as text; anything else is damage. */
    private object IntCodec : FileCodec<Int> {
        override fun encode(value: Int) = value.toString().encodeToByteArray()

        override fun decode(bytes: ByteArray) = bytes.decodeToString().toInt()
    }

    private val clock = FakeClock()
    private val log = CorruptionLog()
    private val store by lazy { FileStore(tmp.root.resolve("pens/pens.txt"), IntCodec, log, clock, Dispatchers.IO) }

    @Test
    fun `missing, then saved and loaded`() = runTest {
        assertEquals(FileLoad.Missing, store.load())
        store.save(42)
        assertEquals(FileLoad.Loaded(42), store.load())
        assertFalse("no temp file left", store.file.resolveSibling("pens.txt.tmp").exists())
    }

    @Test
    fun `a damaged file is quarantined and reported, never replaced by empty data`() = runTest {
        store.save(7)
        store.file.writeText("{not a number")

        val load = store.load()

        assertTrue(load is FileLoad.Corrupt)
        val aside = (load as FileLoad.Corrupt).quarantinedTo
        assertEquals("pens.txt.corrupt-${clock.now().toEpochMilli()}", aside.name)
        assertEquals("{not a number", aside.readText())
        assertFalse(store.file.exists())
        assertEquals(aside, log.files.value.single().quarantinedTo)
        assertEquals(FileLoad.Missing, store.load())
    }

    @Test
    fun `save replaces atomically`() = runTest {
        store.save(1)
        store.save(2)
        assertEquals(FileLoad.Loaded(2), store.load())
    }
}
