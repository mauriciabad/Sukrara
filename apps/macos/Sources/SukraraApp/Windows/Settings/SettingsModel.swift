// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import Observation
import SukraraAlerts
import SukraraCore

/// The settings window's state over the store: bands, unit, "Settings from", the wearer's mirrored values.
@Observable
public final class SettingsModel {
    public enum Pane: String, CaseIterable, Sendable { case general, alerts }

    @ObservationIgnored let store: any SettingsStoring
    public let alerts: AlertSettingsStore
    public let follow: FollowModel
    /// The wearer's mirrored settings (nil while not following or none arrived yet).
    public var wearer: WearerSettings?
    public var pane: Pane = .general
    /// "Defaults" replaces this Mac's own thresholds and bands: asked first.
    public var confirmingDefaults = false
    public let version: String
    private var revision = 0

    public init(store: any SettingsStoring, follow: FollowModel, wearer: WearerSettings? = nil, version: String) {
        self.store = store
        self.alerts = AlertSettingsStore(store: store)
        self.follow = follow
        self.wearer = wearer
        self.version = version
    }

    /// Rows mirror the wearer (read-only) while following in "Settings from: Wearer".
    public var mirrors: Bool { settingsFrom == .wearer && wearer != nil }

    public var settingsFrom: SettingsFrom {
        get { _ = revision; return store.settingsFrom }
        set {
            store.set(newValue.rawValue, for: MacSettings.settingsFrom)
            // `alerts.source` is derived from `follower.settingsFrom` (schema): wearer = mirror, else own rules.
            let source: AlertSource = newValue == .wearer ? .wearer : .own
            if alerts.settings.source != source { alerts.update { $0.source = source } }
            revision += 1
        }
    }

    public var unit: GlucoseUnit {
        get { _ = revision; return store.unit }
        set { store.set(newValue.rawValue, for: MacSettings.unit); revision += 1 }
    }

    /// The shown value of a number setting: the wearer's when mirrored, else this Mac's.
    public func number(_ n: MacSettings.Number) -> Double {
        _ = revision
        if mirrors, let w = wearer?.number(n.id) { return w }
        return store.number(n)
    }

    public func setNumber(_ v: Double, _ n: MacSettings.Number) {
        guard !mirrors else { return }
        if store.setNumber(v, for: n) { revision += 1 }
    }

    /// "Use my own settings": this Mac's values, editable.
    public func useOwn() { settingsFrom = .custom }

    /// "Defaults" (after the confirmation): the schema defaults for the bands and the SPEC defaults for the alerts.
    public func useDefaults() {
        for n in MacSettings.bands { store.set(n.format.default, for: n.spec) }
        alerts.resetToDefaults()
        settingsFrom = .default
        revision += 1
    }

    /// A mirrored number (`alerts.kind.repeat` …) while mirroring, else nil.
    func wearerNumber(_ id: String, kind: AlertKind? = nil) -> Double? {
        mirrors ? wearer?.number(id, kind: kind?.rawValue) : nil
    }

    func wearerBool(_ id: String, kind: AlertKind? = nil) -> Bool? {
        mirrors ? wearer?.bool(id, kind: kind?.rawValue) : nil
    }
}
