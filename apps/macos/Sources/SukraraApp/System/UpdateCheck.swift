// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import Observation

/// A version like "0.12.1" or "v0.12.1-rc.2" (a pre-release suffix sorts before the plain version).
struct AppVersion: Comparable, Equatable, CustomStringConvertible {
    let parts: [Int]
    let prerelease: String?

    init?(_ text: String) {
        var s = Substring(text.trimmingCharacters(in: .whitespaces))
        if s.first == "v" || s.first == "V" { s = s.dropFirst() }
        let split = s.split(separator: "-", maxSplits: 1)
        guard let core = split.first else { return nil }
        let nums = core.split(separator: ".").map { Int($0) }
        guard !nums.isEmpty, nums.count <= 3, nums.allSatisfy({ $0 != nil }) else { return nil }
        parts = nums.compactMap { $0 } + Array(repeating: 0, count: 3 - nums.count)
        prerelease = split.count > 1 ? String(split[1]) : nil
    }

    var major: Int { parts[0] }
    var description: String { parts.map(String.init).joined(separator: ".") + (prerelease.map { "-\($0)" } ?? "") }

    static func < (a: AppVersion, b: AppVersion) -> Bool {
        if a.parts != b.parts { return a.parts.lexicographicallyPrecedes(b.parts) }
        switch (a.prerelease, b.prerelease) {
        case (nil, nil), (nil, _): return false
        case (_, nil): return true
        case (let x?, let y?): return x.compare(y, options: .numeric) == .orderedAscending
        }
    }
}

/// One GitHub release (the fields the check reads).
struct GitHubRelease: Decodable, Equatable {
    let tagName: String
    let htmlURL: URL
    let draft: Bool
    let prerelease: Bool
    let name: String?

    enum CodingKeys: String, CodingKey {
        case tagName = "tag_name", htmlURL = "html_url", draft, prerelease, name
    }
}

/// GETs a URL (tests return recorded JSON; the app uses `URLSession`).
protocol HTTPFetching: Sendable {
    func get(_ url: URL) async throws -> Data
}

struct URLSessionFetcher: HTTPFetching {
    func get(_ url: URL) async throws -> Data {
        var request = URLRequest(url: url, timeoutInterval: 20)
        request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        let (data, response) = try await URLSession.shared.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw URLError(.badServerResponse) }
        return data
    }
}

/// Is there a newer Sukrara? Asks GitHub at most once a day; offers *Download* (opens the release page) and *Later*
/// (skips that version). No auto-install. While the app is 0.x, pre-releases are offered too (every 0.x build is a
/// release candidate).
@Observable
final class UpdateCheck {
    enum State: Equatable {
        case idle
        case available(GitHubRelease, AppVersion)
        /// The last check failed (offline, rate limited): shown only after an explicit "Check for Updates".
        case failed
        case upToDate
    }

    static let releasesURL = URL(string: "https://api.github.com/repos/mauriciabad/sukrara/releases")!

    private(set) var state: State = .idle
    @ObservationIgnored private let fetcher: any HTTPFetching
    @ObservationIgnored private let installed: AppVersion
    @ObservationIgnored private let defaults: UserDefaults
    @ObservationIgnored private static let skippedKey = "updateSkippedVersion"

    init(installed: AppVersion, fetcher: any HTTPFetching, defaults: UserDefaults) {
        self.installed = installed
        self.fetcher = fetcher
        self.defaults = defaults
    }

    /// The newest non-draft release above `installed` (pre-releases only while `installed` is 0.x), or nil.
    static func pick(_ releases: [GitHubRelease], installed: AppVersion) -> (GitHubRelease, AppVersion)? {
        releases
            .filter { !$0.draft && (!$0.prerelease || installed.major == 0) }
            .compactMap { r in AppVersion(r.tagName).map { (r, $0) } }
            .filter { $0.1 > installed }
            .max { $0.1 < $1.1 }
    }

    /// Checks now. `userAsked`: a failure or "up to date" is shown, and a skipped version is offered again.
    func check(userAsked: Bool) async {
        do {
            let data = try await fetcher.get(Self.releasesURL)
            let releases = try JSONDecoder().decode([GitHubRelease].self, from: data)
            guard let (release, version) = Self.pick(releases, installed: installed) else {
                state = userAsked ? .upToDate : .idle
                return
            }
            let skipped = defaults.string(forKey: Self.skippedKey)
            state = !userAsked && skipped == version.description ? .idle : .available(release, version)
        } catch {
            AppLog.update.error("update check failed: \(error.localizedDescription, privacy: .public)")
            state = userAsked ? .failed : .idle
        }
    }

    /// *Later*: don't offer this version again unless the user checks by hand.
    func later() {
        if case .available(_, let version) = state { defaults.set(version.description, forKey: Self.skippedKey) }
        state = .idle
    }

    func dismiss() { state = .idle }
}
