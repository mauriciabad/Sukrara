// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit
import SwiftUI

/// "Glucose Report…": the doctor's report (investigation 18, ReportView.swift) and the treatment history, in a
/// normal window rather than the menu bar panel (HIG "The menu bar": the panel is for the glance; HIG "Windows").
/// Two views switched by a segmented control at the top (the window's title follows it); the report's period sits
/// with the report (HIG "Settings" › Task-specific options), saved as `reportDays`. The Treatments list replaces the
/// menu's old 24 h rows: the last 7 days, newest first, each with its delivery state; Delete (context menu or ⌫)
/// when this Mac may log. Export CSV… under the report saves the report's period (GlucoseCsv.swift). Position remembered (frame autosave), closes with ⌘W.
@MainActor final class ReportWindow: NSObject {
    static let periods = [1, 7, 14, 30]
    let model = HistoryModel()
    private var window: NSWindow?

    /// The report's window, days (`reportDays`, 1|7|14|30, default 14).
    static var days: Int {
        let d = UserDefaults.standard.integer(forKey: "reportDays")
        return periods.contains(d) ? d : 14
    }

    var isVisible: Bool { window?.isVisible == true }

    func show(_ tab: HistoryModel.Tab = .report) {
        let w = window ?? make()
        window = w
        model.tab = tab
        refresh()
        NSApp.activate(ignoringOtherApps: true)
        w.makeKeyAndOrderFront(nil)
    }

    /// New readings, treatments or settings: redraw while open.
    func refresh() { model.reload() }

    private func make() -> NSWindow {
        let host = NSHostingController(rootView: HistoryView(model: model))
        host.sizingOptions = [.preferredContentSize]
        let w = NSWindow(contentViewController: host)
        w.styleMask = [.titled, .closable, .miniaturizable]
        w.isReleasedWhenClosed = false
        w.titlebarAppearsTransparent = false
        model.onTitle = { [weak w] t in w?.title = t }
        w.title = model.tab.title
        if !w.setFrameUsingName("SukraraReport") { w.center() }
        w.setFrameAutosaveName("SukraraReport")
        NSWorkspace.shared.notificationCenter.addObserver(forName: NSWorkspace.accessibilityDisplayOptionsDidChangeNotification,
                                                          object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.model.reload() }
        }
        return w
    }

    /// Evidence aid: `-reportSnapshot <dir>` → report.png and treatments.png (screencapture -l).
    func snapshot(to dir: String) {
        show(.report)
        func shot(_ name: String, then: (() -> Void)? = nil) {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) { [weak self] in
                guard let n = self?.window?.windowNumber else { return }
                let p = Process()
                p.executableURL = URL(fileURLWithPath: "/usr/sbin/screencapture")
                p.arguments = ["-x", "-o", "-l\(n)", "\(dir)/\(name).png"]
                try? p.run(); p.waitUntilExit()
                log("report snapshot → \(dir)/\(name).png")
                then?()
            }
        }
        shot("report") { [weak self] in self?.model.tab = .treatments; shot("treatments") }
    }
}

@MainActor final class HistoryModel: ObservableObject {
    enum Tab: String, CaseIterable {
        case report, treatments
        var title: String { self == .report ? "Glucose Report" : "Treatments" }
    }
    @Published var tab = Tab.report { didSet { onTitle?(tab.title) } }
    @Published var days = ReportWindow.days {
        didSet { UserDefaults.standard.set(days, forKey: "reportDays"); reload() }
    }
    @Published private(set) var metrics: GlucoseMetrics?
    @Published private(set) var bands = Bands()
    @Published private(set) var rows: [Treatment] = []
    @Published private(set) var canDelete = false
    @Published var selection: String?

    var metricsOf: ((TimeInterval, Bands) -> GlucoseMetrics)?
    var bandsOf: () -> Bands = { Bands() }
    var treatments: () -> [Treatment] = { [] }
    var delivery: (Treatment) -> (Delivery, Date)? = { _ in nil }
    /// The wearer's name for the delivery wording ("On Ana's phone since 14:07").
    var who: () -> String = { "the wearer" }
    var details: (Treatment) -> [String] = { _ in [] }
    var canLog: () -> Bool = { false }
    /// A writer deletes only the entries it made (P4): the rest stay read-only here.
    var mayChange: (Treatment) -> Bool = { _ in true }
    var onDelete: ((String) -> Void)?
    /// Export… under the report: the report's period as a CSV (GlucoseCsv.save).
    var onExport: ((Int) -> Void)?
    var onTitle: ((String) -> Void)?

    func reload() {
        bands = bandsOf()
        metrics = metricsOf?(TimeInterval(days) * 86400, bands)
        let since = Int64(Date().timeIntervalSince1970) - 7 * 86400
        rows = treatments().filter { !$0.del && $0.t >= since }.sorted { $0.t > $1.t }
        canDelete = canLog()
    }

    func deletable(_ t: Treatment) -> Bool { canDelete && mayChange(t) }

    func delete(_ id: String?) {
        guard canDelete, let id, let t = rows.first(where: { $0.id == id }), mayChange(t) else { return }
        onDelete?(id)
        reload()
    }
}

struct HistoryView: View {
    @ObservedObject var model: HistoryModel

