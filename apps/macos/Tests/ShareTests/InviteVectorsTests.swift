// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraShare
import Testing

struct InviteVectorsTests {
    let crypto = NostrSDKCrypto()

    @Test func identityVectors() throws {
        let invite = loadJSON("protocol/identity/vectors.json")["invite"] as! [String: String]
        let link = try #require(InviteLink.parse(invite["short_link"]!))
        #expect(Hex.encode(link.secret) == invite["secret"])
        #expect(invite["i_pub"]!.hasPrefix(link.fingerprint))
        #expect(try crypto.publicKey(of: link.inviteKey) == invite["invite_key_pub"])
        #expect(link.url == invite["short_link"])
    }

    @Test func ogsInviteDerivations() throws {
        let v = OgsVectors.shared
        let invite = v.derivations["invite"] as! [String: Any]
        let link = try #require(InviteLink.parse(invite["link"] as! String))
        #expect(link.fingerprint == invite["fingerprint"] as? String)
        #expect(link.token == invite["token"] as? String)
        #expect(Hex.encode(link.secret) == invite["secret"] as? String)
        #expect(link.inviteKey.hex == invite["invite_key_sec"] as? String)
        #expect(Wire.matchCode(followerPublicKey: v.pub("F"), token: link.token) == invite["match_code(F)"] as? String)
    }

    @Test func appSchemeIsTheSameLink() throws {
        let web = "https://sukrara.mauri.app/join#yDFq2KB_YY0AESIzRFVmd4iZqrvM3e7_"
        #expect(InviteLink.parse("sukrara:join#yDFq2KB_YY0AESIzRFVmd4iZqrvM3e7_") == InviteLink.parse(web))
        #expect(InviteLink.parse("  \(web)\n") != nil)
    }

    @Test(arguments: [
        "https://sukrara.mauri.app/join#yDFq2KB_YY0AESIzRFVmd4iZqrvM3e7",  // 31 chars
        "https://sukrara.mauri.app/join#yDFq2KB_YY0AESIzRFVmd4iZqrvM3e7_x",  // 33 chars
        "https://sukrara.mauri.app/join#yDFq2KB+YY0AESIzRFVmd4iZqrvM3e7_",  // not base64url
        "https://sukrara.mauri.app/join#i=abc&t=def",  // v2 link
        "cgmshare:join?i=abc",
        "http://sukrara.mauri.app/join#yDFq2KB_YY0AESIzRFVmd4iZqrvM3e7_",
        "",
    ])
    func onlyTheShortLinkIsAnInvite(text: String) {
        #expect(InviteLink.parse(text) == nil)
    }
}
