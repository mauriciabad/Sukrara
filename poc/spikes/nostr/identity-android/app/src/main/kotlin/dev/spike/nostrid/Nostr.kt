// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.nostrid

import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import org.nostrdevkit.sdk.Event
import org.nostrdevkit.sdk.EventBuilder
import org.nostrdevkit.sdk.Keys
import org.nostrdevkit.sdk.Kind
import org.nostrdevkit.sdk.PublicKey
import org.nostrdevkit.sdk.SecretKey
import org.nostrdevkit.sdk.Tag
import org.nostrdevkit.sdk.Timestamp
import org.nostrdevkit.sdk.UnwrappedGift
import org.nostrdevkit.sdk.nip59MakeGiftWrap

/** Events of investigation 14 (spikes/nostr/PROTOCOL-identity.md); byte-compatible with identity/ident.py. */
object Nostr {
    // relay.damus.io / relay.nostr.net accept gift wraps but can't serve them (AUTH misconfigured, 2026-10-16).
    val RELAYS = listOf("wss://nos.lol", "wss://relay.primal.net", "wss://nostr.mom", "wss://offchain.pub")
    const val RUMOR_REQUEST = 30500     // PROTOCOL-v2 access request (to the wearer's identity)
    const val RUMOR_TO_FOLLOWER = 30501 // PROTOCOL-v2 key delivery (here: "approved" + profile)
    const val RUMOR_INVITE = 30502      // v3 invite, addressed to the link's invite key
    const val DAY = 86400L
    val rng = SecureRandom()

    fun keys(sec: ByteArray): Keys = Keys(SecretKey.fromBytes(sec))
    fun sec(k: Keys): ByteArray = Derive.unhex(k.secretKey().toHex())
    fun pub(k: Keys): String = k.publicKey().toHex()
    fun npub(hex: String): String = PublicKey.parse(hex).toBech32()
    fun shortNpub(hex: String) = npub(hex).let { it.take(12) + "…" + it.takeLast(4) }
    fun now() = System.currentTimeMillis() / 1000
    fun random(n: Int) = ByteArray(n).also { rng.nextBytes(it) }
    fun tag(vararg v: String): Tag = Tag.parse(v.toList())

    /** NIP-01/24 kind 0 + NIP-92 imeta for the picture. Only name + picture: nothing that hints at health. */
    fun metadata(p: Keys, name: String, picture: Pic?): Event {
        val md = JSONObject().put("name", name).put("display_name", name)
        val tags = mutableListOf<Tag>()
        if (picture != null) {
            md.put("picture", picture.url)
            tags += Tag.parse(listOf("imeta", "url ${picture.url}", "m ${picture.mime}", "x ${picture.sha256}",
                "dim ${picture.dim}") + picture.fallbacks.map { "fallback $it" })
        }
        return EventBuilder(Kind(0u), md.toString()).tags(tags).finalize(p)
    }

    data class Pic(val url: String, val mime: String, val sha256: String, val dim: String, val fallbacks: List<String>)
    data class Profile(val name: String, val picture: String?, val fallbacks: List<String>)

    fun parseProfile(ev: JSONObject): Profile {
        val c = runCatching { JSONObject(ev.getString("content")) }.getOrDefault(JSONObject())
        val pic = c.optString("picture").takeIf { it.isNotBlank() }
        val fb = mutableListOf<String>()
        val tags = ev.optJSONArray("tags") ?: JSONArray()
        for (i in 0 until tags.length()) {
            val t = tags.optJSONArray(i) ?: continue
            if (t.optString(0) != "imeta") continue
            val fields = (1 until t.length()).map { t.getString(it) }
            if (fields.none { it == "url $pic" }) continue        // NIP-92: applies only when url == picture
            fb += fields.filter { it.startsWith("fallback ") }.map { it.removePrefix("fallback ") }
        }
        return Profile(c.optString("display_name").ifBlank { c.optString("name") }, pic, fb)
    }

    // ------------------------------------------------------------------------------------------- gift wraps

    fun gift(sender: Keys, recipientHex: String, kind: Int, body: JSONObject, expiresAt: Long): Event {
        val rumor = EventBuilder(Kind(kind.toUShort()), body.toString()).finalizeUnsigned(sender.publicKey())
        return nip59MakeGiftWrap(sender, PublicKey.parse(recipientHex), rumor, null,
            listOf(tag("expiration", expiresAt.toString())))
    }

    data class Unwrapped(val sender: String, val kind: Int, val body: JSONObject)

    fun unwrap(recipient: Keys, ev: JSONObject): Unwrapped? = runCatching {
        UnwrappedGift.fromGiftWrap(recipient, Event.fromJson(ev.toString())).use { u ->
            val r = u.rumor()
            Unwrapped(u.sender().toHex(), r.kind().asU16().toInt(), JSONObject(r.content()))
        }
    }.getOrNull()

    /** Invite: gift wrap sealed by the identity, addressed to inviteKey(secret), NIP-40 expiry = invite expiry. */
    fun inviteWrap(identity: Keys, secret: ByteArray, expiry: Long, profilePub: String?, preview: JSONObject): Event {
        val body = JSONObject(preview.toString()).put("v", 3).put("type", "invite").put("x", expiry)
            .put("r", JSONArray(RELAYS)).put("profile", profilePub ?: JSONObject.NULL)
        return gift(identity, pub(keys(Derive.inviteKey(secret))), RUMOR_INVITE, body, expiry)
    }

    data class OpenedInvite(val iPub: String, val token: String, val expiry: Long, val relays: List<String>,
                            val profile: String?, val name: String?)

