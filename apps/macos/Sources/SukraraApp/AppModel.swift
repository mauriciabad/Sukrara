// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import Observation
import SukraraAlerts
import SukraraCore
import SukraraGraph
import SukraraShare

/// The state every surface of the menu bar app renders, composed of small controllers. One instance, owned by
/// `AppDelegate`. Data comes in through `apply(…)` (the follower runtime, chunk 15); the controllers exist only when the
/// launch plan allows their service (an offline run has no alert controller, no update check, no login item).
@Observable
public final class AppModel {
    public let plan: LaunchPlan
    /// The wearer's readings, oldest first.
    private(set) var readings: [Reading] = []
    private(set) var sensor: SensorInfo?
    private(set) var link: LinkState = .notFollowing
    /// The wearer's readings feed the card and the alert rules: following, or following with the Keychain retrying.
    private var monitoring = false
    var unit: GlucoseUnit = .mgdl
    /// The card's settings (bands, hours…): this Mac's, or the wearer's mirrored ones (`GraphConfig.load`).
    var graph = GraphConfig()
    /// "Now" for the card: moved by the freshness ticker at each freshness step, so a value greys and strikes on time.
    private(set) var now: Date
    var notificationPermission: NotificationPermission = .allowed
    /// Alerts can't break through Focus / Do Not Disturb (no Time Sensitive permission): the panel says so.
    var focusCanHoldAlerts = false

    @ObservationIgnored let alerts: AlertController?
    @ObservationIgnored let updates: UpdateCheck?
    @ObservationIgnored let loginItem: LoginItem?
    @ObservationIgnored let follower: (any FollowerService)?
    @ObservationIgnored private let clock: any TimeSource
    @ObservationIgnored private var ticker: Task<Void, Never>?
    @ObservationIgnored private var feeds: [Task<Void, Never>] = []
    /// Every follower snapshot, after the model took its part (the windows and the mirrored alert settings).
    @ObservationIgnored var onSnapshot: ((FollowerSnapshot) -> Void)?

    init(
        plan: LaunchPlan, clock: any TimeSource = SystemClock(), alerts: AlertController? = nil,
        updates: UpdateCheck? = nil, loginItem: LoginItem? = nil, follower: (any FollowerService)? = nil
    ) {
        self.plan = plan
        self.clock = clock
        self.alerts = alerts
        self.updates = updates
        self.loginItem = loginItem
        self.follower = follower
        now = clock.now
    }

    /// Starts the follower runtime and feeds its snapshots and the wearer's alerts into the model and the alert rules.
    func run() {
        guard let follower, feeds.isEmpty else { return }
        feeds.append(
            Task { [weak self] in
                for await snapshot in await follower.snapshots() { self?.apply(snapshot) }
            })
        feeds.append(
            Task { [weak self] in
                for await alert in await follower.alerts() { self?.alerts?.wearerAlert(alert) }
            })
        Task { await follower.start() }
    }

    /// One follower snapshot: the link, readings, sensor and treatments, and whether the wearer's alerts are mirrored.
    func apply(_ snapshot: FollowerSnapshot) {
        let wasMonitoring = monitoring
        link = snapshot.link
        // an unreadable Keychain (retrying) still follows the wearer: keep alerting on their readings, no-data included
        monitoring = link.isFollowing || snapshot.keychainUnreadable
        if wasMonitoring && !monitoring { alerts?.resetMonitoring() }
        if let alerts {
            alerts.setMirroring(link.isFollowing && alerts.settings.source == .wearer)
            alerts.sensor(
                snapshot.sensor.map {
                    SensorWindow(
                        start: Date(timeIntervalSince1970: TimeInterval($0.start)),
                        end: Date(timeIntervalSince1970: TimeInterval($0.end)),
                        warmupEnd: Date(timeIntervalSince1970: TimeInterval($0.warmupEnd)))
                })
            alerts.treatments(
                snapshot.treatments.compactMap { t in
                    let kind: TreatmentKind? = t.k == .carbs ? .carbs : t.k == .rapid ? .rapidInsulin : nil
                    guard !t.del, let kind else { return nil }
                    return AlertTreatment(kind: kind, at: Date(timeIntervalSince1970: TimeInterval(t.t)))
                })
        }
        sensor = snapshot.sensor.map {
            SensorInfo(start: TimeInterval($0.start), end: TimeInterval($0.end), warmupEnd: TimeInterval($0.warmupEnd))
        }
        // only a followed wearer's readings are shown or alerted on
        apply(readings: monitoring ? snapshot.readings : [])
        onSnapshot?(snapshot)
    }

    public convenience init(plan: LaunchPlan) { self.init(plan: plan, clock: SystemClock()) }

    // MARK: inputs

    /// New readings (the stored history at launch, then each new one). The alert rules get the newest, also an old
    /// one: "no data" is measured from it.
    func apply(readings new: [Reading]) {
        readings = new.sorted { $0.t < $1.t }
        now = clock.now
        if let latest = readings.last { alerts?.reading(latest) }
        restartTicker()
    }

