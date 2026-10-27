// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.treatments

import android.content.Context
import app.mauri.sukrara.core.data.settings.SchemaSettings
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.GlucoseState
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

    /** Whether this phone offers Add treatment right now ([WriterScope]). */
    fun canAdd(): Boolean = WriterScope.canAdd(glucose.value.role, sync.writer.value)

    /** Carbs first while a low alert is going on (eating is what's needed), as the glucose notification decides. */
    fun lowNow(): Boolean =
        glucose.value.alerts.firingKinds
            .any { it in LOW_KINDS }

    companion object {
        val LockTreatmentTime = SchemaSettings.number("lock.treatmentTime")
        private val LOW_KINDS = setOf("low", "very_low")
    }
}

/** The `Application` gives the treatment components (quick dialog, tile) their module. */
interface TreatmentsHost {
    val treatments: TreatmentsModule
}

internal val Context.treatmentsModule: TreatmentsModule
    get() = (applicationContext as TreatmentsHost).treatments
