// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import Testing

/// The String Catalog is written by hand (SwiftPM doesn't extract keys): every key has en + es with the same format
/// specifiers, and every `L10n.t("…")` literal in SukraraApp is a key of the catalog.
@Suite struct LocalizationTests {
    static let macRoot = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        .deletingLastPathComponent()
    static let sources = macRoot.appending(path: "Sources/SukraraApp")
    static let catalog = sources.appending(path: "Windows/Localizable.xcstrings")
    static let languages = ["en", "es"]

    /// key → language → value.
    func catalogValues() throws -> [String: [String: String]] {
        let json = try JSONSerialization.jsonObject(with: Data(contentsOf: Self.catalog)) as? [String: Any]
        let strings = try #require(json?["strings"] as? [String: Any])
        return strings.mapValues { entry in
            let localizations = (entry as? [String: Any])?["localizations"] as? [String: Any] ?? [:]
            return localizations.compactMapValues { loc in
                ((loc as? [String: Any])?["stringUnit"] as? [String: Any])?["value"] as? String
            }
        }
    }

    /// The format specifiers of `s`, positions dropped ("%1$@" → "%@"), sorted.
    static func specifiers(_ s: String) -> [String] {
        s.matches(of: /%(?:\d+\$)?(lld|lf|@|d)/).map { "%" + $0.output.1 }.sorted()
    }

    @Test func everyKeyHasEnglishAndSpanishWithTheSameSpecifiers() throws {
        let values = try catalogValues()
        #expect(!values.isEmpty, "Localizable.xcstrings has no strings")
        var failing: [String] = []
        for (key, byLanguage) in values {
            for language in Self.languages {
                let value = byLanguage[language] ?? ""
                if value.isEmpty || Self.specifiers(value) != Self.specifiers(key) {
                    failing.append("\(language): \(key)")
                }
            }
        }
        #expect(
            failing.isEmpty, "Missing, empty or mismatched specifiers in Localizable.xcstrings: \(failing.sorted())")
    }

    @Test func everyL10nLiteralIsACatalogKey() throws {
        let keys = Set(try catalogValues().keys.map(Self.normalized))
        let files =
            FileManager.default.enumerator(at: Self.sources, includingPropertiesForKeys: nil)?
            .compactMap { $0 as? URL }.filter { $0.pathExtension == "swift" } ?? []
        var used: Set<String> = []
        for file in files { used.formUnion(Self.l10nKeys(in: try String(contentsOf: file, encoding: .utf8))) }
        #expect(used.count > 0, "No L10n.t(\"…\") calls found under \(Self.sources.path)")
        let missing = used.filter { !keys.contains(Self.normalized($0)) }.sorted()
        #expect(missing.isEmpty, "Add these keys (with en + es) to Localizable.xcstrings: \(missing)")
    }

    /// Every specifier as "%@", so an interpolation matches whatever type the catalog key uses.
    static func normalized(_ key: String) -> String { key.replacing(/%(?:\d+\$)?(lld|lf|@|d)/, with: "%@") }

    /// The keys of the `L10n.t("…")` literals in `source`: escapes resolved, each `\(…)` as "%@" (nested calls are
    /// found on their own).
    static func l10nKeys(in source: String) -> [String] {
        let chars = Array(source)
        let marker = Array("L10n.t(")
        var keys: [String] = []
        var i = 0
        while i + marker.count <= chars.count {
            if chars[i] == "L", Array(chars[i..<i + marker.count]) == marker {
                // the literal may start on the next line (a wrapped call)
                var j = i + marker.count
                while j < chars.count, chars[j].isWhitespace { j += 1 }
                if j < chars.count, chars[j] == "\"", let (key, _) = literal(chars, from: j + 1) { keys.append(key) }
            }
            i += 1
        }
        return keys
    }

    /// The string literal starting after its opening quote at `start`: its key and the index after the closing quote.
    static func literal(_ c: [Character], from start: Int) -> (String, Int)? {
        var out = ""
        var i = start
        while i < c.count {
            switch c[i] {
            case "\"": return (out, i + 1)
            case "\\" where i + 1 < c.count && c[i + 1] == "(":
                var depth = 1
                i += 2
                while depth > 0 && i < c.count {
                    if c[i] == "\"" {
                        guard let (_, end) = literal(c, from: i + 1) else { return nil }
                        i = end
                        continue
                    }
                    if c[i] == "(" { depth += 1 } else if c[i] == ")" { depth -= 1 }
                    i += 1
                }
                out += "%@"
            case "\\" where i + 1 < c.count:
                let escaped: [Character: String] = ["n": "\n", "t": "\t", "\"": "\"", "\\": "\\", "'": "'"]
                out += escaped[c[i + 1]] ?? String(c[i + 1])
                i += 2
            case "\n": return nil
            default:
                out.append(c[i])
                i += 1
            }
        }
        return nil
    }
}
