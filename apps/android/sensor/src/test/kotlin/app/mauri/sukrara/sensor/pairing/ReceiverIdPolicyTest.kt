// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor.pairing

import app.mauri.sukrara.core.testing.InMemorySecureStore
import app.mauri.sukrara.core.testing.InMemoryStateStore
import app.mauri.sukrara.sensor.libre3.Libre3Nfc
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A new sensor is started only with the LibreView account's receiver ID (nothing is sent without it), a takeover
 * tries the account's ID and then Sukrara's own. This is what lets the owner take over another app's sensor and hand
 * it back. The tap logic runs over a scripted NFC transceiver: we check every command that reaches the sensor.
 */
class ReceiverIdPolicyTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private val running = hex("00A500010001000000C04E1E0D0101040C043036385A4D524631382F97")
    private val neverStarted = running.copyOf().also { it[17] = 1 }
    private val paired = hex("00A500111DB71932189A948F6E9F0A7562A2FA")
    private val wrongId = hex("00A501B1")
    private val account = 3078366504L
    private val own = 0x1234_5678L
    private val nowMs = 9_000L

    /** A sensor that answers patch info with [info], then each second command from [answers] in turn. */
    private class FakeTag(private val info: ByteArray, private vararg val answers: ByteArray) {
        val sent = ArrayList<ByteArray>()

        fun transceive(cmd: ByteArray): ByteArray? {
            sent += cmd
            return if (sent.size == 1) info else answers.getOrNull(sent.size - 2)
        }

        /** The receiver IDs in the start / takeover commands sent (LE32 at offset 7). */
        fun receiverIds() = sent.drop(1).map { c -> (0 until 4).fold(0L) { acc, k -> acc or ((c[7 + k].toLong() and 0xFF) shl (8 * k)) } }
    }

    private fun tap(tag: FakeTag, account: Long?) = Libre3Nfc.run(tag::transceive, account, own, { nowMs }, {})

    @Test
    fun aNewSensorWithoutAKnownAccountIdGetsNothing() {
        val tag = FakeTag(neverStarted, paired)
        assertEquals(Libre3Nfc.Result.NeedsAccount("068ZMRF18", neverStarted = true), tap(tag, account = null))
        assertEquals(1, tag.sent.size) // only the patch-info read: no start command reached the sensor
    }

    @Test
    fun aNewSensorIsStartedWithTheAccountIdOnly() {
        val tag = FakeTag(neverStarted, paired)
        val r = tap(tag, account) as Libre3Nfc.Result.Paired
        assertTrue(r.activated)
        assertEquals(account, r.receiverId)
        assertEquals(listOf(account), tag.receiverIds())
        assertEquals(0xA0.toByte(), tag.sent[1][1])
    }

    @Test
    fun aTakeoverTriesTheAccountThenTheOwnId() {
        val tag = FakeTag(running, wrongId, paired)
        val r = tap(tag, account) as Libre3Nfc.Result.Paired
        assertEquals(listOf(account, own), tag.receiverIds())
        assertEquals(own, r.receiverId)
        assertEquals(0xA8.toByte(), tag.sent[1][1])
    }

    @Test
    fun aTakeoverWithTheAccountIdSucceedsAtOnce() {
        val tag = FakeTag(running, paired)
        assertEquals(account, (tap(tag, account) as Libre3Nfc.Result.Paired).receiverId)
        assertEquals(listOf(account), tag.receiverIds())
    }

    /** Sukrara's own ID can never take over a sensor (Sukrara never starts one with it): without the account ID, send nothing. */
    @Test
    fun aTakeoverWithoutAnAccountIdSendsNothing() {
        val tag = FakeTag(running, paired)
        assertEquals(Libre3Nfc.Result.NeedsAccount("068ZMRF18", neverStarted = false), tap(tag, account = null))
        assertEquals(1, tag.sent.size)
    }

    @Test
    fun aSensorBoundToAnotherReceiverIsLeftAlone() {
        val tag = FakeTag(running, wrongId, wrongId)
        assertEquals(Libre3Nfc.Result.OwnedByAnotherReceiver("068ZMRF18"), tap(tag, account))
        assertEquals(listOf(account, own), tag.receiverIds())
    }

    @Test
    fun policy() {
        assertNull(ReceiverIds.forStart(null))
        assertEquals(listOf(7L), ReceiverIds.forStart(7L))
        assertNull(ReceiverIds.forTakeover(null, 5L))
        assertEquals(listOf(7L, 5L), ReceiverIds.forTakeover(7L, 5L))
        assertEquals(listOf(7L), ReceiverIds.forTakeover(7L, 7L))
        assertTrue(ReceiverIds.newOwn() >= 0x10000000L)
    }

    @Test
    fun receiverIdFromLibreViewAccount() {
        // Juggluco's getlibreaccountidnumber(), computed independently in Python
        assertEquals(3078366504L, ReceiverIds.fromAccountId("01234567-89ab-cdef-0123-456789abcdef"))
    }

    @Test
    fun ownIdIsMadeOnceAndSurvivesSignOutAndAForgottenSensor() =
        runTest {
            val ids = InMemoryStateStore(ReceiverIdState(account = account, accountEmail = "a@b.c"))
            val store = PairingStore(InMemorySecureStore())
            val nfc = NfcPairing(store, ids, {}, StandardTestDispatcher(testScheduler))
            val (acc, first) = nfc.receiverIds()
            assertEquals(account, acc)
            assertEquals(first, nfc.receiverIds().second)
            ids.update { it.copy(account = null, accountEmail = null) } // sign-out
            store.forget()
            assertEquals(first, ids.data.first().own)
            assertEquals(null to first, nfc.receiverIds())
        }

    /** Every tap replaces the pairing with no authorization: the sensor gave a new PIN, the old kAuth is useless. */
    @Test
    fun aPairedTapReplacesThePairingAndRestartsTheSource() =
        runTest {
            val store = PairingStore(InMemorySecureStore())
            var restarts = 0
            val nfc = NfcPairing(store, InMemoryStateStore(ReceiverIdState()), { restarts++ }, StandardTestDispatcher(testScheduler))
            val p = (Libre3Nfc.run(FakeTag(running, paired)::transceive, account, own, { nowMs }, {}) as Libre3Nfc.Result.Paired)
            store.save(p.pairing.copy(kAuth = ByteArray(149), ended = true))
            assertEquals(PairOutcome.Paired("068ZMRF18", activated = false), nfc.handle(p))
            val saved = (store.load() as PairingLoad.Ok).pairing
            assertNull(saved.kAuth)
            assertEquals(false, saved.ended)
            assertEquals(1, restarts)
            assertEquals(
                PairOutcome.OwnedByAnotherApp("X"),
                nfc.handle(Libre3Nfc.Result.OwnedByAnotherReceiver("X")),
            )
        }
}
