// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraAlerts
import SukraraCore
import SukraraGraph

/// What the status item shows: the value's decision from the one renderer (`GlucoseCard.face`, surface
/// `.menubarTitle`: value, strike when stale, the trend, warm-up / ended / no readings), its range colour
/// (`GlucoseCard.rangeSide`), the mute bell and the tooltip. It adds no freshness or value logic of its own.
struct MenubarTitle: Equatable {
    /// The picture beside (or instead of) the value.
    enum Mark: Equatable {
        /// THE card arrow at the rate's angle (`GlucoseCard.trendImage`).
        case arrow(Double)
        /// The "no trend" ring (a value without a rate).
        case ring
        /// An SF Symbol instead of a value: no readings for 20 min, the sensor ended.
        case symbol(String)
        case none
    }

    /// The value's ink.
    enum Tint: Equatable {
        /// The menu bar's own ink (in range).
        case plain
        /// Below the range: red.
        case below
        /// Above the range: orange.
        case above
        /// Secondary: a stale value, the warm-up's time left.
        case grey
    }

    /// The whole title as text ("112 ↘", "Warming up 42m", "—"): tests and places that can only write text.
    var text: String
    /// What the status item writes beside the mark: "112", "42m", or "" when a symbol stands alone.
    var value: String
    var mark: Mark
    var tint: Tint
    /// Struck through and dimmed: the reading is stale (5–20 min old).
    var strike: Bool
    /// The bell with a slash: Mute all or a side's mute is on (the panel says which and until when).
    var muted: Bool
    /// The VoiceOver sentence: the renderer's value sentence, then the mute.
    var accessibilityLabel: String
    /// The hover text: when the reading is from (or since when none came), the warm-up's end, the sensor's end, and
    /// the Mute all countdown.
    var tooltip: String

    static let endedSymbol = "flag.checkered"

    static func of(
        look: GlucoseLook, data: CardData? = nil, config: GraphConfig = GraphConfig(), spoken: String,
        alerts: AlertUIState, reason: String?
    ) -> MenubarTitle {
        let muted =
            alerts.muteAllUntil != nil
            || alerts.quiets.contains { q in
                if case .sideMuted = q.quiet { return true }
                return false
            }
        let now = data.map { Date(timeIntervalSince1970: $0.now) } ?? Date()
        let latest = data?.readings.max { $0.t < $1.t }
        let readAt = latest.map { Clock.time(Date(timeIntervalSince1970: $0.t)) }
        var text: String
        var value: String
        var mark = Mark.none
        var tint = Tint.plain
        var tooltip = "Sukrara"
        var label = reason.map { look.state == .value ? spoken : $0 } ?? spoken
        switch look.state {
        case .value:
            value = look.text ?? GlucoseCard.noValueText
            text = [value, look.arrowText].compactMap { $0 }.joined(separator: " ")
            switch look.trend {
            case .arrow: mark = look.rate.map { .arrow($0) } ?? .ring
            case .noTrend: mark = .ring
            case nil: mark = .none
            }
            if look.strike {
                tint = .grey
            } else if let data {
                switch GlucoseCard.rangeSide(data, config: config) {
                case .below: tint = .below
                case .above: tint = .above
                case .inRange, nil: tint = .plain
                }
            }
            if let readAt { tooltip = L10n.t("Sukrara · reading at \(readAt)") }
        case .warmingUp:
            let waiting = data?.sensor.map { now.timeIntervalSince1970 >= $0.warmupEnd } ?? false
            value = waiting ? L10n.t("Almost ready") : look.warmup ?? ""
            text = L10n.t("Warming up") + (look.warmup.map { " \($0)" } ?? "")
            tint = .grey
            if let s = data?.sensor {
                tooltip =
                    now.timeIntervalSince1970 >= s.warmupEnd
                    ? L10n.t("Sukrara · waiting for the first reading")
                    : L10n.t("Sukrara · ready at \(Clock.time(Date(timeIntervalSince1970: s.warmupEnd)))")
            }
        case .ended:
            value = ""
            text = L10n.t("Sensor ended")
            mark = .symbol(endedSymbol)
            tooltip = L10n.t("Sensor ended — replace sensor")
            label = tooltip
        case .noReadings:
            value = ""
            text = GlucoseCard.noValueText
            mark = .symbol(GlucoseCard.noReadingsSymbol)
            if let readAt {
                tooltip = L10n.t("Sukrara · no reading since \(readAt)")
                if reason == nil { label = L10n.t("Glucose: no reading since \(readAt)") }
            }
        case .none:
            value = GlucoseCard.noValueText
            text = GlucoseCard.noValueText
            if let reason { tooltip = reason }
        }
        if let until = alerts.muteAllUntil, let line = MuteCopy.allLine(until: until, now: now) {
            tooltip += " · " + line
            label += ". " + line
        } else if muted {
            label += ". " + L10n.t("Alerts muted")
        }
        return MenubarTitle(
            text: text, value: value, mark: mark, tint: tint, strike: look.strike, muted: muted,
            accessibilityLabel: label, tooltip: tooltip)
    }
}
