// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.treatments

import android.content.Context
import app.mauri.sukrara.core.data.settings.SchemaSettings
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.GlucoseState
import app.mauri.sukrara.core.model.PenDuplicates
import app.mauri.sukrara.core.model.PenNearEntry
import app.mauri.sukrara.core.model.TreatmentKind
import app.mauri.sukrara.core.model.storage.SettingsRepository
import app.mauri.sukrara.core.model.storage.TreatmentsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * The treatments area (`AppGraph` composes it with one line): the log, whether this phone may add entries, the
 * glucose state that decides the focused field, and the lock-screen countdown setting.
 */
class TreatmentsModule(
    repository: TreatmentsRepository,
    val sync: TreatmentSync,
    val clock: Clock,
    val glucose: StateFlow<GlucoseState>,
    settings: SettingsRepository,
    private val scope: CoroutineScope,
    /** This phone's alert rules as the dialog needs them (the focused field, the quiet line). */
    val hints: AlertHints,
    /** A pen entry near a typed insulin dose (*Already logged by your pen?*); none without pens. */
    private val penDuplicates: PenDuplicates = PenDuplicates { _, _ -> null },
) {
    val log = TreatmentLog(repository, sync, clock)

    /** Seconds the quick dialog stays over the lock screen (schema `lock.treatmentTime`). */
    val lockSeconds: StateFlow<Double> = settings.flow(LockTreatmentTime).stateIn(scope, SharingStarted.Eagerly, LockTreatmentTime.default)

    /**
     * Saves [entries] at [at] (null = now) in the app's scope, so it completes after the quick dialog has closed.
     * Returns false (nothing saved) when this phone may not add entries.
     */
    fun save(
        entries: List<NewEntry>,
        at: Instant?,
    ): Boolean {
        if (!canAdd()) return false
        // a failed write must not crash the app: the entry simply isn't in the list (diagnosable there)
        scope.launch { runCatching { log.add(entries, at) } }
        return true
    }

    /**
     * The pen entry that already logged one of [entries]' insulin doses at [at] (null = now), if any: the dialog asks
     * before logging it twice. A failure to look is no warning (the dose is the user's to log).
     */
    suspend fun penDuplicate(
        entries: List<NewEntry>,
        at: Instant?,
    ): Pair<NewEntry, PenNearEntry>? {
        val t = (at ?: clock.now()).epochSecond
        return entries
            .filter { it.kind == TreatmentKind.RapidInsulin || it.kind == TreatmentKind.LongInsulin }
            .firstNotNullOfOrNull { e ->
                runCatching { penDuplicates.near(if (e.kind == TreatmentKind.LongInsulin) "long" else "rapid", t) }
                    .getOrNull()
                    ?.let { e to it }
            }
    }

    /** Whether this phone offers Add treatment right now ([WriterScope]). */
    fun canAdd(): Boolean = WriterScope.canAdd(glucose.value.role, sync.writer.value)

    /** Carbs first while the latest fresh reading is low (eating is what's needed), whether or not an alert fired. */
    fun lowNow(): Boolean = hints.lowNow()

    companion object {
        val LockTreatmentTime = SchemaSettings.number("lock.treatmentTime")
    }
}

/** The `Application` gives the treatment components (quick dialog, tile) their module. */
interface TreatmentsHost {
    val treatments: TreatmentsModule
}

internal val Context.treatmentsModule: TreatmentsModule
    get() = (applicationContext as TreatmentsHost).treatments
