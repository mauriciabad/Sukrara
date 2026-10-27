// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit
import SwiftUI

/// "Glucose Report…": the doctor's report (investigation 18, ReportView.swift) in a normal window rather than the
/// menu bar panel (HIG "The menu bar": the panel is for the glance; HIG "Windows"). The period sits with the report
/// (HIG "Settings" › Task-specific options), saved as `reportDays`. Export CSV… under it opens a save panel with the
/// export's own Period (This sensor, 7 / 14 / 30 days, All time; GlucoseCsv.swift), starting on the report's. The treatment history has its own window (TreatmentsWindow.swift). Position remembered (frame
/// autosave), closes with ⌘W.
@MainActor final class ReportWindow: NSObject {
    nonisolated static let periods = [1, 7, 14, 30]
    let model = ReportModel()
    private var window: NSWindow?

    /// The report's window, days (`reportDays`, 1|7|14|30, default 14).
    static var days: Int {
        let d = UserDefaults.standard.integer(forKey: "reportDays")
        return periods.contains(d) ? d : 14
    }

    var isVisible: Bool { window?.isVisible == true }

    func show() {
        let w = window ?? make()
        window = w
        refresh()
        NSApp.activate(ignoringOtherApps: true)
        w.makeKeyAndOrderFront(nil)
    }

    /// New readings or settings: redraw while open.
    func refresh() { model.reload() }

    private func make() -> NSWindow {
        let host = NSHostingController(rootView: ReportWindowView(model: model))
        host.sizingOptions = [.preferredContentSize]
        let w = NSWindow(contentViewController: host)
        w.styleMask = [.titled, .closable, .miniaturizable]
        w.isReleasedWhenClosed = false
        w.title = "Glucose Report"
        if !w.setFrameUsingName("SukraraReport") { w.center() }
        w.setFrameAutosaveName("SukraraReport")
        NSWorkspace.shared.notificationCenter.addObserver(forName: NSWorkspace.accessibilityDisplayOptionsDidChangeNotification,
                                                          object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.model.reload() }
        }
        return w
    }

    /// Evidence aid: `-reportSnapshot <dir>` → report.png (screencapture -l).
    func snapshot(to dir: String) {
        show()
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) { [weak self] in
            guard let n = self?.window?.windowNumber else { return }
            let p = Process()
            p.executableURL = URL(fileURLWithPath: "/usr/sbin/screencapture")
            p.arguments = ["-x", "-o", "-l\(n)", "\(dir)/report.png"]
            try? p.run(); p.waitUntilExit()
            log("report snapshot → \(dir)/report.png")
        }
    }
}

@MainActor final class ReportModel: ObservableObject {
    @Published var days = ReportWindow.days {
        didSet { UserDefaults.standard.set(days, forKey: "reportDays"); reload() }
    }
    @Published private(set) var metrics: GlucoseMetrics?
    @Published private(set) var bands = Bands()
    var metricsOf: ((TimeInterval, Bands) -> GlucoseMetrics)?
    var bandsOf: () -> Bands = { Bands() }
    /// Export CSV… under the report (GlucoseCsv.save, given the report's period to start on).
    var onExport: ((Int) -> Void)?

    func reload() {
        bands = bandsOf()
        metrics = metricsOf?(TimeInterval(days) * 86400, bands)
    }
}

struct ReportWindowView: View {
    @ObservedObject var model: ReportModel

    var body: some View {
        report.frame(width: 420)
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
            // the save panel has the export's own period, starting on this one
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
