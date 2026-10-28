// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit
import SukraraAlerts
import SukraraCore
import SukraraGraph
import SukraraShare
import SwiftUI

/// The dropdown under the status item. Every state it shows has its way out right here: not monitoring → why (and
/// Finish setup); Mute all → countdown + Unmute; a side muted → its line + Unmute; Remind less → Remind as usual;
/// notifications off → Open Notification Settings; an update → Download / Later. Under them, like a menu: Add
/// Treatment… (Return; dimmed with the reason when this Mac can't log), Treatments…, Settings… (⌘,), so a left click
/// reaches every window without the right-click menu.
struct PanelView: View {
    let model: AppModel
    /// The window layer's entry points (nil in a render without windows).
    var links: PanelLinks?
    @Environment(\.colorScheme) private var scheme
    @Environment(\.colorSchemeContrast) private var contrast
    /// Where the chart looks (scrolled / zoomed through time; nil end = now).
    @State private var scroll: ChartScroll

    /// `scroll`: where the chart starts looking (snapshots; the app opens on now).
    init(model: AppModel, links: PanelLinks? = nil, scroll: ChartScroll = ChartScroll()) {
        self.model = model
        self.links = links
        _scroll = State(initialValue: scroll)
    }
    /// Mute all's Custom… field is open: Return belongs to it, not to Add Treatment….
    @State private var customMute = false
    /// The chart's y range, Now pill and way back to now, gliding (`ChartEase`).
    @State private var ease = ChartEase()
    /// Something glides: the card redraws every frame until it settles.
    @State private var gliding = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    static let cardSize = CGSize(width: 340, height: 196)

