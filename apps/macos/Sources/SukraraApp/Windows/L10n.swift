// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// The windows' visible text, from the String Catalog (`Localizable.xcstrings`, en + es) in this module's bundle.
nonisolated enum L10n {
    static func t(_ key: String.LocalizationValue) -> String { String(localized: key, bundle: .module) }
}
