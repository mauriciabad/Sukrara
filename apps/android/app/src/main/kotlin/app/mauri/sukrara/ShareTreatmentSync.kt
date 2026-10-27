// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara

import app.mauri.sukrara.core.model.storage.TreatmentRecord
import app.mauri.sukrara.feature.treatments.TreatmentSync
import app.mauri.sukrara.feature.treatments.TreatmentWriter
import app.mauri.sukrara.share.ShareOwnerModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The treatment log's hand-off to sharing (chunk 19 ⇄ 13). The wearer writes as itself (no creator key: an entry
 * without a writer is the wearer's own, so no follower may change it) and every stored version goes to its followers;
 * without a sharing identity the owner engine ignores it and the next start republishes the last 30 days.
 * A follower that may add entries writes through the follower engine's outbox (chunk 25), not here.
 */
class ShareTreatmentSync(private val share: ShareOwnerModule) : TreatmentSync {
    override val writer: StateFlow<TreatmentWriter?> = MutableStateFlow(TreatmentWriter(key = null, throughOutbox = false))

    /** Hands [record] over without waiting: a save never waits behind a restore or a republish (failures stay in sharing). */
    override suspend fun publish(record: TreatmentRecord) {
        share.scope.launch { runCatching { share.ownerEngine.publishTreatment(record) } }
    }
}
