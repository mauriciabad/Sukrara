// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraCore
import SukraraTesting
import Testing

/// contracts/treatments/treatment-form.json: the same cases Android's TreatmentFormTest passes.
@Suite struct TreatmentFormModelTests {
    nonisolated(unsafe) static let root: [String: Any] = {
        let data = FileManager.default.contents(atPath: Contracts.url("treatments/treatment-form.json").path)!
        return try! JSONSerialization.jsonObject(with: data) as! [String: Any]
    }()

    static func list(_ key: String) -> [[String: Any]] { root[key] as! [[String: Any]] }

    @Test func limitsMatchTheContract() {
        let l = Self.root["limits"] as! [String: Double]
        #expect(l["units"] == TreatmentForm.maxUnits)
        #expect(l["grams"] == TreatmentForm.maxGrams)
        #expect(Int(l["noteBytes"]!) == Treatment.noteBytes)
    }

    @Test func forms() {
        for c in Self.list("forms") {
            let f = TreatmentForm.Fields(
                rapid: c["rapid"] as! String, long: c["long"] as! String, carbs: c["carbs"] as! String,
                note: c["note"] as! String)
            let name = c["name"] as! String
            if c["entries"] is NSNull {
                #expect(f.entries == nil, "\(name)")
                #expect(!f.canSave, "\(name)")
                continue
            }
            let want = (c["entries"] as! [[Any]]).map { e in
                "\(e[0] as! String) \((e[1] as! NSNumber).doubleValue) \(e[2] as! String)"
            }
            let got = (f.entries ?? []).map { "\($0.kind.rawValue) \($0.amount) \($0.note)" }
            #expect(got == want, "\(name)")
            #expect(f.canSave == !want.isEmpty, "\(name)")
        }
    }

    @Test func filters() {
        for c in Self.list("filters") {
            #expect(TreatmentForm.filterAmount(c["in"] as! String) == c["out"] as! String)
        }
        for c in Self.list("minute_filters") {
            #expect(TreatmentForm.filterMinutes(c["in"] as! String) == c["out"] as! String)
        }
    }

    @Test func minutesAgo() {
        for c in Self.list("minutes") {
            let got = TreatmentForm.minutesAgo(c["typed"] as! String)
            if c["invalid"] as? Bool == true {
                #expect(got == .invalid, "\(c)")
            } else {
                #expect(got == .valid((c["ago_s"] as? NSNumber)?.intValue), "\(c)")
            }
        }
    }

    @Test func timePicker() {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = .current
        for c in Self.list("times") {
            let hms = (c["now"] as! String).split(separator: ":").map { Int($0)! }
            let pick = (c["pick"] as! String).split(separator: ":").map { Int($0)! }
            let now = cal.date(
                from: DateComponents(year: 2026, month: 6, day: 15, hour: hms[0], minute: hms[1], second: hms[2]))!
            let t = TreatmentForm.pick(hour: pick[0], minute: pick[1], now: now, calendar: cal)
            let ago = t.map { Int(now.timeIntervalSince1970) - Int($0) }
            #expect(ago == (c["ago_s"] as? NSNumber)?.intValue, "\(c)")
        }
    }

    @Test func savedEntriesAreTheMacs() {
        let ts = TreatmentForm.Fields(rapid: "4", carbs: "30").treatments(at: 100, u: 100_000)
        #expect(ts.map(\.k) == [.rapid, .carbs])
        #expect(ts.allSatisfy { $0.o == .mac && $0.t == 100 && !$0.del })
    }
}
