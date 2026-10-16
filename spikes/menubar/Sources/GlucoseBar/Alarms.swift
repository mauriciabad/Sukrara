// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit
import UserNotifications

/// Follower alarms on the Mac, same rules as the Android follower (docs/06): LOW at ≤ 65, HIGH at ≥ 185 (repeated at
/// most every 30 min while it stays there, cleared when back in range), and "no data" after 20 min without a reading.
/// Checked on each new reading plus one 60 s timer for "no data" (only while the Mac has had data at all).
/// Default on; the menu item toggles it (`defaults write app.mauri.sukrara.poc alarms -bool NO`).
/// Readings older than 15 min (backfill) never alarm.
@MainActor final class Alarms: NSObject {
    var low = 65.0, high = 185.0, noDataMin = 20.0
    private(set) var enabled: Bool
    let menuItem = NSMenuItem(title: "Alarms (low 65 · high 185 · no data 20 min)", action: nil, keyEquivalent: "")
    private var kind: String?, kindAt = Date.distantPast
    private var latest: Date?
    private var noDataAlerted = false
    private var timer: Timer?
    private let center: UNUserNotificationCenter? = Bundle.main.bundleIdentifier == nil ? nil : .current()

    override init() {
        let d = UserDefaults.standard
        enabled = d.object(forKey: "alarms") == nil || d.bool(forKey: "alarms")
        super.init()
        menuItem.target = self
        menuItem.action = #selector(toggle)
        menuItem.state = enabled ? .on : .off
        if enabled { authorize() }
    }

    @objc private func toggle() {
        enabled.toggle()
        menuItem.state = enabled ? .on : .off
        UserDefaults.standard.set(enabled, forKey: "alarms")
        log("alarms \(enabled ? "on" : "off")")
        if enabled { authorize() } else { clear("hilo"); clear("nodata") }
    }

    private func authorize() {
        center?.requestAuthorization(options: [.alert, .sound]) { ok, err in
            log("alarms: notifications \(ok ? "allowed" : "not allowed")\(err.map { " (\($0.localizedDescription))" } ?? "")")
        }
    }

    /// The newest reading the Mac knows (any transport).
    func onLatest(_ r: Reading) {
        if latest.map({ r.date > $0 }) ?? true { latest = r.date }
        if timer == nil {
            let t = Timer(timeInterval: 60, repeats: true) { _ in MainActor.assumeIsolated { self.checkNoData() } }
            RunLoop.main.add(t, forMode: .common)
            timer = t
        }
        if noDataAlerted, Date().timeIntervalSince(r.date) < noDataMin * 60 { noDataAlerted = false; clear("nodata") }
        guard enabled, Date().timeIntervalSince(r.date) < 15 * 60 else { return }
        let k: String? = r.mgdl <= low ? "LOW" : r.mgdl >= high ? "HIGH" : nil
        guard let k else {
            if kind != nil { kind = nil; clear("hilo") }
            return
        }
        if k != kind || Date().timeIntervalSince(kindAt) > 30 * 60 {
            kind = k
            kindAt = Date()
            post("hilo", "\(k) glucose: \(Int(r.mgdl)) mg/dL", "Threshold \(Int(k == "LOW" ? low : high)) mg/dL")
        }
    }

    private func checkNoData() {
        guard enabled, !noDataAlerted, let latest else { return }
        let age = Date().timeIntervalSince(latest)
        guard age >= noDataMin * 60 else { return }
        noDataAlerted = true
        post("nodata", "No glucose data", "Nothing received for \(Int(age / 60)) min")
    }

    private func post(_ id: String, _ title: String, _ body: String) {
        log("ALARM \(title) — \(body)")
        guard let center else { NSSound.beep(); return }
        let c = UNMutableNotificationContent()
        c.title = title
        c.body = body
        c.sound = .default
        c.interruptionLevel = .timeSensitive
        center.add(UNNotificationRequest(identifier: id, content: c, trigger: nil))
    }

    private func clear(_ id: String) { center?.removeDeliveredNotifications(withIdentifiers: [id]) }
}
