// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.ble

import app.mauri.sukrara.protocol.PublicKeyHex
import app.mauri.sukrara.protocol.ble.BleFraming
import app.mauri.sukrara.protocol.ble.BleMessages
import app.mauri.sukrara.protocol.testing.ContractFiles
import app.mauri.sukrara.share.OwnerFixtures.crypto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * contracts/protocol/ble/default-mtu.json: a Mac follower and this wearer at ATT MTU 23 (no MTU exchange). The Mac's
 * HELLO with proof, as plain frames and as one long (prepared) write, gets exactly the fixture's INFO frames and
 * names the link F on a mac. Mac `NearbyTests` replays the follower side of the same file.
 */
class NearbyDefaultMtuTest {
    private val fx = ContractFiles.json("protocol/ble/default-mtu.json")
    private val mtu = fx.int("att_mtu")
    private val sid = fx.str("sid")
    private val follower = PublicKeyHex(fx.str("follower"))
    private val core =
        NearbyServerCore(
            crypto,
            object : NearbyServerCore.Host {
                override fun sid() = sid

                override fun followers(): Collection<PublicKeyHex> = listOf(follower)
            },
        ) { 1_792_087_200_000L }

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun hexes(key: String) = fx.getValue(key).jsonArray.map { hex(it.jsonPrimitive.content) }

    private val named = listOf(NearbyServerCore.Named(follower.hex, fx.obj("expect").str("device"), 1_792_087_200_000L))

    /** Connects at the default MTU and subscribes; the notifications the core sends, frame by frame. */
    private fun subscribe(peer: String): MutableList<ByteArray> {
        core.connected(peer, mtu)
        val sent = mutableListOf<ByteArray>()
        serve(peer, core.subscribed(peer, true), sent)
        return sent
    }

    private fun serve(peer: String, effects: List<NearbyServerCore.Effect>, sent: MutableList<ByteArray>) {
        for (e in effects) {
            if (e is NearbyServerCore.Effect.Notify) {
                sent += e.frame
                serve(peer, core.notificationSent(peer), sent)
            }
        }
    }

    @Test fun everyFrameFitsOneAttWriteOrNotification() {
        (hexes("hello_frames") + hexes("info_frames")).forEach { assertTrue(it.size <= mtu - 3) }
        // this app's follower frames its HELLO the same way at that MTU
        val hello =
            BleMessages.hello(
                fx.str("nonce"),
                fx
                    .getValue("hello")
                    .jsonArray[1]
                    .jsonObject
                    .str("id"),
            )
        BleFraming.frames(1, hello, mtu).forEach { assertTrue(it.size <= mtu - 3) }
    }

    @Test fun plainFramesGetTheInfoAndNameTheMacFollower() {
        val sent = subscribe("mac")
        for (frame in hexes("hello_frames")) {
            val (ok, effects) = core.write("mac", frame, toRx = true, prepared = false, offset = 0, mtu = mtu)
            assertTrue(ok)
            serve("mac", effects, sent)
        }
        assertEquals(hexes("info_frames").map { it.toList() }, sent.map { it.toList() })
        assertEquals(named, core.named())
    }

    @Test fun aLongWriteIsAcceptedLikeThePlainFrames() {
        val sent = subscribe("mac")
        val long = fx.obj("long_write")
        for (part in long.getValue("prepared").jsonArray.map { it.jsonObject }) {
            val (ok, effects) =
                core.write("mac", hex(part.str("value")), toRx = true, prepared = true, offset = part.int("offset"), mtu = mtu)
            assertTrue(ok)
            assertTrue(effects.isEmpty())
        }
        assertTrue(sent.isEmpty())
        val (ok, effects) = core.execute("mac", commit = true)
        assertTrue(ok)
        serve("mac", effects, sent)
        assertEquals(hexes("info_frames").map { it.toList() }, sent.map { it.toList() })
        assertEquals(named, core.named())
    }

    @Test fun aCancelledLongWriteIsDroppedAndAnOutOfOrderOrOversizedOneRefused() {
        val parts =
            fx
                .obj("long_write")
                .getValue("prepared")
                .jsonArray
                .map { it.jsonObject }
        subscribe("a")
        core.write("a", hex(parts[0].str("value")), true, prepared = true, offset = 0, mtu = mtu)
        assertEquals(true to emptyList<NearbyServerCore.Effect>(), core.execute("a", commit = false))
        assertTrue(core.named().isEmpty())

        // a part that skips ahead is refused and the central dropped
        subscribe("b")
        val (skipped, drop) = core.write("b", hex(parts[1].str("value")), true, prepared = true, offset = 18, mtu = mtu)
        assertFalse(skipped)
        assertEquals(listOf(NearbyServerCore.Effect.Disconnect("b")), drop)

        // more than one attribute (512 bytes) is refused
        subscribe("c")
        var offset = 0
        var last = true
        while (last && offset <= BleFraming.MAX_ATTRIBUTE) {
            last = core.write("c", ByteArray(100), true, prepared = true, offset = offset, mtu = mtu).first
            offset += 100
        }
        assertFalse(last)
        assertEquals(600, offset)

        // an executed long write that isn't a frame is refused
        subscribe("d")
        core.write("d", byteArrayOf(0, 1), true, prepared = true, offset = 0, mtu = mtu)
        assertEquals(false to listOf(NearbyServerCore.Effect.Disconnect("d")), core.execute("d", commit = true))
    }

    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content

    private fun JsonObject.int(k: String) = str(k).toInt()

    private fun JsonObject.obj(k: String) = getValue(k).jsonObject
}