    /** Pick the wrap that opens with the link's secret AND whose seal signer matches the fingerprint. */
    fun openInvite(link: Derive.Link, wraps: List<JSONObject>): Result<OpenedInvite> {
        val k = keys(Derive.inviteKey(link.secret))
        val reasons = mutableListOf<String>()
        for (w in wraps) {
            val u = unwrap(k, w) ?: run { reasons += "doesn't open with this link"; null } ?: continue
            when {
                u.kind != RUMOR_INVITE || u.body.optString("type") != "invite" -> reasons += "not an invite"
                !u.sender.startsWith(link.fp) -> reasons += "sealed by ${u.sender.take(16)}…, link expects ${link.fp}…"
                u.body.getLong("x") < now() -> reasons += "expired"
                else -> return Result.success(OpenedInvite(u.sender, Derive.b64u(link.secret), u.body.getLong("x"),
                    strings(u.body.optJSONArray("r")), u.body.optString("profile").takeIf { it.length == 64 },
                    u.body.optString("name").takeIf { it.isNotBlank() }))
            }
        }
        return Result.failure(IllegalStateException(reasons.distinct().joinToString("; ").ifBlank { "nothing on the relays" }))
    }

    /** PROTOCOL-v2 match code: `int(sha256(F.pub_hex + token)) mod 10000`. */
    fun matchCode(fPub: String, token: String): String =
        java.math.BigInteger(1, java.security.MessageDigest.getInstance("SHA-256").digest((fPub + token).toByteArray()))
            .mod(java.math.BigInteger.valueOf(10000)).toString().padStart(4, '0')

    fun strings(a: JSONArray?) = if (a == null) emptyList() else List(a.length()) { a.getString(it) }

    fun authEvent(k: Keys, url: String, challenge: String): Event =
        EventBuilder(Kind(22242u), "").tags(listOf(tag("relay", url), tag("challenge", challenge))).finalize(k)
}

/**
 * Minimal relay I/O: one short-lived WebSocket per call (publish → OK; query → events until EOSE), answering
 * NIP-42 AUTH with [auth] (some relays only serve kind 1059 to the p-tagged key).
 */
object Relays {
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    suspend fun publish(url: String, ev: Event, auth: Keys? = null, timeoutMs: Long = 9000): Pair<Boolean, String> {
        val id = ev.id().toHex()
        val evJson = ev.asJson()
        val done = CompletableDeferred<Pair<Boolean, String>>()
        val ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("""["EVENT",$evJson]""") }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val m = runCatching { JSONArray(text) }.getOrNull() ?: return
                when (m.optString(0)) {
                    "AUTH" -> if (auth != null) webSocket.send("""["AUTH",${Nostr.authEvent(auth, url, m.getString(1)).asJson()}]""")
                    "OK" -> if (m.optString(1) == id) {
                        val msg = m.optString(3)
                        if (!m.optBoolean(2) && msg.startsWith("auth-required") && auth != null) webSocket.send("""["EVENT",$evJson]""")
                        else done.complete(m.optBoolean(2) to msg)
                    }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                done.complete(false to (t.javaClass.simpleName + ": " + (t.message ?: "")).take(80))
            }
        })
        val r = withTimeoutOrNull(timeoutMs) { done.await() } ?: (false to "timeout")
        ws.close(1000, null)
        return r
    }

    suspend fun publishAll(ev: Event, relays: List<String> = Nostr.RELAYS, auth: Keys? = null): Map<String, Pair<Boolean, String>> =
        coroutineScope { relays.map { u -> async { u to publish(u, ev, auth) } }.awaitAll().toMap() }

    suspend fun query(url: String, filter: JSONObject, auth: Keys? = null, timeoutMs: Long = 9000): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        val done = CompletableDeferred<Unit>()
        val sid = "q" + Derive.hex(Nostr.random(4))
        val req = """["REQ","$sid",$filter]"""
        val ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            var authed = false
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(req) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val m = runCatching { JSONArray(text) }.getOrNull() ?: return
                when (m.optString(0)) {
                    "AUTH" -> if (auth != null && !authed) {
                        authed = true
                        webSocket.send("""["AUTH",${Nostr.authEvent(auth, url, m.getString(1)).asJson()}]""")
                    }
                    "OK" -> if (authed) webSocket.send(req)              // our AUTH was accepted → ask again
                    "EVENT" -> if (m.optString(1) == sid) synchronized(out) { out += m.getJSONObject(2) }
                    "EOSE" -> if (m.optString(1) == sid) done.complete(Unit)
                    "CLOSED" -> if (m.optString(1) == sid && !(auth != null && m.optString(2).startsWith("auth-required"))) done.complete(Unit)
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { done.complete(Unit) }
        })
        withTimeoutOrNull(timeoutMs) { done.await() }
        ws.close(1000, null)
        return synchronized(out) { out.toList() }
    }

    /** Union over relays, de-duplicated by id; [via] records which relay served each event. */
    suspend fun queryAll(filter: JSONObject, relays: List<String> = Nostr.RELAYS, auth: Keys? = null): List<Pair<JSONObject, String>> =
        coroutineScope {
            relays.map { u -> async { query(u, filter, auth).map { it to u } } }.awaitAll().flatten()
                .distinctBy { it.first.getString("id") }
        }

    suspend fun latestProfile(pubHex: String): Nostr.Profile? =
        queryAll(JSONObject().put("kinds", JSONArray().put(0)).put("authors", JSONArray().put(pubHex)))
            .maxByOrNull { it.first.getLong("created_at") }?.let { Nostr.parseProfile(it.first) }
}
