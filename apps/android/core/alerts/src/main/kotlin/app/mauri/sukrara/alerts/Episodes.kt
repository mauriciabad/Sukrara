// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.alerts

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * One alert firing: the [n]th of its episode, from [reading] (null for no data without a reading), [threshold] that
 * matched, [treated] = the treatments the headline acknowledges (0, 1, 2 = two or more; SPEC §1 "Headline").
 */
@Serializable
data class Firing(
    val kind: AlertKind,
    val id: String,
    val n: Int,
    val reading: AlertReading?,
    val threshold: Double,
    val at: Long,
    val treated: Int = 0,
)

/**
 * This device's notification actions on one side: muted until [until] (0 = not), repeats relaxed to [relaxMin]
 * (0 = not). [backSince]: since when the side has been back by the margin without crossing out again (0 = it isn't).
 */
@Serializable
data class Snooze(val until: Long = 0, val relaxMin: Int = 0, val backSince: Long = 0) {
    val isEmpty: Boolean get() = until == 0L && relaxMin == 0
}

/** Why an episode is quiet right now (for the UI; null = alerting). */
@Serializable
enum class EpisodeQuiet { TREATMENT, FALLING, NOTIFICATION, ALL }

/**
 * One episode (SPEC §1): a stretch of time on one [side]. Mutable, owned by one [AlertEngine]; [copy] for snapshots.
 */
@Serializable
class Episode(val side: Side, val start: Long, var kind: AlertKind) {
    /** The condition held at the last evaluation, and when it last did. */
    var on: Boolean = true
    var lastIn: Long = start

    /** The firing whose notification is up (gone when the condition goes, a mute starts or the kind changes). */
    var shown: Firing? = null
    var n: Int = 0
    var firedAt: Long? = null
    var firedKind: AlertKind = kind
    var quiet: EpisodeQuiet? = null
    var quietUntil: Long = 0

    /** When the next alert is due (Long.MAX_VALUE = never), from the last evaluation. */
    var next: Long = start

    /** *Unmute* / *Alert me anyway* at this time (0 = never): treatments up to then and the falling mute are lifted. */
    var lifted: Long = 0

    /** Lifted since the last alert: the next match alerts at once. */
    var liftPending: Boolean = false

    fun copy(): Episode = Episode(side, start, kind).also {
        it.on = on; it.lastIn = lastIn; it.shown = shown; it.n = n; it.firedAt = firedAt; it.firedKind = firedKind
        it.quiet = quiet; it.quietUntil = quietUntil; it.next = next; it.lifted = lifted; it.liftPending = liftPending
    }
}

/**
 * Everything one [AlertEngine] must keep across a process restart: open episodes (so an ongoing alert isn't taken for
 * a new one and rings again — the PoC's "two sounds at once" after an update), this device's mutes / Remind less, its
 * Mute all, and the last reading (no data counts from it, also from the alarm receiver with the service dead).
 */
@Serializable
data class AlertMemory(
    val episodes: Map<AlertGroup, Episode> = emptyMap(),
    val snoozes: Map<Side, Snooze> = emptyMap(),
    val muteAllUntil: Long = 0,
    val last: AlertReading? = null,
)

/** Which rules a device runs (SPEC §2/§3): the wearer, or a follower with its own rules or mirroring the wearer. */
@Serializable
enum class AlertRole { WEARER, FOLLOWER_OWN, FOLLOWER_MIRROR }

/**
 * The persisted alert state of one device ([AlertMachine.snapshot]), stored by the runtime in a typed DataStore.
 * [local] drives this device's notifications; [shared] is the wearer's second run without this device's actions
 * (what the followers get, SPEC §2) — saved too, so a restart doesn't re-publish `fire` and ring the followers twice.
 * [role] tags it: a state saved under another role is dropped. Settings are not here: they live in the settings store.
 */
@Serializable
data class AlertState(
    val v: Int = VERSION,
    val role: AlertRole,
    val local: AlertMemory = AlertMemory(),
    val shared: AlertMemory? = null,
    val mirror: MirrorMemory? = null,
) {
    companion object {
        const val VERSION = 1
    }
}

/** [AlertState] ⇄ JSON (V1 format only, no PoC compatibility). */
object AlertStateCodec {
    private val json = Json { ignoreUnknownKeys = true; allowSpecialFloatingPointValues = false }

    fun encode(state: AlertState): String = json.encodeToString(AlertState.serializer(), state)

    /**
     * The saved state, or null when it is missing, corrupt or of another version: the caller starts empty (an
     * ongoing alert may ring again, the safe direction) and never trusts a Mute all it couldn't read.
     */
    fun decode(text: String): AlertState? = try {
        json.decodeFromString(AlertState.serializer(), text).takeIf { it.v == AlertState.VERSION }
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }
}
