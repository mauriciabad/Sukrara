// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.treatments

import app.mauri.sukrara.core.model.storage.TreatmentRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Who this phone writes treatments as. [key] = the sharing identity that creates the entries (null on a wearer that
 * doesn't share); [throughOutbox] = a follower the wearer allowed to add entries: its entries stay *pending* until
 * the wearer has them.
 */
data class TreatmentWriter(
    val key: String?,
    val throughOutbox: Boolean,
)

/**
 * The share layer's side of treatments (`:share` implements it, chunk 13): whether and as whom this phone may add
 * entries, and the hand-off of every stored version (new, edited, deleted, undone) for publishing in the treatments
 * wire format of contracts/protocol.
 */
interface TreatmentSync {
    /** Null = this phone can't add entries (a follower without "Can add entries", or no role yet). */
    val writer: StateFlow<TreatmentWriter?>

    /** Called after [record] was stored locally; publishing failures stay inside the share layer (retried there). */
    suspend fun publish(record: TreatmentRecord)
}

/** A wearer phone that doesn't share (or until the share layer is composed): entries are kept in the local log only. */
class LocalTreatmentSync : TreatmentSync {
    override val writer: StateFlow<TreatmentWriter?> = MutableStateFlow(TreatmentWriter(key = null, throughOutbox = false))

    override suspend fun publish(record: TreatmentRecord) = Unit
}
