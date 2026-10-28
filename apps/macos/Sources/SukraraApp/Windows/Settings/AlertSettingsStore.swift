// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import Observation
import SukraraAlerts
import SukraraCore

/// The alert settings (ONE key `alerts`, contracts/alerts/SPEC.md §3) for the settings window. Safety rule
/// (contracts/settings/README.md): an unreadable stored value never turns alerts off or drops a threshold — the
/// last value decoded stays in use (else the SPEC defaults, alerts ON) and `readError` shows a visible error with
/// "Reset alert settings" as its exit.
@Observable
public final class AlertSettingsStore {
    public private(set) var settings: AlertSettings
    public private(set) var readError = false
    @ObservationIgnored private let store: any SettingsStoring

    public init(store: any SettingsStoring) {
        self.store = store
        self.settings = AlertSettings()
        reload()
    }

    /// Re-reads the stored value; keeps the current one when it can't be decoded.
    public func reload() {
        let data = store.value(MacSettings.alerts)
        if data.isEmpty {
            readError = false
            return
        }
        do {
            settings = try JSONDecoder().decode(AlertSettings.self, from: data)
            readError = false
        } catch {
            readError = true
        }
    }

    public func update(_ change: (inout AlertSettings) -> Void) {
        var s = settings
        change(&s)
        settings = s
        store.set(s.encoded(), for: MacSettings.alerts)
        readError = false
    }

    /// The exit from the read error, and "Defaults": the SPEC §3 defaults (alerts ON).
    public func resetToDefaults() {
        let source = settings.source
        update {
            $0 = AlertSettings()
            $0.source = source
        }
    }
}
