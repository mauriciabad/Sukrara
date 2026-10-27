// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.transport

import app.mauri.sukrara.protocol.UnsignedEvent
import app.mauri.sukrara.protocol.Wire
import app.mauri.sukrara.share.OwnerFixtures.crypto
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pool against fake sockets and virtual time: no network, no real relay. */
class RelayPoolTest {
    private class FakeSocket(val onEvent: (SocketEvent) -> Unit) : RelaySocket {
        val sent = mutableListOf<String>()
        var cancelled = false

        override fun send(text: String): Boolean = sent.add(text)

        override fun cancel() {
            cancelled = true
        }
    }

    private val sockets = mutableListOf<FakeSocket>()
    private val factory = WebSocketFactory { _, onEvent -> FakeSocket(onEvent).also { sockets += it } }
    private val identity = crypto.generate()

    private fun TestScope.pool(relays: List<String> = listOf("wss://a.example")) =
        RelayPool(relays, { identity }, crypto, factory, StandardTestDispatcher(testScheduler), { 1_000L }, jitter = { 1.0 })

    @Test fun onlySecureRelaysAreOpened() = runTest {
        val p = pool(listOf("ws://cleartext.example", "wss://a.example"))
        p.start()
        runCurrent()
        assertEquals(1, sockets.size)
        assertEquals(setOf("wss://a.example"), p.status.value.keys)
        p.close()
    }

    @Test fun reconnectsWithDoublingBackoffAndResendsSubscriptionsAndOutbox() = runTest {
        val p = pool()
        p.start()
        p.subscribe("inbox", listOf(RelayFilter(kinds = listOf(Wire.KIND_WRAP), p = listOf(identity.public.hex))))
        val ev = crypto.sign(identity, UnsignedEvent(Wire.KIND_ALERT, "x", emptyList(), 1_000))
        p.publish(ev)
        runCurrent()
        sockets[0].onEvent(SocketEvent.Message("[\"EOSE\",\"inbox\"]"))
        runCurrent()
        assertTrue(p.status.value.getValue("wss://a.example").connected)

        sockets[0].onEvent(SocketEvent.Closed("test"))
        runCurrent()
        assertFalse(p.status.value.getValue("wss://a.example").connected)
        advanceTimeBy(1_999)
        assertEquals("no reconnect before the 2 s backoff", 1, sockets.size)
        advanceTimeBy(2)
        assertEquals(2, sockets.size)
        assertTrue(sockets[1].sent.any { it.startsWith("[\"REQ\",\"inbox\"") })
        assertTrue(sockets[1].sent.any { it.contains(ev.id) })

        // fails again without the relay saying anything: the next wait doubles
        sockets[1].onEvent(SocketEvent.Closed("test"))
        advanceTimeBy(3_999)
        assertEquals(2, sockets.size)
        advanceTimeBy(2)
        assertEquals(3, sockets.size)
        p.close()
    }

    @Test fun reconnectNowSkipsTheBackoff() = runTest {
        val p = pool()
        p.start()
        runCurrent()
        sockets[0].onEvent(SocketEvent.Closed("offline"))
        runCurrent()
        p.reconnectNow()
        runCurrent()
        assertEquals(2, sockets.size)
        p.close()
    }

    @Test fun authRequiredAnswersTheChallengeOnceAndResends() = runTest {
        val p = pool()
        p.start()
        p.subscribe("inbox", listOf(RelayFilter(kinds = listOf(Wire.KIND_WRAP))))
        runCurrent()
        val s = sockets[0]
        s.onEvent(SocketEvent.Message("[\"AUTH\",\"challenge-1\"]"))
        s.onEvent(SocketEvent.Message("[\"CLOSED\",\"inbox\",\"auth-required: wraps\"]"))
        runCurrent()
        val auth = s.sent.filter { it.startsWith("[\"AUTH\"") }
        assertEquals(1, auth.size)
        assertTrue(auth.single().contains("challenge-1"))
        p.close()
    }

    @Test fun fetchWaitsForEveryRelayBeforeEnding() = runTest {
        val p = pool(listOf("wss://a.example", "wss://b.example"))
        p.start()
        runCurrent()
        val ev = crypto.sign(identity, UnsignedEvent(Wire.KIND_DATA, "x", listOf(listOf("d", "z")), 1_000))
        val result = async { p.fetch(listOf(RelayFilter(authors = listOf(identity.public.hex))), 10_000) }
        runCurrent()
        val sub = Regex("\\[\"REQ\",\"([^\"]+)\"").find(sockets[0].sent.last())!!.groupValues[1]
        sockets[0].onEvent(SocketEvent.Message("[\"EOSE\",\"$sub\"]"))
        runCurrent()
        assertFalse("one quick relay doesn't end the query", result.isCompleted)
        sockets[1].onEvent(SocketEvent.Message("[\"EVENT\",\"$sub\",${ev.toJson()}]"))
        sockets[1].onEvent(SocketEvent.Message("[\"EOSE\",\"$sub\"]"))
        runCurrent()
        val r = result.await()
        assertEquals(2, r.answered)
        assertEquals(listOf(ev.id), r.events.map { it.id })
        p.close()
    }

