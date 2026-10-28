// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara

import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.storage.TreatmentRecord
import app.mauri.sukrara.feature.treatments.TreatmentSync
import app.mauri.sukrara.feature.treatments.TreatmentWriter
import app.mauri.sukrara.share.ShareOwnerModule
import app.mauri.sukrara.share.follower.FollowState
import app.mauri.sukrara.share.follower.FollowerEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The treatment log's hand-off to sharing, by role. The wearer writes as itself (no creator key: an entry without a
 * writer is the wearer's own, so no follower may change it) and every stored version goes to its followers; without a
 * sharing identity the owner engine ignores it and the next start republishes the last 30 days. A follower writes
 * only while the wearer lets it add entries, as its follower key, and its entries stay pending until a relay has them
 * (the follower engine resends them on every connect).
 */
class ShareTreatmentSync(
    private val share: ShareOwnerModule,
    private val follower: FollowerEngine,
    role: Flow<Role?>,
    scope: CoroutineScope,
) : TreatmentSync {
    override val writer: StateFlow<TreatmentWriter?> =
        combine(role, follower.state) { r, f ->
            when (r) {
                Role.Follower -> {
                    if (f is FollowState.Following &&
                        f.canWrite
                    ) {
                        follower.followerKey()?.let { TreatmentWriter(it, throughOutbox = true) }
                    } else {
                        null
                    }
                }

                else -> {
                    TreatmentWriter(key = null, throughOutbox = false)
                }
            }
        }.stateIn(scope, SharingStarted.Eagerly, TreatmentWriter(key = null, throughOutbox = false))

    private val role = role.stateIn(scope, SharingStarted.Eagerly, null)

    /** Hands [record] over without waiting: a save never waits behind a restore or a republish (failures stay in sharing). */
    override suspend fun publish(record: TreatmentRecord) {
        share.scope.launch {
            runCatching {
                if (role.value == Role.Follower) follower.publishTreatment(record) else share.ownerEngine.publishTreatment(record)
            }
        }
    }
}
