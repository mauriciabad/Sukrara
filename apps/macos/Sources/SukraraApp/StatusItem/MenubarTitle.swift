// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraAlerts
import SukraraGraph

/// What the status item shows: the value's decision from the one renderer (`GlucoseCard.face`, surface
/// `.menubarTitle`: value, strike when stale, the arrow, warm-up / ended / no readings) plus the mute bell. It adds no
/// freshness or value logic of its own.
struct MenubarTitle: Equatable {
    /// "112 ↘", "Warming up 42m", "—".
    var text: String
    /// Struck through and dimmed: the reading is stale (5–20 min old).
    var strike: Bool
    /// The bell with a slash: Mute all or a side's mute is on (the panel says which and until when).
    var muted: Bool
    /// The VoiceOver sentence: the renderer's value sentence, then the mute.
    var accessibilityLabel: String

    static func of(look: GlucoseLook, spoken: String, alerts: AlertUIState, reason: String?) -> MenubarTitle {
        let muted =
            alerts.muteAllUntil != nil
            || alerts.quiets.contains { q in
                if case .sideMuted = q.quiet { return true }
                return false
            }
        let text: String
        switch look.state {
        case .value:
            text = [look.text ?? GlucoseCard.noValueText, look.arrowText].compactMap { $0 }.joined(separator: " ")
        case .warmingUp: text = L10n.t("Warming up") + (look.warmup.map { " \($0)" } ?? "")
        case .ended: text = L10n.t("Sensor ended")
        case .noReadings, .none: text = GlucoseCard.noValueText
        }
        var label = reason.map { look.state == .value ? spoken : $0 } ?? spoken
        if muted { label += ". " + L10n.t("Alerts muted") }
        return MenubarTitle(text: text, strike: look.strike, muted: muted, accessibilityLabel: label)
    }
}
