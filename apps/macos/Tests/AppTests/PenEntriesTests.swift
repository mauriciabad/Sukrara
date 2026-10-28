// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraCore
import Testing

@testable import SukraraApp

/// Pen entries on the Mac: the Treatments window's lines and Add Treatment's *Already logged by the pen?*.
@Suite struct PenEntriesTests {
    let t0: Int64 = 1_800_000_000

    func pen(_ k: Treatment.Kind = .rapid, at t: Int64, del: Bool = false) -> Treatment {
        Treatment(
            id: "p\(t)", k: k, a: 8.5, t: t, u: 1, del: del, o: .phone, pr: 1.5, pen: "NP1",
            pd: [.init(t: t - 60, a: 1.5, p: true), .init(t: t, a: 8.5)])
    }

    func typed(_ k: Treatment.Kind, at t: Int64) -> Treatment { Treatment(k: k, a: 8, t: t, u: 2, o: .mac) }

    @Test func aTypedInsulinNearAPenEntryOfItsKindAsksFirst() {
        let model = TreatmentsModel(
            rows: [.init(treatment: pen(at: t0 - 1800), delivery: nil)], canWrite: true, actions: nil)
        #expect(model.penDuplicate(of: [typed(.rapid, at: t0)])?.id == "p\(t0 - 1800)")
        // another kind, carbs only, or further than 45 min: no question
        #expect(model.penDuplicate(of: [typed(.long, at: t0)]) == nil)
        #expect(model.penDuplicate(of: [typed(.carbs, at: t0)]) == nil)
        #expect(model.penDuplicate(of: [typed(.rapid, at: t0 + 46 * 60)]) == nil)
        // a deleted pen entry or one typed by hand doesn't count
        let other = TreatmentsModel(
            rows: [
                .init(treatment: pen(at: t0, del: true), delivery: nil),
                .init(treatment: typed(.rapid, at: t0), delivery: nil),
            ], canWrite: true, actions: nil)
        #expect(other.penDuplicate(of: [typed(.rapid, at: t0)]) == nil)
    }

    @Test func penRowsSayWhereTheyCameFromAndThePriming() {
        let p = pen(at: t0)
        #expect(TreatmentsView.penLine(p) == "From the pen · 1.5u primed")
        var noPrime = p
        noPrime.pr = nil
        #expect(TreatmentsView.penLine(noPrime) == "From the pen")
        #expect(TreatmentsView.penLine(typed(.rapid, at: t0)) == nil)
        #expect(TreatmentsView.doseText(.init(t: t0, a: 1, p: true)) == "1u primed")
        #expect(TreatmentsView.doseText(.init(t: t0, a: 8.5)) == "8.5u")
        #expect(TreatmentsView.loggedBy(p) == "Read from the pen on the wearer's phone")
        #expect(AddTreatmentView.penLine(p).hasPrefix("8.5u rapid at "))
    }
}
