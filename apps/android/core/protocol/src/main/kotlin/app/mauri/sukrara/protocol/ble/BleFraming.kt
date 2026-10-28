// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.protocol.ble

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.UUID

/**
 * The GATT share link (spec ogs-ble B§2–B§4): the wearer's phone is the peripheral, followers are centrals. No BLE
 * bonding: trust comes only from the events, exactly as over relays.
 */
object BleUuids {
    val SERVICE: UUID = UUID.fromString("7c6a0001-5c3e-4f0a-9a57-c95e0b1e0007")

    /** Write with response: follower → wearer frames. */
    val RX: UUID = UUID.fromString("7c6a0002-5c3e-4f0a-9a57-c95e0b1e0007")

    /** Notify: wearer → follower frames. */
    val TX: UUID = UUID.fromString("7c6a0003-5c3e-4f0a-9a57-c95e0b1e0007")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

/** Frame = `[msgId u16][idx u16][count u16]` big-endian + up to `min(mtu − 3, 512) − 6` payload bytes. */
object BleFraming {
    const val HEADER = 6

    /** The ATT attribute limit: no frame (and no long write a central queues) is longer. */
    const val MAX_ATTRIBUTE = 512
    private const val MIN_PAYLOAD = 14

    /** Reassembly limits (B§4.3). */
    const val MAX_MESSAGE = 256 * 1024
    const val MAX_BUFFERS = 16
    const val IDLE_MS = 30_000L

    data class Header(val msgId: Int, val idx: Int, val count: Int)

    /** Splits [payload] for an ATT [mtu]; an attribute value is at most 512 bytes even with MTU 517. */
    fun frames(msgId: Int, payload: ByteArray, mtu: Int): List<ByteArray> {
        val per = (minOf(mtu - 3, MAX_ATTRIBUTE) - HEADER).coerceAtLeast(MIN_PAYLOAD)
        val count = maxOf(1, (payload.size + per - 1) / per)
        require(count <= 0xFFFF) { "message too large" }
        return (0 until count).map { idx ->
            val from = idx * per
            val to = minOf(payload.size, from + per)
            ByteBuffer
                .allocate(HEADER + (to - from))
                .putShort(msgId.toShort())
                .putShort(idx.toShort())
                .putShort(count.toShort())
                .put(payload, from, to - from)
                .array()
        }
    }

    /** null: not a valid frame (shorter than the header, count 0, idx ≥ count). */
    fun header(frame: ByteArray): Header? {
        if (frame.size < HEADER) return null
        val b = ByteBuffer.wrap(frame)
        val h = Header(b.short.toInt() and 0xFFFF, b.short.toInt() and 0xFFFF, b.short.toInt() and 0xFFFF)
        return if (h.count == 0 || h.idx >= h.count) null else h
    }
}

/**
 * Per-peer reassembly; [add] returns the whole message when its last missing frame arrives. A buffer idle for
 * [BleFraming.IDLE_MS] is dropped, a message over [BleFraming.MAX_MESSAGE] bytes is dropped (its later frames
 * swallowed), at most [BleFraming.MAX_BUFFERS] are open (the oldest goes). [nowMs] injected for tests. Not thread-safe:
 * one instance per peer, used from the GATT callback thread.
 */
class Reassembler(private val nowMs: () -> Long) {
    private class Buffer(val count: Int, var touched: Long) {
        val parts = HashMap<Int, ByteArray>()
        var size = 0
        var dead = false
    }

    private val buffers = LinkedHashMap<Int, Buffer>()

    /** Messages dropped for a limit (size or idle), for logs and tests. */
    var dropped = 0
        private set

    fun add(frame: ByteArray): ByteArray? {
        val h = BleFraming.header(frame) ?: return null
        val t = nowMs()
        buffers.entries.removeIf { (_, b) -> (t - b.touched > BleFraming.IDLE_MS).also { if (it && !b.dead) dropped++ } }
        var buf = buffers[h.msgId]
        if (buf == null || buf.count != h.count || h.idx == 0) {
            buffers.remove(h.msgId)
            if (buffers.size >= BleFraming.MAX_BUFFERS) buffers.remove(buffers.keys.first())
            buf = Buffer(h.count, t)
            buffers[h.msgId] = buf
        }
        buf.touched = t
        if (buf.dead) return null
        val part = frame.copyOfRange(BleFraming.HEADER, frame.size)
        buf.size += part.size - (buf.parts.put(h.idx, part)?.size ?: 0)
        if (buf.size > BleFraming.MAX_MESSAGE) {
            buf.dead = true
            buf.parts.clear()
            dropped++
            return null
        }
        if (buf.parts.size < h.count) return null
        buffers.remove(h.msgId)
        return ByteArrayOutputStream(buf.size).apply { for (i in 0 until h.count) write(buf.parts.getValue(i)) }.toByteArray()
    }
}

/**
 * Per-central limits on the wearer (B§8): at most [messages] messages and [bytes] bytes per [windowMs], token buckets
 * full at start. [nowMs] injected for tests.
 */
class RateLimit(
    private val messages: Int = 30,
    private val bytes: Int = 256 * 1024,
    private val windowMs: Long = 60_000,
    private val nowMs: () -> Long,
) {
    private var messageTokens = messages.toDouble()
    private var byteTokens = bytes.toDouble()
    private var at = nowMs()

    private fun refill() {
        val t = nowMs()
        val f = (t - at).toDouble() / windowMs
        at = t
        messageTokens = minOf(messages.toDouble(), messageTokens + f * messages)
        byteTokens = minOf(bytes.toDouble(), byteTokens + f * bytes)
    }

    /** A frame of [size] bytes arrived: false = over the byte budget, drop it. */
    fun frame(size: Int): Boolean {
        refill()
        if (byteTokens < size) return false
        byteTokens -= size
        return true
    }

    /** A whole message arrived: false = over the message budget, drop it. */
    fun message(): Boolean {
        refill()
        if (messageTokens < 1) return false
        messageTokens -= 1
        return true
    }
}
