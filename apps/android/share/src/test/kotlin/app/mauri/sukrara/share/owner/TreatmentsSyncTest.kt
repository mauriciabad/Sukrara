// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.owner

import app.mauri.sukrara.core.model.TreatmentOrigin
import app.mauri.sukrara.core.model.storage.TreatmentRecord
import app.mauri.sukrara.core.testing.InMemoryTreatmentsRepository
import app.mauri.sukrara.protocol.KeyPair
import app.mauri.sukrara.protocol.PublicKeyHex
import app.mauri.sukrara.protocol.SecretKey
import app.mauri.sukrara.protocol.SignedEvent
import app.mauri.sukrara.protocol.Wire
import app.mauri.sukrara.protocol.event.TreatmentCodec
import app.mauri.sukrara.protocol.follow.FollowResult
import app.mauri.sukrara.protocol.follow.FollowerState
import app.mauri.sukrara.protocol.testing.ContractFiles
import app.mauri.sukrara.protocol.testing.FollowerModel
import app.mauri.sukrara.share.OwnerFixtures
import app.mauri.sukrara.share.OwnerFixtures.crypto
import app.mauri.sukrara.share.owner.usecase.SyncTreatments
import app.mauri.sukrara.share.owner.usecase.SyncTreatments.Verdict
import app.mauri.sukrara.share.owner.usecase.TreatmentWire
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The owner side of contracts/protocol/sync/treatments-writer.json: the writer's own events from the fixture are judged
 * by the wearer's phone (writer scope), its echoes and its own entries are replayed through the follower, and the
 * follower ends with the fixture's expected treatments — the same file the Mac `SyncFixtureTests` decode.
 */
class TreatmentsSyncTest {
    private val fx = ContractFiles.json("protocol/sync/treatments-writer.json")
    private val keys = fx.getValue("keys").jsonObject
    private val events = fx.getValue("events").jsonObject
    private val now =
        fx
            .getValue("now")
            .jsonPrimitive.content
            .toLong()
    private val sid = fx.getValue("sid").jsonPrimitive.content

    private fun key(name: String): KeyPair {
        val sec =
            keys
                .getValue(name)
                .jsonObject
                .getValue("sec")
                .jsonPrimitive.content
        return crypto.keyPair(SecretKey.ofHex(sec)!!)!!
    }

    private fun event(name: String) = SignedEvent.parse(events.getValue(name).toString())!!

    private val w = key("W")
    private val owner =
        OwnerState(
            identityHex = key("I").secret.revealHex(),
            deviceHex = key("D").secret.revealHex(),
            sid = sid,
            epochs = listOf(EpochKey(1, key("G1").secret.revealHex())),
            followers = listOf(FollowerRecord(w.public.hex, "Ana", now - 1000), FollowerRecord(key("F").public.hex, "Mac", now - 1000)),
            writers = setOf(w.public.hex),
        )

    /** What the wearer's own event in the fixture holds, as the phone's log stores it (the wearer's own: no creator). */
    private fun wearerRecord(name: String): TreatmentRecord {
        val ev = event(name)
        val plain = crypto.decrypt(key("G1").secret, PublicKeyHex(ev.pubkey), ev.content)!!
        return TreatmentRecord(TreatmentWire.toTreatment(TreatmentCodec.decode(plain)))
    }

