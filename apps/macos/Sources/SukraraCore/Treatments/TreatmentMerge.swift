// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// The treatment merge rule both platforms apply (contracts/treatments, SPEC §1): of two versions of one id, the one
/// with the newer `u` wins; on equal `u` the greater `o` ("phone" > "mac" > "follower"), so every device converges on
/// the same version whatever order they arrive in. A deletion is just a newer version with `del`.
public enum TreatmentMerge {
    public static func winner(_ current: Treatment?, _ incoming: Treatment) -> Treatment {
        guard let current else { return incoming }
        return (incoming.u, incoming.o) > (current.u, current.o) ? incoming : current
    }
}
