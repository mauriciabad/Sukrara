// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
#if DEBUG
    import AppKit
    import ServiceManagement
    import SukraraCore
    import SukraraGraph
    import SwiftUI

    /// `sukrara-snap windows <dir>`: every window state with synthetic data, light + dark, default + large text.
    /// Offline: a scratch defaults suite, no runtime behind the models (actions nil).
    public enum WindowSnapshots {
        /// Renders the windows' text in one language (`es`), whatever the Mac's.
        public static func useLanguage(_ language: String) { L10n.use(language: language) }

        public static func render(to dir: URL) throws -> Int {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            var n = 0
            for (name, view) in scenes() {
                for dark in [false, true] {
                    for large in [false, true] {
                        let v =
                            view
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
            doneModel.flow = SetupFlow(states: [
                .welcome: .done, .follow: .skipped, .alerts: .done, .notifications: .done, .openAtLogin: .done,
            ])
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
            let following = follow(
                .following(wearer: "Marta", lastReading: Date().addingTimeInterval(-180), canWrite: true))
            let settings = SettingsModel(store: store, follow: following, version: "0.12.0")
            out.append(
                (
                    "settings-general",
                    AnyView(
                        SettingsView(
                            model: settings, loginItem: LoginItem(service: SnapLogin(status: .enabled)),
                            updateResult: nil, checkForUpdates: {}, openFollow: {}
                        ).frame(width: 560, height: 640))
                ))
            out.append(
                (
                    "settings-general-login-approval",
                    AnyView(
                        SettingsView(
                            model: settings, loginItem: LoginItem(service: SnapLogin(status: .requiresApproval)),
                            updateResult: nil, checkForUpdates: {}, openFollow: {}
                        ).frame(width: 560, height: 700))
                ))
            let refused = LoginItem(service: SnapLogin(status: .notRegistered, refuses: true))
            refused.set(true)
            out.append(
                (
                    "settings-general-login-failed",
                    AnyView(
                        SettingsView(
                            model: settings, loginItem: refused, updateResult: nil, checkForUpdates: {}, openFollow: {}
                        ).frame(width: 560, height: 700))
                ))
            out.append(
                (
                    "settings-general-login-unavailable",
                    AnyView(
                        SettingsView(
                            model: settings, loginItem: nil, updateResult: nil, checkForUpdates: {}, openFollow: {}
                        ).frame(width: 560, height: 700))
                ))
            let alerts = SettingsModel(store: store, follow: following, version: "0.12.0")
            alerts.pane = .alerts
            out.append(
                (
                    "settings-alerts",
                    AnyView(
                        SettingsView(
                            model: alerts, loginItem: nil, updateResult: nil, checkForUpdates: {}, openFollow: {}
                        ).frame(width: 560, height: 900))
                ))
            let mirrored = SettingsModel(
                store: store, follow: following,
                wearer: WearerSettings(
                    json: #"{"graph":{"low":72},"alerts":{"alerts":{"low":{"on":true,"mgdl":72}}}}"#, wearer: "Marta"),
                version: "0.12.0")
            mirrored.pane = .alerts
            out.append(
                (
                    "settings-alerts-mirrored",
                    AnyView(
                        SettingsView(
                            model: mirrored, loginItem: nil, updateResult: nil, checkForUpdates: {}, openFollow: {}
                        ).frame(width: 560, height: 900))
                ))
            // the Graph and Rings panes: this Mac's own (Custom, a few values changed) and the wearer's (read-only)
            func scratch() -> DefaultsSettingsStore {
                DefaultsSettingsStore(defaults: UserDefaults(suiteName: "app.mauri.sukrara.snap.\(UUID().uuidString)")!)
            }
            let ownStore = scratch()
            let own = SettingsModel(store: ownStore, follow: following, version: "0.12.0")
            own.settingsFrom = .custom
            own.updateGraph {
                $0.lines = [100, 250]
                $0.ringCount = 3
                $0.ringOrder = [.veryLow, .low, .tight, .target, .high, .veryHigh]
            }
            own.setPlaceHours(24, .widget)
            let marta = WearerSettings(
                json: #"{"graph":{"tight":130,"defaultHours":6,"lines":"","lastPlace":"END"}}"#, wearer: "Marta")
            let fromWearer = SettingsModel(store: scratch(), follow: following, wearer: marta, version: "0.12.0")
            for (name, pane, model, height) in [
                ("settings-graph", SettingsModel.Pane.graph, own, 1500.0),
                ("settings-graph-wearer", .graph, fromWearer, 900),
                ("settings-rings", .rings, own, 1300),
                ("settings-rings-wearer", .rings, fromWearer, 700),
                ("settings-following", .following, own, 560),
            ] {
                model.pane = pane
                let m = SettingsModel(store: model.store, follow: model.follow, wearer: model.wearer, version: "0.12.0")
                m.pane = pane
                out.append(
                    (
                        name,
                        AnyView(
                            SettingsView(
                                model: m, loginItem: nil, updateResult: nil, checkForUpdates: {}, openFollow: {}
                            ).frame(width: 600, height: height))
                    ))
            }
            let general = SettingsModel(store: ownStore, follow: following, wearer: marta, version: "0.12.0")
            general.pane = .general
            out.append(
                (
                    "settings-general-custom",
                    AnyView(
                        SettingsView(
                            model: general, loginItem: nil, updateResult: nil, checkForUpdates: {}, openFollow: {}
                        ).frame(width: 600, height: 860))
                ))
            out.append(
                (
                    "treatment-add",
                    AnyView(
                        AddTreatmentView(
                            focus: .carbs,
                            quiet: { _, carbs, _ in carbs ? L10n.t("Lows quiet for \(Durations.text(15))") : nil },
                            save: { _, _ in }, cancel: {}))
                ))
            let t0 = Int64(Date().timeIntervalSince1970)
            let rows: [TreatmentsModel.Row] = [
                .init(treatment: Treatment(id: "1", k: .rapid, a: 4.5, t: t0 - 600, u: 1, o: .mac), delivery: .sending),
                .init(treatment: Treatment(id: "2", k: .carbs, a: 30, t: t0 - 900, u: 1, o: .mac), delivery: .failed),
                .init(treatment: Treatment(id: "3", k: .long, a: 12, t: t0 - 7200, u: 1, o: .phone), delivery: nil),
                .init(
                    treatment: Treatment(id: "4", k: .note, a: 0, t: t0 - 9000, n: "Walk", u: 1, o: .mac),
                    delivery: .accepted),
                .init(
                    treatment: Treatment(
                        id: "5", k: .rapid, a: 8.5, t: t0 - 2400, u: 1, o: .phone, pr: 1.5, pen: "NP1",
                        pd: [
                            .init(t: t0 - 2580, a: 1, p: true), .init(t: t0 - 2520, a: 0.5, p: true),
                            .init(t: t0 - 2400, a: 8.5),
                        ]),
                    delivery: nil),
            ]
            let tm = TreatmentsModel(rows: rows, canWrite: true, actions: nil)
            out.append(("treatments", AnyView(TreatmentsView(model: tm, add: {}).frame(width: 460, height: 380))))
            out.append(
                (
                    "treatments-empty",
                    AnyView(
                        TreatmentsView(model: TreatmentsModel(rows: [], canWrite: false, actions: nil), add: {}).frame(
                            width: 460, height: 300))
                ))
            // the report over a synthetic fortnight, a thin week (low coverage) and nothing
            let plan = LaunchPlan(mode: .normal, services: .none, data: .isolated(root: URL(fileURLWithPath: "/tmp")))
            for (name, days) in [("report", 14), ("report-low-coverage", 3), ("report-empty", 0)] {
                let app = AppModel(plan: plan)
                let now = Date().timeIntervalSince1970
                app.apply(
                    readings: days == 0
                        ? []
                        : RealisticSynthetic.readings(until: now - 60, days: days).map {
                            Reading(t: Date(timeIntervalSince1970: $0.t), mgdl: $0.sgv, rate: $0.rate)
                        })
                let report = ReportModel(store: store, app: app)
                report.days = days == 3 ? 7 : 14
                out.append((name, AnyView(ReportView(model: report))))
            }
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

    /// A login item service in a fixed state (snapshots never touch the real login items).
    struct SnapLogin: LoginService {
        var status: SMAppService.Status
        var refuses = false
        struct Refused: LocalizedError {
            var errorDescription: String? { "Operation not permitted" }
        }
        func register() throws { if refuses { throw Refused() } }
        func unregister() throws {}
    }
#endif
