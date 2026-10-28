// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// Why a payload was refused. The text names the field, never a value (values can be health data).
public struct PayloadError: Error, Equatable, Sendable {
    public let reason: String
    init(_ reason: String) { self.reason = reason }
}

/// A decrypted JSON object with the strict number rules of the reference (`spec/reference/ogs/model.py`): integers
/// may be written `5.0`, booleans are never numbers.
struct JSONObject {
    let raw: [String: Any]

    init(_ raw: [String: Any]) { self.raw = raw }

    init(json: String) throws(PayloadError) {
        guard let object = try? JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any] else {
            throw PayloadError("payload is not an object")
        }
        raw = object
    }

    func has(_ key: String) -> Bool { raw[key] != nil }
    func isNull(_ key: String) -> Bool { raw[key] is NSNull }
    func string(_ key: String) -> String? { raw[key] as? String }
    func object(_ key: String) -> [String: Any]? { raw[key] as? [String: Any] }

    func int(_ key: String) throws(PayloadError) -> Int {
        guard let value = raw[key] else { throw PayloadError("missing \(key)") }
        guard let n = Self.number(value), n.rounded() == n, abs(n) < 9e15 else {
            throw PayloadError("\(key) must be an integer")
        }
        return Int(n)
    }

    /// nil when absent or null.
    func optionalInt(_ key: String) throws(PayloadError) -> Int? {
        guard has(key), !isNull(key) else { return nil }
        return try int(key)
    }

    func number(_ key: String) throws(PayloadError) -> Double {
        guard let value = raw[key] else { throw PayloadError("missing \(key)") }
        guard let n = Self.number(value) else { throw PayloadError("\(key) must be a number") }
        return n
    }

    func optionalNumber(_ key: String) throws(PayloadError) -> Double? {
        guard has(key), !isNull(key) else { return nil }
        return try number(key)
    }

    func requireVersion(_ want: Int) throws(PayloadError) {
        guard (try? int("v")) == want else { throw PayloadError("unsupported v") }
    }

    static func number(_ value: Any) -> Double? {
        guard let n = value as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID() else { return nil }
        let d = n.doubleValue
        return d.isFinite ? d : nil
    }
}

/// One glucose reading as received.
public struct WireReading: Equatable, Sendable {
    public let t: Int
    public let sgv: Int
    /// mg/dL per minute, nil = unknown.
    public let rate: Double?

    public init(t: Int, sgv: Int, rate: Double?) {
        self.t = t
        self.sgv = sgv
        self.rate = rate
    }
}

/// The wearer's current sensor (PROTOCOL-v2 "Sensor life"), unix s.
public struct SensorLife: Equatable, Codable, Sendable {
    public let start: Int
    public let end: Int
    public let warmupEnd: Int

    public init(start: Int, end: Int, warmupEnd: Int) {
        self.start = start
        self.end = end
        self.warmupEnd = warmupEnd
    }
}

/// Live event body (kind 21078): a reading and/or the sensor. `sensor == nil` clears what the follower knew.
public struct LivePayload: Equatable, Sendable {
    public let reading: WireReading?
    public let sensor: SensorLife?

    public static func decode(_ json: String) throws(PayloadError) -> LivePayload {
        let o = try JSONObject(json: json)
        try o.requireVersion(Wire.version)
        var reading: WireReading?
        if o.has("t") || o.has("sgv") {
            let sgv = try o.int("sgv")
            guard (1...1000).contains(sgv) else { throw PayloadError("sgv out of range") }
            reading = WireReading(t: try o.int("t"), sgv: sgv, rate: try o.optionalNumber("r"))
        }
        var sensor: SensorLife?
        if o.has("se") {
            let start = try o.optionalInt("ss") ?? 0
            let end = try o.int("se")
            sensor = SensorLife(start: start, end: end, warmupEnd: try o.optionalInt("sw") ?? start + 3600)
        }
        return LivePayload(reading: reading, sensor: sensor)
    }
}

/// Hour chunk body: rows `[dt, sgv, rate100|null]`, `dt = t − h`.
public struct ChunkPayload: Equatable, Sendable {
    public let hour: Int
    public let readings: [WireReading]

    public static func decode(_ json: String) throws(PayloadError) -> ChunkPayload {
        let o = try JSONObject(json: json)
        try o.requireVersion(Wire.version)
        let hour = try o.int("h")
        guard hour % 3600 == 0 else { throw PayloadError("h is not an hour start") }
        guard let rows = o.raw["r"] as? [Any] else { throw PayloadError("r must be an array") }
        var readings: [WireReading] = []
        for case let row as [Any] in rows {
            guard row.count >= 2 else { throw PayloadError("bad row") }
            let cell = JSONObject(["dt": row[0], "sgv": row[1], "r": row.count > 2 ? row[2] : NSNull()])
            let dt = try cell.int("dt"), sgv = try cell.int("sgv")
            guard (0..<3600).contains(dt), (1...1000).contains(sgv) else { throw PayloadError("row out of range") }
            let rate100 = try cell.optionalInt("r")
            readings.append(WireReading(t: hour + dt, sgv: sgv, rate: rate100.map { Double($0) / 100 }))
        }
        guard readings.count == rows.count else { throw PayloadError("bad row") }
        return ChunkPayload(hour: hour, readings: readings)
    }
}

