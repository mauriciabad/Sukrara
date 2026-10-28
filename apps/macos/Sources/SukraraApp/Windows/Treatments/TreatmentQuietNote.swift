// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraAlerts
import SukraraCore

/// The Add Treatment form's quiet line: "Highs quiet for 1 h · lows quiet for 15 min" for what the form would log at
/// `at` (Android's `Alerts.quietNote`). Rapid insulin quiets highs, carbs quiet lows, for the minutes in Settings ›
/// Alerts still left at `now`. nil when alerts are off, while this Mac mirrors the wearer's alerts (the wearer's
/// phone decides), or when nothing would be quiet.
enum TreatmentQuietNote {
    static func of(
        rapid: Bool, carbs: Bool, at: Int64, now: Int64, settings: AlertSettings?, mirroring: Bool
    ) -> String? {
        guard let s = settings, s.on, !mirroring else { return nil }
        func left(_ on: Bool, _ rule: QuietRule) -> Int? {
            guard on, rule.on else { return nil }
            let l = Int((at + Int64(rule.min * 60) - now) / 60)
            return l > 0 ? l : nil
        }
        switch (left(rapid, s.muteAfterInsulin), left(carbs, s.muteAfterCarbs)) {
        case (let h?, let l?):
            return L10n.t("Highs quiet for \(Durations.text(h)) · lows quiet for \(Durations.text(l))")
        case (let h?, nil): return L10n.t("Highs quiet for \(Durations.text(h))")
        case (nil, let l?): return L10n.t("Lows quiet for \(Durations.text(l))")
        default: return nil
        }
    }
}
