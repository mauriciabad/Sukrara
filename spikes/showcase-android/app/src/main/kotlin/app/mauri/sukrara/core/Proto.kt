// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core

import java.math.BigInteger
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.nostrdevkit.sdk.Event
import org.nostrdevkit.sdk.EventBuilder
import org.nostrdevkit.sdk.Keys
import org.nostrdevkit.sdk.Kind
import org.nostrdevkit.sdk.Nip44Version
import org.nostrdevkit.sdk.PublicKey
import org.nostrdevkit.sdk.Tag
import org.nostrdevkit.sdk.Timestamp
import org.nostrdevkit.sdk.UnwrappedGift
import org.nostrdevkit.sdk.nip44Decrypt
import org.nostrdevkit.sdk.nip44Encrypt
import org.nostrdevkit.sdk.nip59MakeGiftWrap

/** One glucose reading. [t] unix seconds, [sgv] mg/dL, [rate] mg/dL/min (null = unknown). */
data class Reading(val t: Long, val sgv: Int, val rate: Double?, val dev: String = "")

/** `cgmshare:join?i=<npub I>&t=<token>&x=<expiry>&r=<relay>,<relay>` — contains no secret. */
data class Invite(val iPub: String, val token: String, val expiry: Long, val relays: List<String>)

/** Result of opening a NIP-59 gift wrap: [sender] is the *seal* signer (authenticated by the library). */
data class Unwrapped(val sender: String, val kind: Int, val body: JSONObject, val createdAt: Long)

/**
 * CGM-over-Nostr protocol v2 (spikes/nostr/PROTOCOL-v2.md): tags, event builders, gift wraps, invites.
 * Pure protocol, no I/O. Byte-for-byte compatible with the Python reference (spikes/nostr/v2/cgm2.py).
 */
object Proto {
    const val LIVE = 21078
    const val CHUNK = 30078
    const val WRAP = 1059
    const val RUMOR_TO_OWNER = 30500
    const val RUMOR_TO_FOLLOWER = 30501
    const val DAY = 86400L

    val RELAYS = listOf("wss://nos.lol", "wss://relay.primal.net", "wss://nostr.mom", "wss://relay.nostr.net")

    private val rng = SecureRandom()

    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** `H(x) = hex(sha256(x))[:32]` */
    fun h(x: String) = hex(sha256(x.toByteArray())).substring(0, 32)

    /** `gk_n = hex(sha256(G_n.pub))[:8]` — over the 32 pubkey *bytes*. */
    fun gk(gPubHex: String) = hex(sha256(unhex(gPubHex))).substring(0, 8)

    fun chunkD(sid: String, hour: Long) = h("cgm2:h:$sid:$hour")
    fun chunkC(sid: String) = h("cgm2:glucose:$sid")
    fun rosterD(sid: String) = h("cgm2:roster:$sid")
    fun backupD(iPub: String) = h("cgm2:backup:$iPub")
    fun treatmentsC(sid: String) = h("cgm2:treatments:$sid")
    fun treatmentD(sid: String, id: String) = h("cgm2:tr:$sid:$id")

    /** 4 digits both screens show: `int(sha256(F.pub_hex + token)) mod 10000`, zero-padded. */
    fun matchCode(fPub: String, token: String): String =
        BigInteger(1, sha256((fPub + token).toByteArray())).mod(BigInteger.valueOf(10000)).toString().padStart(4, '0')

