// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// The windows' visible text, from the String Catalog (`Localizable.xcstrings`, en + es) in this module's bundle.
nonisolated enum L10n {
    static func t(_ key: String.LocalizationValue) -> String { String(localized: key, bundle: bundle) }

    #if DEBUG
        /// The table the text comes from: the module (the Mac's language), or one language's table for snapshots.
        nonisolated(unsafe) static var bundle: Bundle = .module

        /// Snapshots render one language whatever the Mac's (`sukrara-snap windows-es`). Debug builds only.
        static func use(language: String) {
            guard let path = Bundle.module.path(forResource: language, ofType: "lproj"), let b = Bundle(path: path)
            else { return }
            bundle = b
        }
    #else
        static var bundle: Bundle { .module }
    #endif
}
