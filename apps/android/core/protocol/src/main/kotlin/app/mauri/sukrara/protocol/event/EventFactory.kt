// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.protocol.event

import app.mauri.sukrara.protocol.KeyPair
import app.mauri.sukrara.protocol.NostrCrypto
import app.mauri.sukrara.protocol.PublicKeyHex
import app.mauri.sukrara.protocol.SignedEvent
import app.mauri.sukrara.protocol.UnsignedEvent
import app.mauri.sukrara.protocol.Wire

/**
 * Builds and signs every v2 event exactly as PROTOCOL-v2 lays them out (kinds, tag order `d, c, gk, expiration`, TTLs,
 * NIP-44 v2 to the read key). Time is always passed in; [now] / `createdAt` must strictly increase per addressable
 * `(author, d)` — that is the caller's (publisher's) job.
 */
class EventFactory(private val crypto: NostrCrypto) {
    /** live (21078) signed by the device key [device], encrypted to the read key [readKey], expires now + 1 h. */
    fun live(device: KeyPair, readKey: PublicKeyHex, payload: LivePayload, now: Long): SignedEvent =
        data(device, Wire.KIND_LIVE, readKey, ReadingsCodec.encodeLive(payload), listOf(gkTag(readKey), expiration(now + Wire.TTL_LIVE)), now)

    /** alert (21079): the live envelope. */
    fun alert(device: KeyPair, readKey: PublicKeyHex, payload: AlertPayload, now: Long): SignedEvent =
        data(device, Wire.KIND_ALERT, readKey, AlertCodec.encode(payload), listOf(gkTag(readKey), expiration(now + Wire.TTL_LIVE)), now)

    /** hour chunk (30078), expires hour + 7 d. */
    fun chunk(device: KeyPair, readKey: PublicKeyHex, sid: String, hour: Long, readings: Collection<WireReading>, createdAt: Long): SignedEvent {
        val tags = listOf(
            listOf("d", Wire.chunkD(sid, hour)),
            listOf("c", Wire.glucoseC(sid)),
            gkTag(readKey),
            expiration(hour + Wire.TTL_CHUNK_AFTER_HOUR),
        )
        return data(device, Wire.KIND_DATA, readKey, ReadingsCodec.encodeChunk(hour, readings), tags, createdAt)
    }

    /** roster (30078) signed by the identity, expires now + 30 d. */
    fun roster(identity: KeyPair, readKey: PublicKeyHex, sid: String, roster: RosterPayload, createdAt: Long): SignedEvent {
        val tags = listOf(listOf("d", Wire.rosterD(sid)), gkTag(readKey), expiration(createdAt + Wire.TTL_ROSTER))
        return data(identity, Wire.KIND_DATA, readKey, RosterCodec.encode(roster), tags, createdAt)
    }

    /** treatment (30078) signed by the wearer's device (merged log) or a writer (its own entries), expires now + 30 d. */
    fun treatment(signer: KeyPair, readKey: PublicKeyHex, sid: String, tr: TreatmentPayload, createdAt: Long): SignedEvent {
        val tags = listOf(
            listOf("d", Wire.treatmentD(sid, tr.id)),
            listOf("c", Wire.treatmentsC(sid)),
            gkTag(readKey),
            expiration(createdAt + Wire.TTL_TREATMENT),
        )
        return data(signer, Wire.KIND_DATA, readKey, TreatmentCodec.encode(tr), tags, createdAt)
    }

    /** the wearer's mirrored settings (30078), expires now + 30 d. */
    fun settings(device: KeyPair, readKey: PublicKeyHex, sid: String, s: SettingsPayload, createdAt: Long): SignedEvent {
        val tags = listOf(listOf("d", Wire.settingsD(sid)), gkTag(readKey), expiration(createdAt + Wire.TTL_SETTINGS))
        return data(device, Wire.KIND_DATA, readKey, SettingsCodec.encode(s), tags, createdAt)
    }

    /** owner backup (30078) signed by the identity and encrypted to the identity itself, expires now + 90 d. */
    fun backup(identity: KeyPair, b: BackupPayload, createdAt: Long): SignedEvent {
        val tags = listOf(listOf("d", Wire.backupD(identity.public)), expiration(createdAt + Wire.TTL_BACKUP))
        return data(identity, Wire.KIND_DATA, identity.public, BackupCodec.encode(b), tags, createdAt)
    }

    /** A gift-wrapped [rumor] from [sender] to [recipient], written [now], expiring at the absolute [expiration] (PROTOCOL-v2 TTLs). */
    fun gift(sender: KeyPair, recipient: PublicKeyHex, rumor: Rumor, now: Long, expiration: Long): SignedEvent =
        crypto.giftWrap(sender, recipient, rumor.kind, RumorCodec.encode(rumor), now, expiration)

    private fun data(signer: KeyPair, kind: Int, readKey: PublicKeyHex, plaintext: String, tags: List<List<String>>, createdAt: Long): SignedEvent =
        crypto.sign(signer, UnsignedEvent(kind, crypto.encrypt(signer.secret, readKey, plaintext), tags, createdAt))

    private fun gkTag(readKey: PublicKeyHex) = listOf("gk", Wire.gk(readKey))

    private fun expiration(at: Long) = listOf("expiration", at.toString())
}
