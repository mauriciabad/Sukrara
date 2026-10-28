// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.home.banners

import app.mauri.sukrara.core.model.AlertReadiness
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant

/**
 * The home screen's one non-glucose banner, in priority order (the enum order): banners never stack between the person
 * and their glucose. [safety] banners can't be dismissed: they go away only when the problem is fixed.
 */
enum class BannerKind(val safety: Boolean) {
    /** Android stopped monitoring: readings and alerts are paused even while the last value still shows. */
    MonitoringStopped(safety = true),

    /** Something alerts need is missing (notifications, exact alarm, …): alerts may not ring. */
    AlertsUnreliable(safety = true),

    /** A stored file was damaged and set aside: data loss is never silent (its exit: review and acknowledge). */
    DataProblem(safety = true),

    /**
     * Pen reading is On but Android blocks Sukrara's tag intents: every pen tap does nothing. *Allow* opens Android's
     * screen, *Turn off* turns pen reading off; it goes by itself once allowed (checked on every resume).
     */
    PenTapsBlocked(safety = false),

    /** First-run setup isn't finished: the next step. "Later" hides it for [SETUP_LATER]. */
    FinishSetup(safety = false),
    NotBackedUp(safety = false),
    AddWidget(safety = false),
    ShareWithClinic(safety = false),
}

/** What the banners depend on besides the alert checklist; null = not known on this build yet (never shown). */
data class HomeEnvironment(
    /** The next unfinished first-run setup step's name, or null when setup is done. */
    val nextSetupStep: String? = null,
    val backedUp: Boolean? = null,
    val widgetPlaced: Boolean? = null,
    val clinicUploadSet: Boolean? = null,
    /** Pen reading is On and Android blocks the taps. */
    val penTapsBlocked: Boolean = false,
)

/** When each banner was dismissed (epoch ms); stored in the typed state file "home-banners". */
@Serializable
data class BannerMemory(
    val dismissedAtMs: Map<String, Long> = emptyMap(),
) {
    fun dismissed(
        kind: BannerKind,
        now: Instant,
    ): Boolean {
        val at = dismissedAtMs[kind.name] ?: return false
        return kind != BannerKind.FinishSetup || now.toEpochMilli() - at < SETUP_LATER.toMillis()
    }

    fun dismiss(
        kind: BannerKind,
        now: Instant,
    ): BannerMemory {
        require(!kind.safety) { "$kind can't be dismissed" }
        return copy(dismissedAtMs = dismissedAtMs + (kind.name to now.toEpochMilli()))
    }

    /** Undo on a close: [kind] can show again. */
    fun restore(kind: BannerKind): BannerMemory = copy(dismissedAtMs = dismissedAtMs - kind.name)

    /**
     * How many tips are closed for good (Settings › *Show closed tips*): kinds this build still has, not Finish
     * setup's "Later" (it comes back by itself).
     */
    val closed: Int get() = BannerKind.entries.count { it != BannerKind.FinishSetup && it.name in dismissedAtMs }

    /**
     * Settings › *Show closed tips*: every closed tip can show again (each still only when it applies); Finish setup's
     * "Later" keeps its day.
     */
    fun restoreAll(): BannerMemory = BannerMemory(dismissedAtMs.filterKeys { it == BannerKind.FinishSetup.name })
}

/** "Later" on Finish setup: it comes back after a day until setup is done. */
val SETUP_LATER: Duration = Duration.ofHours(24)

object HomeBanners {
    /**
     * The one banner to show, or null. [readiness] null = the checklist isn't known yet (no warning claimed either
     * way).
     */
    fun pick(
        readiness: AlertReadiness?,
        env: HomeEnvironment,
        monitoringStopped: Boolean,
        dataProblems: Int,
        memory: BannerMemory,
        now: Instant,
    ): BannerKind? =
        BannerKind.entries.firstOrNull { kind ->
            val wanted =
                when (kind) {
                    BannerKind.MonitoringStopped -> monitoringStopped
                    BannerKind.AlertsUnreliable -> readiness?.ready == false
                    BannerKind.DataProblem -> dataProblems > 0
                    BannerKind.PenTapsBlocked -> env.penTapsBlocked
                    BannerKind.FinishSetup -> env.nextSetupStep != null
                    BannerKind.NotBackedUp -> env.backedUp == false
                    BannerKind.AddWidget -> env.widgetPlaced == false
                    BannerKind.ShareWithClinic -> env.clinicUploadSet == false
                }
            wanted && (kind.safety || kind == BannerKind.PenTapsBlocked || !memory.dismissed(kind, now))
        }
}
