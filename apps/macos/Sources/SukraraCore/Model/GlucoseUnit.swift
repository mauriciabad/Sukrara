// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// The unit glucose is shown in: a setting (never derived from the region). Values are stored in mg/dL.
public enum GlucoseUnit: String, Sendable, CaseIterable, Codable {
    case mgdl
    case mmol

    /// mg/dL per mmol/L (glucose's molar mass / 10), the factor Android and the CSV export use.
    public static let mgdlPerMmol = 18.0182

    /// "mg/dL" / "mmol/L".
    public var symbol: String { self == .mgdl ? "mg/dL" : "mmol/L" }

    /// What a screen reader says for the unit.
    public var spoken: String { self == .mgdl ? "milligrams per decilitre" : "millimoles per litre" }

    /// A value as shown, without the unit: "112" / "6.2" (one decimal, '.' like Android's widgets and the CSV).
    public func format(_ mgdl: Int) -> String {
        switch self {
        case .mgdl: String(mgdl)
        case .mmol: String(format: "%.1f", Double(mgdl) / Self.mgdlPerMmol)
        }
    }
}
