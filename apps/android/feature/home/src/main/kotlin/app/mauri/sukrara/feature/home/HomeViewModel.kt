// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.mauri.sukrara.core.model.AlertReadiness
import app.mauri.sukrara.core.model.AlertRequirement
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.GlucoseState
import app.mauri.sukrara.core.model.ModuleState
import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.ReadingHistory
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.ServiceState
import app.mauri.sukrara.core.model.Treatment
import app.mauri.sukrara.core.model.storage.StateStore
import app.mauri.sukrara.core.model.storage.TreatmentsRepository
import app.mauri.sukrara.feature.home.banners.BannerKind
import app.mauri.sukrara.feature.home.banners.BannerMemory
import app.mauri.sukrara.feature.home.banners.HomeBanners
import app.mauri.sukrara.feature.home.banners.HomeEnvironment
import app.mauri.sukrara.graph.GlucoseLook
import app.mauri.sukrara.graph.GraphConfig
import app.mauri.sukrara.protocol.event.Avatar
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * What the home screen needs from the app. `AppGraph` (`:app`) implements it from the modules it composes; home only
 * reads state and asks through these ports (it never depends on `:monitor`).
 */
interface HomeDeps {
    val clock: Clock

    /** THE glucose state (`GlucoseHub.state`). */
    val glucose: StateFlow<GlucoseState>
    val treatments: TreatmentsRepository

    /** The person's graph settings (`GraphSettings.config`): the card's ranges, time shown, rings… */
    val graphConfig: Flow<GraphConfig>

    /**
     * The stored readings of the last 31 days before the live window (oldest first, re-read hourly): home joins them
     * with [glucose]'s recent readings to scroll and zoom back and to count the multi-day slots.
     */
    val storedHistory: Flow<List<Reading>> get() = flowOf(emptyList())

    /** The alert checklist; null = not known on this build yet (no warning is claimed either way). */
    val readiness: StateFlow<AlertReadiness?>

    /** Setup progress, backup, widget… (the app re-reads them on resume). */
    val environment: StateFlow<HomeEnvironment>

    /** Damaged stored files not acknowledged yet (`DataModule.corruption`). */
    val dataProblems: StateFlow<List<DataProblem>>

    /** The person has seen [problem] (and maybe copied the report). */
    fun acknowledge(problem: DataProblem)

    /** Banner dismissals (typed state "home-banners"). */
    val bannerMemory: StateStore<BannerMemory>

    /** Starts monitoring again after Android stopped it (a user action, so the service may start). */
    fun resumeMonitoring()

    /** Mutes, unmutes, ends a quiet or reminds as usual (the alert runtime, this phone only). */
    fun alert(action: HomeAlertAction)

    /** Opens the system screen that fixes [requirement]. */
    fun fix(requirement: AlertRequirement)

    /**
     * Deletes treatment [id] through the treatment log (a tombstone version, synced to the other devices); returns the
     * deleted version for Undo, or null when it isn't stored or this phone may not change it (a follower and the
     * wearer's entry).
     */
    suspend fun deleteTreatment(id: String): Treatment?

    /** Brings [previous] (what [deleteTreatment] returned) back as the newest version, through the treatment log. */
    suspend fun undoTreatment(previous: Treatment)

    /** Whether this phone may delete treatment [id] (a follower only the entries it added). */
    suspend fun mayChangeTreatment(id: String): Boolean

    /** How the wearer appears to followers, while this phone shares (null = no identity, or not the wearer). */
    val profile: Flow<HomeProfile?> get() = flowOf(null)

    /** What the clinic upload has of the entries (the list's cloud marks); null = its entries upload is off. */
    val uploadMarks: Flow<UploadMarks?> get() = flowOf(null)

    /**
     * The family name of the sensor this phone reads directly while Sukrara's support for it is untested (never
     * confirmed on a real sensor); null otherwise. Home asks the wearer for feedback while it is set.
     */
    val untestedSensor: Flow<String?> get() = flowOf(null)
}

/** The wearer's own row on home: picture, name (blank = not set yet) and how many follow. */
data class HomeProfile(val name: String, val avatar: Avatar?, val followers: Int)

