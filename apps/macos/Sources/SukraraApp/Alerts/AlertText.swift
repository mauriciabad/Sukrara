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

    /// "Low · 62 mg/dL ↓" (no reading: just the meaning); the sensor's LO / HI as such, never its stored 39 / 401.
    func title(_ f: AlertFiring) -> String {
        guard let r = f.reading, f.kind != .noData else { return headline(f) }
        if r.isLo {
            return "\(headline(f)) · LO · " + (language == .en ? "below 40 mg/dL" : "por debajo de 40 mg/dL")
        }
        if r.isHi {
            return "\(headline(f)) · HI · " + (language == .en ? "above 400 mg/dL" : "por encima de 400 mg/dL")
        }
        return "\(headline(f)) · \(Int(r.mgdl.rounded())) mg/dL \(TrendAngle.text(r.rate))"
    }

    /// "Falling slowly. Below 70 mg/dL since 14:05." / "No reading for 20 min."
    func body(_ f: AlertFiring, now: Date) -> String {
        if f.kind == .noData {
            let minutes = f.reading.map { Durations.minutesLeft(now.timeIntervalSince($0.at)) }
            switch language {
            case .en: return minutes.map { "No reading for \(Durations.text($0))." } ?? "No reading."
            case .es: return minutes.map { "Sin lecturas desde hace \(Durations.text($0))." } ?? "Sin lecturas."
            }
        }
        let trend = trendWords(f.reading?.rate)
        let threshold = Int(f.threshold.rounded())
        let limit: String
        switch (language, f.kind) {
        case (_, .fallingFast), (_, .risingFast):
            limit =
                language == .en
                ? "Faster than \(f.threshold.formatted()) mg/dL per minute."
                : "Más de \(f.threshold.formatted()) mg/dL por minuto."
        case (.en, _): limit = f.kind.isBelowThreshold ? "Below \(threshold) mg/dL." : "At or above \(threshold) mg/dL."
        case (.es, _):
            limit = f.kind.isBelowThreshold ? "Por debajo de \(threshold) mg/dL." : "\(threshold) mg/dL o más."
        }
        return "\(trend). \(limit)"
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
        if f.kind.isBelowThreshold { return base + (f.treated == 1 ? " · come algo más" : " · come algo más otra vez") }
        return base + (f.treated == 1 ? " · sigue alta tras la insulina" : " · sigue alta tras más insulina")
    }

    func trendWords(_ rate: Double?) -> String {
        guard let r = rate else { return language == .en ? "Trend unknown" : "Tendencia desconocida" }
        switch language {
        case .en:
            return r >= 2
                ? "Rising fast" : r >= 1 ? "Rising" : r > -1 ? "Steady" : r > -2 ? "Falling" : "Falling fast"
        case .es:
            return r >= 2
                ? "Subiendo rápido" : r >= 1 ? "Subiendo" : r > -1 ? "Estable" : r > -2 ? "Bajando" : "Bajando rápido"
        }
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
