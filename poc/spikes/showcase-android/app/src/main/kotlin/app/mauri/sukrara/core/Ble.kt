// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core

import java.nio.ByteBuffer
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Investigation 07: Bluetooth LE as a second [Transport] for the SAME signed + encrypted v2 events.
 *
 * GATT (owner = peripheral / GATT server, follower = central):
 *  - service  [SERVICE]
 *  - [RX] write (with response): follower → owner frames
 *  - [TX] notify:                owner → follower frames (CCCD 0x2902)
 *
 * Frame = `[msgId u16][idx u16][count u16]` (big endian) + up to `mtu - 3 - 6` payload bytes.
 * A message is the UTF-8 of a JSON array, Nostr-wire style:
 *  - `["HELLO", {"v":2,"nonce":<32 hex>,"id":<128 hex>}]` follower → owner: "send me INFO + what you have"; `nonce` is
 *    fresh per HELLO; `id` (once the follower knows `sid`) proves which follower and device is connecting ([idProof])
 *  - `["INFO", {...}]`         owner → follower: `{"v":2,"nonce":<the HELLO's>,"m":H("cgm2:ble:"+sid+":"+nonce)}`, plus
 *    `"i":<I hex>,"relays":[…],"pair":{"t":token,"x":expiry,"profile":{…}}` only while "Pair nearby" is open (P12)
 *  - `["EVENT", <nostr event>]` both ways: live, chunks, roster, gift wraps, treatments — verified by the engine
 *
 * BLE pairing/bonding is NOT used: anyone nearby may connect. Trust comes only from the events (signatures,
 * roster, NIP-44 to G_n, seal signer), exactly as for relays. A stranger learns neither the phone's name (no scan
 * response) nor `I` (blinded INFO); a follower writes only to an owner whose INFO proves it holds the stream's `sid`.
 */
object Ble {
    val SERVICE: UUID = UUID.fromString("7c6a0001-5c3e-4f0a-9a57-c95e0b1e0007")
    val RX: UUID = UUID.fromString("7c6a0002-5c3e-4f0a-9a57-c95e0b1e0007")
    val TX: UUID = UUID.fromString("7c6a0003-5c3e-4f0a-9a57-c95e0b1e0007")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    const val HEADER = 6

    fun msg(type: String, body: Any): ByteArray = JSONArray().put(type).put(body).toString().toByteArray()

    fun event(eventJson: String): ByteArray = """["EVENT",$eventJson]""".toByteArray()

    /** Split [payload] into frames of at most [mtu] - 3 bytes. */
    fun frames(msgId: Int, payload: ByteArray, mtu: Int): List<ByteArray> {
        // an attribute value is at most 512 bytes (Android rejects longer notifications even with MTU 517)
        val per = (minOf(mtu - 3, 512) - HEADER).coerceAtLeast(14)
        val count = maxOf(1, (payload.size + per - 1) / per)
        require(count <= 0xFFFF) { "message too large" }
        return (0 until count).map { idx ->
            val from = idx * per
            val to = minOf(payload.size, from + per)
            ByteBuffer.allocate(HEADER + (to - from))
                .putShort(msgId.toShort()).putShort(idx.toShort()).putShort(count.toShort())
                .put(payload, from, to - from).array()
        }
    }

    /** Reassembly limits (spec/ogs-ble.md B§4.3, P13). */
    const val MAX_MESSAGE = 256 * 1024
    const val MAX_BUFFERS = 16
    const val IDLE_MS = 30_000L

    /**
     * Per-peer reassembly; returns the full message when its last missing frame arrives. A buffer idle for
     * [IDLE_MS] is dropped, a message over [MAX_MESSAGE] bytes is dropped (and its later frames ignored), and at most
     * [MAX_BUFFERS] are open at once (the oldest goes). [now] in ms, for tests.
     */
    class Reassembler(private val now: () -> Long = System::currentTimeMillis) {
        private class Buf(val count: Int, var touched: Long) {
            val parts = HashMap<Int, ByteArray>()
            var size = 0
            var dead = false   // over the cap: swallow the rest of this message
        }
        private val bufs = LinkedHashMap<Int, Buf>()
        /** Messages dropped for a limit (size or idle), for logs and tests. */
        var dropped = 0; private set

