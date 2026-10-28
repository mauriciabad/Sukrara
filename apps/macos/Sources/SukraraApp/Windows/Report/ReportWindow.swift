// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit
import Observation
import SukraraCore
import SukraraGraph
import SwiftUI
import UniformTypeIdentifiers

/// The Glucose Report window's state: the period (remembered in `reportDays`), the report over the wearer's readings
/// (`GlucoseReport`, every number from the renderer's stats) and what Export CSV… writes.
@Observable
final class ReportModel {
    var days: Int {
        didSet { store.set(days, for: MacSettings.reportDays) }
    }
    @ObservationIgnored private let store: any SettingsStoring
    @ObservationIgnored private let app: AppModel

    init(store: any SettingsStoring, app: AppModel) {
        self.store = store
        self.app = app
        let d = store.value(MacSettings.reportDays)
        days = GlucoseReport.periods.contains(d) ? d : GlucoseReport.defaultDays
    }

    /// The report at the app's "now" (it moves with each reading and freshness step).
    var report: GlucoseReport {
        GlucoseReport.of(
            app.cardData.readings, days: days, now: app.cardData.now, config: app.graph, unit: app.unit)
    }

    var hasReadings: Bool { !app.readings.isEmpty }

    /// The sensor the export's "This sensor" period covers (its start and end; the wearer's serial isn't shared).
    var sensor: GlucoseCsv.Sensor? {
        app.sensor.map { GlucoseCsv.Sensor(serial: "", start: Int64($0.start), end: Int64($0.end)) }
    }

    /// The export period the save panel starts on: the report's own when the export offers it.
    var startPeriod: GlucoseCsv.Period { ReportModel.period(days: days) }

    static func period(days: Int) -> GlucoseCsv.Period {
        switch days {
        case 7: .d7
        case 14: .d14
        case 30: .d30
        default: .d7
        }
    }

    /// The CSV for `period` at `now`, and its file name; nil when the period has no known range (no sensor).
    func csv(_ period: GlucoseCsv.Period, now: Date = Date()) -> (name: String, text: String)? {
        let readings = app.readings
        let treatments = app.treatments
        let oldest = (readings.map { Int64($0.t.timeIntervalSince1970) } + treatments.map(\.t)).min()
        let nowS = Int64(now.timeIntervalSince1970)
        guard let range = period.range(now: nowS, sensor: sensor, oldest: oldest) else { return nil }
        let zone = TimeZone.current
        let text = GlucoseCsv.write(
            readings: readings, treatments: treatments, from: range.from, to: range.to, zone: zone,
            generatedAt: nowS, sensor: sensor, unit: app.unit)
        return (GlucoseCsv.fileName(from: range.from, to: range.to, zone: zone), text)
    }
}

/// "Glucose Report": the period, the report, Export CSV… (a save panel with the export's own Period).
struct ReportView: View {
    @Bindable var model: ReportModel
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let report = model.report
        VStack(alignment: .leading, spacing: 14) {
            Picker(L10n.t("Period"), selection: $model.days) {
                ForEach(GlucoseReport.periods, id: \.self) { d in
                    Text(d == 1 ? L10n.t("1 day") : L10n.t("\(d) days")).tag(d)
                }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .frame(maxWidth: .infinity)
            HStack(alignment: .firstTextBaseline) {
                Text(L10n.t("Time in ranges")).font(.headline)
                Spacer()
                if !report.empty {
                    Text(L10n.t("Sensor active \(report.coverage + " %")")).font(.caption).foregroundStyle(.secondary)
                }
            }
            if report.empty {
                Text(L10n.t("No readings in this period yet."))
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, minHeight: 120)
            } else {
                if report.lowCoverage {
                    Label(
                        L10n.t(
                            "Only \(report.coverage + " %") of the period has sensor data; a report needs \("70 %")."),
                        systemImage: "exclamationmark.triangle"
                    )
                    .font(.caption)
                    .foregroundStyle(.orange)
                }
                ranges(report)
                Divider()
                metric(L10n.t("Average glucose"), report.average)
                metric(L10n.t("GMI"), report.gmi)
                metric(L10n.t("Variability (CV)"), report.cv)
                Text(L10n.t("Goals for type 1 and type 2 diabetes (international consensus)."))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            HStack {
                Spacer()
                Button(L10n.t("Export CSV…")) { ReportExport.run(model) }
                    .disabled(!model.hasReadings)
                    .help(model.hasReadings ? "" : L10n.t("No readings to export yet."))
            }
        }
        .padding(20)
        .frame(width: 420)
    }