/** Everything the home screen draws, from one source. */
data class HomeUiState(
    val glucose: GlucoseState,
    val now: Instant,
    val look: GlucoseLook,
    /** Why there is no value, or null when the card shows one. */
    val reason: NoDataReason?,
    val banner: BannerKind?,
    val firstProblem: AlertRequirement?,
    val nextSetupStep: String?,
    val dataProblems: List<DataProblem>,
    /** The last 24 h, newest first (deleted ones are gone; the undo brings them back). */
    val treatments: List<Treatment>,
    /** Every reading home can scroll to (31 days), oldest first: the stored days joined with the live window. */
    val history: List<Reading> = glucose.recent,
    /** The treatments the chart can scroll to (31 days), newest first. */
    val chartTreatments: List<Treatment> = treatments,
    /** A just-deleted treatment the snackbar offers to bring back. */
    val undo: Treatment?,
    /** The graph settings the card draws with. */
    val config: GraphConfig = GraphConfig(),
    /** Ids of [treatments] this phone may not delete (a follower and the wearer's entries): no Delete button. */
    val readOnly: Set<String> = emptySet(),
    /** The wearer's profile while sharing (the top bar shows it, with Share). */
    val profile: HomeProfile? = null,
    /** The clinic upload's cloud mark per listed entry id (empty while its entries upload is off). */
    val marks: Map<String, UploadMark> = emptyMap(),
    /** The just-deleted entry was in the clinic upload: the snackbar says it is removed there too. */
    val undoInClinic: Boolean = false,
    /** The wearer's sensor family whose support is untested: the feedback card under the card (wearer only). */
    val untestedSensor: String? = null,
    /** The restart card's System UI module runs an older build (else it isn't loaded yet). */
    val systemUiUpdated: Boolean = false,
) {
    val mutedUntil: Instant? get() = glucose.alerts.mutedUntil?.takeIf { it > now }
}

