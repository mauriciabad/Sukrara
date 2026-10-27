// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
#if DEBUG
    import AppKit
    import SukraraCore
    import SwiftUI

    /// `sukrara-snap windows <dir>`: every window state with synthetic data, light + dark, default + large text.
    /// Offline: a scratch defaults suite, no runtime behind the models (actions nil).
    public enum WindowSnapshots {
        public static func render(to dir: URL) throws -> Int {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            var n = 0
            for (name, view) in scenes() {
                for dark in [false, true] {
                    for large in [false, true] {
                        let v = view
                            .environment(\.colorScheme, dark ? .dark : .light)
                            .dynamicTypeSize(large ? .xxxLarge : .large)
                            .background(Color(nsColor: .windowBackgroundColor))
                        let file = dir.appending(path: "\(name)-\(dark ? "dark" : "light")\(large ? "-large" : "").png")
                        try png(v, appearance: dark ? .darkAqua : .aqua).write(to: file)
                        n += 1
                    }
                }
            }
            return n
        }

        private static func scenes() -> [(String, AnyView)] {
            let suite = "app.mauri.sukrara.snap.\(UUID().uuidString)"
            let store = DefaultsSettingsStore(defaults: UserDefaults(suiteName: suite)!)
            func follow(_ s: FollowStatus, failure: String? = nil, nearby: Bool = false) -> FollowModel {
                let m = FollowModel(status: s, actions: nil, macName: "Ana's MacBook")
                if nearby { m.nearby = NearbyPhone(wearer: "Marta") }
                return m
            }
            var out: [(String, AnyView)] = []
            for step in SetupFlow.Step.allCases {
                let m = SetupModel(store: store, follow: follow(.notFollowing))
                m.flow.open(step)
                out.append(("setup-\(step.rawValue)", AnyView(SetupView(model: m, close: {}))))
            }
            let doneModel = SetupModel(store: store, follow: follow(.notFollowing))
            doneModel.flow = SetupFlow(states: [.welcome: .done, .follow: .skipped, .alerts: .done, .notifications: .done, .openAtLogin: .done])
            out.append(("setup-done-skipped", AnyView(SetupView(model: doneModel, close: {}))))
            let nearbyModel = follow(.notFollowing, nearby: true)
            out.append(("follow-nearby", AnyView(FollowStep(follow: nearbyModel).padding().frame(width: 520))))
            let waiting = follow(.waiting(wearer: "Marta", code: nil), nearby: true)
            out.append(("follow-nearby-waiting", AnyView(FollowStep(follow: waiting).padding().frame(width: 520))))
            let bad = follow(.notFollowing)
            bad.linkText = "hello"
            Task { await bad.follow() }
            out.append(("follow-error-invalid", AnyView(FollowStep(follow: bad).padding().frame(width: 520))))
            let noQR = follow(.notFollowing)
            Task { await noQR.follow(qrText: nil) }
            out.append(("follow-error-noqr", AnyView(FollowStep(follow: noQR).padding().frame(width: 520))))
            let following = follow(.following(wearer: "Marta", lastReading: Date().addingTimeInterval(-180), canWrite: true))
            let settings = SettingsModel(store: store, follow: following, version: "0.90.0")
            out.append(("settings-general", AnyView(SettingsView(model: settings, openAtLogin: .constant(true), updateResult: nil, checkForUpdates: {}, openFollow: {}).frame(width: 560, height: 640))))
            let alerts = SettingsModel(store: store, follow: following, version: "0.90.0")
            alerts.pane = .alerts
            out.append(("settings-alerts", AnyView(SettingsView(model: alerts, openAtLogin: .constant(true), updateResult: nil, checkForUpdates: {}, openFollow: {}).frame(width: 560, height: 900))))
            let mirrored = SettingsModel(
                store: store, follow: following,
                wearer: WearerSettings(json: #"{"graph":{"low":72},"alerts":{"alerts":{"low":{"on":true,"mgdl":72}}}}"#, wearer: "Marta"),
                version: "0.90.0")
            mirrored.pane = .alerts
            out.append(("settings-alerts-mirrored", AnyView(SettingsView(model: mirrored, openAtLogin: .constant(true), updateResult: nil, checkForUpdates: {}, openFollow: {}).frame(width: 560, height: 900))))
            out.append(("treatment-add", AnyView(AddTreatmentView(focusCarbs: true, save: { _ in }, cancel: {}))))
            let t0 = Int64(Date().timeIntervalSince1970)
            let rows: [TreatmentsModel.Row] = [
                .init(treatment: Treatment(id: "1", k: .rapid, a: 4.5, t: t0 - 600, u: 1, o: .mac), delivery: .sending),
                .init(treatment: Treatment(id: "2", k: .carbs, a: 30, t: t0 - 900, u: 1, o: .mac), delivery: .failed),
                .init(treatment: Treatment(id: "3", k: .long, a: 12, t: t0 - 7200, u: 1, o: .phone), delivery: nil),
                .init(treatment: Treatment(id: "4", k: .note, a: 0, t: t0 - 9000, n: "Walk", u: 1, o: .mac), delivery: .accepted),
            ]
            let tm = TreatmentsModel(rows: rows, canWrite: true, actions: nil)
            out.append(("treatments", AnyView(TreatmentsView(model: tm, add: {}).frame(width: 460, height: 380))))
            out.append(("treatments-empty", AnyView(TreatmentsView(model: TreatmentsModel(rows: [], canWrite: false, actions: nil), add: {}).frame(width: 460, height: 300))))
            return out
        }

        /// Renders `view` inside an off-screen window: lists (NSTableView), tab strips and toolbars only draw their
        /// content once they're in a window.
        static func png(_ view: some View, appearance: NSAppearance.Name) throws -> Data {
            let host = NSHostingView(rootView: view)
            host.appearance = NSAppearance(named: appearance)
            let size = host.fittingSize
            let window = NSWindow(
                contentRect: CGRect(origin: CGPoint(x: -20_000, y: -20_000), size: size), styleMask: [.borderless],
                backing: .buffered, defer: false)
            window.isReleasedWhenClosed = false
            window.appearance = host.appearance
            window.contentView = host
            host.frame = CGRect(origin: .zero, size: size)
            window.orderFrontRegardless()
            for _ in 0..<4 {
                host.layoutSubtreeIfNeeded()
                host.displayIfNeeded()
                RunLoop.main.run(until: Date().addingTimeInterval(0.05))
            }
            defer { window.orderOut(nil) }
            guard let rep = host.bitmapImageRepForCachingDisplay(in: host.bounds) else { throw SnapError.render }
            host.cacheDisplay(in: host.bounds, to: rep)
            guard let data = rep.representation(using: .png, properties: [:]) else { throw SnapError.render }
            return data
        }

        enum SnapError: Error { case render }
    }
#endif