        /** null: not a valid frame (too short, idx ≥ count, count 0). Doesn't touch any buffer. */
        fun header(frame: ByteArray): Triple<Int, Int, Int>? {
            if (frame.size < HEADER) return null
            val b = ByteBuffer.wrap(frame)
            val id = b.short.toInt() and 0xFFFF
            val idx = b.short.toInt() and 0xFFFF
            val count = b.short.toInt() and 0xFFFF
            return if (count == 0 || idx >= count) null else Triple(id, idx, count)
        }

        fun add(frame: ByteArray): JSONArray? {
            val (id, idx, count) = header(frame) ?: return null
            val t = now()
            bufs.entries.removeIf { (_, v) -> (t - v.touched > IDLE_MS).also { if (it && !v.dead) dropped++ } }
            var buf = bufs[id]
            if (buf == null || buf.count != count || idx == 0) {
                bufs.remove(id)
                if (bufs.size >= MAX_BUFFERS) bufs.remove(bufs.keys.first())
                buf = Buf(count, t)
                bufs[id] = buf
            }
            buf.touched = t
            if (buf.dead) return null
            val part = frame.copyOfRange(HEADER, frame.size)
            buf.size += part.size - (buf.parts.put(idx, part)?.size ?: 0)
            if (buf.size > MAX_MESSAGE) { buf.dead = true; buf.parts.clear(); dropped++; return null }
            if (buf.parts.size < count) return null
            bufs.remove(id)
            val all = java.io.ByteArrayOutputStream(buf.size).apply { for (i in 0 until count) write(buf.parts[i]!!) }.toByteArray()
            return runCatching { JSONArray(String(all)) }.getOrNull()
        }
    }

    /**
     * Per-central limits on the owner (B§8, P13): at most [messages] messages and [bytes] bytes per [windowMs]
     * (token buckets, full at start). [now] in ms, for tests.
     */
    class RateLimit(private val messages: Int = 30, private val bytes: Int = 256 * 1024, private val windowMs: Long = 60_000,
                    private val now: () -> Long = System::currentTimeMillis) {
        private var msgTokens = messages.toDouble()
        private var byteTokens = bytes.toDouble()
        private var at = now()

        private fun refill() {
            val t = now()
            val f = (t - at).toDouble() / windowMs
            at = t
            msgTokens = minOf(messages.toDouble(), msgTokens + f * messages)
            byteTokens = minOf(bytes.toDouble(), byteTokens + f * bytes)
        }

        /** A frame of [size] bytes arrived: false = over the byte budget, drop it. */
        fun frame(size: Int): Boolean { refill(); if (byteTokens < size) return false; byteTokens -= size; return true }

        /** A whole message arrived: false = over the message budget, drop it. */
        fun message(): Boolean { refill(); if (msgTokens < 1) return false; msgTokens -= 1; return true }
    }

    /** A fresh 16-byte nonce, hex (HELLO). */
    fun newNonce(): String = Proto.hex(ByteArray(16).also { java.security.SecureRandom().nextBytes(it) })

    /** `m = H("cgm2:ble:" + sid + ":" + nonce)`: proves the owner holds the stream's `sid` without naming it (P12). */
    fun infoM(sid: String, nonce: String) = Proto.h("cgm2:ble:$sid:$nonce")

    /** spec/ogs-ble.md B§5: the identity proof's kind (never sent or published) and what a follower device can be. */
    const val ID_KIND = 21080
    val DEVICES = listOf("phone", "mac")

    /** B§5: the identity proof's content, H("cgm2:ble-id:" + sid + ":" + nonce + ":" + dev). */
    fun idContent(sid: String, nonce: String, dev: String) = Proto.h("cgm2:ble-id:$sid:$nonce:$dev")

