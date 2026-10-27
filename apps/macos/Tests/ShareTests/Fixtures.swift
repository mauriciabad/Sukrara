// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraShare
import SukraraTesting

/// The OGS vectors (contracts/protocol/ogs/ogs-draft1.json): events made by the Python reference with the
/// showcase's wire format — passing them means following a Sukrara 0.11 wearer works at the wire level.
/// Immutable after loading (a parsed JSON tree nobody mutates), so sharing it across tests is safe.
struct OgsVectors: @unchecked Sendable {
    let raw: [String: Any]

    static let shared: OgsVectors = {
        let data = try! Data(contentsOf: Contracts.url("protocol/ogs/ogs-draft1.json"))
        return OgsVectors(raw: try! JSONSerialization.jsonObject(with: data) as! [String: Any])
    }()

    var now: Int { raw["now"] as! Int }
    var sid: String { raw["sid"] as! String }
    var derivations: [String: Any] { raw["derivations"] as! [String: Any] }

    func secret(_ name: String) -> SecretKeyHex { SecretKeyHex(key(name)["sec"]!)! }
    func pub(_ name: String) -> String { key(name)["pub"]! }
    private func key(_ name: String) -> [String: String] { (raw["keys"] as! [String: Any])[name] as! [String: String] }

    var eventNames: [String] { (raw["events"] as! [String: Any]).keys.sorted() }

    func event(_ name: String) -> SignedEvent {
        let object = (raw["events"] as! [String: Any])[name]!
        return SignedEvent.decode(json: JSONText.encode(object))!
    }

    /// nil for `live-tampered` (its content was changed after signing).
    func plaintext(_ name: String) -> [String: Any]? { (raw["plaintexts"] as! [String: Any])[name] as? [String: Any] }
}

func loadJSON(_ path: String) -> [String: Any] {
    try! JSONSerialization.jsonObject(with: Data(contentsOf: Contracts.url(path))) as! [String: Any]
}
