// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.protocol.follow

import app.mauri.sukrara.protocol.KeyPair
import app.mauri.sukrara.protocol.NostrCrypto
import app.mauri.sukrara.protocol.PublicKeyHex
import app.mauri.sukrara.protocol.SecretKey
import app.mauri.sukrara.protocol.SignedEvent
import app.mauri.sukrara.protocol.Wire
import app.mauri.sukrara.protocol.event.AlertCodec
import app.mauri.sukrara.protocol.event.AlertPayload
import app.mauri.sukrara.protocol.event.ChunkPayload
import app.mauri.sukrara.protocol.event.InvalidPayload
import app.mauri.sukrara.protocol.event.LivePayload
import app.mauri.sukrara.protocol.event.ReadingsCodec
import app.mauri.sukrara.protocol.event.RosterCodec
import app.mauri.sukrara.protocol.event.RosterPayload
import app.mauri.sukrara.protocol.event.Rumor
import app.mauri.sukrara.protocol.event.RumorCodec
import app.mauri.sukrara.protocol.event.SettingsCodec
import app.mauri.sukrara.protocol.event.SettingsPayload
import app.mauri.sukrara.protocol.event.TreatmentCodec
import app.mauri.sukrara.protocol.event.TreatmentPayload
import app.mauri.sukrara.protocol.event.WriterScope

/** Where a follower stands with the wearer. */
enum class FollowStatus { New, Requested, Active, Declined, InviteUsed, Revoked }

/** A read key the follower holds: epoch [n] and its key pair (secret redacted in logs). */
data class Epoch(val n: Int, val key: KeyPair)

/**
 * Everything a follower must remember to judge the next event: who the wearer is, which keys it trusts, and the dedupe /
 * ordering marks. Not the data itself (readings, treatments, settings are stored by the follower engine). Chunk 25
 * persists it (the epochs hold secrets: SecureStore).
 */
data class FollowerState(
    val follower: KeyPair,
    val identity: PublicKeyHex?,
    val status: FollowStatus = FollowStatus.New,
    val sid: String? = null,
    val epochs: Map<String, Epoch> = emptyMap(),
    val devices: List<String> = emptyList(),
    val writers: List<String> = emptyList(),
    val relays: List<String> = emptyList(),
    val roster: RosterPayload? = null,
    val rosterEpoch: Int = -1,
    val rosterCreatedAt: Long = -1,
    val sensorCreatedAt: Long = -1,
    val settingsU: Long = -1,
    /** Treatment id → key that signed the first accepted version (writer scope). */
    val creators: Map<String, String> = emptyMap(),
    /** Treatment id → `(u, o)` of the version held (merge order and writer scope). */
    val held: Map<String, Pair<Long, String>> = emptyMap(),
    /** Alert `(id, state, n)` → its `at`, kept for [Wire.ALERT_MAX_AGE]. */
    val alertsSeen: Map<Triple<String, Boolean, Int>, Long> = emptyMap(),
    /** Event id → expiration (or created_at + 31 d when it has none), pruned once passed. */
    val seen: Map<String, Long> = emptyMap(),
    val rekeyAt: Map<String, Long> = emptyMap(),
) {
    override fun toString() = "FollowerState(status=$status, epochs=${epochs.values.map { it.n }}, devices=${devices.size})"
}

/** What the follower made of one event. [code] is the `verdict:what` string of the OGS / sync fixtures. */
sealed interface FollowResult {
    val code: String

    data class Accepted(val item: Item) : FollowResult {
        override val code get() = "accepted:${item.what}"
    }

    data class Rejected(val reason: String, val detail: String = "") : FollowResult {
        override val code get() = "rejected:$reason"
    }

    /** Not an error: duplicate, expired, older, unknown epoch… [rekey] = a rekey request to send to the wearer. */
    data class Ignored(val reason: String, val rekey: Rumor.Rekey? = null) : FollowResult {
        override val code get() = "ignored:$reason"
    }
}

/** The accepted content, for the follower engine to store. */
sealed interface Item {
    val what: String

    data class Key(val epoch: Int) : Item {
        override val what = "key"
    }

