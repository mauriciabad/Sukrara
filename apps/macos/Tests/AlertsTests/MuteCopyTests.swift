// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraAlerts
import SukraraTesting
import Testing

/// Every case of `contracts/alerts/copy.json` (en), the file Android's MuteCopyTest reads too.
@Suite struct MuteCopyTests {
    struct Case: Decodable, Sendable, CustomTestStringConvertible {
        var state: String
        var side: String?
        var treatment: String?
        var leftMin: Int?
        var intervalMin: Int?
        var min: Int?
        var until: String?
        var en: String
        var testDescription: String { "\(state) \(side ?? "") → \(en)" }
    }

    struct File: Decodable { var cases: [Case] }

    static func cases() -> [Case] {
        guard let data = try? Data(contentsOf: Contracts.url("alerts/copy.json")) else { return [] }
        return (try? JSONDecoder().decode(File.self, from: data).cases) ?? []
    }

    @Test func copyFileIsFound() { #expect(Self.cases().count >= 15) }

    @Test(arguments: cases())
    func line(_ c: Case) throws {
        let side = c.side.flatMap(AlertSide.init(rawValue:))
        let mine: String? =
            switch c.state {
            case "side_muted": side.map { MuteCopy.sideMuted($0, leftMin: c.leftMin ?? -1) }
            case "side_after":
                side.flatMap { s in
                    c.treatment.flatMap(TreatmentKind.init(rawValue:)).map {
                        MuteCopy.sideAfter(s, treatment: $0, leftMin: c.leftMin ?? -1)
                    }
                }
            case "side_falling": side.map(MuteCopy.sideFalling)
            case "side_less": side.map { MuteCopy.sideLess($0, intervalMin: c.intervalMin ?? -1) }
            case "all_timed": MuteCopy.allTimed(leftMin: c.leftMin ?? -1)
            case "all_forever": MuteCopy.allForever
            case "notif_title": side.map(MuteCopy.notificationTitle)
            case "notif_text": MuteCopy.notificationText(until: c.until ?? "")
            case "pill": MuteCopy.pill(leftMin: c.leftMin ?? -1)
            case "pill_forever": MuteCopy.pillForever
            case "choice": MuteCopy.choice(c.min ?? -1)
            case "choice_forever": MuteCopy.choiceForever
            default: nil
            }
        #expect(mine == c.en)
    }

    @Test func allLineCountsDown() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let end = now.addingTimeInterval(72 * 60)
        #expect(MuteCopy.allLine(until: end, now: now) == "Alerts muted · 1 h 12 min left")
        #expect(MuteCopy.allLine(until: end, now: now.addingTimeInterval(71 * 60 + 1)) == "Alerts muted · 1 min left")
        #expect(MuteCopy.allLine(until: end, now: end) == nil)
        #expect(MuteCopy.allLine(until: muteAllForever, now: now) == MuteCopy.allForever)
    }

    @Test func quietReasonLines() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let in12 = now.addingTimeInterval(12 * 60)
        #expect(
            MuteCopy.line(.treatment(.carbs, at: now, until: in12), side: .low, now: now)
                == "Low alerts muted after carbs · 12 min left")
        #expect(
            MuteCopy.line(.fallingBack(inMinutes: 8), side: .high, now: now)
                == "High alerts muted while falling back into range")
        #expect(MuteCopy.line(.sideMuted(until: in12), side: .noData, now: now) == "No data alerts muted · 12 min left")
    }
}
