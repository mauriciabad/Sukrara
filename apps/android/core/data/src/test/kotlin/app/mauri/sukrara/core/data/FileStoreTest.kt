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
    fun `a damaged file stays Corrupt on every load, is kept and reported once, never read as Missing`() = runTest {
        store.save(7)
        store.file.writeText("{not a number")

        val load = store.load()

        assertTrue(load is FileLoad.Corrupt)
        val aside = (load as FileLoad.Corrupt).quarantinedTo
        assertEquals("pens.txt.corrupt-${clock.now().toEpochMilli()}", aside.name)
        assertEquals("{not a number", aside.readText())
        assertEquals("{not a number", store.file.readText())
        assertEquals(load, store.load())
        assertEquals(aside, log.files.value.single().quarantinedTo)
    }

    @Test
    fun `a deliberate save replaces a damaged file`() = runTest {
        store.file.parentFile!!.mkdirs()
        store.file.writeText("x")
        assertTrue(store.load() is FileLoad.Corrupt)
        store.save(3)
        assertEquals(FileLoad.Loaded(3), store.load())
    }

    @Test
    fun `reports survive the process until acknowledged`() = runTest {
        store.file.parentFile!!.mkdirs()
        store.file.writeText("x")
        store.load()

        val nextRun = CorruptionLog()
        nextRun.rescan(listOf(tmp.root))
        val report = nextRun.files.value.single()
        assertEquals("pens.txt", report.store)

        nextRun.acknowledge(report)
        val afterAck = CorruptionLog().apply { rescan(listOf(tmp.root)) }
        assertTrue(afterAck.files.value.isEmpty())
        assertTrue("the copy is kept", tmp.root.resolve("pens").list()!!.any { ".seen-" in it })
    }

    @Test
    fun `save replaces atomically`() = runTest {
        store.save(1)
        store.save(2)
        assertEquals(FileLoad.Loaded(2), store.load())
    }
}
