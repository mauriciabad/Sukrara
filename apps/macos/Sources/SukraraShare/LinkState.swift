// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later

/// Whether this Mac follows a wearer. Placeholder: chunk 15 (follower runtime) adds the connecting / stale /
/// stopped states with their exits.
public enum LinkState: Sendable, Equatable {
    /// Not following anyone yet (first run, or after "Stop following").
    case notLinked
}
