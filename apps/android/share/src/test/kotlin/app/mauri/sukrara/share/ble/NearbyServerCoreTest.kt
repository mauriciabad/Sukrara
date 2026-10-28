// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.ble

import app.mauri.sukrara.protocol.PublicKeyHex
import app.mauri.sukrara.protocol.SignedEvent
import app.mauri.sukrara.protocol.Wire
import app.mauri.sukrara.protocol.ble.BleFraming
import app.mauri.sukrara.protocol.ble.BleMessages
import app.mauri.sukrara.protocol.ble.HelloProof
import app.mauri.sukrara.protocol.ble.Reassembler
import app.mauri.sukrara.share.OwnerFixtures.crypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wearer's side of the Bluetooth link (fake GATT: frames shuttled between [NearbyServerCore] and a follower's
 * [NearbyLink]): INFO + backlog, the identity proof, pushes ahead of the backlog, and the B§8 limits.
 */
class NearbyServerCoreTest {
    private var nowMs = 1_792_000_000_000L
    private val sid = "74767bf125c92e2efb705d29cb173f5bafda20bce46bf81e1a0a603e854c913a"
    private val identity = crypto.generate().public
    private val mom = crypto.generate()
    private var followers = listOf(mom.public)
    private val core =
        NearbyServerCore(
            crypto,
            object : NearbyServerCore.Host {
                override fun sid() = sid

                override fun followers(): Collection<PublicKeyHex> = followers
            },
        ) { nowMs }
    private val nowS get() = nowMs / 1000

    /** A follower device: its link, and the messages it received, in order. */
    private inner class Device(val id: String, follower: Boolean = true, val mtu: Int = 185) {
        val link = NearbyLink(NearbyLink.Identity(identity, sid.takeIf { follower }, mom.takeIf { follower }), crypto) { nowMs }
        private val rx = Reassembler { nowMs }
        val received = mutableListOf<BleMessages.Message>()
        val later = mutableListOf<NearbyServerCore.Effect.HelloLater>()
        val disconnected = mutableListOf<String>()

        fun connect(nonce: String = NearbyLink.nonce()) {
            core.connected(id)
            core.mtu(id, mtu)
            serve(core.subscribed(id, true))
            write(link.connected(mtu, nonce))
        }

        fun write(effects: List<NearbyLink.Effect>) {
            var pending = effects
            while (true) {
                val w = pending.filterIsInstance<NearbyLink.Effect.Write>().firstOrNull() ?: return
                val (ok, out) = core.write(id, w.frame, toRx = true, prepared = false, offset = 0)
                serve(out)
                pending = link.wrote(ok)
            }
        }

        /** The stack sends every notification at once. */
        fun serve(effects: List<NearbyServerCore.Effect>) {
            for (e in effects) {
                when (e) {
                    is NearbyServerCore.Effect.Notify -> {
                        rx.add(e.frame)?.let { received += BleMessages.parse(it)!! }
                        link.received(e.frame)
                        serve(core.notificationSent(e.peer))
                    }

                    is NearbyServerCore.Effect.HelloLater -> {
                        later += e
                    }

                    is NearbyServerCore.Effect.Disconnect -> {
                        disconnected += e.peer
                    }

                    is NearbyServerCore.Effect.Incoming -> {
                        Unit
                    }
                }
            }
        }

        fun events() = received.filter { it.type == "EVENT" }.map { SignedEvent.parse(it.body.toString())!! }
    }

    private fun event(kind: Int, tags: List<List<String>> = emptyList(), expires: Long = nowS + Wire.HOUR, n: Int = 0) =
        SignedEvent(
            id = "%064x".format(kind * 1000 + n + tags.hashCode().toLong().and(0xffff)),
            pubkey = identity.hex,
            createdAt = nowS,
            kind = kind,
            tags = tags + listOf(listOf("expiration", expires.toString())),
            content = "c".repeat(200),
            sig = "0".repeat(128),
        )

    private fun data(d: String, c: String? = null, expires: Long = nowS + Wire.DAY) =
        event(Wire.KIND_DATA, listOfNotNull(listOf("d", d), c?.let { listOf("c", it) }), expires)

    @Test fun helloGetsInfoThenTheBacklogInOrderWithoutExpiredEvents() {
        val chunk = data(Wire.chunkD(sid, 0))
        val roster = data(Wire.rosterD(sid))
        val treatment = data(Wire.treatmentD(sid, "t1"), c = Wire.treatmentsC(sid))
        val live = event(Wire.KIND_LIVE)
        val wrap = event(Wire.KIND_WRAP)
        val stale = data(Wire.chunkD(sid, 3600), expires = nowS - 1)
        listOf(chunk, live, treatment, wrap, roster, stale).forEach { core.publish(it) }

        val mac = Device("mac")
        mac.connect()
        assertEquals("INFO", mac.received.first().type)
        assertTrue(mac.link.verified)
        assertEquals(listOf(roster, chunk, treatment, live, wrap), mac.events())
    }

