// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// The Add treatment form's rules (contracts/treatments/treatment-form.json, the same cases Android's TreatmentForm
/// passes): what the fields keep of typed text, the limits, rounding, and the entries Save creates.
public enum TreatmentForm {
    public static let maxUnits = 100.0
    public static let maxGrams = 500.0
    /// Longest "minutes ago": under a day, like the time picker (a picked time is at most 23:59 back).
    public static let maxAgoMinutes = 1439

    public enum Amount: Equatable, Sendable {
        case empty, invalid, value(Double)
    }

    /// What an amount field keeps of a typed text: ASCII digits, `.` and `,`, at most 5 characters.
    public static func filterAmount(_ s: String) -> String {
        String(s.filter { $0.isASCII && ($0.isNumber || $0 == "." || $0 == ",") }.prefix(5))
    }

    /// What the minutes-ago field keeps: ASCII digits, at most 4.
    public static func filterMinutes(_ s: String) -> String {
        String(s.filter { $0.isASCII && $0.isNumber }.prefix(4))
    }

    /// What the note keeps: one line (a line break becomes a space); a change past the byte limit is refused.
    public static func filterNote(_ new: String, old: String) -> String {
        let s = new.replacingOccurrences(of: "\n", with: " ")
        return s.utf8.count <= Treatment.noteBytes ? s : old
    }

    /// "4", "4.5" (insulin in 0.1u steps) or whole grams; `.invalid` past the limits, at 0 or 0 after rounding.
    public static func parse(_ s: String, grams: Bool) -> Amount {
        let t = s.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".")
        if t.isEmpty { return .empty }
        guard let v = Double(t), v.isFinite, v > 0, v <= (grams ? maxGrams : maxUnits) else { return .invalid }
        let a = grams ? v.rounded() : (v * 10).rounded() / 10
        return a > 0 ? .value(a) : .invalid
    }

    /// Typed minutes ago → seconds ago (nil inside `.valid` = now); `.invalid` when empty or a day or more.
    public static func minutesAgo(_ s: String) -> MinutesAgo {
        guard let m = Int(s.trimmingCharacters(in: .whitespaces)), (0...maxAgoMinutes).contains(m) else {
            return .invalid
        }
        return .valid(m == 0 ? nil : m * 60)
    }

    public enum MinutesAgo: Equatable, Sendable {
        case invalid
        case valid(Int?)
    }

    /// The picked hour:minute today, or yesterday when that is later than `now` (entries are logged after the fact,
    /// never ahead); nil = within the current minute, i.e. now. Epoch seconds.
    public static func pick(hour: Int, minute: Int, now: Date, calendar: Calendar = .current) -> Int64? {
        guard var p = calendar.date(bySettingHour: hour, minute: minute, second: 0, of: now) else { return nil }
        if p > now, let y = calendar.date(byAdding: .day, value: -1, to: p) { p = y }
        return now.timeIntervalSince(p) < 60 ? nil : Int64(p.timeIntervalSince1970)
    }

    /// The four fields as typed.
    public struct Fields: Equatable, Sendable {
        public var rapid = "", long = "", carbs = "", note = ""

        public init(rapid: String = "", long: String = "", carbs: String = "", note: String = "") {
            self.rapid = rapid
            self.long = long
            self.carbs = carbs
            self.note = note
        }

        public var amounts: [(kind: Treatment.Kind, amount: Amount)] {
            [
                (.rapid, TreatmentForm.parse(rapid, grams: false)), (.long, TreatmentForm.parse(long, grams: false)),
                (.carbs, TreatmentForm.parse(carbs, grams: true)),
            ]
        }

        public var hasInvalidAmount: Bool { amounts.contains { $0.amount == .invalid } }

        /// The entries Save creates as (kind, amount, note) in kind order; nil = an invalid amount (Save disabled).
        public var entries: [(kind: Treatment.Kind, amount: Double, note: String)]? {
            if hasInvalidAmount { return nil }
            var out = amounts.compactMap { k, a -> (kind: Treatment.Kind, amount: Double, note: String)? in
                if case .value(let v) = a { return (k, v, "") }
                return nil
            }
            let n = note.trimmingCharacters(in: .whitespacesAndNewlines)
            if !n.isEmpty { out.append((.note, 0, Treatment.truncated(n))) }
            return out
        }

        public var canSave: Bool { entries.map { !$0.isEmpty } ?? false }

        /// The entries as treatments written by this Mac at `t` (epoch s), version `u` (epoch ms).
        public func treatments(at t: Int64, u: Int64) -> [Treatment] {
            (entries ?? []).map { Treatment(k: $0.kind, a: $0.amount, t: t, n: $0.note, u: u, o: .mac) }
        }
    }
}