    func apply(sensor info: SensorInfo?) { sensor = info }

    // MARK: what the surfaces show

    /// The card's data at `now`, from the stores only (never made-up values on a release build).
    var cardData: CardData {
        CardData(
            readings: readings.map { GraphReading(t: $0.t.timeIntervalSince1970, v: Double($0.mgdl), rate: $0.rate) },
            sensor: sensor, alerts: CardAlertsMapping.of(alerts?.ui ?? .idle, now: now),
            now: now.timeIntervalSince1970)
    }

    /// Nobody is followed and nothing is on its way (not following, declined, revoked, invite used, broken).
    var needsFollow: Bool {
        switch link {
        case .following, .requested, .joining: false
        default: true
        }
    }

    /// A low alert is up (Add treatment then puts the focus on Carbs).
    var isLow: Bool { alerts?.ui.active[.level]?.kind.isBelowThreshold == true }

    var look: GlucoseLook { GlucoseCard.face(cardData, unit: unit, surface: .menubarTitle) }

    var menubarTitle: MenubarTitle {
        MenubarTitle.of(
            look: look,
            spoken: GlucoseCard.accessibilityLabel(cardData, unit: unit, surface: .menubarTitle, config: graph),
            alerts: alerts?.ui ?? .idle, reason: notMonitoringReason)
    }

    /// The menu bar title text (tests, VoiceOver fallbacks).
    public var statusTitle: String { menubarTitle.text }

    /// Why nothing is monitored, in words (the panel's first line), or nil while a value can be shown. Before the
    /// first reading the app says so instead of "no data" — and no no-data alarm is armed (CHANGES P3).
    public var notMonitoringReason: String? {
        if plan.isOffline && plan.mode != .fixture {
            return L10n.t("Not monitoring: offline dev build (no sensor, relays or alerts)")
        }
        // shown over the stored readings too: with the Keychain unreadable no new reading can arrive
        if case .broken(let why) = link { return L10n.t("Not monitoring: \(why)") }
        guard readings.isEmpty else { return nil }
        switch link {
        case .notFollowing, .joining: return L10n.t("Not monitoring: not following anyone yet")
        case .requested: return L10n.t("Not monitoring: waiting for the wearer to allow this Mac")
        case .following: return L10n.t("Waiting for the first reading from the wearer")
        case .declined: return L10n.t("Not monitoring: the wearer declined")
        case .inviteUsed: return L10n.t("Not monitoring: the invite was used by another device")
        case .revoked: return L10n.t("Not monitoring: the wearer stopped sharing")
        case .broken(let why): return L10n.t("Not monitoring: \(why)")
        }
    }

    // MARK: freshness ticker

    /// Wakes at the next freshness step of the newest reading (fresh → late → stale → old), so the title and card
    /// change exactly then. No `Timer`: one task, replaced on every new reading.
    private func restartTicker() {
        ticker?.cancel()
        guard let t = readings.last?.t.timeIntervalSince1970,
            let next = Freshness.nextChangeAt(t, now: clock.now.timeIntervalSince1970)
        else { return }
        let wait = max(0.5, next - clock.now.timeIntervalSince1970)
        ticker = Task { [weak self] in
            try? await Task.sleep(for: .seconds(wait), tolerance: .seconds(1))
            guard !Task.isCancelled, let self else { return }
            self.now = self.clock.now
            self.restartTicker()
        }
    }

    /// The Mac woke or the clock changed: re-read the time, re-check the alerts.
    func networkCameBack() {
        if let follower { Task { await follower.reconnect() } }
    }

    /// The clock changed (also small NTP corrections): re-read the time and re-check the alerts; the connections stay.
    func systemTimeChanged() {
        now = clock.now
        alerts?.tick()
        restartTicker()
    }

    /// The Mac woke: the time moved and the sockets are likely dead; reconnect now instead of after the watchdog.
    func didWake() {
        systemTimeChanged()
        networkCameBack()
    }

    func stop() {
        ticker?.cancel()
        feeds.forEach { $0.cancel() }
        alerts?.stop()
        if let follower { Task { await follower.stop() } }
    }
}

/// The alert engine's state as the card's badge decision (`CardAlerts`): what the card draws under the value.
enum CardAlertsMapping {
    static func of(_ ui: AlertUIState, now: Date) -> CardAlerts {
        let level = ui.active[.level]
        let side: AlertStatus.Side? = level.map { $0.kind.isBelowThreshold ? .low : .high }
        let status: AlertStatus
        switch (side, level?.quiet, ui.muteAllUntil) {
        case (let s?, .sideMuted(let until)?, _): status = .paused(s, until: until.timeIntervalSince1970)
        case (let s?, _, let all?): status = .paused(s, until: min(all.timeIntervalSince1970, AlertStatus.never))
        case (let s?, nil, nil): status = .needed(s)
        case (nil, _, let all?): status = .muted(until: min(all.timeIntervalSince1970, AlertStatus.never))
        default: status = .none
        }
        return CardAlerts(status: status)
    }
}
