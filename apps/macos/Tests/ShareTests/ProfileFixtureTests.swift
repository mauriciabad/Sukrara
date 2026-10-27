// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraShare
import Testing

/// contracts/protocol/identity/profile-v1.json: names, v1 normalisation, editor ops, wire acceptance, update body.
struct ProfileFixtureTests {
    let fixture = loadJSON("protocol/identity/profile-v1.json")

    func same(_ avatar: Avatar?, _ expected: Any?) -> Bool {
        guard let expected = expected as? [String: Any] else { return avatar == nil }
        return avatar?.json == JSONText.encode(expected)
    }

    @Test func names() {
        for c in fixture["names"] as! [[String: String]] {
            #expect(Profile.normalisedName(typed: c["typed"]!, previous: c["previous"]!) == c["name"])
        }
    }

    @Test func v1Normalisation() {
        for c in fixture["v1"] as! [[String: Any]] {
            let avatar = Avatar.v1(
                c: c["c"] as! Int, s: c["s"] as! String, a: c["a"] as! Int, n: (c["n"] as! NSNumber).int64Value)
            #expect(same(avatar, c["avatar"]))
        }
    }

    @Test func editorOps() throws {
        for c in fixture["ops"] as! [[String: Any]] {
            let from = try #require(Avatar(wire: c["from"]))
            let result: Avatar =
                switch c["op"] as! String {
                case "turn": from.turned()
                case "shape": from.with(shape: c["value"] as? String)
                case "colors": from.with(colors: c["value"] as? Int)
                default: from
                }
            #expect(same(result, c["avatar"]), "\(c["op"]!)")
        }
    }

    @Test func wire() {
        for c in fixture["wire"] as! [[String: Any]] {
            #expect(same(Avatar(wire: c["in"]), c["avatar"]))
        }
    }

    @Test func updateBody() {
        for c in fixture["update"] as! [[String: Any]] {
            let profile = Profile(name: c["name"] as! String, avatar: Avatar(wire: c["avatar"]))
            #expect(JSONText.encode(FollowerMessages.profileBody(profile)) == JSONText.encode(c["body"]!))
        }
    }
}