    @Test fun writerScopeOnTheOwnerAndTheEchoesGiveTheFixturesFollowerState() =
        runTest {
            val repo = InMemoryTreatmentsRepository()
            val sync = SyncTreatments(OwnerFixtures.context(), repo)
            val carbs = wearerRecord("owner-carbs")
            repo.upsertIfNewer(carbs)
            val published = mutableListOf(sync.publish(owner, carbs, now))

            val rapid = sync.receive(owner, event("writer-rapid"), now, "relay")
            val edit = sync.receive(owner, event("writer-edit"), now, "relay")
            assertEquals(Verdict.Accepted, rapid.verdict)
            assertEquals(Verdict.Accepted, edit.verdict)
            // the writer tries to change the wearer's carbs entry: refused, nothing echoed
            assertEquals(SyncTreatments.Received(Verdict.NotCreator), sync.receive(owner, event("writer-hijack"), now, "relay"))
            published += listOfNotNull(rapid.echo, edit.echo)

            val writerEntry = repo.get(TreatmentCodec.decode(plainOf(edit.echo!!)).id)!!
            assertEquals(w.public.hex, writerEntry.creator)
            assertEquals(TreatmentOrigin.Follower, writerEntry.treatment.origin)

            val delete = wearerRecord("owner-delete")
            repo.upsertIfNewer(delete)
            published += sync.publish(owner, delete, now)

            // every owner event is the device key's (the merged log), under the current epoch
            val device = owner.device(crypto).public.hex
            val gk = Wire.gk(owner.readKey(crypto).public)
            assertTrue(published.all { it.pubkey == device && it.tag("gk") == gk })

            val follower = FollowerModel(crypto, FollowerState(follower = key("F"), identity = key("I").public))
            follower.feed(event("key-1"), now)
            follower.feed(event("roster-writer"), now)
            published.forEach { assertTrue(it.id, follower.feed(it, now) is FollowResult.Accepted) }
            assertEquals(fx.getValue("expected").jsonObject.getValue("treatments"), follower.stateJson().getValue("treatments"))
        }

    @Test fun anOlderWriterVersionAndANonWriterAreNotMerged() =
        runTest {
            val repo = InMemoryTreatmentsRepository()
            val sync = SyncTreatments(OwnerFixtures.context(), repo)
            assertEquals(Verdict.Accepted, sync.receive(owner, event("writer-edit"), now, null).verdict)
            assertEquals(Verdict.Older, sync.receive(owner, event("writer-rapid"), now, null).verdict)
            // the writer lost "Can add entries"
            assertEquals(Verdict.NotWriter, sync.receive(owner.copy(writers = emptySet()), event("writer-edit"), now, null).verdict)
        }

    @Test fun republishSendsTheLast30DaysWithTombstonesAndStampsIt() =
        runTest {
            val repo = InMemoryTreatmentsRepository()
            val sync = SyncTreatments(OwnerFixtures.context(), repo)
            val deleted =
                wearerRecord("owner-delete").let {
                    it.copy(treatment = it.treatment.copy(modifiedAt = Instant.ofEpochSecond(now - Wire.DAY)))
                }
            val old =
                deleted.treatment.copy(
                    id = "c0000000-0000-4000-8000-00000000000c",
                    modifiedAt = Instant.ofEpochSecond(now - 40 * Wire.DAY),
                )
            repo.upsertIfNewer(deleted)
            repo.upsertIfNewer(TreatmentRecord(old))

            val step = sync.republish(owner, now)
            assertEquals(now, step.state.treatmentsPublishedAt)
            val sent = step.publish.map { TreatmentCodec.decode(plainOf(it)) }
            assertEquals(listOf(deleted.treatment.id), sent.map { it.id })
            assertTrue(sent.single().deleted)
            // created_at strictly increases per entry, or relays keep the older version
            assertTrue(
                sync
                    .republish(owner, now)
                    .publish
                    .single()
                    .createdAt > step.publish.single().createdAt,
            )
        }

    @Test fun theWriterSubscriptionFollowsTheActiveWriters() {
        val sync = SyncTreatments(OwnerFixtures.context(), InMemoryTreatmentsRepository())
        val filter = sync.writerFilter(owner, now)!!
        assertTrue(filter.matches(event("writer-rapid")))
        assertEquals(false, filter.matches(event("owner-carbs")))
        val removed = owner.copy(followers = owner.followers.map { if (it.key == w.public.hex) it.copy(revoked = now) else it })
        assertEquals(null, sync.writerFilter(removed, now))
    }

    private fun plainOf(ev: SignedEvent) = crypto.decrypt(key("G1").secret, PublicKeyHex(ev.pubkey), ev.content)!!
}
