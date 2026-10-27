// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.protocol

import app.mauri.sukrara.protocol.event.BackupCodec
import app.mauri.sukrara.protocol.event.BackupEpoch
import app.mauri.sukrara.protocol.event.BackupPayload
import app.mauri.sukrara.protocol.event.EventFactory
import app.mauri.sukrara.protocol.event.RosterPayload
import app.mauri.sukrara.protocol.owner.IdentityCheck
import app.mauri.sukrara.protocol.owner.IdentityConflict
import app.mauri.sukrara.protocol.owner.OwnerMarks
import app.mauri.sukrara.protocol.testing.ContractFiles
import app.mauri.sukrara.protocol.testing.OgsVectors
import app.mauri.sukrara.protocol.testing.OgsVectors.Companion.str
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A3, contracts/protocol/sync/owner-conflict.json: the wearer's phone (device D2 after a restore + rotation to epoch 2)
 * sees roster / backup events signed by its identity. Its own echoes, its own rotation and late old events are fine;
 * a newer roster or backup from the showcase still publishing as the same identity is a conflict.
 */
class IdentityConflictTest {
    private val crypto = SdkTestCrypto()
    private val v = OgsVectors()
    private val f = EventFactory(crypto)
    private val i = v.key("I")
    private val d = v.key("D")
    private val g1 = v.key("G1")
    private val g2 = v.key("G2")
    private val d2 = crypto.keyPair(SecretKey.ofBytes(Hex.sha256("sukrara/sync/D2".toByteArray()))!!)!!
    private val g3 = crypto.keyPair(SecretKey.ofBytes(Hex.sha256("sukrara/sync/G3".toByteArray()))!!)!!
    private val now = v.now
    private val marks = OwnerMarks(i, d2.public.hex, v.sid, mapOf(Wire.gk(g1.public) to (1 to g1), Wire.gk(g2.public) to (2 to g2)), now - 50, now - 40)

    private fun roster(epoch: Int, g: KeyPair, device: KeyPair, at: Long) =
        f.roster(i, g.public, v.sid, RosterPayload(epoch, listOf(device.public.hex), emptyList(), emptyList(), null), at)

    private fun backup(epochs: List<Pair<Int, KeyPair>>, at: Long) =
        f.backup(i, BackupPayload(v.sid, epochs.map { (n, g) -> BackupEpoch(n, crypto.nsec(g.secret)) }, emptyList(), emptyList(), emptyList(), emptyList(), null), at)

    private fun cases(): List<Triple<String, SignedEvent, String>> = listOf(
        Triple("own-roster-echo", roster(2, g2, d2, now - 50), "fine"),
        Triple("own-rotation-unrecorded", roster(2, g2, d2, now - 10), "fine"),
        Triple("old-roster-late", roster(1, g1, d, now - 500), "fine"),
        Triple("showcase-roster", roster(1, g1, d, now + 10), "conflict:RosterFromOtherDevice"),
        Triple("unknown-epoch-roster", roster(3, g3, d, now + 20), "conflict:RosterUnknownEpoch"),
        Triple("own-backup-echo", backup(listOf(1 to g1, 2 to g2), now - 40), "fine"),
        Triple("own-backup-unrecorded", backup(listOf(1 to g1, 2 to g2), now - 5), "fine"),
        Triple("old-backup-late", backup(listOf(1 to g1), now - 1000), "fine"),
        Triple("showcase-backup", backup(listOf(1 to g1), now + 30), "conflict:BackupFromElsewhere"),
        Triple("stranger-roster", f.roster(v.key("X"), g1.public, v.sid, RosterPayload(1, listOf(d.public.hex), emptyList(), emptyList(), null), now + 40), "fine"),
    )

    private fun code(c: IdentityCheck) = when (c) {
        IdentityCheck.Fine -> "fine"
        is IdentityCheck.Conflict -> "conflict:${c.reason.name}"
    }

    @Test fun ownerConflictFixture() {
        if (System.getProperty("sukrara.regen") == "true") {
            val fx = buildJsonObject {
                put("v", 1)
                put("about", "A3 owner-side identity conflict (contracts/protocol/sync/README.md). Throwaway keys.")
                put("keys", buildJsonObject { listOf("I" to i, "D" to d, "D2" to d2, "G1" to g1, "G2" to g2, "G3" to g3).forEach { (n, k) -> put(n, buildJsonObject { put("sec", k.secret.revealHex()); put("pub", k.public.hex) }) } })
                put("sid", v.sid)
                put("marks", buildJsonObject {
                    put("identity", "I")
                    put("device", "D2")
                    put("epochs", buildJsonObject { put("1", "G1"); put("2", "G2") })
                    put("lastRosterAt", marks.lastRosterAt)
                    put("lastBackupAt", marks.lastBackupAt)
                })
                val list = cases()
                put("events", buildJsonObject { list.forEach { (n, ev) -> put(n, Json.parseToJsonElement(ev.toJson())) } })
                put("checks", buildJsonArray { list.forEach { (n, _, e) -> add(buildJsonObject { put("event", n); put("expect", e) }) } })
            }
            File(System.getProperty("sukrara.contracts"), "protocol/sync/owner-conflict.json").writeText(Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), fx) + "\n")
        }
        val fx = ContractFiles.json("protocol/sync/owner-conflict.json")
        val events = fx.getValue("events").jsonObject
        fx.getValue("checks").jsonArray.forEach {
            val c = it.jsonObject
            val ev = SignedEvent.parse(events.getValue(c.str("event")).toString())!!
            assertEquals(c.str("event"), c.str("expect"), code(IdentityConflict.check(crypto, marks, ev)))
        }
    }

    @Test fun backupBodyKeepsWhatTheShowcaseWrites() {
        val showcase = """{"v":2,"sid":"${v.sid}","epochs":[{"n":1,"g":"${crypto.nsec(g1.secret)}"}],
            "followers":[{"f":"${d.public.hex}","name":"Mom","since":5,"avatar":null}],
            "invites":[{"t":"tok","x":9,"label":"Mom","nearby":false,"sentAt":3,"claimedBy":null,"done":true}],
            "relays":["wss://nos.lol","ws://x"],"writers":["${d.public.hex}"],"profile":{"name":"Alex","avatar":null}}"""
        val b = BackupCodec.decode(showcase)
        assertEquals(listOf(1), b.epochs.map { it.n })
        assertEquals("Mom", b.followers.single().name)
        assertEquals("Mom", b.invites.single().label)
        assertEquals(true, b.invites.single().done)
        assertEquals(listOf("wss://nos.lol"), b.relays)
        assertEquals(listOf(d.public.hex), b.writers)
        assertEquals(b, BackupCodec.decode(BackupCodec.encode(b)))
        assertEquals("BackupPayload(epochs=[1], followers=1)", b.toString())
    }
}
