// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// The settings the wearer's phone mirrors to followers (PROTOCOL-v2 "Settings": `{"graph":{…},"alerts":{…}}`),
/// addressed by schema id through the entry's `wire` path. A follower in "Settings from: Wearer" shows these values
/// read-only; an id that isn't mirrored or wasn't sent reads as nil (the follower's own value applies).
public struct WearerSettings: Sendable, Equatable {
    /// The wearer's name for "From <wearer>".
    public let wearer: String?
    private let root: [String: JSONValue]

    public init?(json: String, wearer: String?) {
        guard let data = json.data(using: .utf8),
            case .object(let o)? = try? JSONDecoder().decode(JSONValue.self, from: data)
        else { return nil }
        self.root = o
        self.wearer = wearer
    }

    /// The mirrored number for a schema id (`graph.band.low`), coerced to its format; `kind` fills `{kind}`.
    public func number(_ id: String, kind: String? = nil) -> Double? {
        guard let (info, value) = mirrored(id, kind: kind), case .number(let v) = value else { return nil }
        guard let format = info.number.flatMap({ SettingsSpec.numbers[$0] }) else { return v }
        return format.coerce(v)
    }

    /// The mirrored bool for a schema id (`alerts.kind.on` with a kind, `graph.side`).
    public func bool(_ id: String, kind: String? = nil) -> Bool? {
        guard let (_, value) = mirrored(id, kind: kind), case .bool(let b) = value else { return nil }
        return b
    }

    /// The mirrored text for a schema id (`graph.slots`, `graph.ringOrder`, `graph.lastElse` …: the wire spellings).
    public func string(_ id: String) -> String? {
        guard let (_, value) = mirrored(id, kind: nil), case .string(let s) = value else { return nil }
        return s
    }

    private func mirrored(_ id: String, kind: String?) -> (SettingInfo, JSONValue)? {
        guard let info = SettingsSpec.settings.first(where: { $0.id == id }), info.mirror, let wire = info.wire else {
            return nil
        }
        let path = wire.replacingOccurrences(of: "{kind}", with: kind ?? "").split(separator: ".").map(String.init)
        var node = JSONValue.object(root)
        for part in path {
            guard case .object(let o) = node, let next = o[part] else { return nil }
            node = next
        }
        return (info, node)
    }
}

/// A JSON value, enough to walk the mirrored settings.
enum JSONValue: Decodable, Sendable, Equatable {
    case object([String: JSONValue]), array([JSONValue]), number(Double), bool(Bool), string(String), null

    init(from decoder: any Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() {
            self = .null
        } else if let b = try? c.decode(Bool.self) {
            self = .bool(b)
        } else if let n = try? c.decode(Double.self) {
            self = .number(n)
        } else if let s = try? c.decode(String.self) {
            self = .string(s)
        } else if let a = try? c.decode([JSONValue].self) {
            self = .array(a)
        } else {
            self = .object(try c.decode([String: JSONValue].self))
        }
    }
}
