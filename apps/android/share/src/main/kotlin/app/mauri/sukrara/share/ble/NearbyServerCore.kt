// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.ble

import app.mauri.sukrara.protocol.NostrCrypto
import app.mauri.sukrara.protocol.PublicKeyHex
import app.mauri.sukrara.protocol.SignedEvent
import app.mauri.sukrara.protocol.Wire
import app.mauri.sukrara.protocol.ble.BleFraming
import app.mauri.sukrara.protocol.ble.BleMessages
import app.mauri.sukrara.protocol.ble.HelloProof
import app.mauri.sukrara.protocol.ble.RateLimit
import app.mauri.sukrara.protocol.ble.Reassembler
import kotlinx.serialization.json.JsonObject

/**
 * The wearer's side of the Bluetooth share link (ogs-ble B§5–B§8) without android.bluetooth: per-central framing,
 * HELLO → INFO + backlog, the push of every published event, the identity proof that names a link, and the abuse
 * limits. The GATT server adapter turns [Effect]s into GATT calls. Not thread-safe: one instance, used from the
 * adapter's serial executor. Peers are keyed by the central's address.
 */
class NearbyServerCore(
    private val crypto: NostrCrypto,
    private val host: Host,
    private val nowMs: () -> Long,
) {
    /** What only the sharing engine knows, read at the moment it's needed. */
    interface Host {
        /** The stream id (null without an identity: HELLOs then go unanswered). */
        fun sid(): String?

        /** Active followers' keys: an identity proof can name only one of them. */
        fun followers(): Collection<PublicKeyHex>
    }

    sealed interface Effect {
        class Notify(val peer: String, val frame: ByteArray) : Effect

        data class Disconnect(val peer: String) : Effect

        /** An EVENT a central wrote; trusted only by its signatures (the engine checks it like a relay event). */
        data class Incoming(val event: SignedEvent, val peer: String) : Effect

        /** Call [helloDue] for [peer] after [delayMs] (a HELLO that came too soon after the last backlog). */
        data class HelloLater(val peer: String, val delayMs: Long) : Effect
    }

    /** A connected follower device whose HELLO proof named an active follower ([device] = `phone` / `mac`). */
    data class Named(val follower: String, val device: String, val sinceMs: Long)

    private inner class Peer(val sinceMs: Long) {
        var mtu = DEFAULT_MTU
        var subscribed = false
        val queue = ArrayDeque<ByteArray>()

        /** INFO, live and alert pushes: ahead of a long backlog (frames of different messages may interleave, B§4.3). */
        val urgent = ArrayDeque<ByteArray>()
        var inFlight = false
        val rx = Reassembler(nowMs)
        val limit = RateLimit(nowMs = nowMs)
        var backlogAt = Long.MIN_VALUE / 2
        var helloPending: String? = null
        var follower: String? = null
        var device: String? = null
    }

    private val peers = LinkedHashMap<String, Peer>()
    private var msgId = 0
    private var roster: SignedEvent? = null
    private var live: SignedEvent? = null
    private val others = LinkedHashMap<String, SignedEvent>()
    private val treatments = LinkedHashMap<String, SignedEvent>()
    private val wraps = ArrayDeque<SignedEvent>()
    private val provedNonces = LinkedHashSet<String>()

    /** Subscribed links named by their identity proof, oldest first (Settings › Sharing › Nearby devices). */
    fun named(): List<Named> =
        peers.values
            .filter { it.subscribed && it.follower != null }
            .sortedBy { it.sinceMs }
            .map { Named(it.follower!!, it.device ?: HelloProof.DEVICES.first(), it.sinceMs) }

    fun connected(peer: String, mtu: Int = DEFAULT_MTU): List<Effect> {
        if (peer in peers) return emptyList()
        if (peers.size >= MAX_CENTRALS) return listOf(Effect.Disconnect(peer))
        peers[peer] = Peer(nowMs()).also { it.mtu = mtu }
        return emptyList()
    }

    fun disconnected(peer: String) {
        peers.remove(peer)
    }

    fun mtu(peer: String, mtu: Int) {
        peers[peer]?.mtu = mtu
    }

    /** The CCCD write on TX: notifications on or off. */
    fun subscribed(peer: String, on: Boolean): List<Effect> {
        val p = peers[peer] ?: return emptyList()
        p.subscribed = on
        if (!on) {
            p.queue.clear()
            p.urgent.clear()
            p.inFlight = false
        }
        return if (on) pump(peer, p) else emptyList()
    }

    /**
     * A write on RX. First = whether to answer it with success (checked before answering, B§8): a malformed frame or
     * one over the byte budget gets an error and the central is dropped.
     */
    fun write(
        peer: String,
        value: ByteArray?,
        toRx: Boolean,
        prepared: Boolean,
        offset: Int,
        mtu: Int = DEFAULT_MTU,
    ): Pair<Boolean, List<Effect>> {
        val p =
            peers[peer] ?: if (peers.size < MAX_CENTRALS) {
                Peer(nowMs()).also {
                    it.mtu = mtu
                    peers[peer] = it
                }
            } else {
                null
            }
        val refused =
            p == null || !toRx || value == null || prepared || offset != 0 ||
                BleFraming.header(value) == null || !p.limit.frame(value.size)
        if (refused) return false to listOf(Effect.Disconnect(peer))
        val message = p!!.rx.add(value!!) ?: return true to emptyList()
        if (!p.limit.message()) return true to emptyList()
        val m = BleMessages.parse(message) ?: return true to emptyList()
        return true to
            when (m.type) {
                "HELLO" -> {
                    hello(peer, p, m.body as? JsonObject)
                }

                "EVENT" -> {
                    listOfNotNull(
                        (m.body as? JsonObject)?.let { SignedEvent.parse(it.toString()) }?.let { Effect.Incoming(it, peer) },
                    )
                }

                else -> {
                    emptyList()
                }
            }
    }

    /** The stack sent the notification in flight (`onNotificationSent`): the next one. */
    fun notificationSent(peer: String): List<Effect> {
        val p = peers[peer] ?: return emptyList()
        p.inFlight = false
        return pump(peer, p)
    }

    /**
     * The stack refused a notification: [busy] = try the same frame again soon; otherwise the link is dropped (the
     * follower connects again and its HELLO brings the backlog it missed).
     */
    fun notifyFailed(peer: String, frame: ByteArray, busy: Boolean): List<Effect> {
        val p = peers[peer] ?: return emptyList()
        p.inFlight = false
        if (busy) {
            p.urgent.addFirst(frame)
            return emptyList()
        }
        p.queue.clear()
        p.urgent.clear()
        return listOf(Effect.Disconnect(peer))
    }

    /** Retry after [notifyFailed] with busy. */
    fun retry(peer: String): List<Effect> = peers[peer]?.let { pump(peer, it) }.orEmpty()

    /** A [Effect.HelloLater] came due. */
    fun helloDue(peer: String): List<Effect> {
        val p = peers[peer] ?: return emptyList()
        val nonce = p.helloPending ?: return emptyList()
        p.helloPending = null
        return backlog(peer, p, nonce)
    }

    /** Every event the wearer publishes (never the owner backup or an invite wrap: the engine keeps those off). */
    fun publish(event: SignedEvent): List<Effect> {
        remember(event)
        return broadcast(event, except = null)
    }

    /** An event a central sent that the engine accepted (a writer's entry): to the other centrals. */
    fun forward(event: SignedEvent, except: String?): List<Effect> = broadcast(event, except)

    /** "Forget": the links [follower]'s proof named lose the name and are dropped. */
    fun forget(follower: String): List<Effect> =
        peers.entries.filter { it.value.follower == follower }.map { (id, p) ->
            p.follower = null
            p.device = null
            Effect.Disconnect(id)
        }

    /** Bluetooth went off / the server closed: every central is gone. */
    fun reset() {
        peers.clear()
    }

    private fun hello(peer: String, p: Peer, body: JsonObject?): List<Effect> {
        val parsed = body?.let(BleMessages::parseHello)
        val nonce = parsed?.nonce ?: NearbyLink.nonce()
        parsed?.id?.let { identify(p, nonce, it) }
        val wait = p.backlogAt + BACKLOG_GAP_MS - nowMs()
        if (wait <= 0) return backlog(peer, p, nonce)
        val first = p.helloPending == null
        p.helloPending = nonce
        return if (first) listOf(Effect.HelloLater(peer, wait)) else emptyList()
    }

    /** B§6 "Who's connected": a proof for a nonce not accepted before that names an active follower names the link. */
    private fun identify(p: Peer, nonce: String, sig: String) {
        val sid = host.sid() ?: return
        if (nonce in provedNonces) return
        val (follower, device) =
            HelloProof.identify(crypto, sig, sid, nonce, host.followers()) ?: run {
                // a proof that no longer names an active follower: the link loses its name
                p.follower = null
                p.device = null
                return
            }
        provedNonces += nonce
        while (provedNonces.size > PROVED_NONCES) provedNonces.remove(provedNonces.first())
        p.follower = follower.hex
        p.device = device
    }

    private fun backlog(peer: String, p: Peer, nonce: String): List<Effect> {
        val sid = host.sid() ?: return emptyList()
        p.backlogAt = nowMs()
        val now = nowMs() / 1000
        // expired events never go out again
        others.entries.removeIf { it.value.isExpired(now) }
        treatments.entries.removeIf { it.value.isExpired(now) }
        wraps.removeAll { it.isExpired(now) }
        if (roster?.isExpired(now) == true) roster = null
        if (live?.isExpired(now) == true) live = null
        enqueue(p, BleMessages.info(sid, nonce, null, now), urgent = true)
        val ordered = listOfNotNull(roster) + others.values + treatments.values + listOfNotNull(live) + wraps
        ordered.forEach { enqueue(p, BleMessages.event(it)) }
        return pump(peer, p)
    }

    /** The backlog slot an event takes (B§6): roster, treatments, live and wraps apart, the rest by `d` / kind. */
    private fun remember(ev: SignedEvent) {
        val sid = host.sid()
        when (ev.kind) {
            Wire.KIND_LIVE -> {
                live = ev
            }

            Wire.KIND_ALERT -> {
                put(others, "alert", ev, MAX_OTHERS)
            }

            Wire.KIND_WRAP -> {
                wraps.addLast(ev)
                while (wraps.size > MAX_WRAPS) wraps.removeFirst()
            }

            Wire.KIND_DATA -> {
                val d = ev.tag("d") ?: return
                when {
                    sid != null && d == Wire.rosterD(sid) -> roster = ev
                    sid != null && ev.tag("c") == Wire.treatmentsC(sid) -> put(treatments, d, ev, MAX_TREATMENTS)
                    else -> put(others, d, ev, MAX_OTHERS)
                }
            }
        }
    }

    private fun put(map: LinkedHashMap<String, SignedEvent>, key: String, ev: SignedEvent, max: Int) {
        map.remove(key)
        map[key] = ev
        while (map.size > max) map.remove(map.keys.first())
    }

    private fun broadcast(event: SignedEvent, except: String?): List<Effect> {
        val payload = BleMessages.event(event)
        return peers.entries
            .filter { it.value.subscribed && it.key != except }
            .flatMap { (id, p) ->
                enqueue(p, payload, urgent = event.kind == Wire.KIND_LIVE || event.kind == Wire.KIND_ALERT)
                pump(id, p)
            }
    }

    private fun enqueue(p: Peer, payload: ByteArray, urgent: Boolean = false) {
        msgId = (msgId + 1) and 0xFFFF
        BleFraming.frames(msgId, payload, p.mtu).forEach((if (urgent) p.urgent else p.queue)::addLast)
    }

    private fun pump(peer: String, p: Peer): List<Effect> {
        if (!p.subscribed || p.inFlight) return emptyList()
        val frame = p.urgent.removeFirstOrNull() ?: p.queue.removeFirstOrNull() ?: return emptyList()
        p.inFlight = true
        return listOf(Effect.Notify(peer, frame))
    }

    companion object {
        const val MAX_CENTRALS = 8
        const val BACKLOG_GAP_MS = 5_000L
        const val DEFAULT_MTU = 23
        private const val MAX_OTHERS = 40
        private const val MAX_TREATMENTS = 200
        private const val MAX_WRAPS = 10
        private const val PROVED_NONCES = 256
    }
}