    data class Roster(val roster: RosterPayload) : Item {
        override val what = "roster"
    }

    data class Chunk(val chunk: ChunkPayload) : Item {
        override val what = "chunk"
    }

    /** A live event. [sensorChanged]: it is the newest one, so [live].sensor replaces the follower's (null = cleared). */
    data class Live(val live: LivePayload, val sensorChanged: Boolean) : Item {
        override val what = if (live.reading != null) "live" else "sensor"
    }

    data class Alert(val alert: AlertPayload) : Item {
        override val what = "alert"
    }

    /** [override]: replace whatever is held for the id, whatever its `u` (wearer beats a forged writer copy). */
    data class Treatment(val treatment: TreatmentPayload, val author: String, val override: Boolean) : Item {
        override val what = "treatment"
    }

    data class Settings(val settings: SettingsPayload) : Item {
        override val what = "settings"
    }

    data object Revoked : Item {
        override val what = "revoked"
    }

    data object InviteUsed : Item {
        override val what = "invite-used"
    }

    data object Declined : Item {
        override val what = "declined"
    }
}

/**
 * The follower's acceptance rules (PROTOCOL-v2 §Acceptance rules, §Treatments, §Alerts, §Sensor life, §Settings),
 * transport-independent: relays and Bluetooth feed the same [onEvent]. A port of the reference follower
 * (`poc/spec/reference/ogs/follower.py`) plus the settings rule. Pure and single-threaded: the follower engine owns one
 * instance and calls it from one coroutine.
 */
class StreamFollower(private val crypto: NostrCrypto, initial: FollowerState) {
    var state: FollowerState = initial
        private set

    /** The follower sent its access request: waits for a key, `invite-used` or `declined`. */
    fun requested() {
        state = state.copy(status = FollowStatus.Requested)
    }

    fun onEvent(ev: SignedEvent, now: Long): FollowResult {
        prune(now)
        if (ev.id in state.seen) return FollowResult.Ignored("duplicate")
        if (!crypto.verify(ev)) return FollowResult.Rejected("bad-signature")
        state = state.copy(seen = state.seen + (ev.id to (ev.expiration ?: (ev.createdAt + 31 * Wire.DAY))))
        if (ev.isExpired(now)) return FollowResult.Ignored("expired")
        return try {
            if (ev.kind == Wire.KIND_WRAP) onWrap(ev) else onStreamEvent(ev, now)
        } catch (e: InvalidPayload) {
            FollowResult.Rejected("invalid", e.reason)
        }
    }

    private fun prune(now: Long) {
        val s = state
        if (s.seen.values.any { it <= now } || s.alertsSeen.values.any { now - it > Wire.ALERT_MAX_AGE }) {
            state = s.copy(seen = s.seen.filterValues { it > now }, alertsSeen = s.alertsSeen.filterValues { now - it <= Wire.ALERT_MAX_AGE })
        }
    }

    private fun onStreamEvent(ev: SignedEvent, now: Long): FollowResult {
        if (state.status == FollowStatus.Revoked) return FollowResult.Ignored("revoked")
        val gk = ev.tag("gk") ?: return FollowResult.Ignored("no-gk")
        val author = ev.pubkey
        val epoch = state.epochs[gk] ?: return unknownEpoch(ev, gk, now)
        val sid = state.sid ?: return FollowResult.Ignored("no-sid")
        return when (ev.kind) {
            Wire.KIND_DATA -> onData(ev, epoch, sid, author)
            Wire.KIND_LIVE, Wire.KIND_ALERT -> {
                if (author !in state.devices) return FollowResult.Rejected("author-not-device")
                val plain = decrypt(epoch, author, ev.content)
                if (ev.kind == Wire.KIND_LIVE) onLive(ev, ReadingsCodec.decodeLive(plain), now) else onAlert(AlertCodec.decode(plain), now)
            }
            else -> FollowResult.Ignored("unknown-kind")
        }
    }

