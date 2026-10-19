// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.tidepool

import java.time.ZoneId

/**
 * Sends one [TidepoolQueue.Plan] (plain JVM: the app's cycle and the end-to-end harness run this same code).
 * Readings go in batches and each batch is remembered at once through [save], so a long backfill keeps its progress
 * through a failure; the treatments and notes follow, then the whole plan is remembered.
 */
object TidepoolSync {
    fun send(client: TidepoolClient, dataSet: String, plan: TidepoolQueue.Plan, state: TidepoolQueue.State, zone: ZoneId,
             device: String, version: String, nowS: Long, save: (TidepoolQueue.State) -> Unit): TidepoolQueue.State {
        var st = state
        val empty = TidepoolQueue.Plan(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        for (chunk in plan.cbg.chunked(TidepoolQueue.BATCH)) {
            client.addData(dataSet, chunk.map { TidepoolData.cbg(it, zone, device, version) })
            st = TidepoolQueue.applied(st, empty.copy(cbg = chunk), emptyMap(), nowS).also(save)
        }
        if (plan.deletes.isNotEmpty()) client.deleteData(dataSet, plan.deletes.map { TidepoolData.treatmentOrigin(it.id) })
        client.addData(dataSet, plan.upserts.mapNotNull { TidepoolData.treatment(it, zone, device, version) })
        for (t in plan.noteDeletes) st.notes[t.id]?.let { client.removeNote(it) }
        for (t in plan.noteEdits) client.editNote(st.notes.getValue(t.id), TidepoolData.noteEdit(t))
        val userId = client.session?.userId ?: throw TidepoolClient.SignedOut("not signed in")
        for (t in plan.noteCreates) {   // remembered one by one: a retry must not post a note twice
            val id = client.sendNote(TidepoolData.note(t, userId))
            st = TidepoolQueue.applied(st, empty.copy(noteCreates = listOf(t)), mapOf(t.id to id), nowS).also(save)
        }
        return TidepoolQueue.applied(st, plan.copy(cbg = emptyList(), noteCreates = emptyList()), emptyMap(), nowS).also(save)
    }
}