    @Test fun theIdentityProofNamesTheLinkOnlyForAnActiveFollowerAndNoReplay() {
        val phone = Device("a")
        phone.connect(nonce = "0a0b")
        assertEquals(listOf(NearbyServerCore.Named(mom.public.hex, "phone", nowMs)), core.named())

        // someone replaying that HELLO (same nonce, same proof) from another address isn't named
        val replay = HelloProof.sign(crypto, mom, sid, "0a0b", "phone")
        core.connected("b")
        core.subscribed("b", true)
        BleFraming.frames(1, BleMessages.hello("0a0b", replay), 185).forEach { core.write("b", it, true, false, 0) }
        assertEquals(1, core.named().size)

        // a device that isn't a follower (or was removed) stays unnamed
        followers = emptyList()
        Device("c").connect()
        assertEquals(1, core.named().size)
        assertEquals(listOf(NearbyServerCore.Effect.Disconnect("a")), core.forget(mom.public.hex))
        assertTrue(core.named().isEmpty())
    }

    @Test fun liveAndAlertPushesGoAheadOfALongBacklog() {
        repeat(30) { core.publish(data(Wire.chunkD(sid, it * 3600L))) }
        val slow = Device("slow", mtu = 23)
        core.connected("slow")
        core.subscribed("slow", true)
        // the HELLO arrives; the stack is slow: take only the first notification
        val hello =
            slow.link
                .connected(23, "aa")
                .filterIsInstance<NearbyLink.Effect.Write>()
                .single()
        var pending = hello
        var out = core.write("slow", pending.frame, true, false, 0)
        while (out.second.none { it is NearbyServerCore.Effect.Notify }) {
            pending =
                slow.link
                    .wrote(true)
                    .filterIsInstance<NearbyLink.Effect.Write>()
                    .single()
            out = core.write("slow", pending.frame, true, false, 0)
        }
        val live = event(Wire.KIND_LIVE, n = 1)
        core.publish(live)
        // drain: the live event completes before the 30 chunks do
        val rx = Reassembler { nowMs }
        val order = mutableListOf<String>()
        var effects = out.second
        while (true) {
            val n = effects.filterIsInstance<NearbyServerCore.Effect.Notify>().firstOrNull() ?: break
            rx.add(n.frame)?.let { m ->
                BleMessages.parse(m)!!.let {
                    order +=
                        if (it.type == "EVENT") SignedEvent.parse(it.body.toString())!!.kind.toString() else it.type
                }
            }
            effects = core.notificationSent("slow")
        }
        assertEquals("INFO", order.first())
        assertTrue(order.indexOf(Wire.KIND_LIVE.toString()) < order.lastIndexOf(Wire.KIND_DATA.toString()))
    }

    @Test fun aSecondHelloWithinFiveSecondsIsAnsweredWhenDue() {
        val d = Device("a")
        d.connect()
        val before = d.received.size
        d.write(d.link.hello("bb"))
        assertEquals(before, d.received.size)
        assertEquals(NearbyServerCore.BACKLOG_GAP_MS, d.later.single().delayMs)
        nowMs += NearbyServerCore.BACKLOG_GAP_MS
        d.serve(core.helloDue("a"))
        assertEquals("INFO", d.received[before].type)
    }

    @Test fun malformedFramesAndTooManyCentralsAreRefused() {
        core.connected("x")
        assertEquals(false to listOf(NearbyServerCore.Effect.Disconnect("x")), core.write("x", byteArrayOf(0, 1, 0), true, false, 0))
        assertFalse(core.write("x", BleFraming.frames(1, "[]".toByteArray(), 23).single(), true, true, 0).first)
        assertFalse(core.write("x", BleFraming.frames(1, "[]".toByteArray(), 23).single(), false, false, 0).first)

        repeat(NearbyServerCore.MAX_CENTRALS) { core.connected("p$it") }
        assertEquals(listOf(NearbyServerCore.Effect.Disconnect("p8")), core.connected("p8"))
    }

    @Test fun eventsFromACentralGoToTheEngineAndForwardSkipsTheSender() {
        val a = Device("a")
        val b = Device("b")
        a.connect()
        b.connect()
        val ev = event(Wire.KIND_DATA, listOf(listOf("d", "x")))
        val frames = BleFraming.frames(5, BleMessages.event(ev), 185)
        val incoming = frames.flatMap { core.write("a", it, true, false, 0).second }
        assertEquals(listOf(NearbyServerCore.Effect.Incoming(ev, "a")), incoming)
        val aBefore = a.events().size
        val forwarded = core.forward(ev, except = "a")
        b.serve(forwarded)
        assertEquals(ev, b.events().last())
        assertEquals(aBefore, a.events().size)
        assertTrue(forwarded.none { it is NearbyServerCore.Effect.Notify && it.peer == "a" })
    }
}
