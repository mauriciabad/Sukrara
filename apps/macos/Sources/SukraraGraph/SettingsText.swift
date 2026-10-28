// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import CoreGraphics
import Foundation
import SukraraCore

// The card settings' text forms: how they are stored on this Mac and sent in the wearer's settings event
// (contracts/protocol/PROTOCOL-v2.md "Settings", the same spellings as Android's GraphConfig JSON). Every parser takes
// anything and returns a value the settings accept (or nil = use the default).

extension GraphConfig {
    /// "TIR:86400:1,TIR:604800:1,GMI:1209600:1" (kind:window s:faded).
    public static func slotsText(_ s: [RingSpec]) -> String { s.map(slotText).joined(separator: ",") }

    public static func slotText(_ r: RingSpec) -> String { "\(r.kind.rawValue):\(Int(r.window)):\(r.faded ? 1 : 0)" }

    /// Exactly three valid slots, else nil.
    public static func parseSlots(_ text: String) -> [RingSpec]? {
        let s = text.split(separator: ",").compactMap { parseSlot(String($0)) }
        return s.count == 3 ? s : nil
    }

    /// One slot, "TIR:2592000:1"; nil when malformed or "" (= nothing, for `lastElse`). The period is coerced to the
    /// schema's `ringWindow`.
    public static func parseSlot(_ e: String) -> RingSpec? {
        let p = e.trimmingCharacters(in: .whitespaces).split(separator: ":").map(String.init)
        guard p.count > 1, let kind = SlotKind(rawValue: p[0]), let w = TimeInterval(p[1]) else { return nil }
        return RingSpec(window: SettingsSpec.ringWindow.coerce(w), kind: kind, faded: p.count < 3 || p[2] != "0")
    }

    /// "100,120" → sorted distinct values the line format accepts (`SettingsSpec.line`), at most its `maxItems`.
    public static func parseLines(_ text: String) -> [Int] {
        let spec = SettingsSpec.line
        let values = text.split(separator: ",").compactMap { Double($0.trimmingCharacters(in: .whitespaces)) }
        return Array(Set(values.filter(spec.valid).map { Int($0) }).sorted().prefix(spec.maxItems))
    }

    public static func linesText(_ l: [Int]) -> String { l.map(String.init).joined(separator: ",") }

    /// "TIGHT,TARGET,…" with each of the six bands once, else nil.
    public static func parseRingOrder(_ text: String) -> [Band]? { SukraraGraph.parseRingOrder(text) }

    public static func ringOrderText(_ o: [Band]) -> String { SukraraGraph.ringOrderText(o) }

    /// The renderer's default fallback of the last-treatment slot.
    public static var defaultLastElseSlot: RingSpec { defaultLastElse }
    public static var defaultRingSlots: [RingSpec] { defaultSlots }
}

extension MarkShow {
    /// The stored and wire form ("rapid:point+line+amount+time,long:…").
    public var settingsText: String { text }
    public static func parseSettings(_ text: String?) -> MarkShow { parse(text) }
    /// Whether kind `k` (rapid, long, carbs, note) shows `part` (point, line, amount, time).
    public func shows(_ k: String, _ part: String) -> Bool { has(k, part) }
    public func setting(_ k: String, _ part: String, _ on: Bool) -> MarkShow { with(k, part, on) }
    /// The parts kind `k` can show, in order.
    public static func partsOf(_ k: String) -> [String] { parts(k) }
    public static var kindIds: [String] { kinds }
    public static var partIds: [String] { allParts }
}

extension Band {
    /// The settings spelling (`ringOrder`).
    public var settingsName: String { name }
    /// The range's colour on the card (the settings' ring order list shows it).
    public func color(dark: Bool) -> CGColor { GraphPalette.of(dark: dark).of(self) }
}
