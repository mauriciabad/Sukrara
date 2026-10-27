// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import Testing

@testable import SukraraApp

/// Recorded GitHub answers; no network.
struct RecordedFetcher: HTTPFetching {
    let body: Data?
    func get(_ url: URL) async throws -> Data {
        guard let body else { throw URLError(.notConnectedToInternet) }
        return body
    }
}

@Suite struct UpdateCheckTests {
    /// Shape of `GET /repos/mauriciabad/Sukrara/releases` (trimmed to the fields read).
    static let releases = Data(
        #"""
        [
          {"tag_name": "v0.91.0-rc.1", "html_url": "https://github.com/mauriciabad/Sukrara/releases/tag/v0.91.0-rc.1", "draft": false, "prerelease": true, "name": "0.91.0 RC 1"},
          {"tag_name": "v0.92.0", "html_url": "https://github.com/mauriciabad/Sukrara/releases/tag/v0.92.0", "draft": true, "prerelease": false, "name": "draft"},
          {"tag_name": "v0.90.1", "html_url": "https://github.com/mauriciabad/Sukrara/releases/tag/v0.90.1", "draft": false, "prerelease": false, "name": "0.90.1"},
          {"tag_name": "v0.11.53", "html_url": "https://github.com/mauriciabad/Sukrara/releases/tag/v0.11.53", "draft": false, "prerelease": false, "name": "Showcase"}
        ]
        """#.utf8)

    func releases() throws -> [GitHubRelease] { try JSONDecoder().decode([GitHubRelease].self, from: Self.releases) }

    @Test func versionsCompareWithPrereleasesFirst() {
        #expect(AppVersion("v0.90.1")! > AppVersion("0.90.0")!)
        #expect(AppVersion("0.91.0-rc.1")! < AppVersion("0.91.0")!)
        #expect(AppVersion("0.91.0-rc.10")! > AppVersion("0.91.0-rc.2")!)
        #expect(AppVersion("0.11.53")! < AppVersion("0.90.0")!)
        #expect(AppVersion("1.2")?.description == "1.2.0")
        #expect(AppVersion("garbage") == nil)
    }

    @Test func zeroXOffersPrereleasesAndSkipsDrafts() throws {
        let pick = UpdateCheck.pick(try releases(), installed: AppVersion("0.90.0")!)
        #expect(pick?.0.tagName == "v0.91.0-rc.1")
    }

    @Test func oneXOffersOnlyFinalReleases() throws {
        var list = try releases()
        list.append(
            GitHubRelease(
                tagName: "v1.1.0-rc.1", htmlURL: URL(string: "https://example.invalid")!, draft: false,
                prerelease: true, name: nil))
        #expect(UpdateCheck.pick(list, installed: AppVersion("1.0.0")!) == nil)
    }

    @Test func nothingNewerIsUpToDateOnlyWhenAsked() async {
        let defaults = UserDefaults(suiteName: "update-\(UUID().uuidString)")!
        let check = UpdateCheck(
            installed: AppVersion("0.99.0")!, fetcher: RecordedFetcher(body: Self.releases), defaults: defaults)
        await check.check(userAsked: false)
        #expect(check.state == .idle)
        await check.check(userAsked: true)
        #expect(check.state == .upToDate)
    }

    @Test func laterSkipsThatVersionUntilCheckedByHand() async {
        let defaults = UserDefaults(suiteName: "update-\(UUID().uuidString)")!
        let check = UpdateCheck(
            installed: AppVersion("0.90.0")!, fetcher: RecordedFetcher(body: Self.releases), defaults: defaults)
        await check.check(userAsked: false)
        guard case .available(_, let v) = check.state else {
            Issue.record("nothing offered")
            return
        }
        #expect(v.description == "0.91.0-rc.1")
        check.later()
        await check.check(userAsked: false)
        #expect(check.state == .idle)
        await check.check(userAsked: true)
        if case .available = check.state {} else { Issue.record("a manual check offers it again") }
    }

    @Test func offlineFailsQuietlyUnlessAsked() async {
        let defaults = UserDefaults(suiteName: "update-\(UUID().uuidString)")!
        let check = UpdateCheck(
            installed: AppVersion("0.90.0")!, fetcher: RecordedFetcher(body: nil), defaults: defaults)
        await check.check(userAsked: false)
        #expect(check.state == .idle)
        await check.check(userAsked: true)
        #expect(check.state == .failed)
    }
}
