// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import Observation
import SukraraAlerts
import SukraraCore
import SukraraGraph
import SukraraShare

/// The state every surface of the menu bar app renders, composed of small controllers. One instance, owned by
/// `AppDelegate`. Data comes in through `apply(…)` (the follower runtime); the controllers exist only when the
/// launch plan allows their service (an offline run has no alert controller, no update check, no login item).
@Observable
public final class AppModel {
    public let plan: LaunchPlan
    /// The wearer's readings, oldest first.
    private(set) var readings: [Reading] = []
    private(set) var sensor: SensorInfo?
    /// The wearer's live treatments as the chart's marks, oldest first.
    private(set) var marks: [GraphMark] = []
    private(set) var link: LinkState = .notFollowing
    /// The wearer's readings feed the card and the alert rules: following, or following with the Keychain retrying.
    private var monitoring = false
    var unit: GlucoseUnit = .mgdl
    /// The card's settings (bands, hours…): this Mac's, or the wearer's mirrored ones (`GraphConfig.load`).
    var graph = GraphConfig()
    /// The widgets' card settings: the same, with the Widgets place's own time shown and sensor time left.
    var widgetGraph = GraphConfig()
    /// "Now" for the card: moved by the freshness ticker at each freshness step, so a value greys and strikes on time.
    private(set) var now: Date
    var notificationPermission: NotificationPermission = .allowed
    /// Alerts can't break through Focus / Do Not Disturb (no Time Sensitive permission): the panel says so.
    var focusCanHoldAlerts = false

    @ObservationIgnored let alerts: AlertController?
    @ObservationIgnored let updates: UpdateCheck?
    @ObservationIgnored let loginItem: LoginItem?
    @ObservationIgnored let follower: (any FollowerService)?
    /// The widgets' data (nil when the plan doesn't allow the widget feed: dev and snapshot runs).
    @ObservationIgnored let widget: WidgetFeed?
    /// Counts settings changes, so each one reloads the widgets (`feedWidget`).
    @ObservationIgnored private var widgetSettings = 0
    @ObservationIgnored private let clock: any TimeSource
    @ObservationIgnored private var ticker: Task<Void, Never>?
    @ObservationIgnored private var feeds: [Task<Void, Never>] = []
    /// Every follower snapshot, after the model took its part (the windows and the mirrored alert settings).
    @ObservationIgnored var onSnapshot: ((FollowerSnapshot) -> Void)?

