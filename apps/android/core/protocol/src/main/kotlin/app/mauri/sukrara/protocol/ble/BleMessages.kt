// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.protocol.ble

import app.mauri.sukrara.protocol.Hex
import app.mauri.sukrara.protocol.KeyPair
import app.mauri.sukrara.protocol.NostrCrypto
import app.mauri.sukrara.protocol.PublicKeyHex
import app.mauri.sukrara.protocol.SignedEvent
import app.mauri.sukrara.protocol.UnsignedEvent
import app.mauri.sukrara.protocol.Wire
import app.mauri.sukrara.protocol.WireJson
import app.mauri.sukrara.protocol.event.Profile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * BLE share messages: the UTF-8 of a Nostr-style JSON array (B§3).
 *  - `["HELLO", {"v":2,"nonce":<hex>,"id"?:<sig hex>}]` follower → wearer;
 *  - `["INFO", {"v":2,"nonce","m", "i"?, "relays"?, "pair"?}]` wearer → follower (`i`/`relays`/`pair` only while
 *    "Pair nearby" is open);
 *  - `["EVENT", <nostr event>]` both ways, verified by the engines like any relay event.
 */
object BleMessages {
    data class Message(val type: String, val body: JsonElement)

    data class Hello(val nonce: String, val id: String?)

    /** Pairing offer inside INFO: the invite token, its expiry and the wearer's profile. */
    data class Pairing(val identity: PublicKeyHex, val relays: List<String>, val token: String, val expiry: Long, val profile: Profile?)

    fun event(event: SignedEvent): ByteArray = "[\"EVENT\",${event.toJson()}]".toByteArray()

    fun hello(nonce: String, id: String?): ByteArray = message("HELLO", buildJsonObject {
        put("v", Wire.V)
        put("nonce", nonce)
        id?.let { put("id", it) }
    })

    /** INFO: `nonce` + `m` always; `i`, `relays`, `pair` only for an open, unexpired [pairing]. */
    fun info(sid: String, nonce: String, pairing: Pairing?, now: Long): ByteArray = message("INFO", buildJsonObject {
        put("v", Wire.V)
        put("nonce", nonce)
        put("m", infoM(sid, nonce))
        if (pairing != null && pairing.expiry > now) {
            put("i", pairing.identity.hex)
            put("relays", buildJsonArray { pairing.relays.forEach { add(JsonPrimitive(it)) } })
            put("pair", buildJsonObject {
                put("t", pairing.token)
                put("x", pairing.expiry)
                pairing.profile?.let { put("profile", it.toJson()) }
            })
        }
    })

    private fun message(type: String, body: JsonElement): ByteArray =
        buildJsonArray {
            add(JsonPrimitive(type))
            add(body)
        }.toString().toByteArray()

    /** null for anything that isn't `[type, body]`. */
    fun parse(bytes: ByteArray): Message? = runCatching {
        val arr = WireJson.parseToJsonElement(bytes.decodeToString()) as JsonArray
        Message((arr[0] as JsonPrimitive).content, arr[1])
    }.getOrNull()

    /** The HELLO's nonce (1–64 hex) and identity proof (128 hex) if well-formed. */
    fun parseHello(body: JsonElement): Hello? {
        val o = body as? JsonObject ?: return null
        val nonce = (o["nonce"] as? JsonPrimitive)?.content?.takeIf { Hex.isHex(it, 1, 64) } ?: return null
        val id = (o["id"] as? JsonPrimitive)?.content?.takeIf { Hex.isHex(it, 128, 128) }
        return Hello(nonce, id)
    }

    /** `m = H("cgm2:ble:" + sid + ":" + nonce)`: proves the wearer holds the stream's `sid` without naming it. */
    fun infoM(sid: String, nonce: String) = Wire.h("cgm2:ble:$sid:$nonce")

    enum class Owner { Ours, Other, Unknown }

    /**
     * Is the phone that sent [info] our wearer? [nonces] = those our recent HELLOs carried. Ours: `i` is our identity,
     * or `m` matches our `sid` for one of our nonces. Other: another identity, or a known `sid` that doesn't match.
     * Unknown: no `sid` yet (request pending) and no `i` — receive, but don't write.
     */
    fun owner(info: JsonObject, identity: PublicKeyHex?, sid: String?, nonces: Collection<String>): Owner {
        val i = (info["i"] as? JsonPrimitive)?.content?.ifEmpty { null }
        if (identity != null && i != null) return if (i == identity.hex) Owner.Ours else Owner.Other
        if (sid == null) return Owner.Unknown
        val n = (info["nonce"] as? JsonPrimitive)?.content
        val m = (info["m"] as? JsonPrimitive)?.content
        return if (n != null && n in nonces && m == infoM(sid, n)) Owner.Ours else Owner.Other
    }
}

/**
 * The HELLO identity proof (B§5/B§6): the follower key's BIP-340 signature of a kind-21080 event (created_at 0, no tags,
 * never sent) whose content is `H("cgm2:ble-id:" + sid + ":" + nonce + ":" + dev)`, `dev` = `phone` | `mac`.
 */
object HelloProof {
    const val KIND = 21080
    val DEVICES = listOf("phone", "mac")

    fun content(sid: String, nonce: String, dev: String) = Wire.h("cgm2:ble-id:$sid:$nonce:$dev")

    fun sign(crypto: NostrCrypto, follower: KeyPair, sid: String, nonce: String, dev: String): String =
        crypto.sign(follower, UnsignedEvent(KIND, content(sid, nonce, dev), emptyList(), 0)).sig

    /** The (follower key, device) among [followers] whose proof [sig] signs for [nonce], or null. */
    fun identify(crypto: NostrCrypto, sig: String, sid: String, nonce: String, followers: Collection<PublicKeyHex>): Pair<PublicKeyHex, String>? {
        if (!Hex.isHex(sig, 128, 128)) return null
        for (f in followers) {
            for (dev in DEVICES) {
                if (crypto.verify(proofEvent(f, content(sid, nonce, dev), sig))) return f to dev
            }
        }
        return null
    }

    /** The proof as a full event: NIP-01 id over `[0,pubkey,0,21080,[],content]` (content is hex: nothing to escape). */
    private fun proofEvent(f: PublicKeyHex, content: String, sig: String): SignedEvent {
        val id = Hex.encode(Hex.sha256("[0,\"${f.hex}\",0,$KIND,[],\"$content\"]".toByteArray()))
        return SignedEvent(id, f.hex, 0, KIND, emptyList(), content, sig)
    }
}