    /** Readable later once the key arrives, so not marked seen; a rekey only for I or a roster device, throttled. */
    private fun unknownEpoch(ev: SignedEvent, gk: String, now: Long): FollowResult {
        state = state.copy(seen = state.seen - ev.id)
        val s = state
        val trusted = ev.pubkey == s.identity?.hex || ev.pubkey in s.devices
        if (s.status == FollowStatus.Active && trusted && now - (s.rekeyAt[gk] ?: Long.MIN_VALUE / 2) >= Wire.REKEY_THROTTLE) {
            state = s.copy(rekeyAt = s.rekeyAt + (gk to now))
            return FollowResult.Ignored("unknown-epoch", Rumor.Rekey(gk))
        }
        return FollowResult.Ignored("unknown-epoch")
    }

    private fun decrypt(epoch: Epoch, author: String, content: String): String {
        val sender = PublicKeyHex.parse(author) ?: throw InvalidPayload("bad author")
        return crypto.decrypt(epoch.key.secret, sender, content) ?: throw InvalidPayload("does not decrypt")
    }

    private fun onData(ev: SignedEvent, epoch: Epoch, sid: String, author: String): FollowResult {
        val d = ev.tag("d")
        val c = ev.tag("c")
        return when {
            d == Wire.rosterD(sid) -> onRoster(ev, epoch, author)
            d == Wire.settingsD(sid) -> onSettings(ev, epoch, author)
            c == Wire.treatmentsC(sid) -> onTreatment(ev, epoch, sid, author)
            c == Wire.glucoseC(sid) -> {
                if (author !in state.devices) return FollowResult.Rejected("author-not-device")
                val chunk = ReadingsCodec.decodeChunk(decrypt(epoch, author, ev.content))
                if (d != Wire.chunkD(sid, chunk.hour)) return FollowResult.Rejected("d-mismatch")
                FollowResult.Accepted(Item.Chunk(chunk))
            }
            else -> FollowResult.Ignored("unknown-30078")
        }
    }

    private fun onRoster(ev: SignedEvent, epoch: Epoch, author: String): FollowResult {
        if (author != state.identity?.hex) return FollowResult.Rejected("roster-not-by-identity")
        val roster = RosterCodec.decode(decrypt(epoch, author, ev.content))
        if (roster.epoch != epoch.n) return FollowResult.Rejected("roster-epoch-mismatch", "${roster.epoch} under gk of epoch ${epoch.n}")
        val s = state
        if (compareValuesBy(roster.epoch to ev.createdAt, s.rosterEpoch to s.rosterCreatedAt, { it.first }, { it.second }) <= 0) {
            return FollowResult.Ignored("roster-older")
        }
        state = s.copy(
            roster = roster,
            rosterEpoch = roster.epoch,
            rosterCreatedAt = ev.createdAt,
            devices = roster.devices,
            writers = roster.writers,
            relays = roster.relays.ifEmpty { s.relays },
        )
        return FollowResult.Accepted(Item.Roster(roster))
    }

    private fun onSettings(ev: SignedEvent, epoch: Epoch, author: String): FollowResult {
        if (author !in state.devices) return FollowResult.Rejected("author-not-device")
        val settings = SettingsCodec.decode(decrypt(epoch, author, ev.content))
        if (settings.u <= state.settingsU) return FollowResult.Ignored("settings-older")
        state = state.copy(settingsU = settings.u)
        return FollowResult.Accepted(Item.Settings(settings))
    }

    private fun onTreatment(ev: SignedEvent, epoch: Epoch, sid: String, author: String): FollowResult {
        val s = state
        val fromDevice = author in s.devices
        if (!fromDevice && author !in s.writers) return FollowResult.Rejected("author-not-in-roster")
        val tr = TreatmentCodec.decode(decrypt(epoch, author, ev.content))
        if (ev.tag("d") != Wire.treatmentD(sid, tr.id)) return FollowResult.Rejected("d-mismatch")
        val creator = s.creators[tr.id]
        val held = s.held[tr.id]
        val override = when (WriterScope.follower(author, fromDevice, tr.origin, creator, held?.second, s.devices)) {
            WriterScope.Verdict.Ignore -> return FollowResult.Ignored("treatment-held-by-wearer")
            WriterScope.Verdict.Reject -> return FollowResult.Rejected("writer-not-creator")
            WriterScope.Verdict.Override -> true
            WriterScope.Verdict.Accept -> false
        }
        val creators = if (creator == null || override) s.creators + (tr.id to author) else s.creators
        val newer = held == null || compareValuesBy(tr.u to tr.origin, held, { it.first }, { it.second }) > 0
        if (!override && !newer) {
            state = s.copy(creators = creators)
            return FollowResult.Ignored("treatment-older")
        }
        state = s.copy(creators = creators, held = s.held + (tr.id to (tr.u to tr.origin)))
        return FollowResult.Accepted(Item.Treatment(tr, author, override))
    }

