// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara

import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.storage.StateStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

/** The typed state file `role.json`: the phone's role from first-run setup; null = setup not done yet. */
@Serializable
data class RoleRecord(
    val role: Role? = null,
)

/**
 * The role, kept in two files (`role.json` and `role-backup.json`) so one damaged file never stops monitoring: a
 * damaged file is quarantined and reported (home's data-problem banner) and reads as "no role", and then the other
 * copy answers. Without that, a damaged `role.json` would send the phone back to setup and silently stop the service
 * and its alerts. Writers use [update] (chunk 24's setup); it writes the primary first, then the backup.
 */
class RoleStore(
    private val primary: StateStore<RoleRecord>,
    private val backup: StateStore<RoleRecord>,
) : StateStore<RoleRecord> {
    override val data: Flow<RoleRecord> = combine(primary.data, backup.data) { p, b -> if (p.role != null) p else b }

    /** Not atomic across the two files: the role has one writer (setup), never two at once. */
    override suspend fun update(transform: (RoleRecord) -> RoleRecord) {
        val next = transform(data.first())
        primary.update { next }
        backup.update { next }
    }

    /**
     * Brings the two copies back in line at start (`AppGraph.ready`): a role only one file has is copied to the other
     * (an install from before the backup existed, a write cut between the two files, a quarantined file). The primary
     * wins when both have one: it is written first. A role clear cut between the two writes comes back from the
     * backup: on purpose, keeping monitoring is the safe failure (setup clears it again).
     */
    suspend fun heal() {
        val p = primary.data.first()
        val b = backup.data.first()
        when {
            p.role != null && p != b -> backup.update { p }
            p.role == null && b.role != null -> primary.update { b }
        }
    }
}
