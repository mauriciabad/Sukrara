// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// What a treatment entry is (contracts/treatments, SPEC §1 `k`).
public enum TreatmentKind: String, Sendable, Codable, CaseIterable {
    case rapid, long, carbs, note
}

/// Who wrote a treatment entry (SPEC §1 `o`): the wearer's phone, this Mac, or a follower.
public enum TreatmentOrigin: String, Sendable, Codable, Comparable {
    case follower, mac, phone

    /// The merge's tie-break on equal `u`: compared by raw value, as both platforms do ("follower" < "mac" < "phone").
    public static func < (a: Self, b: Self) -> Bool { a.rawValue < b.rawValue }
}

/// One treatment entry (insulin, carbs or a note) as SPEC §1 defines it, the same keys on both platforms and the wire.
public struct Treatment: Sendable, Hashable, Codable, Identifiable {
    /// UUID v4, lowercase.
    public var id: String
    public var k: TreatmentKind
    /// Units (one decimal) or grams (whole); 0 for notes.
    public var a: Double
    /// When, epoch seconds.
    public var t: Int64
    /// Free text, at most `noteBytes` UTF-8 bytes.
    public var n: String
    /// Last modified, epoch milliseconds: the last-writer-wins version.
    public var u: Int64
    public var del: Bool
    public var o: TreatmentOrigin
    /// Pen entries only: primed units (air shots, never injected), the pen's serial, its doses for this injection.
    public var pr: Double?
    public var pen: String?
    public var pd: [PenDose]?

    /// One dose a pen recorded: when, units, and whether it was priming.
    public struct PenDose: Sendable, Hashable, Codable {
        public var t: Int64
        public var a: Double
        public var p: Bool

        public init(t: Int64, a: Double, p: Bool = false) {
            self.t = t
            self.a = a
            self.p = p
        }

        /// `a` and `p` may be missing (0, false); `t` may not.
        public init(from decoder: any Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            t = try c.decode(Int64.self, forKey: .t)
            a = try c.decodeIfPresent(Double.self, forKey: .a) ?? 0
            p = try c.decodeIfPresent(Bool.self, forKey: .p) ?? false
        }

        private enum CodingKeys: String, CodingKey { case t, a, p }
    }

    public static let noteBytes = 100
    public static let penMaxBytes = 32
    public static let pdMax = 20

    public init(
        id: String = UUID().uuidString.lowercased(), k: TreatmentKind, a: Double, t: Int64, n: String = "", u: Int64,
        del: Bool = false, o: TreatmentOrigin, pr: Double? = nil, pen: String? = nil, pd: [PenDose]? = nil
    ) {
        self.id = id
        self.k = k
        self.a = a
        self.t = t
        self.n = Self.truncated(n)
        self.u = u
        self.del = del
        self.o = o
        self.pr = pr
        self.pen = pen
        self.pd = pd
    }

    /// Read from an insulin pen, not typed by hand.
    public var fromPen: Bool { pen != nil }

    /// `s` cut to whole characters within `bytes` UTF-8 bytes.
    public static func truncated(_ s: String, bytes: Int = noteBytes) -> String {
        var out = ""
        for ch in s where out.utf8.count + String(ch).utf8.count <= bytes { out.append(ch) }
        return out
    }

    private enum CodingKeys: String, CodingKey { case id, k, a, t, n, u, del, o, pr, pen, pd }

    /// Missing optional keys take their defaults; an entry that breaks the receiver rules (OGS D§6.1: negative
    /// priming, an over-long pen serial, too many / negative / unordered doses, an unknown kind) fails to decode, so
    /// the caller drops the whole entry.
    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        k = try c.decode(TreatmentKind.self, forKey: .k)
        a = try c.decodeIfPresent(Double.self, forKey: .a) ?? 0
        t = try c.decode(Int64.self, forKey: .t)
        n = Self.truncated(try c.decodeIfPresent(String.self, forKey: .n) ?? "")
        u = try c.decode(Int64.self, forKey: .u)
        del = try c.decodeIfPresent(Bool.self, forKey: .del) ?? false
        o = try c.decode(TreatmentOrigin.self, forKey: .o)
        pr = try c.decodeIfPresent(Double.self, forKey: .pr)
        pen = try c.decodeIfPresent(String.self, forKey: .pen)
        pd = try c.decodeIfPresent([PenDose].self, forKey: .pd)
        func bad(_ why: String) -> DecodingError {
            .dataCorrupted(.init(codingPath: c.codingPath, debugDescription: why))
        }
        if let pr, pr < 0 { throw bad("negative pr") }
        if let pen, pen.utf8.count > Self.penMaxBytes { throw bad("pen over \(Self.penMaxBytes) bytes") }
        if let pd {
            if pd.count > Self.pdMax { throw bad("more than \(Self.pdMax) doses") }
            if pd.contains(where: { $0.a < 0 }) { throw bad("negative dose") }
            if zip(pd, pd.dropFirst()).contains(where: { $0.t > $1.t }) { throw bad("doses out of time order") }
        }
    }
}