    fun newToken(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16).also { rng.nextBytes(it) })

    fun newSid(): String = hex(ByteArray(32).also { rng.nextBytes(it) })

    fun pub(k: Keys): String = k.publicKey().toHex()
    fun npub(hexPub: String): String = PublicKey.parse(hexPub).toBech32()
    fun shortNpub(hexPub: String): String = npub(hexPub).let { it.take(10) + "…" + it.takeLast(4) }
    fun hourOf(t: Long) = t - Math.floorMod(t, 3600L)
    fun nowS() = System.currentTimeMillis() / 1000

    fun enc(sender: Keys, recipientHex: String, json: String): String =
        nip44Encrypt(sender.secretKey(), PublicKey.parse(recipientHex), json, Nip44Version.V2)

    fun dec(recipient: Keys, senderHex: String, ct: String): JSONObject =
        JSONObject(nip44Decrypt(recipient.secretKey(), PublicKey.parse(senderHex), ct))

    fun tag(vararg v: String): Tag = Tag.parse(v.toList())
    fun expiration(at: Long) = tag("expiration", at.toString())

    fun signed(k: Keys, kind: Int, content: String, tags: List<Tag>, createdAt: Long = nowS()): Event =
        EventBuilder(Kind(kind.toUShort()), content)
            .tags(tags)
            .customCreatedAt(Timestamp.fromSecs(createdAt.toULong()))
            .finalize(k)

    // ------------------------------------------------------------------------------------------------ data events

    private fun rate2(r: Double?): Any = r?.let { Math.round(it * 100) / 100.0 } ?: JSONObject.NULL

    /** live, kind 21078, signed by D, `["gk", gk_n]`, expires now + 1 h. */
    fun liveEvent(d: Keys, gPub: String, r: Reading, now: Long = nowS()): Event {
        val body = JSONObject().put("v", 2).put("t", r.t).put("sgv", r.sgv).put("r", rate2(r.rate)).put("dev", r.dev)
        return signed(d, LIVE, enc(d, gPub, body.toString()), listOf(tag("gk", gk(gPub)), expiration(now + 3600)), now)
    }

    /** hour chunk, kind 30078, signed by D; `d`/`c` opaque, expires hour + 7 d. */
    fun chunkEvent(d: Keys, gPub: String, sid: String, hour: Long, rows: Collection<Reading>, createdAt: Long): Event {
        val inHour = rows.filter { it.t >= hour && it.t < hour + 3600 }.sortedBy { it.t }
        val r = JSONArray()
        inHour.forEach {
            r.put(JSONArray().put(it.t - hour).put(it.sgv).put(it.rate?.let { x -> Math.round(x * 100) } ?: JSONObject.NULL))
        }
        val body = JSONObject().put("v", 2).put("h", hour).put("dev", inHour.lastOrNull()?.dev ?: "").put("r", r)
        val tags = listOf(tag("d", chunkD(sid, hour)), tag("c", chunkC(sid)), tag("gk", gk(gPub)), expiration(hour + 7 * DAY))
        return signed(d, CHUNK, enc(d, gPub, body.toString()), tags, createdAt)
    }

    /** roster, kind 30078, signed by I, encrypted to G_n, expires now + 30 d. */
    fun rosterEvent(i: Keys, gPub: String, sid: String, epoch: Int, devices: List<String>, relays: List<String>,
                    createdAt: Long = nowS(), writers: List<String> = emptyList()): Event {
        val body = JSONObject().put("v", 2).put("epoch", epoch).put("devices", JSONArray(devices))
            .put("writers", JSONArray(writers)).put("relays", JSONArray(relays))
        val tags = listOf(tag("d", rosterD(sid)), tag("gk", gk(gPub)), expiration(createdAt + 30 * DAY))
        return signed(i, CHUNK, enc(i, gPub, body.toString()), tags, createdAt)
    }

    /**
     * Treatment (libreview SPEC §5; investigation 07): kind 30078 per treatment, signed by the writer W (a roster
     * `writers` key), NIP-44 from W to G_n, `d = H("cgm2:tr:"+sid+":"+id)`, `c = H("cgm2:treatments:"+sid)`, 30 d expiry.
     * Body = the §1 treatment JSON (`id,k,a,t,n,u,del,o`) without `lv`.
     */
    fun treatmentEvent(w: Keys, gPub: String, sid: String, tr: JSONObject, now: Long = nowS()): Event {
        val tags = listOf(tag("d", treatmentD(sid, tr.getString("id"))), tag("c", treatmentsC(sid)), tag("gk", gk(gPub)),
            expiration(now + 30 * DAY))
        return signed(w, CHUNK, enc(w, gPub, tr.toString()), tags, now)
    }

    /** owner backup, kind 30078, signed by I and encrypted to I itself, expires now + 90 d. */
    fun backupEvent(i: Keys, body: JSONObject, createdAt: Long = nowS()): Event {
        val iPub = pub(i)
        return signed(i, CHUNK, enc(i, iPub, body.toString()), listOf(tag("d", backupD(iPub)), expiration(createdAt + 90 * DAY)), createdAt)
    }

    // ------------------------------------------------------------------------------------------------ gift wraps

    /** NIP-59: rumor (unsigned, author = sender) → seal (signed by sender) → wrap (one-time key, `p` = recipient). */
    fun gift(sender: Keys, recipientHex: String, kind: Int, body: JSONObject, ttlS: Long = 30 * DAY): Event {
        val rumor = EventBuilder(Kind(kind.toUShort()), body.toString()).finalizeUnsigned(sender.publicKey())
        return nip59MakeGiftWrap(sender, PublicKey.parse(recipientHex), rumor, java.time.Duration.ofSeconds(ttlS))
    }

    /** null if not for us / malformed / seal invalid. The library verifies the seal and that rumor.pubkey == seal.pubkey. */
    fun unwrap(recipient: Keys, ev: Event): Unwrapped? = runCatching {
        UnwrappedGift.fromGiftWrap(recipient, ev).use { u ->
            val r = u.rumor()
            Unwrapped(u.sender().toHex(), r.kind().asU16().toInt(), JSONObject(r.content()), r.createdAt().asSecs().toLong())
        }
    }.getOrNull()

    fun tagValue(ev: JSONObject, name: String): String? {
        val tags = ev.optJSONArray("tags") ?: return null
        for (i in 0 until tags.length()) {
            val t = tags.optJSONArray(i) ?: continue
            if (t.length() > 1 && t.optString(0) == name) return t.optString(1)
        }
        return null
    }

    // ------------------------------------------------------------------------------------------------ invites

    /** Sukrara: invites are App Links (https://sukrara.mauri.app/join#<query>, verified via assetlinks.json). */
    const val WEB_JOIN = "https://sukrara.mauri.app/join#"

    fun inviteLink(iPub: String, token: String, expiry: Long, relays: List<String>): String =
        WEB_JOIN + "i=${npub(iPub)}&t=$token&x=$expiry&r=${URLEncoder.encode(relays.joinToString(","), "UTF-8")}"

    /** The same invite as `cgmshare:join?…` (GlucoseBar on the Mac and CGM Share only parse this form). */
    fun legacyLink(link: String): String? = inviteQueryOf(link)?.let { "cgmshare:join?$it" }

    /** Is this one of our invite links (either form)? */
    fun isInvite(link: String) = inviteQueryOf(link) != null

    private fun inviteQueryOf(link: String): String? {
        val l = link.trim()
        val q = when {
            l.startsWith("cgmshare:join?") -> l.substringAfter("cgmshare:join?")
            l.startsWith("https://sukrara.mauri.app/join") -> l.substringAfter("#", "").ifEmpty { l.substringAfter("?", "") }
            else -> ""
        }
        return q.takeIf { it.contains("i=") && it.contains("t=") }
    }

    fun parseInvite(link: String): Invite? = runCatching {
        val q = inviteQueryOf(link) ?: return null
        val m = q.split("&").associate { kv ->
            kv.substringBefore("=") to URLDecoder.decode(kv.substringAfter("=", ""), "UTF-8")
        }
        Invite(
            iPub = PublicKey.parse(m.getValue("i")).toHex(),
            token = m.getValue("t"),
            expiry = m.getValue("x").toLong(),
            relays = m.getValue("r").split(",").map { it.trim() }.filter { it.startsWith("wss://") },
        )
    }.getOrNull()

    fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else List(a.length()) { a.getString(it) }
}