    var body: some View {
        TimelineView(.periodic(from: .now, by: 30)) { context in
            VStack(alignment: .leading, spacing: 10) {
                #if DEBUG
                    if model.plan.mode == .fixture {
                        Label(L10n.t("Synthetic data (dev build)"), systemImage: "flask")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                #endif
                if let reason = model.notMonitoringReason {
                    Label(reason, systemImage: "antenna.radiowaves.left.and.right.slash")
                        .font(.callout)
                        .accessibilityLabel(reason)
                } else {
                    card
                }
                if let links { SetupRows(links: links, needsFollow: model.needsFollow) }
                if model.notificationPermission.needsAttention { notificationsWarning }
                if model.focusCanHoldAlerts { focusNote }
                if let alerts = model.alerts { AlertBlocks(alerts: alerts, now: context.date, custom: $customMute) }
                if let updates = model.updates { UpdateRow(updates: updates) }
                if let links {
                    ActionRows(
                        links: links, add: AddTreatmentAvailability(link: model.link), returnAdds: !customMute)
                }
            }
            .padding(14)
            .frame(width: Self.cardSize.width + 28, alignment: .leading)
            // the panel opens at now again
            .onDisappear {
                scroll = ChartScroll()
                ease = ChartEase()
            }
        }
    }

    @ViewBuilder private var card: some View {
        let data = model.panelData
        let bounds = chart(data)
        TimelineView(.animation(paused: !gliding)) { frame in
            cardImage(
                data,
                view: ease.view(scroll, default: bounds.default, now: data.now, at: frame.date.timeIntervalSince1970))
        }
        .onChange(of: target(data), initial: true) { _, new in
            ease.retarget(y: new.y, away: new.away, at: Date().timeIntervalSince1970, instant: reduceMotion)
            gliding = !ease.settled(at: Date().timeIntervalSince1970)
        }
        .task(id: ease) {
            try? await Task.sleep(for: .seconds(Glide.settle + 0.05))
            if !Task.isCancelled { gliding = false }
        }
    }

    /// Where the chart is going: the visible window's own y range and whether it is away from now.
    private struct Target: Equatable {
        var y: ClosedRange<Double>?
        var away: Bool
    }

    private func target(_ data: CardData) -> Target {
        Target(
            y: GlucoseCard.yRange(
                surface: .menubarCard, config: model.graph, readings: data.readings, end: scroll.end ?? data.now,
                span: scroll.span), away: scroll.away)
    }

    /// Back to now and the place's own window, end and length gliding there together.
    private func goBack(_ data: CardData) {
        guard scroll.away else { return }
        let b = chart(data)
        ease.goBack(
            from: scroll.end ?? b.now, span: scroll.span(b.default), at: Date().timeIntervalSince1970,
            instant: reduceMotion)
        scroll = ChartScroll()
    }

    @ViewBuilder private func cardImage(_ data: CardData, view: ChartView?) -> some View {
        let increased = contrast == .increased
        if let image = GlucoseCard.image(
            surface: .menubarCard, size: Self.cardSize, config: model.graph, data: data, unit: model.unit,
            dark: scheme == .dark, view: view, contrast: increased)
        {
            let rects = GlucoseCard.chartRects(
                surface: .menubarCard, size: Self.cardSize, config: model.graph, data: data, unit: model.unit,
                view: view)
            Image(decorative: image, scale: 2)
                .accessibilityElement()
                .accessibilityLabel(
                    GlucoseCard.accessibilityLabel(data, unit: model.unit, surface: .menubarCard, config: model.graph)
                )
                .modifier(
                    ChartActions(scroll: $scroll, chart: chart(data), hasChart: rects.plot != nil) { goBack(data) }
                )
                .overlay(alignment: .topLeading) {
                    if let plot = rects.plot {
                        ChartGestures(
                            scroll: { dx, _ in moveChart(by: dx, plotWidth: plot.width, data) },
                            zoom: { factor, x in
                                zoomChart(factor, at: (x * Self.cardSize.width - plot.minX) / plot.width, data)
                            },
                            smartZoom: { x in
                                let b = chart(data)
                                scroll.smartZoom(
                                    anchor: Double((x * Self.cardSize.width - plot.minX) / plot.width),
                                    default: b.default, first: b.first, now: b.now)
                            }
                        )
                        .frame(width: Self.cardSize.width, height: Self.cardSize.height)
                    }
                }
                .overlay(alignment: .topLeading) { pill(data) }
                .overlay(alignment: .topLeading) {
                    if let now = rects.now, scroll.away {
                        Button {
                            goBack(data)
                        } label: {
                            Color.clear.contentShape(Capsule()).frame(width: now.width, height: now.height)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(L10n.t("Back to Now"))
                        .help(L10n.t("Back to Now"))
                        .offset(x: now.minX, y: now.minY)
                    }
                }
                // the module reaches 6 pt past the card, into the panel's margin (the card keeps its size)
                .background(Self.module(scheme: scheme, increased: increased).padding(-6))
        }
    }

    /// The recessed module behind the card (12 pt corners; black 18 % in dark mode, white 50 % in light mode; an
    /// outline with Increase Contrast).
    static func module(scheme: ColorScheme, increased: Bool) -> some View {
        RoundedRectangle(cornerRadius: 12, style: .continuous)
            .fill(scheme == .dark ? Color.black.opacity(0.18) : Color.white.opacity(0.5))
            .overlay {
                if increased {
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .strokeBorder(Color(nsColor: .separatorColor), lineWidth: 1)
                }
            }
    }

    /// The bounds a gesture works within: the place's own length, the oldest reading, now.
    private func chart(_ data: CardData) -> ChartActions.Bounds {
        ChartActions.Bounds(
            default: GlucoseCard.chartSpan(surface: .menubarCard, config: model.graph) ?? 12 * 3600,
            first: data.readings.first?.t ?? data.now, now: data.now)
    }

    private func moveChart(by dx: CGFloat, plotWidth: CGFloat, _ data: CardData) {
        let b = chart(data)
        let span = scroll.span(b.default)
        // the content follows the fingers: moving it right shows earlier readings
        scroll.by(-Double(dx) * span / Double(max(plotWidth, 1)), minEnd: b.first + span, now: b.now)
    }

    private func zoomChart(_ factor: CGFloat, at x: CGFloat, _ data: CardData) {
        let b = chart(data)
        scroll.zoom(Double(factor), anchor: Double(x), default: b.default, first: b.first, now: b.now)
    }

    /// The card's pill as a control over the spot the renderer drew it: Eat carbs / Take insulin opens Add Treatment
    /// with that field focused, a mute pill opens its ways out (the same as the blocks below).
    @ViewBuilder private func pill(_ data: CardData) -> some View {
        let canAdd = AddTreatmentAvailability(link: model.link) == .available && links != nil
        let action = PillAction.of(
            data.alerts.status, canAdd: canAdd, muteAll: model.alerts?.ui.muteAllUntil != nil)
        if action != .none,
            let rect = GlucoseCard.pillRect(
                surface: .menubarCard, size: Self.cardSize, config: model.graph, data: data, unit: model.unit)
        {
            let spot = Color.clear.contentShape(Capsule()).frame(width: rect.width, height: rect.height)
            Group {
                switch action {
                case .treat(let kind):
                    Button {
                        links?.openAddTreatment(kind)
                    } label: {
                        spot
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(kind == .carbs ? L10n.t("Eat carbs") : L10n.t("Take insulin"))
                    .accessibilityHint(L10n.t("Add Treatment"))
                case .quiet(let side):
                    if let alerts = model.alerts {
                        Menu {
                            QuietMenu(alerts: alerts, side: side, now: Date())
                        } label: {
                            spot
                        }
                        .menuStyle(.button)
                        .buttonStyle(.plain)
                        .menuIndicator(.hidden)
                        .accessibilityLabel(L10n.t("Show Alert Options"))
                    }
                case .muteAll:
                    if let alerts = model.alerts {
                        Menu {
                            Button(L10n.t("Unmute")) { alerts.perform(.unmuteAll) }
                            MuteAllLengths(alerts: alerts, now: Date(), custom: .constant(false), offerCustom: false)
                        } label: {
                            spot
                        }
                        .menuStyle(.button)
                        .buttonStyle(.plain)
                        .menuIndicator(.hidden)
                        .accessibilityLabel(L10n.t("Show Alert Options"))
                    }
                case .none: EmptyView()
                }
            }
            .help(L10n.t("Show what you can do"))
            .offset(x: rect.minX, y: rect.minY)
        }
    }

    /// Without Time Sensitive notifications a Focus holds Sukrara's alarms back: say so, and where to allow it.
    private var focusNote: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label(
                L10n.t(
                    "A Focus (Do Not Disturb, Sleep…) can hold back Sukrara's alarms on this Mac. Allow Sukrara in each Focus, and keep the phone as the alarm."
                ),
                systemImage: "moon.fill"
            )
            .font(.callout)
            .fixedSize(horizontal: false, vertical: true)
            Button(L10n.t("Open Focus Settings")) {
                if let url = URL(string: "x-apple.systempreferences:com.apple.Focus-Settings.extension") {
                    NSWorkspace.shared.open(url)
                }
            }
        }
    }

    private var notificationsWarning: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label(
                model.notificationPermission == .denied
                    ? L10n.t("Notifications are off: alerts can't reach you.")
                    : L10n.t("Alert banners or sounds are off: alerts may go unnoticed."),
                systemImage: "exclamationmark.triangle.fill"
            )
            .foregroundStyle(.orange)
            Button(L10n.t("Open Notification Settings")) {
                if let url = URL(string: "x-apple.systempreferences:com.apple.Notifications-Settings.extension") {
                    NSWorkspace.shared.open(url)
                }
            }
        }
    }
}

