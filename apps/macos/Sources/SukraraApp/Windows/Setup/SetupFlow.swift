// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraCore

/// The Mac's first-run walk as a pure state machine: Welcome → Follow → Alerts (thresholds confirmed explicitly) →
/// Notifications → Open at login → Done. Every step can be done or skipped; a skipped step keeps "Finish setup"
/// visible in the panel until it's done. The step states persist under the schema key `setup.steps`.
public struct SetupFlow: Equatable, Sendable {
    public enum Step: String, CaseIterable, Codable, Sendable {
        case welcome, follow, alerts, notifications, openAtLogin
    }

    public enum StepState: String, Codable, Sendable {
        case done, skipped
    }

    public private(set) var states: [Step: StepState]
    /// The step on screen; nil = the walk is over (Done page or closed).
    public private(set) var current: Step?

    public init(states: [Step: StepState] = [:]) {
        self.states = states
        self.current = Step.allCases.first { states[$0] == nil }
    }

    /// The walk was never finished or skipped through: open it at launch.
    public var isFirstRun: Bool { states.isEmpty }
    /// Steps left to do: skipped or never reached ("Finish setup" lists them; empty = setup complete).
    public var unfinished: [Step] { Step.allCases.filter { $0 != .welcome && states[$0] != .done } }
    public var needsFinishing: Bool { !isFirstRun && !unfinished.isEmpty }
    public var isComplete: Bool { unfinished.isEmpty }

    public mutating func complete(_ step: Step) { record(step, .done) }
    public mutating func skip(_ step: Step) { record(step, .skipped) }

    /// Back to the previous step (Back button); the state of the step left stays as it was.
    public mutating func back() {
        guard let c = current, let i = Step.allCases.firstIndex(of: c), i > 0 else { return }
        current = Step.allCases[i - 1]
    }

    /// "Finish setup": reopen at the first unfinished step.
    public mutating func resume() { current = unfinished.first }

    /// Jump to one step (Finish setup row → that step).
    public mutating func open(_ step: Step) { current = step }

    private mutating func record(_ step: Step, _ state: StepState) {
        // Skipping never overwrites a step already done (e.g. Follow done earlier, then walking past it again).
        if !(state == .skipped && states[step] == .done) { states[step] = state }
        let order = Step.allCases
        guard let i = order.firstIndex(of: step) else { return }
        current = order.dropFirst(i + 1).first { states[$0] != .done }
    }

    // MARK: Persistence (`setup.steps`)

    public init(store: some SettingsStoring) {
        let data = store.value(MacSettings.setupSteps)
        let raw = (try? JSONDecoder().decode([String: StepState].self, from: data)) ?? [:]
        self.init(states: Dictionary(uniqueKeysWithValues: raw.compactMap { k, v in Step(rawValue: k).map { ($0, v) } }))
    }

    public func save(to store: some SettingsStoring) {
        let raw = Dictionary(uniqueKeysWithValues: states.map { ($0.key.rawValue, $0.value) })
        let e = JSONEncoder()
        e.outputFormatting = .sortedKeys
        store.set((try? e.encode(raw)) ?? Data(), for: MacSettings.setupSteps)
    }
}
