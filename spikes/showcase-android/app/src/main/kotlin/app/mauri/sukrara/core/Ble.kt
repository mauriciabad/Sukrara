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
 *  - `["HELLO", {"v":2}]`      follower → owner: "send me INFO + what you have"
 *  - `["INFO", {...}]`         owner → follower: `{"v":2,"i":<I hex>,"relays":[…],"pair":{"t":token,"x":expiry}|null}`
 *  - `["EVENT", <nostr event>]` both ways: live, chunks, roster, gift wraps, treatments — verified by the engine
 *
 * BLE pairing/bonding is NOT used: anyone nearby may connect. Trust comes only from the events (signatures,
 * roster, NIP-44 to G_n, seal signer), exactly as for relays.
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

    /** Per-peer reassembly; returns the full message when its last missing frame arrives. */
    class Reassembler {
        private val parts = HashMap<Int, Array<ByteArray?>>()

        fun add(frame: ByteArray): JSONArray? {
            if (frame.size < HEADER) return null
            val b = ByteBuffer.wrap(frame)
            val id = b.short.toInt() and 0xFFFF
            val idx = b.short.toInt() and 0xFFFF
            val count = b.short.toInt() and 0xFFFF
            if (count == 0 || idx >= count) return null
            var arr = parts[id]
            if (arr == null || arr.size != count || idx == 0) { arr = arrayOfNulls(count); parts[id] = arr }
            arr[idx] = frame.copyOfRange(HEADER, frame.size)
            if (arr.any { it == null }) return null
            parts.remove(id)
            val all = arr.fold(ByteArray(0)) { acc, p -> acc + p!! }
            if (parts.size > 16) parts.clear()
            return runCatching { JSONArray(String(all)) }.getOrNull()
        }
    }

    fun info(iPub: String, relays: List<String>, token: String?, expiry: Long): JSONObject = JSONObject()
        .put("v", 2).put("i", iPub).put("relays", JSONArray(relays))
        .put("pair", if (token != null && expiry > Proto.nowS()) JSONObject().put("t", token).put("x", expiry) else JSONObject.NULL)
}
