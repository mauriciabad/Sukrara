// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.mauri.sukrara.core.data.db.OutboxEntity
import app.mauri.sukrara.core.model.DefaultDispatchers
import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.SourceId
import app.mauri.sukrara.core.testing.FakeClock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Duration
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class DataModuleTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = FakeClock()
    private val data = DataModule(context, "0.90.0-test", DefaultDispatchers, clock, secretBox = SoftwareSecretBox())

    @After
    fun close() = data.database.close()

    @Test
    fun `ready prunes old history but keeps recent readings and pending outbox entries`() = runTest {
        val now = clock.now()
        data.readings.upsert(
            listOf(
                Reading(now - Duration.ofDays(91), 100, null, SourceId.Libre3),
                Reading(now - Duration.ofDays(89), 110, null, SourceId.Libre3),
            ),
        )
        val old = (now - Duration.ofDays(30)).toEpochMilli()
        data.database.outbox().put(OutboxEntity("pending", 1, "{}", createdAt = old, sentAt = null))
        data.database.outbox().put(OutboxEntity("sent", 1, "{}", createdAt = old, sentAt = old))

        data.ready()

        assertEquals(listOf(110), data.readings.window(Instant.EPOCH).first().map { it.mgdl })
        assertEquals(listOf("pending"), data.database.outbox().pending().first().map { it.id })
    }

    @Test
    fun `ready re-reports damaged files kept by an earlier run`() = runTest {
        File(context.filesDir, "state").mkdirs()
        File(context.filesDir, "state/pairing.json.corrupt-1700000000000").writeText("{bad")

        data.ready()

        assertEquals(listOf("pairing.json"), data.corruption.files.value.map { it.store })
    }
}
