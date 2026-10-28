// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import CoreGraphics
import Foundation
import SukraraCore

/// The doctor's report for the last `days` (LibreView's AGP page, Android's ReportCard): time in the five ranges with
/// each share's consensus goal, average glucose, GMI, variability (CV) and the share of the window with sensor data.
/// Every number and its text comes from here (`GlucoseMetrics`, `ShareText`, `Goal`); the window only lays it out.
public struct GlucoseReport: Equatable, Sendable {
    /// The report's periods (days), the window's segmented control.
    public static let periods = [1, 7, 14, 30]
    public static let defaultDays = 14

    /// One line of the time-in-ranges table, high on top.
    public struct Row: Equatable, Sendable, Identifiable {
        public enum Kind: String, Sendable {
            case veryHigh, high, above, target, tight, low, veryLow, below
        }
        public var kind: Kind
        public var id: String { kind.rawValue }
        /// The range in the user's unit ("> 250", "181–250"); empty for the group totals.
        public var range: String
        /// The share as a percentage's number ("64", "5.2"), without "%".
        public var share: String
        /// The goal ("< 5%") and whether it's met; nil for rows without a goal.
        public var goal: String?
        public var met: Bool?
        /// A group total (above / target / below): bold.
        public var strong: Bool
    }

    /// A piece of the stacked bar, very high on top: its band and share (0…1).
    public struct BarPiece: Equatable, Sendable {
        public var band: Band
        public var share: Double
    }

    /// A summary number with its goal: average glucose, GMI, CV.
    public struct Metric: Equatable, Sendable {
        public var value: String
        public var goal: String
        public var met: Bool
    }

    public var days: Int
    /// No reading in the window.
    public var empty: Bool
    /// "86": the share of the window with sensor data, as a percentage's number.
    public var coverage: String
    /// Less than 70 % of the window has data: the numbers aren't representative.
    public var lowCoverage: Bool
    public var rows: [Row]
    public var bar: [BarPiece]
    public var average: Metric
    public var gmi: Metric
    public var cv: Metric

    /// The report over `readings` for the `days` before `now`, with the user's ranges and unit.
    public static func of(
        _ readings: [GraphReading], days: Int, now: TimeInterval, config: GraphConfig, unit: GlucoseUnit
    ) -> GlucoseReport {
        let b = config.bands.sane()
        let m = GlucoseMetrics.of(readings, from: now - TimeInterval(days) * 86400, to: now, bands: b)
        let t = m.tir
        func u(_ mg: Int) -> String { unit.format(mg) }
        func row(_ kind: Row.Kind, _ range: String, _ share: Double, _ goal: Goal?, strong: Bool = false) -> Row {
            Row(
                kind: kind, range: range, share: ShareText.of(share, goal), goal: goal?.text,
                met: goal.flatMap { $0.metShare(share) }, strong: strong)
        }
        let rows = [
            row(.veryHigh, "> \(u(b.veryHigh))", t.veryHigh, .veryHigh),
            row(.high, "\(u(b.high + 1))–\(u(b.veryHigh))", t.high, nil),
            row(.above, "", t.above, .above, strong: true),
            row(.target, "\(u(b.low))–\(u(b.high))", t.inRange, .inRange, strong: true),
            row(.tight, "\(u(b.low))–\(u(b.tight))", t.tight, nil),
            row(.low, "\(u(b.veryLow))–\(u(b.low - 1))", t.low, nil),
            row(.veryLow, "< \(u(b.veryLow))", t.veryLow, .veryLow),
            row(.below, "", t.below, .below, strong: true),
        ]
        let order: [Band] = [.veryHigh, .high, .target, .tight, .low, .veryLow]
        let mean = Int(m.mean.rounded())
        return GlucoseReport(
            days: days, empty: m.empty, coverage: ShareText.coverage(t.coverage),
            lowCoverage: !m.empty && t.coverage < Goal.minCoverage, rows: rows,
            bar: order.map { BarPiece(band: $0, share: t.share($0)) }.filter { $0.share > 0 },
            average: Metric(
                value: "\(u(mean)) \(unit.symbol)",
                goal: unit == .mgdl ? Goal.average.text : "< \(u(154)) \(unit.symbol)", met: Goal.average.met(m)),
            gmi: Metric(
                value: String(format: "%.1f %%  ·  %d mmol/mol", m.gmi, Int(m.gmiMmolMol.rounded())),
                goal: Goal.gmi.text + " · < 53", met: Goal.gmi.met(m)),
            cv: Metric(value: String(format: "%.1f %%", m.cv), goal: Goal.cv.text, met: Goal.cv.met(m)))
    }

    /// A band's colour on the card's palette (the bar).
    public static func color(_ band: Band, dark: Bool) -> CGColor { GraphPalette.of(dark: dark).of(band) }
}
