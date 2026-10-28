// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraShare
import Testing

struct OgsVectorsTests {
    let v = OgsVectors.shared
    let crypto = NostrSDKCrypto()

    @Test func tagDerivations() throws {
        let d = v.derivations
        #expect(Wire.glucoseC(sid: v.sid) == d["H('cgm2:glucose:'+sid)"] as? String)
        let chunk = d["d_chunk(sid, hour)"] as! [String: Any]
        #expect(Wire.chunkD(sid: v.sid, hour: chunk["hour"] as! Int) == chunk["d"] as? String)
        #expect(Wire.rosterD(sid: v.sid) == d["d_roster(sid)"] as? String)
        #expect(Wire.treatmentsC(sid: v.sid) == d["c_treatments(sid)"] as? String)
        #expect(Wire.backupD(identity: v.pub("I")) == d["d_backup(I)"] as? String)
        #expect(Wire.gk(groupPublicKey: v.pub("G1")) == d["gk(G1)"] as? String)
        #expect(Wire.gk(groupPublicKey: v.pub("G2")) == d["gk(G2)"] as? String)
        #expect(try crypto.nsecEncode(v.secret("G1")) == d["nsec(G1)"] as? String)
        #expect(try crypto.nsecDecode(d["nsec(G1)"] as! String) == v.secret("G1"))
        let carbs = try TreatmentPayload.decode(v.plaintext("treatment-owner")!["content"] as! String)
        #expect(Wire.treatmentD(sid: v.sid, id: carbs.id) == d["d_treatment(sid, carbs.id)"] as? String)
    }

    @Test func keysMatchTheirPublicKeys() throws {
        for name in ["I", "D", "F", "W", "X", "G1", "G2"] {
            #expect(try crypto.publicKey(of: v.secret(name)) == v.pub(name))
        }
    }

    @Test func everyEventVerifiesAndOpensToItsPlaintext() throws {
        for name in v.eventNames {
            let event = v.event(name)
            let plain = v.plaintext(name) ?? [:]
            #expect(crypto.verify(event) == (name != "live-tampered"), "\(name)")
            if let rumor = plain["rumor"] as? [String: Any] {
                let recipient = plain["recipient"] as! String
                let secret = try #require(recipientSecret(recipient), "\(name)")
                let opened = try crypto.unwrap(event, with: secret)
                #expect(opened.sender == plain["seal_signer"] as? String, "\(name)")
                #expect(opened.kind == UInt16(rumor["kind"] as! Int), "\(name)")
                #expect(opened.content == rumor["content"] as? String, "\(name)")
            } else if name != "live-tampered" {
                let key = try #require(groupSecret(plain["read_key"] as! String), "\(name)")
                let text = try crypto.nip44Decrypt(event.content, with: key, from: event.pubkey)
                #expect(text == plain["content"] as? String, "\(name)")
            }
        }
    }

    @Test func treatmentPayloads() throws {
        let payloads = v.raw["treatment_payloads"] as! [String: Any]
        let pen = try TreatmentPayload.decode(JSONText.encode(payloads["pen-entry"]!))
        #expect(pen.primed == 1.5)
        #expect(pen.doses?.count == 4)
        #expect(pen.amount == 8.5)
        for (i, invalid) in (payloads["invalid"] as! [Any]).enumerated() {
            #expect(throws: PayloadError.self, "invalid #\(i)") {
                try TreatmentPayload.decode(JSONText.encode(invalid))
            }
        }
    }

    @Test func inviteChecks() throws {
        let invite = v.derivations["invite"] as! [String: Any]
        let link = try #require(InviteLink.parse(invite["link"] as! String))
        let opener = InviteOpener(crypto: crypto)
        for check in v.raw["invite_checks"] as! [[String: Any]] {
            let now = check["now"] as? Int ?? v.now
            let result = opener.open(v.event(check["wrap"] as! String), link: link, now: now)
            switch result {
            case .success(let opened):
                #expect(check["expect"] as? String == "ok")
                #expect(opened.identity == v.pub("I"))
                #expect(opened.profile?.name == "Alex")
                #expect(opened.relays.allSatisfy { $0.hasPrefix("wss://") })
            case .failure(let why):
                #expect(why.rawValue == check["expect"] as? String)
            }
        }
    }

    private func recipientSecret(_ pub: String) -> SecretKeyHex? {
        if let name = ["I", "D", "F", "W", "X", "G1", "G2"].first(where: { v.pub($0) == pub }) {
            return v.secret(name)
        }
        let invite = v.derivations["invite"] as! [String: Any]
        return invite["invite_key_pub"] as? String == pub ? SecretKeyHex(invite["invite_key_sec"] as! String) : nil
    }

    private func groupSecret(_ readKey: String) -> SecretKeyHex? {
        ["G1", "G2"].first { v.pub($0) == readKey || v.secret($0).hex == readKey }.map(v.secret)
    }
}
