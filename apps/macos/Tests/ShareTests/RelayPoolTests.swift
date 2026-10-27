// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraShare
import SukraraTesting
import Testing

/// RelayPool over scripted sockets: no test opens a network connection.
struct RelayPoolTests {
    let v = OgsVectors.shared
    let crypto = NostrSDKCrypto()
    let relay = "wss://relay.test.invalid"

    func pool(_ factory: FakeWebSocketFactory, authKey: SecretKeyHex? = nil) -> RelayPool {
        RelayPool(
            relays: [relay, "https://not-a-relay.invalid"], authKey: authKey, crypto: crypto, factory: factory,
            sleep: { _ in await Task.yield() }, now: { 1_792_087_200 })
    }

    @Test func backoffDoublesToTheCapWithJitter() {
        let b = Backoff()
        #expect(b.delay(attempt: 0, jitter: 1) == 2)
        #expect(b.delay(attempt: 3, jitter: 1) == 16)
        #expect(b.delay(attempt: 30, jitter: 1) == 300)
        #expect(b.delay(attempt: 3, jitter: 0.5) == 8)
    }

    @Test func onlySecureRelaysAndSubscriptionsResentAfterReconnect() async throws {
        let factory = FakeWebSocketFactory()
        let pool = pool(factory)
        await pool.subscribe("live", RelayFilter(kinds: [Wire.kindLive], authors: [v.pub("D")]))
        await pool.start()
        #expect(await factory.waitForConnections(1))
        #expect(factory.urls.map(\.absoluteString) == [relay])  // only wss:// relays are used
        let first = factory.sockets[0]
        #expect(await first.waitUntilSent { $0.contains { $0.hasPrefix("[\"REQ\",\"live\"") } })
        await pool.publish(v.event("access-request"))
        first.fail()
        #expect(await factory.waitForConnections(2))
        let second = factory.sockets[1]
        #expect(
            await second.waitUntilSent { sent in
                sent.contains { $0.hasPrefix("[\"REQ\",\"live\"") } && sent.contains { $0.hasPrefix("[\"EVENT\"") }
            })
        await pool.stop()
    }

    @Test func sameFilterIsNotResent() async throws {
        let factory = FakeWebSocketFactory()
        let pool = pool(factory)
        await pool.start()
        #expect(await factory.waitForConnections(1))
        let filter = RelayFilter(kinds: [Wire.kindWrap], p: [v.pub("F")])
        await pool.subscribe("inbox", filter)
        await pool.subscribe("inbox", filter)
        let socket = factory.sockets[0]
        #expect(await socket.waitUntilSent { !$0.isEmpty })
        #expect(socket.sent.filter { $0.hasPrefix("[\"REQ\"") }.count == 1)
        await pool.stop()
    }

    @Test func eventsEndOfStoredAndOkReachInbound() async throws {
        let factory = FakeWebSocketFactory()
        let pool = pool(factory)
        await pool.start()
        #expect(await factory.waitForConnections(1))
        let socket = factory.sockets[0]
        let live = v.event("live")
        socket.push("[\"EVENT\",\"live\",\(live.json())]")
        socket.push("[\"EOSE\",\"live\"]")
        socket.push("[\"OK\",\"abc\",false,\"blocked\"]")
        var got: [RelayInbound] = []
        for await message in pool.inbound {
            got.append(message)
            if got.count == 4 { break }
        }
        #expect(
            got == [
                .connection(.connected, relay: "relay.test.invalid"),
                .event(live, subscription: "live", relay: "relay.test.invalid"),
                .endOfStored(subscription: "live", relay: "relay.test.invalid"),
                .ok(eventId: "abc", accepted: false, relay: "relay.test.invalid"),
            ])
        await pool.stop()
    }

    @Test func authRequiredAnsweredWithNip42ThenReqResent() async throws {
        let factory = FakeWebSocketFactory()
        let pool = pool(factory, authKey: v.secret("F"))
        await pool.subscribe("inbox", RelayFilter(kinds: [Wire.kindWrap], p: [v.pub("F")]))
        await pool.start()
        #expect(await factory.waitForConnections(1))
        let socket = factory.sockets[0]
        socket.push("[\"AUTH\",\"challenge-1\"]")
        socket.push("[\"CLOSED\",\"inbox\",\"auth-required: wraps only for their recipient\"]")
        #expect(await socket.waitUntilSent { $0.contains { $0.hasPrefix("[\"AUTH\"") } })
        let authFrame = try #require(socket.sent.first { $0.hasPrefix("[\"AUTH\"") })
        let array = try #require(try JSONSerialization.jsonObject(with: Data(authFrame.utf8)) as? [Any])
        let auth = try #require(SignedEvent.decode(json: JSONText.encode(array[1])))
        #expect(auth.kind == Wire.kindAuth)
        #expect(auth.pubkey == v.pub("F"))
        #expect(auth.tag("challenge") == "challenge-1")
        #expect(auth.tag("relay") == relay)
        #expect(crypto.verify(auth))
        socket.push("[\"OK\",\"\(auth.id)\",true,\"\"]")
        #expect(await socket.waitUntilSent { $0.filter { $0.hasPrefix("[\"REQ\",\"inbox\"") }.count == 2 })
        await pool.stop()
    }

    @Test func stopClosesTheSocketsAndTheStream() async throws {
        let factory = FakeWebSocketFactory()
        let pool = pool(factory)
        await pool.start()
        #expect(await factory.waitForConnections(1))
        await pool.stop()
        #expect(await pool.connectedCount == 0)
        var count = 0
        for await _ in pool.inbound { count += 1 }  // ends: stop finished the stream
        #expect(count >= 0)
    }
}
