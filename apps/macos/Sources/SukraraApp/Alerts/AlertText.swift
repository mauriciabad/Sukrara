// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraAlerts
import SukraraCore
import SukraraGraph

/// The words of an alert notification in the user's language (English, Spanish). Self-contained: the value, the
/// direction and what it means, readable on the lock screen without opening the app.
struct AlertText: Equatable {
    enum Language: String { case en, es }

    let language: Language

    /// The first preferred language Sukrara speaks, else English.
    static func preferred(_ languages: [String] = Locale.preferredLanguages) -> AlertText {
        let spoken = languages.lazy
            .compactMap { Locale(identifier: $0).language.languageCode?.identifier }
            .compactMap(Language.init(rawValue:))
            .first
        return AlertText(language: spoken ?? .en)
    }

    /// The notification title: the headline only (the same words on every platform, contracts/alerts/SPEC.md §1).
    func title(_ f: AlertFiring) -> String { headline(f) }

    /// The body: the value and arrow (`69 mg/dL ↘`), with the rate for falling / rising fast (`· falling 2.4
    /// mg/dL/min`), and for no data how long and the last value. Never the threshold, never a made-up number: the
    /// sensor's LO / HI as such (not its stored 39 / 401), and without a reading it says so.
    func body(_ f: AlertFiring, now: Date) -> String {
        let en = language == .en
        if f.kind == .noData {
            let since = Durations.text(
                max(Int(f.threshold.rounded()), Int(now.timeIntervalSince(f.reading?.at ?? now) / 60)))
            guard let r = f.reading else { return en ? "No reading for \(since)" : "Sin lecturas desde hace \(since)" }
            return en
                ? "No reading for \(since) · last \(value(r))" : "Sin lecturas desde hace \(since) · última \(value(r))"
        }
        guard let r = f.reading else { return en ? "No recent reading" : "Sin lectura reciente" }
        let shown = "\(value(r)) \(TrendAngle.text(r.rate))"
        guard let rate = r.rate, f.kind == .fallingFast || f.kind == .risingFast else { return shown }
        let amount = String(format: "%.1f", locale: Locale(identifier: language.rawValue), abs(rate))
        switch (language, f.kind) {
        case (.en, .fallingFast): return "\(shown) · falling \(amount) mg/dL/min"
        case (.en, _): return "\(shown) · rising \(amount) mg/dL/min"
        case (.es, .fallingFast): return "\(shown) · baja \(amount) mg/dL/min"
        case (.es, _): return "\(shown) · sube \(amount) mg/dL/min"
        }
    }

    /// `69 mg/dL`, or the sensor's LO / HI with what it means.
    private func value(_ r: GlucoseSample) -> String {
        if r.isLo { return language == .en ? "LO · below 40 mg/dL" : "LO · por debajo de 40 mg/dL" }
        if r.isHi { return language == .en ? "HI · above 400 mg/dL" : "HI · por encima de 400 mg/dL" }
        return "\(Int(r.mgdl.rounded())) mg/dL"
    }

    /// The headline in the language (English: the engine's SPEC copy; highs never suggest more insulin).
    func headline(_ f: AlertFiring) -> String {
        guard language == .es else { return f.headline }
        let base: String
        switch f.kind {
        case .veryLow: base = "Glucosa muy baja"
        case .low: base = "Glucosa baja"
        case .high: base = "Glucosa alta"
        case .veryHigh: base = "Glucosa muy alta"
        case .extremelyHigh: base = "Glucosa extremadamente alta"
        case .fallingFast: base = "Bajando rápido"
        case .risingFast: base = "Subiendo rápido"
        case .noData: base = "Sin datos de glucosa"
        }
        guard f.treated > 0, f.kind.group == .level else { return base }
        if f.kind.isBelowThreshold { return base + (f.treated == 1 ? " · come más" : " · vuelve a comer") }
        return base + (f.treated == 1 ? " · sigue alta tras la insulina" : " · sigue alta tras más insulina")
    }

    /// The muted note: "Low alerts muted" / "Until 10:42".
    func mutedTitle(_ side: AlertSide) -> String {
        switch language {
        case .en: return MuteCopy.notificationTitle(side)
        case .es:
            let name: String
            switch side {
            case .low: name = "de glucosa baja"
            case .high: name = "de glucosa alta"
            case .fallingFast: name = "de bajada rápida"
            case .risingFast: name = "de subida rápida"
            case .noData: name = "sin datos"
            }
            return "Alertas \(name) silenciadas"
        }
    }

    func mutedBody(until: String) -> String {
        language == .en ? MuteCopy.notificationText(until: until) : "Hasta las \(until)"
    }

    // Action titles (HIG title case in English; the ellipsis = more input follows).
    var mute: String { language == .en ? "Mute…" : "Silenciar…" }
    var muteButton: String { language == .en ? "Mute" : "Silenciar" }
    var mutePlaceholder: String { language == .en ? "30 min, 1 h, 2 h…" : "30 min, 1 h, 2 h…" }
    var remindLess: String { language == .en ? "Remind Less" : "Recordar menos" }
    var remindAsUsual: String { language == .en ? "Remind as Usual" : "Recordar como siempre" }
    var unmute: String { language == .en ? "Unmute" : "Reactivar" }
    var changeTime: String { language == .en ? "Change Time…" : "Cambiar hora…" }
}
