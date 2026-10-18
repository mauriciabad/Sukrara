// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit

/// "Glucose Report…": the doctor's report (investigation 18, ReportView.swift) in its own window instead of 300 pt of
/// the menu — HIG "Menus": be mindful of menu length; a menu bar extra's menu is for the glance and frequent
/// commands. The window (HIG "Windows") keeps its position (frame autosave), closes with ⌘W, and carries its one
/// option, the period, right above what it changes (HIG "Settings" › Task-specific options), saved as `reportDays`.
@MainActor final class ReportWindow: NSObject {
    static let periods = [1, 7, 14, 30]
    private var window: NSWindow?
    private let report = ReportView(frame: NSRect(x: 0, y: 0, width: 368, height: 300))
    private let picker = NSSegmentedControl(labels: periods.map { $0 == 1 ? "1 day" : "\($0) days" },
                                            trackingMode: .selectOne, target: nil, action: nil)
    private var height: NSLayoutConstraint?
    var metricsOf: ((TimeInterval, Bands) -> GlucoseMetrics)?
    var bands: () -> Bands = { Bands() }

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
    func refresh() {
        guard let w = window else { return }
        let days = Self.days
        picker.selectedSegment = Self.periods.firstIndex(of: days) ?? 2
        report.bands = bands()
        report.days = days
        report.metrics = metricsOf?(TimeInterval(days) * 86400, report.bands)
        height?.constant = report.fittingHeight()
        w.contentView?.layoutSubtreeIfNeeded()
        w.setContentSize(w.contentView?.fittingSize ?? w.frame.size)
    }

    private func make() -> NSWindow {
        let w = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 400, height: 380), styleMask: [.titled, .closable, .miniaturizable],
                         backing: .buffered, defer: true)
        w.title = "Glucose Report"
        w.isReleasedWhenClosed = false
        // The report's colours were checked against the palette's ground (as on Android and the widgets).
        w.backgroundColor = NSColor(name: nil) { a in
            NSColor(cgColor: MacSurface.palette(dark: a.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua).bg) ?? .windowBackgroundColor
        }
        picker.target = self
        picker.action = #selector(pick)
        picker.setAccessibilityLabel("Report period")
        report.inset = NSEdgeInsets(top: 0, left: 0, bottom: 0, right: 0)
        let content = NSView()
        for v in [picker, report] as [NSView] { v.translatesAutoresizingMaskIntoConstraints = false; content.addSubview(v) }
        height = report.heightAnchor.constraint(equalToConstant: 300)
        NSLayoutConstraint.activate([
            picker.topAnchor.constraint(equalTo: content.topAnchor, constant: 14),
            picker.centerXAnchor.constraint(equalTo: content.centerXAnchor),
            report.topAnchor.constraint(equalTo: picker.bottomAnchor, constant: 14),
            report.leadingAnchor.constraint(equalTo: content.leadingAnchor, constant: 24),
            report.trailingAnchor.constraint(equalTo: content.trailingAnchor, constant: -24),
            report.bottomAnchor.constraint(equalTo: content.bottomAnchor, constant: -20),
            report.widthAnchor.constraint(equalToConstant: GlucoseReport.width),
            height!,
        ])
        w.contentView = content
        if !w.setFrameUsingName("SukraraReport") { w.center() }
        w.setFrameAutosaveName("SukraraReport")
        NSWorkspace.shared.notificationCenter.addObserver(forName: NSWorkspace.accessibilityDisplayOptionsDidChangeNotification,
                                                          object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.report.needsDisplay = true }
        }
        return w
    }

    @objc private func pick() {
        UserDefaults.standard.set(Self.periods[max(0, picker.selectedSegment)], forKey: "reportDays")
        refresh()
    }

    /// Evidence aid: `-reportSnapshot out.png`.
    func snapshot(to path: String) {
        show()
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
            guard let frame = self?.window?.contentView?.superview,
                  let rep = frame.bitmapImageRepForCachingDisplay(in: frame.bounds) else { return }
            frame.cacheDisplay(in: frame.bounds, to: rep)
            try? rep.representation(using: .png, properties: [:])?.write(to: URL(fileURLWithPath: path))
            log("report snapshot → \(path)")
        }
    }
}
