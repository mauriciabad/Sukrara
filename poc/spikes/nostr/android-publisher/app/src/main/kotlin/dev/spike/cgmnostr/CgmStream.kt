// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.cgmnostr

import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Nip44Version
import rust.nostr.sdk.Tag
import rust.nostr.sdk.Timestamp
import rust.nostr.sdk.nip44Encrypt

/**
 * CGM-over-Nostr protocol v1 (spikes/nostr/PROTOCOL.md): keys, follower link, chunk ids and event building.
 * Pure protocol, no I/O except key persistence — this is the part that would move into GlucoBridge as-is.
 *
 * [source] = S (signs everything, never leaves the phone), [read] = G (NIP-44 target, shared with followers).
 */
class CgmStream(val source: Keys, val read: Keys, val relays: List<String>, val dev: String = "juggluco") {

    private val readPubHex = read.publicKey().toHex()

    val npub: String get() = source.publicKey().toBech32()

    /** `cgmnostr:1?s=<npub S>&k=<nsec G>&r=<url-encoded "relay,relay,…">` (same encoding as cgmnostr.py). */
    fun followerLink(): String =
        "cgmnostr:1?s=$npub&k=${read.secretKey().toBech32()}&r=${URLEncoder.encode(relays.joinToString(","), "UTF-8")}"

    /** NIP-49 export of S (scrypt log_n=16 → ~64 MiB, ~0.5 s: call off the main thread). */
    fun sourceNcryptsec(password: String): String = source.secretKey().encrypt(password).toBech32()

    /** First 32 hex chars of sha256("cgm1:" + G.pub_hex + ":" + hourStart). */
    fun chunkId(hourStart: Long): String {
        val d = MessageDigest.getInstance("SHA-256").digest("cgm1:$readPubHex:$hourStart".toByteArray())
        return d.joinToString("") { "%02x".format(it) }.substring(0, 32)
    }

    private fun encrypt(json: String): String =
        nip44Encrypt(source.secretKey(), read.publicKey(), json, Nip44Version.V2)

    private fun build(kind: Int, json: String, tags: List<Tag>, createdAt: Long): Event =
        EventBuilder(Kind(kind.toUShort()), encrypt(json))
            .tags(tags)
            .customCreatedAt(Timestamp.fromSecs(createdAt.toULong()))
            .finalize(source)

    /** Kind 21078, expires now + 1 h. */
    fun liveEvent(r: Reading, now: Long): Event {
        val body = JSONObject()
            .put("v", 1).put("t", r.t).put("sgv", r.sgv)
            .put("r", r.rate?.let { Math.round(it * 100) / 100.0 } ?: JSONObject.NULL)
            .put("dev", dev)
        return build(LIVE_KIND, body.toString(), listOf(expiration(now + LIVE_TTL)), now)
    }

    /** Kind 30078 hourly chunk for [hourStart]; [rows] = readings inside that hour. Expires hourStart + 7 d. */
    fun chunkEvent(hourStart: Long, rows: Collection<Reading>, createdAt: Long): Event {
        val r = JSONArray()
        rows.filter { it.t >= hourStart && it.t < hourStart + 3600 }.sortedBy { it.t }.forEach {
            r.put(JSONArray().put(it.t - hourStart).put(it.sgv).put(it.rate?.let { x -> Math.round(x * 100) } ?: JSONObject.NULL))
        }
        val body = JSONObject().put("v", 1).put("h", hourStart).put("dev", dev).put("r", r)
        val tags = listOf(Tag.identifier(chunkId(hourStart)), expiration(hourStart + CHUNK_TTL))
        return build(CHUNK_KIND, body.toString(), tags, createdAt)
    }

    private fun expiration(at: Long) = Tag.expiration(Timestamp.fromSecs(at.toULong()))

    companion object {
        const val LIVE_KIND = 21078
        const val CHUNK_KIND = 30078
        const val LIVE_TTL = 3600L
        const val CHUNK_TTL = 7 * 24 * 3600L

        /**
         * Write set. The protocol allows 3–6; on the phone we keep 4 sockets for battery. relay.damus.io is left out
         * (it bans bursts and currently bans our home IP); offchain.pub is a good 5th/alternate.
         */
        val RELAYS = listOf(
            "wss://nos.lol",
            "wss://relay.primal.net",
            "wss://nostr.mom",
            "wss://relay.nostr.net",
        )

        /** Load S and G from app-private storage, generating them on first run. */
        fun loadOrCreate(filesDir: File): CgmStream {
            val f = File(filesDir, "keys.json")
            val (s, g) = if (f.exists()) {
                val j = JSONObject(f.readText())
                Keys.parse(j.getString("s")) to Keys.parse(j.getString("g"))
            } else {
                val s = Keys.generate()
                val g = Keys.generate()
                val tmp = File(filesDir, "keys.json.tmp")
                tmp.writeText(JSONObject().put("s", s.secretKey().toHex()).put("g", g.secretKey().toHex()).toString())
                tmp.renameTo(f)
                s to g
            }
            return CgmStream(s, g, RELAYS)
        }
    }
}
