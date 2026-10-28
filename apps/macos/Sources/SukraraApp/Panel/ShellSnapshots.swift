// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
#if DEBUG
    import AppKit
    import SukraraAlerts
    import SukraraCore
    import SukraraGraph
    import SukraraShare
    import SwiftUI

    /// `sukrara-snap shell <dir>`: the menu bar title and the panel in the states a person meets, light + dark,
    /// with synthetic data and an alert controller on in-memory fakes (nothing is posted, stored or scheduled).
    public enum ShellSnapshots {
        public static func render(to dir: URL) throws -> Int {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            var n = 0
            for (name, scene) in scenes() {
                for dark in [false, true] {
                    let view = scene.view
                        .environment(\.colorScheme, dark ? .dark : .light)
                        .background(Color(nsColor: .windowBackgroundColor))
                    let file = dir.appending(path: "\(name)-\(dark ? "dark" : "light").png")
                    try WindowSnapshots.png(view, appearance: dark ? .darkAqua : .aqua).write(to: file)
                    n += 1
                }
            }
            return n
        }

        private struct Scene { var view: AnyView }

        private static func scenes() -> [(String, Scene)] {
            let now = Date()
            let day = RealisticSynthetic.readings(until: now.timeIntervalSince1970 - 60, days: 1).map {
                Reading(t: Date(timeIntervalSince1970: $0.t), mgdl: Int($0.v.rounded()), rate: $0.rate)
            }
            func ending(_ mgdl: Int, rate: Double, age: TimeInterval = 60) -> [Reading] {
                day.filter { $0.t < now.addingTimeInterval(-age - 240) }
                    + [Reading(t: now.addingTimeInterval(-age), mgdl: mgdl, rate: rate)]
            }
            let suite = "app.mauri.sukrara.snap.\(UUID().uuidString)"
            let store = DefaultsSettingsStore(defaults: UserDefaults(suiteName: suite)!)
            let done = SetupFlow(states: [
                .welcome: .done, .follow: .done, .alerts: .done, .notifications: .done, .openAtLogin: .done,
            ])

            func scene(
                link: LinkState = .following(wearer: "Marta", canWrite: true), readings: [Reading],
                setup: SetupFlow = done, permission: NotificationPermission = .allowed,
                mode: LaunchMode = .normal, focus: Bool = false, act: (AlertController) -> Void = { _ in }
            ) -> Scene {
                var own = AlertSettings()
                own.source = .own
                let controller = AlertController(
                    settings: own, clock: SystemClock(), store: MemoryAlertState(), notifier: SilentNotifier(),
                    wake: NoWake())
                // the plan only decides what the panel says (nothing here starts a service: no `AppServices`); `.all`
                // shows the panel of a monitoring Mac, the fixture's the offline synthetic run
                let plan = LaunchPlan(
                    mode: mode, services: mode == .fixture ? .none : .all,
                    data: .isolated(root: FileManager.default.temporaryDirectory.appending(path: suite)))
                let model = AppModel(plan: plan, alerts: controller)
                var snapshot = FollowerSnapshot.empty
                snapshot.link = link
                snapshot.readings = readings
                let start = Int(now.timeIntervalSince1970) - 6 * 86400
                snapshot.sensor = WearerSensor(start: start, end: start + 14 * 86400, warmupEnd: start + 3600)
                model.apply(snapshot)
                act(controller)
                controller.refreshUI()
                model.notificationPermission = permission
                model.focusCanHoldAlerts = focus
                let setupModel = SetupModel(store: store, follow: FollowModel(status: .notFollowing, actions: nil))
                setupModel.flow = setup
                let treatments = TreatmentsModel(rows: [], canWrite: false, actions: nil)
                treatments.isFollowing = link.isFollowing
                if case .following(_, true) = link { treatments.canWrite = true }
                let links = PanelLinks(
                    setup: setupModel, treatments: treatments, openSetup: {}, openFollow: {}, openAddTreatment: {},
                    openTreatments: {}, openSettings: {})
                return Scene(
                    view: AnyView(
                        VStack(alignment: .leading, spacing: 8) {
                            MenubarStrip(title: model.menubarTitle)
                            PanelView(model: model, links: links)
                        }))
            }

            return [
                ("panel-following", scene(readings: ending(112, rate: -0.4))),
                ("panel-low", scene(readings: ending(62, rate: -1.6))),
                ("panel-high", scene(readings: ending(246, rate: 1.2))),
                ("panel-stale", scene(readings: ending(131, rate: 0, age: 12 * 60))),
                (
                    "panel-muted-all",
                    scene(readings: ending(118, rate: 0.2)) {
                        $0.perform(.muteAll(until: now.addingTimeInterval(3600)))
                    }
                ),
                (
                    "panel-cant-add-treatments",
                    scene(link: .following(wearer: "Marta", canWrite: false), readings: ending(108, rate: 0.1))
                ),
                ("panel-low-muted", scene(readings: ending(64, rate: -0.8)) { $0.perform(.mute(.low, minutes: 30)) }),
                ("panel-waiting-first-reading", scene(readings: [])),
                ("panel-not-following", scene(link: .notFollowing, readings: [])),
                ("panel-revoked", scene(link: .revoked, readings: [])),
                (
                    "panel-finish-setup",
                    scene(
                        link: .notFollowing, readings: [],
                        setup: SetupFlow(states: [.welcome: .done, .follow: .skipped, .alerts: .done]))
                ),
                ("panel-notifications-off", scene(readings: ending(105, rate: 0), permission: .denied)),
                ("panel-focus-can-hold", scene(readings: ending(105, rate: 0), focus: true)),
                ("panel-fixture", scene(readings: ending(112, rate: -0.4), mode: .fixture)),
            ]
        }
    }

    /// The status item's title as the menu bar draws it (the same attributed string and bell), on a bar-like strip.
    private struct MenubarStrip: View {
        let title: MenubarTitle

        var body: some View {
            HStack(spacing: 4) {
                Text(AttributedString(StatusItemController.attributed(title)))
                if title.muted { Image(systemName: "bell.slash") }
            }
            .padding(.horizontal, 10)
            .frame(height: 24)
            .background(.bar)
            .accessibilityLabel(title.accessibilityLabel)
        }
    }

    private final class MemoryAlertState: AlertStateStoring {
        private var state: AlertState?
        func load() -> AlertState? { state }
        func save(_ state: AlertState) { self.state = state }
    }

    private struct SilentNotifier: AlertNotifying {
        func post(_ firing: AlertFiring, sound: Bool, category: String) {}
        func clear(_ group: AlertGroup) {}
        func postMuted(_ side: AlertSide, until: Date) {}
        func removeMuted(_ side: AlertSide) {}
    }

    private struct NoWake: WakeScheduling {
        func schedule(at date: Date?, _ fire: @escaping @MainActor () -> Void) {}
    }
#endif