/// VoiceOver's way through the chart: Earlier, Later, Zoom Out, Zoom In and Back to Now as the card's actions.
private struct ChartActions: ViewModifier {
    struct Bounds { var `default`, first, now: TimeInterval }
    @Binding var scroll: ChartScroll
    let chart: Bounds
    let hasChart: Bool
    /// Back to now (gliding there).
    let back: () -> Void

    func body(content: Content) -> some View {
        if hasChart {
            content
                .accessibilityAction(named: L10n.t("Earlier")) {
                    scroll.page(forward: false, default: chart.default, first: chart.first, now: chart.now)
                }
                .accessibilityAction(named: L10n.t("Later")) {
                    scroll.page(forward: true, default: chart.default, first: chart.first, now: chart.now)
                }
                .accessibilityAction(named: L10n.t("Zoom Out")) {
                    scroll.step(out: true, default: chart.default, first: chart.first, now: chart.now)
                }
                .accessibilityAction(named: L10n.t("Zoom In")) {
                    scroll.step(out: false, default: chart.default, first: chart.first, now: chart.now)
                }
                .accessibilityAction(named: L10n.t("Back to Now"), back)
        } else {
            content
        }
    }
}

/// What the window layer opens from the panel (each closes the panel first).
struct PanelLinks {
    let setup: SetupModel
    /// Whether this Mac may log (`AddTreatmentAvailability`).
    let treatments: TreatmentsModel
    let openSetup: () -> Void
    let openFollow: () -> Void
    /// Add Treatment with a field focused (nil: the one the latest reading suggests).
    let openAddTreatment: (Treatment.Kind?) -> Void
    let openTreatments: () -> Void
    let openReport: () -> Void
    let openSettings: () -> Void
}