    init(
        plan: LaunchPlan, clock: any TimeSource = SystemClock(), alerts: AlertController? = nil,
        updates: UpdateCheck? = nil, loginItem: LoginItem? = nil, follower: (any FollowerService)? = nil,
        widget: WidgetFeed? = nil
    ) {
        self.plan = plan
        self.clock = clock
        self.alerts = alerts
        self.updates = updates
        self.loginItem = loginItem
        self.follower = follower
        self.widget = widget
        now = clock.now
        alerts?.onUIChange = { [weak self] in self?.feedWidget() }
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
        monitoring = link.isFollowing || (snapshot.keychainUnreadable && !snapshot.readings.isEmpty)
        if wasMonitoring && !monitoring { alerts?.resetMonitoring() }
        if let alerts {
            alerts.setMirroring(monitoring && alerts.settings.source == .wearer)
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
        marks = monitoring ? CardAlertsMapping.marks(snapshot.treatments) : []
        sensor = snapshot.sensor.map {
            SensorInfo(start: TimeInterval($0.start), end: TimeInterval($0.end), warmupEnd: TimeInterval($0.warmupEnd))
        }
        // only a followed wearer's readings are shown or alerted on
        apply(readings: monitoring ? snapshot.readings : [])
        onSnapshot?(snapshot)  // first: it loads the wearer's mirrored card settings the widget draws with
        feedWidget()
    }

    /// Hands the card's current input to the widgets: every follower snapshot, every alert badge change, and the unit
    /// or card settings changing (`settingsChanged`: the widget redraws at once, a settings change is rare and
    /// deliberate). The widget ages the value by itself, so the freshness ticker doesn't feed it.
    func feedWidget(settingsChanged: Bool = false) {
        guard let widget else { return }
        if settingsChanged { widgetSettings += 1 }
        let data = cardData
        let look = self.look  // the menu bar's face: the same value text and state the widget shows
        let state =
            "\(monitoring ? "monitoring" : "idle") \(look.state.rawValue) \(data.alerts.status) settings \(widgetSettings)"
        widget.update(
            WidgetSnapshot(writtenAt: clock.now.timeIntervalSince1970, data: data, config: widgetGraph, unit: unit),
            shown: .init(value: look.text ?? "—", state: state))
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

    func apply(sensor info: SensorInfo?) {
        sensor = info
        restartTicker()
    }

    // MARK: what the surfaces show

    /// The card's data at `now`, from the stores only (never made-up values on a release build).
    var cardData: CardData {
        CardData(
            readings: readings.map { GraphReading(t: $0.t.timeIntervalSince1970, v: Double($0.mgdl), rate: $0.rate) },
            marks: marks, sensor: sensor,
            alerts: cardAlerts, now: now.timeIntervalSince1970)
    }

    /// The card's alert decision: the engine's state, or while mirroring the wearer's alerts the same rules over the
    /// latest reading (this Mac's engine then runs only no data).
    private var cardAlerts: CardAlerts {
        var card = CardAlertsMapping.of(alerts?.ui ?? .idle, settings: alerts?.settings, marks: marks, now: now)
        if let alerts, alerts.mirroring {
            let latest = readings.last.map { GlucoseSample(at: $0.t, mgdl: Double($0.mgdl), rate: $0.rate) }
            card.status = CardAlertsMapping.mirroredStatus(
                latest: latest, settings: alerts.settings, marks: marks, sideMutes: alerts.mirroredSideMutes(at: now),
                muteAll: alerts.ui.muteAllUntil, now: now)
            card.last = CardAlertsMapping.lastTreatment(marks, status: card.status, now: now.timeIntervalSince1970) {
                card.muteEnds[$0.id]
            }
        }
        return card
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
        let data = cardData
        return MenubarTitle.of(
            look: GlucoseCard.face(data, unit: unit, surface: .menubarTitle), data: data, config: graph,
            spoken: GlucoseCard.accessibilityLabel(cardData, unit: unit, surface: .menubarTitle, config: graph),
            alerts: alerts?.ui ?? .idle, reason: notMonitoringReason)
    }

    /// The menu bar title text (tests, VoiceOver fallbacks).
    public var statusTitle: String { menubarTitle.text }

    /// Why nothing is monitored, in words (the panel's first line), or nil while a value can be shown. Before the
    /// first reading the app says so instead of "no data" — and no no-data alarm is armed.
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

    /// Wakes at the next freshness step of the newest reading (fresh → late → stale → old), at the sensor's end and
    /// at each minute of its warm-up, so the title and card change exactly then. No `Timer`: one task, replaced on
    /// every new reading.
    private func restartTicker() {
        ticker?.cancel()
        let nowT = clock.now.timeIntervalSince1970
        guard let next = Self.nextWake(latest: readings.last?.t.timeIntervalSince1970, sensor: sensor, now: nowT)
        else { return }
        let wait = max(0.5, next - nowT)
        ticker = Task { [weak self] in
            try? await Task.sleep(for: .seconds(wait), tolerance: .seconds(1))
            guard !Task.isCancelled, let self else { return }
            self.now = self.clock.now
            self.restartTicker()
        }
    }

    /// The next moment the title changes by itself: a freshness step, the sensor's end, or the warm-up's next whole
    /// minute (its figure counts down without readings). nil = nothing more will change.
    static func nextWake(latest: TimeInterval?, sensor: SensorInfo?, now: TimeInterval) -> TimeInterval? {
        var times = [Freshness.nextChangeAt(latest, now: now)].compactMap { $0 }
        if let s = sensor {
            if s.end > now { times.append(s.end + 0.5) }
            if s.start <= now, s.warmupEnd + 15 * 60 > now, latest.map({ $0 < s.start }) ?? true {
                // the figure rounds the time left up to whole minutes: it changes as each minute before the end passes
                let left = s.warmupEnd - now
                times.append(left > 0 ? now + left.truncatingRemainder(dividingBy: 60) + 0.5 : s.warmupEnd + 15 * 60)
            }
        }
        return times.min()
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

    /// The clock's look changed (24-hour time, time zone): the card's times and the widgets are drawn again.
    func clockStyleChanged() {
        now = clock.now
        feedWidget(settingsChanged: true)
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
