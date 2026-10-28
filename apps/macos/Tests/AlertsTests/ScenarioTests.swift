// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Testing

/// The shared alert timelines (`contracts/alerts/scenarios`, SPEC §7): the regression guard both platforms run.
/// Expectations never change without the owner's OK (CLAUDE.md).
@Suite struct ScenarioTests {
    @Test func scenarioFilesAreFound() {
        #expect(Scenario.all().count >= 50, "no scenarios under \(Scenario.directory.path)")
    }

    /// The driver isn't vacuous: a wrong or missing expectation is reported.
    @Test func driverReportsDifferences() throws {
        let wrong = Scenario.parse("t.txt", "scenario wrong\n 0 glucose 69 => High glucose\n 5")
        #expect(
            try runScenario(wrong[0]) == [
                "minute 0: expected [High glucose], got [Low glucose]",
                "minute 5: expected [nothing], got [Low glucose]",
            ])
        let missing = Scenario.parse("t.txt", "scenario missing\n 0 glucose 69\n 5")
        #expect(
            try runScenario(missing[0]) == [
                "minute 0: expected [nothing], got [Low glucose]", "minute 5: expected [nothing], got [Low glucose]",
            ])
    }

    @Test(arguments: Scenario.all())
    func scenario(_ sc: Scenario) throws {
        let diffs = try runScenario(sc)
        #expect(diffs.isEmpty, "\(sc.file): \(sc.title)\n\(diffs.joined(separator: "\n"))")
    }
}