/// Whether the panel's Add Treatment… can open, and why not in words (on the row, its tooltip and VoiceOver hint):
/// each follow state names what would change it.
enum AddTreatmentAvailability: Equatable {
    case available
    /// Following nobody: there's no wearer to log for.
    case notFollowing
    /// Access asked, the wearer hasn't allowed this Mac yet.
    case waiting(wearer: String?)
    /// Following, but the wearer hasn't turned on "Can add entries" for this Mac.
    case notWriter(wearer: String?)
    /// The wearer stopped sharing with this Mac.
    case revoked
    /// The link is over (declined, invite used elsewhere, broken): a new invite is needed.
    case ended

    init(link: LinkState) {
        switch link {
        case .following(let wearer, let canWrite): self = canWrite ? .available : .notWriter(wearer: wearer)
        case .requested(_, let wearer, _): self = .waiting(wearer: wearer)
        case .joining: self = .waiting(wearer: nil)
        case .revoked: self = .revoked
        case .declined, .inviteUsed, .broken: self = .ended
        case .notFollowing: self = .notFollowing
        }
    }

    var reason: String? {
        switch self {
        case .available: nil
        case .notFollowing: L10n.t("Follow someone to add treatments.")
        case .waiting(let w?): L10n.t("Waiting for \(w) to approve this Mac")
        case .waiting(nil): L10n.t("Waiting for the wearer to approve this Mac")
        case .notWriter(let w?): L10n.t("\(w) hasn't turned on “Can add entries” for this Mac")
        case .notWriter(nil): L10n.t("The wearer hasn't turned on “Can add entries” for this Mac")
        case .revoked: L10n.t("The wearer stopped sharing with this Mac")
        case .ended: L10n.t("Not following anyone: ask for a new invite")
        }
    }
}

/// What pressing the card's pill does: Eat carbs / Take insulin → Add Treatment with that field focused (where this
/// Mac may log); a side's mute → its ways out; Mute all's → Unmute / Change Time.
enum PillAction: Equatable {
    case treat(Treatment.Kind)
    case quiet(AlertSide)
    case muteAll
    case none

    /// `muteAll`: Mute all is on, so a paused side's way out is Mute all's (Unmute / Change Time).
    static func of(_ status: AlertStatus, canAdd: Bool, muteAll: Bool = false) -> PillAction {
        if muteAll, case .paused = status { return .muteAll }
        return switch status {
        case .none: .none
        case .needed(let side): canAdd ? .treat(side == .low ? .carbs : .rapid) : .none
        case .taken(let side, _, _), .paused(let side, _): .quiet(side == .low ? .low : .high)
        case .muted: .muteAll
        }
    }
}

/// The panel's menu-like rows: Add Treatment… (the default button: Return), Treatments…, Settings… ⌘,.
private struct ActionRows: View {
    let links: PanelLinks
    let add: AddTreatmentAvailability
    /// Return chooses Add Treatment… (off while a text field in the panel takes Return).
    var returnAdds = true

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Divider().padding(.bottom, 4)
            Button {
                links.openAddTreatment(nil)
            } label: {
                PanelRowLabel(
                    title: L10n.t("Add Treatment…"), symbol: "plus.circle", shortcut: add == .available ? "↩" : nil)
            }
            .keyboardShortcut(add == .available && returnAdds ? .defaultAction : nil)
            .disabled(add != .available)
            .help(add.reason ?? "")
            .accessibilityHint(add.reason ?? "")
            if let reason = add.reason {
                Text(reason)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .padding(.leading, 32)
                    .accessibilityHidden(true)  // already the row's hint
            }
            Button(action: links.openTreatments) {
                PanelRowLabel(title: L10n.t("Treatments…"), symbol: "list.bullet", shortcut: nil)
            }
            Button(action: links.openReport) {
                PanelRowLabel(title: L10n.t("Glucose Report…"), symbol: "chart.bar.doc.horizontal", shortcut: nil)
            }
            Divider().padding(.vertical, 4)
            Button(action: links.openSettings) {
                PanelRowLabel(title: L10n.t("Settings…"), symbol: "gearshape", shortcut: "⌘,")
            }
            .keyboardShortcut(",", modifiers: .command)
        }
        .buttonStyle(PanelRowStyle())
    }
}

/// A menu item's look: symbol, title, the shortcut on the trailing edge (hidden from VoiceOver: it reads the
/// shortcut from the button itself).
private struct PanelRowLabel: View {
    let title: String
    let symbol: String
    let shortcut: String?

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: symbol).frame(width: 18).accessibilityHidden(true)
            Text(title)
            Spacer(minLength: 8)
            if let shortcut { Text(shortcut).foregroundStyle(.secondary).accessibilityHidden(true) }
        }
        .contentShape(Rectangle())
    }
}