    /** B§5: HELLO `id` = the follower key [f]'s BIP-340 signature of the identity proof (kind 21080, created_at 0, no tags). */
    fun idProof(f: org.nostrdevkit.sdk.Keys, sid: String, nonce: String, dev: String): String =
        JSONObject(Proto.signed(f, ID_KIND, idContent(sid, nonce, dev), emptyList(), 0).asJson()).getString("sig")

    /** The identity proof's event id (NIP-01 serialization; the content is hex, so nothing to escape): what F signs. */
    fun idEventId(fPub: String, sid: String, nonce: String, dev: String): ByteArray =
        Proto.sha256("""[0,"$fPub",0,$ID_KIND,[],"${idContent(sid, nonce, dev)}"]""".toByteArray())

    /**
     * B§6 "Who's connected": the (follower key, device) among [followers] whose identity proof [sig] signs for [nonce],
     * or null. Without `sid` the signature can't be tested against any key.
     */
    fun identify(sig: String, sid: String, nonce: String, followers: Collection<String>): Pair<String, String>? {
        if (sig.length != 128 || !sig.all { it in '0'..'9' || it in 'a'..'f' }) return null
        for (f in followers) for (dev in DEVICES) {
            val ok = runCatching { Schnorr.verify(idEventId(f, sid, nonce, dev), Proto.unhex(f), Proto.unhex(sig)) }.getOrDefault(false)
            if (ok) return f to dev
        }
        return null
    }

    /** The HELLO's nonce if it is one (1–64 hex chars), else null (the owner then makes one). */
    fun nonceOf(hello: JSONObject?): String? = hello?.optString("nonce")?.takeIf { it.length in 1..64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }

    /**
     * INFO (P12): `nonce` + `m` always; `i`, `relays` and `pair` (with the wearer's [profile]) only while "Pair nearby"
     * is open, like a v3 invite wrap. Without pairing a stranger learns nothing that links two sessions or the stream.
     */
    fun info(iPub: String, sid: String, nonce: String, relays: List<String>, token: String?, expiry: Long, profile: Profile,
             now: Long = Proto.nowS()): JSONObject {
        val o = JSONObject().put("v", 2).put("nonce", nonce).put("m", infoM(sid, nonce))
        if (token != null && expiry > now) {
            o.put("i", iPub).put("relays", JSONArray(relays))
                .put("pair", JSONObject().put("t", token).put("x", expiry).put("profile", profile.toJson()))
        }
        return o
    }

    enum class Owner { OURS, OTHER, UNKNOWN }

    /**
     * Is the phone that sent [info] the owner of our stream? [nonces] = the ones our recent HELLOs carried.
     * OURS: `m` matches our `sid` for one of our nonces, or `i` is our identity. OTHER: it names another identity, or we
     * know the `sid` and its proof doesn't match. UNKNOWN: we don't know the `sid` yet (request pending) and it shows no
     * `i` — receive, but don't write.
     */
    fun owner(info: JSONObject, iPub: String?, sid: String?, nonces: Collection<String>): Owner {
        val i = info.optString("i").ifEmpty { null }
        if (iPub != null && i != null) return if (i == iPub) Owner.OURS else Owner.OTHER
        if (sid == null) return Owner.UNKNOWN
        val n = info.optString("nonce")
        return if (n in nonces && info.optString("m") == infoM(sid, n)) Owner.OURS else Owner.OTHER
    }

    /** The NIP-40 expiration of an event JSON, or null. */
    fun expiration(eventJson: String): Long? = runCatching {
        val tags = JSONObject(eventJson).optJSONArray("tags") ?: return null
        (0 until tags.length()).map { tags.getJSONArray(it) }.firstOrNull { it.length() > 1 && it.optString(0) == "expiration" }
            ?.getString(1)?.toLong()
    }.getOrNull()

    /** The backlog never re-sends an event whose expiration has passed (P13). */
    fun expired(eventJson: String, now: Long = Proto.nowS()): Boolean = expiration(eventJson)?.let { it <= now } == true
}