/// Roster body (signed by the identity): who publishes, who may write treatments, the wearer's profile.
public struct RosterPayload: Equatable, Sendable {
    public let epoch: Int
    public let devices: [String]
    public let writers: [String]
    public let relays: [String]
    public let profile: Profile?

    public static func decode(_ json: String) throws(PayloadError) -> RosterPayload {
        let o = try JSONObject(json: json)
        try o.requireVersion(Wire.version)
        guard let devices = o.raw["devices"] as? [String], devices.allSatisfy(Hex.isKey) else {
            throw PayloadError("devices must be hex pubkeys")
        }
        let writers = o.has("writers") ? o.raw["writers"] as? [String] : []
        guard let writers, writers.allSatisfy(Hex.isKey) else { throw PayloadError("writers must be hex pubkeys") }
        return RosterPayload(
            epoch: try o.int("epoch"), devices: devices, writers: writers,
            relays: Relays.secure(o.raw["relays"]), profile: o.object("profile").flatMap(Profile.init(wire:)))
    }
}

/// Alert body (kind 21079).
public struct AlertPayload: Equatable, Sendable {
    public static let kinds = [
        "very_low", "low", "high", "very_high", "extremely_high", "falling_fast", "rising_fast", "no_data",
    ]
    public let id: String
    public let kind: String
    public let fires: Bool
    public let at: Int
    public let t: Int?
    public let sgv: Int?
    public let rate: Double?
    public let threshold: Double?
    public let count: Int
    public let treatments: Int

    public static func decode(_ json: String) throws(PayloadError) -> AlertPayload {
        let o = try JSONObject(json: json)
        try o.requireVersion(Wire.version)
        guard o.string("type") == "alert" else { throw PayloadError("type must be alert") }
        guard let kind = o.string("k"), kinds.contains(kind) else { throw PayloadError("unknown alert kind") }
        guard let state = o.string("state"), state == "fire" || state == "clear" else {
            throw PayloadError("state must be fire|clear")
        }
        guard let id = o.string("id"), !id.isEmpty else { throw PayloadError("missing id") }
        return AlertPayload(
            id: id, kind: kind, fires: state == "fire", at: try o.int("at"), t: try o.optionalInt("t"),
            sgv: try o.optionalInt("sgv"), rate: try o.optionalNumber("r"), threshold: try o.optionalNumber("th"),
            count: try o.optionalInt("n") ?? 1, treatments: try o.optionalInt("tr") ?? 0)
    }
}

/// The wearer's mirrored settings (PROTOCOL-v2 "Settings"). The body is kept as received: mapping `graph` /
/// `alerts` onto the Mac's settings is the settings layer's job (`WearerSettings`).
public struct SettingsPayload: Equatable, Sendable {
    public let u: Int
    public let json: String

    public static func decode(_ json: String) throws(PayloadError) -> SettingsPayload {
        let o = try JSONObject(json: json)
        try o.requireVersion(Wire.version)
        guard o.string("type") == "settings" else { throw PayloadError("type must be settings") }
        return SettingsPayload(u: try o.int("u"), json: json)
    }
}

/// Treatment entry (contracts/treatments/SPEC.md §1, wire `v` 1), incl. the pen keys of investigation 29.
public struct TreatmentPayload: Equatable, Sendable {
    public static let kinds = ["rapid", "long", "carbs", "note"]

    public struct Dose: Equatable, Sendable {
        public let t: Int
        public let a: Double
        public let primed: Bool?

        public init(t: Int, a: Double, primed: Bool?) {
            self.t = t
            self.a = a
            self.primed = primed
        }
    }

    public var id: String
    public var kind: String
    public var amount: Double
    public var t: Int
    public var note: String
    /// Merge version, unix ms.
    public var u: Int
    public var deleted: Bool
    /// Origin as created: `phone`, `mac` or `follower`.
    public var origin: String
    public var primed: Double?
    public var pen: String?
    public var doses: [Dose]?

    public init(
        id: String, kind: String, amount: Double, t: Int, note: String, u: Int, deleted: Bool, origin: String,
        primed: Double? = nil, pen: String? = nil, doses: [Dose]? = nil
    ) {
        self.id = id
        self.kind = kind
        self.amount = amount
        self.t = t
        self.note = note
        self.u = u
        self.deleted = deleted
        self.origin = origin
        self.primed = primed
        self.pen = pen
        self.doses = doses
    }

    /// SPEC §1 merge: `(u, o)` compared lexicographically, the greater wins.
    public func isNewer(than other: TreatmentPayload) -> Bool { (u, origin) > (other.u, other.origin) }