    private func ranges(_ report: GlucoseReport) -> some View {
        HStack(alignment: .top, spacing: 12) {
            // the stacked bar, very high on top, each band's share of the height
            GeometryReader { geo in
                VStack(spacing: 2) {
                    ForEach(Array(report.bar.enumerated()), id: \.offset) { _, piece in
                        Rectangle()
                            .fill(Color(cgColor: GlucoseReport.color(piece.band, dark: scheme == .dark)))
                            .frame(
                                height: max(
                                    1,
                                    (geo.size.height - CGFloat(report.bar.count - 1) * 2) * CGFloat(piece.share)))
                    }
                    Spacer(minLength: 0)
                }
            }
            .frame(width: 10)
            .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                ForEach(report.rows) { row in
                    rangeRow(row)
                    if row.kind == .above || row.kind == .tight { Divider() }
                }
            }
        }
        .fixedSize(horizontal: false, vertical: true)
    }

    private func rangeRow(_ row: GlucoseReport.Row) -> some View {
        HStack(spacing: 8) {
            Text(Self.label(row.kind))
                .fontWeight(row.strong ? .semibold : .regular)
                .foregroundStyle(row.strong ? .primary : .secondary)
                .frame(width: 96, alignment: .leading)
            Text(row.range).font(.caption).foregroundStyle(.secondary).frame(width: 70, alignment: .leading)
            Text(row.share + "%")
                .font(row.strong ? .body.bold().monospacedDigit() : .callout.monospacedDigit())
                .frame(width: 54, alignment: .trailing)
            Spacer(minLength: 0)
            if let goal = row.goal, let met = row.met { GoalChip(text: goal, met: met) }
        }
        .accessibilityElement(children: .combine)
    }

    private func metric(_ label: String, _ m: GlucoseReport.Metric) -> some View {
        HStack {
            Text(label).fontWeight(.semibold).frame(width: 120, alignment: .leading)
            Text(m.value).monospacedDigit()
            Spacer(minLength: 0)
            GoalChip(text: m.goal, met: m.met)
        }
        .accessibilityElement(children: .combine)
    }

    static func label(_ kind: GlucoseReport.Row.Kind) -> String {
        switch kind {
        case .veryHigh: L10n.t("Very high")
        case .high: L10n.t("High")
        case .above: L10n.t("Above range")
        case .target: L10n.t("Target")
        case .tight: L10n.t("Very good")
        case .low: L10n.t("Low")
        case .veryLow: L10n.t("Very low")
        case .below: L10n.t("Below range")
        }
    }
}

/// A goal: green with ✓ when met, orange when missed (VoiceOver says which).
private struct GoalChip: View {
    let text: String
    let met: Bool

    var body: some View {
        Text((met ? "✓ " : "") + text)
            .font(.caption.weight(.semibold))
            .padding(.horizontal, 5)
            .padding(.vertical, 1)
            .foregroundStyle(met ? Color.green : Color.orange)
            .background(RoundedRectangle(cornerRadius: 4).fill((met ? Color.green : Color.orange).opacity(0.15)))
            .accessibilityLabel(met ? L10n.t("Goal \(text): met") : L10n.t("Goal \(text): missed"))
    }
}

/// Export CSV…: a save panel with a Period pop-up (This sensor when known, 7 / 14 / 30 days, All time), starting on
/// the report's period; the file name follows the period.
@MainActor
enum ReportExport {
    static func run(_ model: ReportModel) {
        let panel = NSSavePanel()
        panel.allowedContentTypes = [.commaSeparatedText]
        panel.canCreateDirectories = true
        let periods = GlucoseCsv.Period.offered(sensor: model.sensor)
        let popup = NSPopUpButton(frame: .zero, pullsDown: false)
        for p in periods { popup.addItem(withTitle: title(p)) }
        let start = periods.firstIndex(of: model.startPeriod) ?? 0
        popup.selectItem(at: start)
        let label = NSTextField(labelWithString: L10n.t("Period:"))
        let row = NSStackView(views: [label, popup])
        row.orientation = .horizontal
        row.edgeInsets = NSEdgeInsets(top: 8, left: 12, bottom: 8, right: 12)
        panel.accessoryView = row
        panel.nameFieldStringValue = model.csv(periods[start])?.name ?? "sukrara-glucose.csv"
        let follower = PopupFollower { index in
            if let name = model.csv(periods[index])?.name { panel.nameFieldStringValue = name }
        }
        popup.target = follower
        popup.action = #selector(PopupFollower.changed(_:))
        NSApp.activate()
        panel.begin { response in
            _ = follower  // kept alive while the panel is open
            guard response == .OK, let url = panel.url,
                let csv = model.csv(periods[popup.indexOfSelectedItem])
            else { return }
            do {
                try Data(csv.text.utf8).write(to: url, options: .atomic)
            } catch {
                let alert = NSAlert(error: error)
                alert.runModal()
            }
        }
    }

    static func title(_ p: GlucoseCsv.Period) -> String {
        switch p {
        case .sensor: L10n.t("This sensor")
        case .d7: L10n.t("Last 7 days")
        case .d14: L10n.t("Last 14 days")
        case .d30: L10n.t("Last 30 days")
        case .all: L10n.t("All time")
        }
    }
}

/// The Period pop-up's target: renames the file for the chosen period.
private final class PopupFollower: NSObject {
    let onChange: (Int) -> Void
    init(_ onChange: @escaping (Int) -> Void) { self.onChange = onChange }
    @objc func changed(_ sender: NSPopUpButton) { onChange(sender.indexOfSelectedItem) }
}