/// Full-width rows highlighted on hover and press like menu items, dimmed when disabled.
private struct PanelRowStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View { Row(configuration: configuration) }

    private struct Row: View {
        let configuration: ButtonStyleConfiguration
        @Environment(\.isEnabled) private var isEnabled
        @Environment(\.isFocused) private var isFocused
        @State private var hovering = false

        var body: some View {
            // hover or Full Keyboard Access focus, in the system's menu selection colours
            let lit = (hovering || isFocused) && isEnabled
            configuration.label
                .padding(.horizontal, 6)
                .padding(.vertical, 4)
                .frame(maxWidth: .infinity, alignment: .leading)
                .foregroundStyle(lit ? Color(nsColor: .selectedMenuItemTextColor) : .primary)
                .background(
                    RoundedRectangle(cornerRadius: 5)
                        .fill(lit ? Color(nsColor: .selectedContentBackgroundColor) : .clear)
                        .opacity(configuration.isPressed ? 0.8 : 1)
                )
                .opacity(isEnabled ? 1 : 0.45)
                .onHover { hovering = $0 }
        }
    }
}

/// "Finish setup…" while a setup step is skipped or not reached; "Follow Someone…" when the walk is done but this
/// Mac follows nobody (after Stop following, a decline, a revoke).
private struct SetupRows: View {
    let links: PanelLinks
    let needsFollow: Bool

    var body: some View {
        if !links.setup.flow.isComplete {
            Button(L10n.t("Finish Setup…"), systemImage: "checklist", action: links.openSetup)
        } else if needsFollow {
            Button(L10n.t("Follow Someone…"), systemImage: "person.badge.plus", action: links.openFollow)
        }
    }
}

/// Mute all and each side's quiet, with countdowns (`MuteCopy`) and their exits: Unmute (a side's Mute) or Alert Me
/// Anyway (a treatment or falling-back quiet), Remind as Usual / Remind Less, and the lengths to change or start a
/// mute; Mute all: Unmute and Change Time, or Mute All Alerts with a typed Custom… length.
private struct AlertBlocks: View {
    let alerts: AlertController
    let now: Date
    @Binding var custom: Bool

    var body: some View {
        let ui = alerts.ui
        if let until = ui.muteAllUntil, let line = MuteCopy.allLine(until: until, now: now) {
            HStack {
                Label(line, systemImage: "bell.slash.fill")
                Spacer()
                Button(L10n.t("Unmute")) { alerts.perform(.unmuteAll) }
                    .accessibilityLabel(L10n.t("Alerts: Unmute"))
                Menu(L10n.t("Change Time")) {
                    MuteAllLengths(alerts: alerts, now: now, custom: $custom, offerCustom: true)
                }
                .fixedSize()
                .accessibilityLabel(L10n.t("Alerts: Change time"))
            }
        }
        ForEach(ui.quiets, id: \.kind) { q in
            VStack(alignment: .leading, spacing: 4) {
                if let reason = q.quiet, let line = MuteCopy.line(reason, side: q.side, now: now) {
                    Label(line, systemImage: "bell.slash")
                }
                if q.relaxMin > 0 {
                    Label(MuteCopy.sideLess(q.side, intervalMin: q.relaxMin), systemImage: "bell.badge.waveform")
                }
                HStack {
                    QuietMenu(alerts: alerts, side: q.side, now: now)
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
            }
        }
        if ui.muteAllUntil == nil {
            Menu(L10n.t("Mute All Alerts")) {
                MuteAllLengths(alerts: alerts, now: now, custom: $custom, offerCustom: true)
            }
            .fixedSize()
        }
        if custom { CustomMuteAll(alerts: alerts, done: { custom = false }) }
    }
}

/// A side's ways out, as buttons (the panel) or menu items (the pill's menu): Unmute / Alert Me Anyway, Remind as
/// Usual / Remind Less, and Change Time / Mute with the mute lengths.
struct QuietMenu: View {
    let alerts: AlertController
    let side: AlertSide
    let now: Date

