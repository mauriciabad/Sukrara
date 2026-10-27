// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.owner

import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.SourceId
import app.mauri.sukrara.protocol.Wire
import app.mauri.sukrara.protocol.event.ReadingsCodec
import app.mauri.sukrara.share.OwnerFixtures
import app.mauri.sukrara.share.OwnerFixtures.crypto
import app.mauri.sukrara.share.owner.usecase.CreateIdentity
import app.mauri.sukrara.share.owner.usecase.PublishReadings
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic readings only. Events are decoded with :core:protocol as a follower would. */
class PublishReadingsTest {
    private val ctx = OwnerFixtures.context()
    private val now = Wire.hourOf(OwnerFixtures.NOW) + 1800
    private val s = CreateIdentity(ctx)(null, now).state
    private val publisher = PublishReadings(ctx)

    private fun reading(t: Long, mgdl: Int) = Reading(Instant.ofEpochSecond(t), mgdl, 1.0, SourceId.Libre3)

    private fun decrypt(content: String) = crypto.decrypt(s.readKey(crypto).secret, s.device(crypto).public, content)!!

    @Test fun aFreshReadingGoesLiveAndAnEarlierHourAsAChunkAtOnce() {
        val out = publisher.onReadings(s, listOf(reading(now - 3700, 100), reading(now - 60, 140)), now)
        val live = out.single { it.kind == Wire.KIND_LIVE }
        assertEquals(140, ReadingsCodec.decodeLive(decrypt(live.content)).reading!!.sgv)
        assertEquals(s.device(crypto).public.hex, live.pubkey)
        val chunk = out.single { it.tag("d") == Wire.chunkD(s.sid, Wire.hourOf(now - 3700)) }
        assertEquals(listOf(100), ReadingsCodec.decodeChunk(decrypt(chunk.content)).readings.map { it.sgv })
    }

    @Test fun anOldReadingIsNotLiveAndTheCurrentHourChunkIsPacedByTheTick() {
        val first = publisher.onReadings(s, listOf(reading(now - 900, 120)), now)
        assertTrue(first.none { it.kind == Wire.KIND_LIVE })
        assertEquals(Wire.chunkD(s.sid, Wire.hourOf(now)), first.single().tag("d"))
        // a minute later the current hour changed again: it waits for the next tick (≥ 5 min after the last chunk)
        assertTrue(publisher.onReadings(s, listOf(reading(now - 840, 121)), now + 60).none { it.kind == Wire.KIND_DATA })
        assertTrue(publisher.tick(s, now + 120).isEmpty())
        assertEquals(Wire.chunkD(s.sid, Wire.hourOf(now)), publisher.tick(s, now + PublishReadings.CHUNK_EVERY).single().tag("d"))
    }

    @Test fun republishedChunksHaveStrictlyIncreasingCreatedAt() {
        publisher.onReadings(s, listOf(reading(now - 3700, 100)), now)
        val first = publisher.republish(s, now).single()
        val second = publisher.republish(s, now).single()
        assertTrue(second.createdAt > first.createdAt)
    }
}
