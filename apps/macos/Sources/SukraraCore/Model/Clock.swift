// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// The time source. Everything that compares against "now" (staleness, alert timers, snoozes) takes a `Clock`, so
/// tests drive time with a fake instead of sleeping.
public protocol Clock: Sendable {
    var now: Date { get }
}

/// Wall-clock time.
public struct SystemClock: Clock {
    public init() {}
    public var now: Date { Date() }
}