    @Test fun aFailedRelaySettlesTheFetch() = runTest {
        val p = pool(listOf("wss://a.example", "wss://b.example"))
        p.start()
        runCurrent()
        val result = async { p.fetch(listOf(RelayFilter(kinds = listOf(1))), 10_000) }
        runCurrent()
        val sub = Regex("\\[\"REQ\",\"([^\"]+)\"").find(sockets[0].sent.last())!!.groupValues[1]
        sockets[0].onEvent(SocketEvent.Message("[\"EOSE\",\"$sub\"]"))
        sockets[1].onEvent(SocketEvent.Closed("refused"))
        runCurrent()
        assertEquals(1, result.await().answered)
        p.close()
    }

    @Test fun aNewerLiveEventReplacesTheOlderInTheOutbox() = runTest {
        val p = pool()
        p.start()
        runCurrent()
        val first = crypto.sign(identity, UnsignedEvent(Wire.KIND_LIVE, "1", emptyList(), 1_000))
        val second = crypto.sign(identity, UnsignedEvent(Wire.KIND_LIVE, "2", emptyList(), 1_001))
        p.publish(first)
        p.publish(second)
        runCurrent()
        sockets[0].onEvent(SocketEvent.Closed("x"))
        advanceTimeBy(2_001)
        assertFalse(sockets[1].sent.any { it.contains(first.id) })
        assertTrue(sockets[1].sent.any { it.contains(second.id) })
        p.close()
    }

    @Test fun anOversizedMessageDropsTheSocketAndNothingReachesInbound() = runTest {
        val p = pool()
        val inbound = mutableListOf<RelayInbound>()
        backgroundScope.launch { p.inbound.collect { inbound += it } }
        p.start()
        p.subscribe("inbox", listOf(RelayFilter(kinds = listOf(Wire.KIND_WRAP))))
        runCurrent()
        val huge = "[\"EOSE\",\"" + "x".repeat(RelayPool.MAX_MESSAGE_CHARS) + "\"]"
        sockets[0].onEvent(SocketEvent.Message(huge))
        runCurrent()
        assertTrue(sockets[0].cancelled)
        val status = p.status.value.getValue("wss://a.example")
        assertFalse(status.connected)
        assertEquals(RelayPool.TOO_BIG, status.lastError)
        assertTrue(inbound.isEmpty())
        advanceTimeBy(2_001)
        assertEquals("it reconnects after the backoff like any failure", 2, sockets.size)
        p.close()
    }

    @Test fun aFloodFasterThanTheQueueDropsTheSocket() = runTest {
        val p = pool()
        p.start()
        runCurrent()
        // the pool's dispatcher doesn't run while we push: the queue fills and the next push trips it
        repeat(RelayPool.INBOUND_QUEUE + 1) { sockets[0].onEvent(SocketEvent.Message("[\"EOSE\",\"s$it\"]")) }
        sockets[0].onEvent(SocketEvent.Message("[\"EOSE\",\"late\"]"))
        runCurrent()
        assertTrue(sockets[0].cancelled)
        assertEquals(RelayPool.FLOODED, p.status.value.getValue("wss://a.example").lastError)
        p.close()
    }

    @Test fun aRelayThatAnswersThenOversendsKeepsItsGrowingBackoff() = runTest {
        val p = pool()
        p.start()
        runCurrent()
        val huge = "[\"EOSE\",\"" + "x".repeat(RelayPool.MAX_MESSAGE_CHARS) + "\"]"
        // 2 s, then 4 s: a normal message first (which marks the socket open) doesn't bring the 2 s back
        listOf(2_000L, 4_000L).forEachIndexed { i, wait ->
            sockets[i].onEvent(SocketEvent.Message("[\"EOSE\",\"ok\"]"))
            sockets[i].onEvent(SocketEvent.Message(huge))
            runCurrent()
            assertEquals(RelayPool.TOO_BIG, p.status.value.getValue("wss://a.example").lastError)
            advanceTimeBy(wait - 1)
            assertEquals(i + 1, sockets.size)
            advanceTimeBy(2)
            assertEquals(i + 2, sockets.size)
        }
        p.close()
    }

    @Test fun aRelayClosingAfterItsMessagesKeepsItsReason() = runTest {
        val p = pool()
        p.start()
        runCurrent()
        sockets[0].onEvent(SocketEvent.Message("[\"EOSE\",\"ok\"]"))
        sockets[0].onEvent(SocketEvent.Closed("going away"))
        runCurrent()
        assertEquals("going away", p.status.value.getValue("wss://a.example").lastError)
        p.close()
    }
}
