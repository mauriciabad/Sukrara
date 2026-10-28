// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.ble

import app.mauri.sukrara.protocol.KeyPair
import app.mauri.sukrara.protocol.NostrCrypto
import app.mauri.sukrara.protocol.PublicKeyHex
import app.mauri.sukrara.protocol.SignedEvent
import app.mauri.sukrara.protocol.ble.BleFraming
import app.mauri.sukrara.protocol.ble.BleMessages
import app.mauri.sukrara.protocol.ble.HelloProof
import app.mauri.sukrara.protocol.ble.Reassembler
import kotlinx.serialization.json.JsonObject
import java.security.SecureRandom

/**
 * The follower's protocol over one Bluetooth connection to a wearer's phone, without android.bluetooth (tests drive it
 * with fake frames): HELLO with a fresh nonce on connect (and when the link goes silent), INFO checked against our
 * last 4 nonces, EVENTs handed out, frames written one at a time (the next after the write response). Nothing but
 * HELLO is written to a phone before its INFO proved it's our wearer's ([verified]) — ogs-ble B§6 "Which owner".
 * Not thread-safe: one instance per connection, used from the central's serial executor.
 */
class NearbyLink(
    @Volatile var identity: Identity,
    private val crypto: NostrCrypto,
    private val nowMs: () -> Long,
) {
    /** Who we are as far as the follower record knows (null before the first key). */
    data class Identity(val identity: PublicKeyHex? = null, val sid: String? = null, val follower: KeyPair? = null) {
        override fun toString() = "Identity(sid=${sid != null})"
    }

    sealed interface Effect {
        /** Write this frame to RX (with response). */
        class Write(val frame: ByteArray) : Effect

        data class Event(val event: SignedEvent) : Effect

        data class Info(val owner: BleMessages.Owner) : Effect

        /** The message sent with [token] reached the phone (true) or can't be sent (false). */
        data class Sent(val token: Int, val ok: Boolean) : Effect
    }

    private class Pending(val frame: ByteArray, val token: Int?)

    private var mtu: Int? = null
    private var reassembler = Reassembler(nowMs)
    private val outbox = ArrayDeque<Pending>()
    private var inFlight: Pending? = null
    private var msgId = SecureRandom().nextInt(0x10000)
    private val sentNonces = ArrayDeque<String>()

    /** Our last HELLO nonces: an INFO must prove `sid` over one of them. */
    val nonces: List<String> get() = sentNonces.toList()

    /** The phone proved it's our wearer's. */
    var verified = false
        private set

    val isConnected: Boolean get() = mtu != null

    /** Notifications are on: start with HELLO. [mtu] = the negotiated ATT MTU (23 until negotiated). */
    fun connected(mtu: Int, nonce: String): List<Effect> {
        this.mtu = mtu
        return hello(nonce)
    }

    /** HELLO with [nonce] (the last 4 are kept) and, once we know `sid`, the identity proof (B§5, `dev` = phone). */
    fun hello(nonce: String): List<Effect> {
        sentNonces.addLast(nonce)
        while (sentNonces.size > NONCES) sentNonces.removeFirst()
        val id = identity
        val sid = id.sid
        val follower = id.follower
        val proof =
            if (sid != null && follower != null) {
                runCatching { HelloProof.sign(crypto, follower, sid, nonce, DEVICE) }.getOrNull()
            } else {
                null
            }
        return enqueue(BleMessages.hello(nonce, proof), token = null)
    }

    /** An EVENT for the wearer's phone; refused (`Sent false`) until the phone proved it's the wearer's. */
    fun send(event: SignedEvent, token: Int): List<Effect> {
        if (!isConnected || !verified) return listOf(Effect.Sent(token, false))
        return enqueue(BleMessages.event(event), token)
    }

    /** A notification on TX. */
    fun received(frame: ByteArray): List<Effect> {
        val whole = reassembler.add(frame) ?: return emptyList()
        val message = BleMessages.parse(whole) ?: return emptyList()
        return when (message.type) {
            "EVENT" -> {
                val ev = (message.body as? JsonObject)?.let { SignedEvent.parse(it.toString()) }
                listOfNotNull(ev?.let { Effect.Event(it) })
            }

            "INFO" -> {
                val body = message.body as? JsonObject ?: return emptyList()
                val owner = BleMessages.owner(body, identity.identity, identity.sid, sentNonces)
                verified = owner == BleMessages.Owner.Ours
                listOf(Effect.Info(owner))
            }

            else -> {
                emptyList()
            }
        }
    }

    /** The write response for the frame in flight. */
    fun wrote(ok: Boolean): List<Effect> {
        val done = inFlight ?: return emptyList()
        inFlight = null
        val out = mutableListOf<Effect>()
        done.token?.let { out += Effect.Sent(it, ok) }
        // a failed frame breaks its message and what follows: fail everything (the central drops the link)
        return out + if (ok) pump() else failAll()
    }

    /** The connection is gone: everything queued fails. */
    fun disconnected(): List<Effect> {
        val failed = failAll()
        mtu = null
        verified = false
        reassembler = Reassembler(nowMs)
        return failed
    }

    private fun failAll(): List<Effect> {
        val tokens = (listOfNotNull(inFlight) + outbox).mapNotNull { it.token }
        inFlight = null
        outbox.clear()
        return tokens.map { Effect.Sent(it, false) }
    }

    private fun enqueue(payload: ByteArray, token: Int?): List<Effect> {
        val m = mtu ?: return listOfNotNull(token?.let { Effect.Sent(it, false) })
        msgId = (msgId + 1) and 0xFFFF
        val frames = BleFraming.frames(msgId, payload, m)
        frames.forEachIndexed { i, f -> outbox.addLast(Pending(f, if (i == frames.lastIndex) token else null)) }
        return pump()
    }

    private fun pump(): List<Effect> {
        if (inFlight != null) return emptyList()
        val next = outbox.removeFirstOrNull() ?: return emptyList()
        inFlight = next
        return listOf(Effect.Write(next.frame))
    }

    companion object {
        const val NONCES = 4
        const val DEVICE = "phone"
        private val random = SecureRandom()

        /** 16 fresh random bytes, lowercase hex (B§5). */
        fun nonce(): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    }
}