    private fun onLive(ev: SignedEvent, live: LivePayload, now: Long): FollowResult {
        val reading = live.reading
        if (reading != null && reading.t > now + FUTURE_TOLERANCE) return FollowResult.Rejected("reading-in-future")
        val newest = ev.createdAt >= state.sensorCreatedAt
        if (newest) state = state.copy(sensorCreatedAt = ev.createdAt)
        return FollowResult.Accepted(Item.Live(live, newest))
    }

    private fun onAlert(alert: AlertPayload, now: Long): FollowResult {
        if (now - alert.at > Wire.ALERT_MAX_AGE) return FollowResult.Ignored("alert-stale")
        val key = Triple(alert.id, alert.fire, alert.n)
        if (key in state.alertsSeen) return FollowResult.Ignored("alert-duplicate")
        state = state.copy(alertsSeen = state.alertsSeen + (key to alert.at))
        return FollowResult.Accepted(Item.Alert(alert))
    }

    private fun onWrap(ev: SignedEvent): FollowResult {
        val s = state
        if (ev.tag("p") != s.follower.public.hex) return FollowResult.Ignored("wrap-not-for-us")
        val u = crypto.unwrap(s.follower.secret, ev) ?: return FollowResult.Rejected("unwrap")
        if (u.kind != Wire.KIND_TO_FOLLOWER || u.sender != s.identity) return FollowResult.Rejected("wrap-not-from-identity")
        return when (val rumor = RumorCodec.decode(u.kind, u.content)) {
            is Rumor.KeyDelivery -> onKey(rumor)
            Rumor.Revoked -> {
                state = s.copy(status = FollowStatus.Revoked)
                FollowResult.Accepted(Item.Revoked)
            }
            Rumor.InviteUsed, Rumor.Declined -> {
                val item = if (rumor == Rumor.InviteUsed) Item.InviteUsed else Item.Declined
                if (s.status != FollowStatus.Requested) return FollowResult.Ignored(item.what)
                state = s.copy(status = if (rumor == Rumor.InviteUsed) FollowStatus.InviteUsed else FollowStatus.Declined)
                FollowResult.Accepted(item)
            }
            else -> FollowResult.Ignored("unknown-type")
        }
    }

    private fun onKey(k: Rumor.KeyDelivery): FollowResult {
        val s = state
        if (s.sid != null && k.sid != s.sid) return FollowResult.Rejected("sid-changed")
        val secret: SecretKey = crypto.parseNsec(k.readKeyNsec) ?: throw InvalidPayload("bad read key")
        val key = crypto.keyPair(secret) ?: throw InvalidPayload("bad read key")
        val gk = Wire.gk(key.public)
        if (gk in s.epochs) return FollowResult.Ignored("key-known")
        val newest = s.epochs.values.maxOfOrNull { it.n } ?: 0
        val latest = k.epoch >= newest
        state = s.copy(
            sid = k.sid,
            epochs = s.epochs + (gk to Epoch(k.epoch, key)),
            devices = if (latest && k.devices.isNotEmpty()) k.devices else s.devices,
            relays = if (latest && k.relays.isNotEmpty()) k.relays else s.relays,
            status = if (s.status == FollowStatus.Revoked) FollowStatus.Revoked else FollowStatus.Active,
        )
        return FollowResult.Accepted(Item.Key(k.epoch))
    }

    private companion object {
        /** A reading more than 5 min in the future is rejected (reference follower). */
        const val FUTURE_TOLERANCE = 300L
    }
}