    public static func decode(_ json: String) throws(PayloadError) -> TreatmentPayload {
        let o = try JSONObject(json: json)
        try o.requireVersion(Wire.treatmentVersion)
        guard let id = o.string("id"),
            id.wholeMatch(of: /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/) != nil
        else { throw PayloadError("id must be a lowercase UUID") }
        guard let kind = o.string("k"), kinds.contains(kind) else { throw PayloadError("unknown treatment kind") }
        let note = o.has("n") ? o.string("n") : ""
        guard let note, note.utf8.count <= 100 else { throw PayloadError("n must be ≤ 100 bytes") }
        let amount = try o.optionalNumber("a") ?? 0
        guard amount >= 0 else { throw PayloadError("negative amount") }
        let primed = try o.optionalNumber("pr")
        guard (primed ?? 0) >= 0 else { throw PayloadError("negative pr") }
        var pen: String?
        if o.has("pen") {
            guard let p = o.string("pen"), p.utf8.count <= 32 else { throw PayloadError("pen must be ≤ 32 bytes") }
            pen = p
        }
        return TreatmentPayload(
            id: id, kind: kind, amount: amount, t: try o.int("t"), note: note, u: try o.int("u"),
            deleted: o.raw["del"] as? Bool ?? false, origin: o.string("o") ?? "", primed: primed, pen: pen,
            doses: try doses(o))
    }

    private static func doses(_ o: JSONObject) throws(PayloadError) -> [Dose]? {
        guard o.has("pd") else { return nil }
        guard let list = o.raw["pd"] as? [Any], list.count <= 20 else { throw PayloadError("pd must be ≤ 20 doses") }
        var out: [Dose] = []
        for item in list {
            guard let raw = item as? [String: Any] else { throw PayloadError("pd dose is not an object") }
            let dose = JSONObject(raw)
            let t = try dose.int("t"), a = try dose.number("a")
            let primed: Bool?
            if dose.has("p") {
                guard let p = raw["p"] as? NSNumber, CFGetTypeID(p) == CFBooleanGetTypeID() else {
                    throw PayloadError("pd dose: p must be a boolean")
                }
                primed = p.boolValue
            } else {
                primed = nil
            }
            guard a >= 0 else { throw PayloadError("pd dose: negative a") }
            if let last = out.last, t < last.t { throw PayloadError("pd not in time order") }
            out.append(Dose(t: t, a: a, primed: primed))
        }
        return out
    }

    /// The wire body (`v` 1), pen keys only when present.
    public func wireObject() -> [String: Any] {
        var body: [String: Any] = [
            "v": Wire.treatmentVersion, "id": id, "k": kind, "a": amount, "t": t, "n": note, "u": u, "del": deleted,
            "o": origin,
        ]
        if let primed { body["pr"] = primed }
        if let pen { body["pen"] = pen }
        if let doses {
            body["pd"] = doses.map { d -> [String: Any] in
                var o: [String: Any] = ["t": d.t, "a": d.a]
                if let p = d.primed { o["p"] = p }
                return o
            }
        }
        return body
    }
}

/// What the identity sends to a follower (rumor kind 30501).
public enum ToFollowerMessage: Equatable, Sendable {
    case key(KeyDelivery)
    case revoked
    case inviteUsed
    case declined
    case unknown(String)

    public static func decode(_ json: String) throws(PayloadError) -> ToFollowerMessage {
        let o = try JSONObject(json: json)
        guard let type = o.string("type") else { throw PayloadError("rumor without type") }
        try o.requireVersion(Wire.version)
        switch type {
        case "key": return .key(try KeyDelivery.decode(o))
        case "revoked": return .revoked
        case "invite-used": return .inviteUsed
        case "declined": return .declined
        default: return .unknown(type)
        }
    }
}

/// Key delivery: the read key `g` (nsec) of epoch `n` for stream `sid`.
public struct KeyDelivery: Equatable, Sendable {
    public let sid: String
    public let epoch: Int
    public let nsec: String
    public let devices: [String]
    public let relays: [String]

    static func decode(_ o: JSONObject) throws(PayloadError) -> KeyDelivery {
        guard let sid = o.string("sid"), Hex.isKey(sid) else { throw PayloadError("bad sid") }
        guard let g = o.string("g") else { throw PayloadError("missing g") }
        let devices = (o.raw["devices"] as? [String] ?? []).filter(Hex.isKey)
        return KeyDelivery(
            sid: sid, epoch: try o.int("epoch"), nsec: g, devices: devices, relays: Relays.secure(o.raw["relays"]))
    }
}

public enum Relays {
    /// Only `wss://` URLs from a received relay list (no cleartext, no junk).
    public static func secure(_ value: Any?) -> [String] {
        (value as? [Any] ?? []).compactMap { $0 as? String }.filter { $0.hasPrefix("wss://") }
    }
}
