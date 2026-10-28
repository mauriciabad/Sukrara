// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.owner.usecase

import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.protocol.SignedEvent
import app.mauri.sukrara.protocol.Wire
import app.mauri.sukrara.protocol.event.AlertPayload
import app.mauri.sukrara.protocol.event.LivePayload
import app.mauri.sukrara.protocol.event.WireReading
import app.mauri.sukrara.protocol.owner.IdentityCheck
import app.mauri.sukrara.protocol.owner.IdentityConflict
import app.mauri.sukrara.share.owner.ConflictRecord
import app.mauri.sukrara.share.owner.OwnerState
import java.util.TreeMap

/**
 * Readings to followers: a live event for each new reading younger than [LIVE_MAX_AGE]; hour chunks for
 * every hour that changed — earlier hours at once, the current hour at most every [CHUNK_EVERY] — with `created_at`
 * strictly increasing per hour. Keeps the last [BACKFILL_HOURS] in memory (what a new follower or a rotation gets).
 * Not thread-safe: the engine calls it on its serial dispatcher.
 */
class PublishReadings(private val ctx: OwnerContext) {
    private val readings = TreeMap<Long, WireReading>()
    private val dirty = sortedSetOf<Long>()
    private val chunkSentAt = mutableMapOf<Long, Long>()
    private val chunkCreatedAt = mutableMapOf<Long, Long>()
    private var newestLive = 0L

    /** New readings from the hub (oldest first). Returns the events to publish. */
    fun onReadings(s: OwnerState, new: List<Reading>, now: Long): List<SignedEvent> {
        val minT = Wire.hourOf(now) - (BACKFILL_HOURS - 1) * Wire.HOUR
        val out = mutableListOf<SignedEvent>()
        for (r in new.sortedBy { it.t }) {
            val w = WireReading(r.t.epochSecond, r.mgdl, r.rateMgdlPerMin)
            if (w.t < minT || w.t > now + FUTURE_SLACK) continue
            if (readings.put(w.t, w) != w) dirty += Wire.hourOf(w.t)
            if (w.t > newestLive && now - w.t < LIVE_MAX_AGE) {
                newestLive = w.t
                out += ctx.events.live(s.device(ctx.crypto), s.readKey(ctx.crypto).public, LivePayload(w, null), now)
            }
        }
        prune(now)
        return out + flush(s, now, force = false)
    }

    /** Every hour of the last 24 h again under the current epoch (start, approval, rotation, a follower's request). */
    fun republish(s: OwnerState, now: Long, since: Long = 0): List<SignedEvent> {
        val from = Wire.hourOf(maxOf(since, now - 24 * Wire.HOUR))
        dirty += readings.keys.filter { it >= from }.map(Wire::hourOf)
        return flush(s, now, force = true)
    }

    /** Called every [CHUNK_EVERY]: the current hour's chunk if it changed. */
    fun tick(s: OwnerState, now: Long): List<SignedEvent> = flush(s, now, force = false)

    fun publishAlert(s: OwnerState, alert: AlertPayload, now: Long): SignedEvent =
        ctx.events.alert(s.device(ctx.crypto), s.readKey(ctx.crypto).public, alert, now)

    private fun flush(s: OwnerState, now: Long, force: Boolean): List<SignedEvent> {
        val current = Wire.hourOf(now)
        val out = mutableListOf<SignedEvent>()
        for (h in dirty.descendingSet().toList()) {
            if (!force && h >= current && now - (chunkSentAt[h] ?: 0) < CHUNK_EVERY) continue
            dirty -= h
            val rows = readings.subMap(h, h + Wire.HOUR).values
            if (rows.isEmpty()) continue
            val createdAt = maxOf(now, (chunkCreatedAt[h] ?: 0) + 1)
            out += ctx.events.chunk(s.device(ctx.crypto), s.readKey(ctx.crypto).public, s.sid, h, rows, createdAt)
            chunkSentAt[h] = now
            chunkCreatedAt[h] = createdAt
        }
        return out
    }

    private fun prune(now: Long) {
        val cutoff = Wire.hourOf(now) - BACKFILL_HOURS * Wire.HOUR
        readings.headMap(cutoff).clear()
        dirty.removeIf { it < cutoff }
        chunkSentAt.keys.removeIf { it < cutoff - Wire.HOUR }
        chunkCreatedAt.keys.removeIf { it < cutoff - Wire.HOUR }
    }

    companion object {
        const val BACKFILL_HOURS = 25L
        const val CHUNK_EVERY = 300L
        const val LIVE_MAX_AGE = 600L
        const val FUTURE_SLACK = 60L
    }
}

/**
 * Watches the roster / backup events signed by our identity that come back from relays. One from another device
 * (e.g. the old phone still sharing after a restore) blocks roster / backup / rotation publishing until the person
 * retries — never republishing back and forth.
 */
class IdentityConflictDetector(private val ctx: OwnerContext) {
    /** The state with the conflict recorded, or null when [ev] is fine (ours, older or unrelated). */
    fun check(s: OwnerState, ev: SignedEvent, now: Long): OwnerState? =
        when (val r = IdentityConflict.check(ctx.crypto, s.marks(ctx.crypto), ev)) {
            IdentityCheck.Fine -> {
                null
            }

            is IdentityCheck.Conflict -> {
                val newest = maxOf(ev.createdAt, s.conflict?.eventCreatedAt ?: 0)
                s.copy(conflict = ConflictRecord(r.reason, now, newest))
            }
        }

    /**
     * "I turned it off — retry": clears the block and republishes roster and backup with `created_at` past the other
     * app's event, so relays keep ours. If the other app is still running, its next event blocks again.
     */
    fun retry(s: OwnerState, now: Long): Step {
        val conflict = s.conflict ?: return Step(s)
        // the conflict's created_at is the floor for this publish (ControlEvents.nextAt), then it is forgotten. After
        // the other app rotated (unknown epoch) a plain roster would be older than its epoch: rotate instead, so our
        // followers get a newer key naming this phone.
        val step =
            if (conflict.reason == IdentityCheck.Reason.RosterUnknownEpoch) {
                Rotate(ctx)(s, now)
            } else {
                ControlEvents.roster(ctx, s, now) + { ControlEvents.backup(ctx, it, now) }
            }
        return Step(step.state.copy(conflict = null), step.publish)
    }

    companion object {
        /** Whether followers still accept this phone's readings during [reason] (false once the other app rotated). */
        fun followersReceiving(reason: IdentityCheck.Reason): Boolean = reason != IdentityCheck.Reason.RosterUnknownEpoch
    }
}