    var body: some View {
        VStack(spacing: 0) {
            Picker("View", selection: $model.tab) {
                Text("Report").tag(HistoryModel.Tab.report)
                Text("Treatments").tag(HistoryModel.Tab.treatments)
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .fixedSize()
            .padding(.vertical, 12)
            Divider()
            switch model.tab {
            case .report: report
            case .treatments: treatments
            }
        }
        .frame(width: 420)
    }

    private var report: some View {
        VStack(spacing: 14) {
            Picker("Period", selection: $model.days) {
                ForEach(ReportWindow.periods, id: \.self) { Text($0 == 1 ? "1 day" : "\($0) days").tag($0) }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .fixedSize()
            ReportRep(metrics: model.metrics, bands: model.bands, days: model.days)
                .frame(width: GlucoseReport.width, height: reportHeight)
            // the period above is the file's period too
            Button("Export CSV…") { model.onExport?(model.days) }
                .buttonStyle(.bordered)
                .buttonBorderShape(.capsule)
        }
        .padding(.top, 14).padding(.bottom, 20)
        .frame(maxWidth: .infinity)
    }

    private var reportHeight: CGFloat {
        let v = ReportView(frame: .zero)
        v.metrics = model.metrics
        v.bands = model.bands
        v.days = model.days
        v.inset = NSEdgeInsets(top: 0, left: 0, bottom: 0, right: 0)
        return v.fittingHeight()
    }

    @ViewBuilder private var treatments: some View {
        if model.rows.isEmpty {
            Text("No treatments in the last 7 days")
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, minHeight: 200)
        } else {
            List(selection: $model.selection) {
                ForEach(days, id: \.0) { day, rows in
                    Section(day) {
                        ForEach(rows, id: \.id) { t in
                            TreatmentRow(t: t, delivery: model.delivery(t), who: model.who())
                                .tag(t.id)
                                .help(model.details(t).joined(separator: "\n"))
                                .contextMenu {
                                    Button("Delete", role: .destructive) { model.delete(t.id) }.disabled(!model.deletable(t))
                                }
                        }
                    }
                }
            }
            .onDeleteCommand { model.delete(model.selection) }
            .frame(height: 420)
        }
    }

    /// Rows grouped by day: "Today", "Yesterday", "Thursday 16 October".
    private var days: [(String, [Treatment])] {
        let cal = Calendar.current
        var out: [(String, [Treatment])] = []
        for t in model.rows {
            let d = Date(timeIntervalSince1970: TimeInterval(t.t))
            let name = cal.isDateInToday(d) ? "Today" : cal.isDateInYesterday(d) ? "Yesterday" : d.formatted(.dateTime.weekday(.wide).day().month(.wide))
            if out.last?.0 == name { out[out.count - 1].1.append(t) } else { out.append((name, [t])) }
        }
        return out
    }
}

private struct TreatmentRow: View {
    let t: Treatment
    let delivery: (Delivery, Date)?
    let who: String
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: QuickLog.kinds.first { $0.k == t.k }?.symbol ?? "circle")
                .foregroundStyle(Color(cgColor: TreatmentMarks.color(t.k, dark: scheme == .dark)))
                .frame(width: 18)
            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                if !t.n.isEmpty, t.k != "note" { Text(t.n).font(.callout).foregroundStyle(.secondary) }
            }
            Spacer()
            // Delivery like Messages' "Delivered": a symbol, its sentence as tooltip and for VoiceOver.
            if let (d, at) = delivery {
                let text = d.text(who: who, at: at)
                Image(systemName: d.symbol)
                    .foregroundStyle(d == .delivered ? AnyShapeStyle(.green) : d == .late ? AnyShapeStyle(.orange) : AnyShapeStyle(.secondary))
                    .help(text)
                    .accessibilityLabel(text)
            }
            // Time of day, then the time since it like Android's lists (`12m`, `3h`), refreshed while shown.
            TimelineView(.periodic(from: .now, by: 30)) { ctx in
                HStack(spacing: 6) {
                    Text(Clock.time(Date(timeIntervalSince1970: TimeInterval(t.t))))
                        .foregroundStyle(.secondary)
                    Text(TreatmentMarks.duration(ctx.date.timeIntervalSince1970 - TimeInterval(t.t)))
                        .frame(minWidth: 34, alignment: .trailing)
                }
                .monospacedDigit()
            }
        }
        .padding(.vertical, 2)
    }

    private var title: String {
        switch t.k {
        case "rapid": "\(TreatmentMarks.amount(k: t.k, a: t.a) ?? "") rapid insulin"
        case "long": "\(TreatmentMarks.amount(k: t.k, a: t.a) ?? "") long insulin"
        case "carbs": "\(TreatmentMarks.amount(k: t.k, a: t.a) ?? "") carbs"
        default: t.n.isEmpty ? "Note" : t.n
        }
    }
}

private struct ReportRep: NSViewRepresentable {
    let metrics: GlucoseMetrics?
    let bands: Bands
    let days: Int

    func makeNSView(context: Context) -> ReportView {
        let v = ReportView(frame: NSRect(x: 0, y: 0, width: GlucoseReport.width, height: 300))
        v.inset = NSEdgeInsets(top: 0, left: 0, bottom: 0, right: 0)
        return v
    }

    func updateNSView(_ v: ReportView, context: Context) {
        v.metrics = metrics
        v.bands = bands
        v.days = days
        v.needsDisplay = true
    }
}
