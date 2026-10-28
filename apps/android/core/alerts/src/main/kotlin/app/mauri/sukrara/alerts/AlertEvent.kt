// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.alerts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The `alert` event content (wearer → followers, SPEC §4; the envelope and encryption are `:core:protocol`'s).
 * [state] `fire` or `clear` (a clear repeats the [id] of the firing it ends); [at] unix s; [t]/[sgv]/[r] the reading
 * (null for no data without one); [th] the threshold that matched; [n] the episode's count; [tr] treatments
 * acknowledged in the headline (0, 1, 2).
 */
@Serializable
data class AlertEvent(
    val v: Int = 2,
    val type: String = TYPE,
    val id: String,
    val k: AlertKind,
    val state: State,
    val at: Long,
    val t: Long? = null,
    val sgv: Int? = null,
    val r: Double? = null,
    val th: Double? = null,
    val n: Int = 0,
    val tr: Int = 0,
) {
    @Serializable
    enum class State {
        @SerialName("fire") FIRE,
        @SerialName("clear") CLEAR,
    }

    /** The firing a follower shows for a `fire` event. */
    fun toFiring(): Firing = Firing(k, id, n, if (t != null && sgv != null) AlertReading(t, sgv, r) else null, th ?: 0.0, at, tr)

    companion object {
        const val TYPE = "alert"

        /** Followers ignore events older than this (SPEC §4). */
        const val MAX_AGE_S = 10 * 60L

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

        fun fire(f: Firing) = AlertEvent(
            id = f.id, k = f.kind, state = State.FIRE, at = f.at, t = f.reading?.t, sgv = f.reading?.mgdl,
            r = f.reading?.rate, th = f.threshold, n = f.n, tr = f.treated,
        )

        fun clear(kind: AlertKind, id: String, at: Long, last: AlertReading?) = AlertEvent(
            id = id, k = kind, state = State.CLEAR, at = at, t = last?.t, sgv = last?.mgdl, r = last?.rate,
        )

        fun encode(e: AlertEvent): String = json.encodeToString(serializer(), e)

        /** A decrypted payload as an alert event, or null (not one, another version, unknown kind). */
        fun decode(text: String): AlertEvent? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
            ?.takeIf { it.v == 2 && it.type == TYPE }
    }
}