    var body: some View {
        let q = alerts.ui.quiets.first { $0.side == side }
        let sideMuted: Bool = {
            if case .sideMuted = q?.quiet { return true }
            return false
        }()
        if let q, let quiet = q.quiet, MuteCopy.line(quiet, side: side, now: now) != nil {
            if sideMuted {
                Button(L10n.t("Unmute")) { alerts.perform(.unmute(side)) }
            } else if case .allMuted = quiet {
                EmptyView()
            } else {
                // a treatment or falling back keeps it quiet: the engine lifts that cover for this episode
                Button(L10n.t("Alert Me Anyway")) { alerts.perform(.unmute(side)) }
            }
        }
        if let q, q.relaxMin > 0 {
            Button(L10n.t("Remind as Usual")) { alerts.perform(.remindAsUsual(side)) }
        } else if q?.canRelax == true {
            Button(L10n.t("Remind Less")) { alerts.perform(.remindLess(side)) }
        }
        Menu(sideMuted ? L10n.t("Change Time") : L10n.t("Mute")) {
            ForEach(AlertLimits.mutePresets, id: \.self) { minutes in
                Button(MuteCopy.choice(minutes)) { alerts.perform(.mute(side, minutes: minutes)) }
            }
        }
        .accessibilityLabel(
            sideMuted
                ? L10n.t("\(MuteCopy.sideName(side)) alerts: Change time")
                : L10n.t("\(MuteCopy.sideName(side)) alerts: Mute"))
    }
}

/// Mute all's lengths: For 30 min … For 8 h, Until You Turn It Off, and Custom… (a typed length).
struct MuteAllLengths: View {
    let alerts: AlertController
    let now: Date
    @Binding var custom: Bool
    let offerCustom: Bool

    var body: some View {
        ForEach(AlertLimits.muteAllPresets.filter { $0 > 0 }, id: \.self) { minutes in
            Button(MuteCopy.choice(minutes)) {
                alerts.perform(.muteAll(until: now.addingTimeInterval(TimeInterval(minutes * 60))))
            }
        }
        Button(MuteCopy.menuForever) { alerts.perform(.muteAll(until: muteAllForever)) }
        if offerCustom {
            Divider()
            Button(L10n.t("Custom…")) { custom = true }
        }
    }
}

/// Mute all for a typed length ("2 h 30 min", "90"), within the schema's range; Escape or Cancel closes it.
private struct CustomMuteAll: View {
    let alerts: AlertController
    let done: () -> Void
    @State private var text = ""
    @State private var invalid = false
    @FocusState private var focused: Bool

    private var range: ClosedRange<Int> { Int(SettingsSpec.muteAll.min)...Int(SettingsSpec.muteAll.max) }
    private var note: String {
        L10n.t("Enter \(Durations.text(range.lowerBound))–\(Durations.text(range.upperBound)), e.g. 1 h 30 min")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                TextField(
                    L10n.t("Mute alerts for"),
                    text: Binding(
                        get: { text },
                        set: {
                            text = $0
                            invalid = false
                        }), prompt: Text(verbatim: "2 h 30 min")
                )
                .textFieldStyle(.roundedBorder)
                .focused($focused)
                .onSubmit(commit)
                .accessibilityHint(note)
                Button(L10n.t("Mute"), action: commit).disabled(text.isEmpty)
                Button(L10n.t("Cancel"), action: done).keyboardShortcut(.cancelAction)
            }
            Text(note).font(.caption).foregroundStyle(invalid ? Color.red : Color.secondary)
        }
        .onAppear { focused = true }
    }

    private func commit() {
        guard let m = Durations.parse(text), range.contains(m) else {
            invalid = true
            return
        }
        alerts.perform(.muteAll(until: Date().addingTimeInterval(TimeInterval(m * 60))))
        done()
    }
}

private struct UpdateRow: View {
    let updates: UpdateCheck

    var body: some View {
        switch updates.state {
        case .available(let release, let version):
            HStack {
                Label(L10n.t("Sukrara \(version.description) is available"), systemImage: "arrow.down.circle")
                Spacer()
                Button(L10n.t("Later")) { updates.later() }
                Button(L10n.t("Download")) {
                    // only the project's own release page
                    if release.htmlURL.host() == "github.com" { NSWorkspace.shared.open(release.htmlURL) }
                }
            }
        case .failed:
            HStack {
                Text(L10n.t("Couldn't check for updates."))
                Spacer()
                Button(L10n.t("OK")) { updates.dismiss() }
            }
        case .upToDate:
            HStack {
                Text(L10n.t("Sukrara is up to date."))
                Spacer()
                Button(L10n.t("OK")) { updates.dismiss() }
            }
        case .idle:
            EmptyView()
        }
    }
}
