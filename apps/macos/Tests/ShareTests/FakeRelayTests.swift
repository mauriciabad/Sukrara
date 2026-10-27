// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraShare
import SukraraTesting
import Testing

@Suite struct FakeRelayTests {
    func event(_ id: String, kind: Int, pubkey: String = "a", tags: [[String]] = []) -> SignedEvent {
        SignedEvent(
            id: id, pubkey: pubkey, createdAt: Date(timeIntervalSince1970: 1000), kind: kind, tags: tags, content: "",
            sig: "")
    }

    @Test func deliversStoredThenLiveMatchingEvents() async throws {
        let relay = FakeRelay(stored: [event("1", kind: 30078), event("x", kind: 1)])
        let stream = relay.subscribe(EventFilter(kinds: [30078]))
        await relay.publish(event("2", kind: 30078))
        var ids: [String] = []
        for try await e in stream {
            ids.append(e.id)
            if ids.count == 2 { break }
        }
        #expect(ids == ["1", "2"])
    }

    @Test func filterMatchesAuthorsSinceAndTags() {
        let e = event("1", kind: 1, pubkey: "p1", tags: [["p", "me"]])
        #expect(EventFilter(authors: ["p1"], tags: ["p": ["me"]]).matches(e))
        #expect(!EventFilter(authors: ["p2"]).matches(e))
        #expect(!EventFilter(since: Date(timeIntervalSince1970: 2000)).matches(e))
        #expect(!EventFilter(tags: ["p": ["other"]]).matches(e))
    }
}
