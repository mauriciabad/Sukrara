// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara

import app.mauri.sukrara.core.data.CorruptFile
import app.mauri.sukrara.core.data.DataModule
import app.mauri.sukrara.core.model.AlertReadiness
import app.mauri.sukrara.core.model.AlertReadinessSource
import app.mauri.sukrara.core.model.AlertRequirement
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.GlucoseState
import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.SystemAccessState
import app.mauri.sukrara.core.model.Treatment
import app.mauri.sukrara.core.model.storage.StateStore
import app.mauri.sukrara.core.model.storage.TreatmentsRepository
import app.mauri.sukrara.feature.home.DataProblem
import app.mauri.sukrara.feature.home.HomeDeps
import app.mauri.sukrara.feature.home.HomeProfile
import app.mauri.sukrara.feature.home.UploadMarks
import app.mauri.sukrara.feature.home.banners.BannerMemory
import app.mauri.sukrara.feature.home.banners.HomeEnvironment
import app.mauri.sukrara.feature.treatments.TreatmentLog
import app.mauri.sukrara.graph.GraphConfig
import app.mauri.sukrara.share.owner.OwnerShareState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The home screen's ports, built from the modules `AppGraph` composes: [glucose] is the hub's state and [readiness]
 * THE alert checklist (`AlertsModule.readiness`, the same instance settings and setup render).
 */
class HomeModule(
    private val data: DataModule,
    override val clock: Clock,
    private val scope: CoroutineScope,
    override val glucose: StateFlow<GlucoseState>,
    override val graphConfig: Flow<GraphConfig>,
    override val storedHistory: Flow<List<Reading>>,
    private val readinessSource: AlertReadinessSource,
    private val unmuteAlerts: () -> Unit,
    /** Ends one side's quiet: the alert kind's wire name ("low" / "high"). */
    private val endQuietAlerts: (String) -> Unit,
    private val resume: () -> Unit,
    /** First-run setup's first unfinished row, for the Finish-setup banner; null = done. */
    nextSetupStep: Flow<String?>,
    private val refreshSetup: () -> Unit,
    /** Whether a graph widget is on the home screen, checked now (null = unknown: the banner stays hidden). */
    private val widgetPlaced: () -> Boolean? = { null },
    /** The phone's treatment writes (writer check, versioning, sync); a function: the treatments module needs home. */
    private val treatmentLog: () -> TreatmentLog,
    /** The wearer's sharing state (profile row, "Not backed up"); a function: sharing is built on first use. */
    private val sharing: () -> Flow<OwnerShareState> = { emptyFlow() },
    /** Pen reading is On and Android blocks the taps (home's banner); read on every resume. */
    private val penTapsBlocked: suspend () -> Boolean = { false },
    /** The wearer's clinic upload is set (null on a follower): the *Share your data with your clinic* banner. */
    private val clinicUploadSet: () -> Flow<Boolean?> = { emptyFlow() },
    /** What the clinic upload has of the entries (the list's cloud marks); a function: integrations start later. */
    private val clinicMarks: () -> Flow<UploadMarks?> = { emptyFlow() },
    /** The family of the sensor this phone reads directly while its support is untested (the feedback card). */
    override val untestedSensor: Flow<String?> = flowOf(null),
    /** Root and the System UI module (the *Restart System UI* card). */
    private val systemAccess: Flow<SystemAccessState> = emptyFlow(),
    /** This build's version code: a closed restart card comes back with a new build. */
    private val build: Long = 0,
) : HomeDeps {
    override val treatments: TreatmentsRepository get() = data.treatments

    override val readiness: StateFlow<AlertReadiness?> get() = readinessSource.readiness

    private val env = MutableStateFlow(HomeEnvironment())
    override val environment: StateFlow<HomeEnvironment> = env

    init {
        scope.launch { nextSetupStep.collect { step -> env.update { it.copy(nextSetupStep = step) } } }
        scope.launch {
            systemAccess.collect { a ->
                // as Settings › Status bar: only with root confirmed (asked while the status-bar value is on)
                val restart = a.module.takeIf { it.needsRestart && a.root == true }
                env.update { it.copy(systemUiRestart = restart, build = build) }
            }
        }
        // "Not backed up" only for a wearer whose identity exists and lives on this phone alone
        scope.launch {
            sharing().collect { s -> env.update { it.copy(backedUp = (s as? OwnerShareState.Sharing)?.backedUp) } }
        }
        scope.launch { clinicUploadSet().collect { set -> env.update { it.copy(clinicUploadSet = set) } } }
    }

    override val profile: Flow<HomeProfile?> by lazy {
        sharing().map { s ->
            (s as? OwnerShareState.Sharing)?.let {
                HomeProfile(
                    it.profile?.name.orEmpty(),
                    it.profile?.avatar,
                    it.followers.count { f ->
                        f.active
                    },
                )
            }
        }
    }

    override val uploadMarks: Flow<UploadMarks?> = flow { emitAll(clinicMarks()) }

    override val bannerMemory: StateStore<BannerMemory> by lazy {
        data.typedStore("home-banners", BannerMemory.serializer(), BannerMemory(), whenCorrupt = BannerMemory())
    }

    override val dataProblems: StateFlow<List<DataProblem>> =
        data.corruption.files
            .map { files -> files.map { it.toProblem() } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Re-reads what can change outside the app (the activity calls it on every resume). */
    fun refresh() {
        readinessSource.refresh()
        refreshSetup()
        env.update { it.copy(widgetPlaced = widgetPlaced()) }
        scope.launch { runCatching { penTapsBlocked() }.getOrNull()?.let { b -> env.update { it.copy(penTapsBlocked = b) } } }
    }

    override fun acknowledge(problem: DataProblem) {
        data.corruption.files.value
            .firstOrNull { it.toProblem() == problem }
            ?.let(data.corruption::acknowledge)
    }

    override fun unmute() = unmuteAlerts()

    override fun endQuiet(lowSide: Boolean) = endQuietAlerts(if (lowSide) "low" else "high")

    override fun resumeMonitoring() = resume()

    override fun fix(requirement: AlertRequirement) {
        readinessSource.fix(requirement)
    }

    override suspend fun deleteTreatment(id: String): Treatment? = treatmentLog().delete(id)

    override suspend fun undoTreatment(previous: Treatment) {
        treatmentLog().undo(previous)
    }

    override suspend fun mayChangeTreatment(id: String): Boolean = treatmentLog().mayChange(id)

    private fun CorruptFile.toProblem() = DataProblem(store = store, reason = reason, keptAs = quarantinedTo?.name, at = at)
}