class HomeViewModel(
    private val deps: HomeDeps,
) : ViewModel() {
    private val undo = MutableStateFlow<Treatment?>(null)
    private val undoInClinic = MutableStateFlow(false)
    private val uploadMarks: StateFlow<UploadMarks?> = deps.uploadMarks.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private var undoExpiry: Job? = null

    /** Ticks on each minute so ages ("3 min ago"), freshness steps and countdowns move without a new reading. */
    private val minutes: Flow<Instant> =
        flow {
            while (true) {
                val now = deps.clock.now()
                emit(now)
                delay(MINUTE_MS - now.toEpochMilli() % MINUTE_MS)
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val recentTreatments: Flow<Recent> =
        minutes
            .map { it.truncatedTo(ChronoUnit.HOURS) }
            .distinctUntilChanged()
            .flatMapLatest { hour -> deps.treatments.between(hour - CHART_WINDOW, hour + Duration.ofHours(1) + FUTURE_SLACK) }
            .mapLatest { list ->
                // only the listed (last day's) entries have a Delete button to decide
                val listed = list.filter { it.at >= deps.clock.now() - TREATMENTS_WINDOW - Duration.ofHours(1) }
                Recent(list, listed.filterNot { deps.mayChangeTreatment(it.id) }.mapTo(HashSet()) { it.id })
            }

    val state: StateFlow<HomeUiState> =
        combine(
            combine(deps.glucose, deps.storedHistory) { g, stored -> Live(g, ReadingHistory.join(stored, g.recent)) },
            minutes,
            deps.readiness,
            combine(combine(deps.environment, deps.untestedSensor, ::Pair), deps.profile, uploadMarks, ::Triple),
            combine(
                deps.bannerMemory.data,
                recentTreatments,
                combine(undo, undoInClinic, ::Pair),
                deps.dataProblems,
                deps.graphConfig,
                ::Extras,
            ),
        ) { (glucose, history), tick, readiness, (envUntested, profile, marks), (memory, recent, undone, problems, config) ->
            val (env, untested) = envUntested
            val (pendingUndo, inClinic) = undone
            val now = maxOf(tick, deps.clock.now())
            val chart = recent.treatments.sortedByDescending { it.at }
            val listed = chart.filter { it.at >= now - TREATMENTS_WINDOW && it.at <= now + FUTURE_SLACK }
            val look = homeLook(glucose, now)
            HomeUiState(
                glucose = glucose,
                now = now,
                look = look,
                reason = NoDataReason.of(glucose, look),
                banner = HomeBanners.pick(readiness, env, glucose.service is ServiceState.Stopped, problems.size, memory, now),
                firstProblem = readiness?.firstProblem,
                nextSetupStep = env.nextSetupStep,
                systemUiUpdated = env.systemUiRestart == ModuleState.Update,
                dataProblems = problems,
                treatments = listed,
                history = history,
                chartTreatments = chart,
                undo = pendingUndo,
                config = config,
                readOnly = recent.readOnly,
                profile = profile?.takeIf { glucose.role != Role.Follower },
                marks = marks?.let { m -> listed.mapNotNull { t -> m.mark(t, now)?.let { t.id to it } }.toMap() }.orEmpty(),
                undoInClinic = pendingUndo != null && inClinic,
                untestedSensor = untested?.takeIf { glucose.role == Role.Wearer },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial())

    private fun initial(): HomeUiState {
        val now = deps.clock.now()
        val glucose = deps.glucose.value
        val look = homeLook(glucose, now)
        return HomeUiState(
            glucose = glucose,
            now = now,
            look = look,
            reason = NoDataReason.of(glucose, look),
            banner = null,
            firstProblem = null,
            nextSetupStep = null,
            dataProblems = emptyList(),
            treatments = emptyList(),
            undo = null,
        )
    }

    fun dismiss(kind: BannerKind) {
        if (kind.safety) return
        if (kind == BannerKind.RestartSystemUi) {
            val key = deps.environment.value.restartKey ?: return
            viewModelScope.launch { deps.bannerMemory.update { it.dismissKey(key, deps.clock.now()) } }
            return
        }
        viewModelScope.launch { deps.bannerMemory.update { it.dismiss(kind, deps.clock.now()) } }
    }

    /** Undo on the "Tip closed" snackbar. */
    fun restore(kind: BannerKind) {
        viewModelScope.launch { deps.bannerMemory.update { it.restore(kind) } }
    }

    fun alert(action: HomeAlertAction) = deps.alert(action)

    fun acknowledge(problem: DataProblem) = deps.acknowledge(problem)

    fun fix(requirement: AlertRequirement) = deps.fix(requirement)

    /** Deletes [treatment] through the treatment log and offers Undo for [UNDO_WINDOW] (none when the log refused). */
    fun delete(treatment: Treatment) {
        // read before the delete: the upload retracts it from the clinic right after
        val inClinic = uploadMarks.value?.removesOnDelete(treatment) == true
        viewModelScope.launch {
            val deleted = deps.deleteTreatment(treatment.id) ?: return@launch
            undoInClinic.value = inClinic
            undo.value = deleted
            undoExpiry?.cancel()
            undoExpiry =
                launch {
                    delay(UNDO_WINDOW.toMillis())
                    undo.compareAndSet(deleted, null)
                }
        }
    }

    /** Brings the just-deleted treatment back as a newer version (the treatment log's undo). */
    fun undoDelete() {
        val treatment = undo.value ?: return
        undo.value = null
        undoExpiry?.cancel()
        viewModelScope.launch { deps.undoTreatment(treatment) }
    }

    /** The snackbar went away without Undo. */
    fun undoShown() {
        undo.value = null
    }

    private data class Live(
        val glucose: GlucoseState,
        val history: List<Reading>,
    )

    private data class Recent(
        val treatments: List<Treatment>,
        val readOnly: Set<String>,
    )

    private data class Extras(
        val memory: BannerMemory,
        val treatments: Recent,
        val undo: Pair<Treatment?, Boolean>,
        val problems: List<DataProblem>,
        val config: GraphConfig,
    )

    companion object {
        val UNDO_WINDOW: Duration = Duration.ofSeconds(5)
        private val TREATMENTS_WINDOW: Duration = Duration.ofHours(24)

        /** How far back the chart scrolls (the stored history's 31 days). */
        private val CHART_WINDOW: Duration = Duration.ofDays(31)

        /** Entries logged a little ahead (a dose about to be taken) still show. */
        private val FUTURE_SLACK: Duration = Duration.ofHours(1)
        private const val MINUTE_MS = 60_000L

        fun factory(deps: HomeDeps) = viewModelFactory { initializer { HomeViewModel(deps) } }
    }
}
