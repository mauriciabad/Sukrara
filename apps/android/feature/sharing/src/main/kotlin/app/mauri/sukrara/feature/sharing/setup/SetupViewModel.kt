// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.mauri.sukrara.alerts.AlertKind
import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.core.model.AlertReadiness
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.feature.sharing.SetupModule
import app.mauri.sukrara.share.owner.OwnerShareState
import app.mauri.sukrara.share.owner.usecase.RestoreIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The restore form's state. The code and password never live here (screen-local, not saved). */
sealed interface RestoreUi {
    data object Idle : RestoreUi

    /** Looking for the backup on the relays (up to ~12 s). */
    data object Busy : RestoreUi

    /** Shown inline under the form; the typed code stays so the person can fix it and retry. */
    data class Failed(val result: RestoreIdentity.Result) : RestoreUi

    data class Restored(val followers: Int) : RestoreUi
}

/** Everything one setup screen shows. [step] null = setup is over: go home. */
data class SetupUi(
    val step: SetupStep?,
    /** 1-based position and the number of steps ("Step 3 of 7"). */
    val index: Int,
    val count: Int,
    val facts: SetupFacts,
    val record: SetupRecord,
    val readiness: AlertReadiness,
    val alerts: AlertThresholds,
    val gate: RestoreGate,
    val share: OwnerShareState,
    val restore: RestoreUi,
    val busy: Boolean,
    /** Opened from the Finish-setup card: one step, then home. */
    val single: Boolean,
    /** Back shows the previous step (not on the first step, nor back into the role once chosen). */
    val canBack: Boolean,
)

/**
 * [openedOn]: opened on that one step (Settings › Sharing's Set up opens [SetupStep.Identity]); it is shown until it
 * is completed or left, then the screen goes back where it was opened.
 */
class SetupViewModel(
    private val m: SetupModule,
    private val openedOn: SetupStep? = null,
) : ViewModel() {
    private val shown = MutableStateFlow<SetupStep?>(null)
    private val restore = MutableStateFlow<RestoreUi>(RestoreUi.Idle)
    private val busy = MutableStateFlow(false)
    private val single = MutableStateFlow(false)

    /** False until [openedOn] is reopened in the record: no other step flashes and setup doesn't end before it shows. */
    private val ready = MutableStateFlow(openedOn == null)

    /** The `ui` flow has seen [openedOn] reopened in the record. */
    private var seenOpen = false

    /** The thresholds being edited on the alert step (null = the stored ones). */
    private val editing = MutableStateFlow<AlertSettings?>(null)

    private val inputs =
        combine(m.setupStore.data, m.roles.role, m.hub.state, m.sharing.state, combine(m.alertSettings.settings, m.alertsConfirmedAt, ::Pair)) {
                record,
                role,
                glucose,
                share,
                (alerts, confirmedAt),
            ->
            Inputs(record, role, glucose, share, alerts, confirmedAt != null)
        }

    val ui: StateFlow<SetupUi?> =
        // the checklist is THE live flow (the monitor computes it asynchronously): never a copy taken on resume
        combine(inputs, m.readiness.readiness, shown, combine(restore, busy, single, editing, ready, ::Locals)) { i, readiness, override, l ->
            if (!l.ready) return@combine null
            // opened on one step: nothing until this flow itself sees the reopened record (no early "setup is over")
            if (openedOn != null && !seenOpen) {
                if (openedOn in i.record.completed || openedOn in i.record.skipped) return@combine null
                seenOpen = true
            }
            val now = m.clock.now()
            val facts =
                SetupFacts(
                    role = i.role,
                    followerAvailable = m.followerAvailable,
                    ownSensorLive = RestoreGate.ownSensorLive(i.glucose, now),
                    hasIdentity = i.share is OwnerShareState.Sharing || i.share is OwnerShareState.AnotherAppSharingAsYou,
                    alertsConfirmed = i.alertsConfirmed,
                )
            val steps = SetupMachine.steps(facts)
            val step =
                if (l.single) {
                    override?.takeIf { it in steps && it !in i.record.completed && it !in i.record.skipped }
                } else {
                    override?.takeIf { it in steps } ?: SetupMachine.next(i.record, facts)
                }
            val at = steps.indexOf(step)
            SetupUi(
                step = step,
                index = (steps.indexOf(step) + 1).coerceAtLeast(1),
                count = steps.size,
                facts = facts,
                record = i.record,
                readiness = readiness,
                alerts = AlertThresholds(l.editing ?: i.alerts),
                gate = RestoreGate.evaluate(facts.ownSensorLive),
                share = i.share,
                restore = l.restore,
                busy = l.busy,
                single = l.single,
                canBack = !l.single && at > 0 && !(facts.role != null && steps[at - 1] == SetupStep.Role),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        val step = openedOn
        if (step != null) {
            viewModelScope.launch {
                single.value = true
                shown.value = step
                try {
                    m.setupStore.update { SetupMachine.reopen(it, step) }
                } finally {
                    ready.value = true
                }
            }
        }
    }

    fun onResume() {
        m.refresh()
    }

    /** The Finish-setup steps already shown in this visit: done or *Later* moves on to the next one, never back. */
    private val visited = mutableSetOf<SetupStep>()

    /**
     * Opened from home after setup was finished: the next Finish-setup row's step not shown yet in this visit (alerts
     * first). False when there is none left, or when opened on one step ([openedOn]): then the screen goes back.
     */
    suspend fun reopenFirstUnfinished(): Boolean {
        if (openedOn != null || leaving) return false
        val item = m.finishItems.first().firstOrNull { it.step !in visited } ?: return false
        visited += item.step
        open(item.step)
        return true
    }

    /** Reopened from the Finish-setup card: shown until it is completed or skipped again (Back skips it again). */
    fun open(step: SetupStep) {
        single.value = true
        shown.value = step
        record { SetupMachine.reopen(it, step) }
    }

    /** Set by Back on a reopened step: the screen leaves, no further Finish-setup step opens. */
    private var leaving = false

    /**
     * Back: the previous step (its answers are kept); [onLeave] on the first step (leave setup). A reopened step is
     * left as skipped (written first), so its Finish-setup row stays.
     */
    fun back(onLeave: () -> Unit) {
        val u = ui.value ?: return onLeave()
        if (u.single) {
            leaving = true
            val step = u.step
            // the skip is written before the screen leaves (leaving clears this view model and its scope)
            viewModelScope.launch {
                try {
                    if (step != null) m.setupStore.update { SetupMachine.skip(it, step) }
                } finally {
                    onLeave()
                }
            }
            return
        }
        if (!u.canBack) return onLeave()
        val steps = SetupMachine.steps(u.facts)
        shown.value = steps[steps.indexOf(u.step) - 1]
    }

    fun readinessFix(r: app.mauri.sukrara.core.model.AlertRequirement) = m.readiness.fix(r)

    fun continueWelcome() = record(SetupMachine::completeWelcome)

    /** P10: called after the person confirmed what the role means. A changed role resets the alert runtime first. */
    fun chooseRole(role: Role) =
        act {
            val facts = ui.value?.facts ?: return@act
            val chosen = SetupMachine.chooseRole(role, confirmed = true, facts) as? Result.Done ?: return@act
            if (facts.role != chosen.value) m.resetAlerts()
            m.roles.set(chosen.value)
            m.startMonitoring()
        }

    fun completePermissions() = record(SetupMachine::completePermissions)

    fun completeSensor() {
        val facts = ui.value?.facts ?: return
        record { SetupMachine.completeSensor(it, facts) }
    }

    fun editThreshold(
        kind: AlertKind,
        value: Double,
    ) {
        val field = ThresholdField.entries.firstOrNull { it.kind == kind } ?: return
        val current = ui.value?.alerts ?: return
        editing.value = current.with(field, value).settings
    }

    /** P4: saves exactly what is shown to the store the alert runtime reads, then completes the step. */
    fun confirmAlerts() =
        act {
            val shownAlerts = ui.value?.alerts ?: return@act
            if (!shownAlerts.ordered) return@act
            m.confirmAlerts(shownAlerts)
            editing.value = null
            m.setupStore.update(SetupMachine::confirmAlerts)
        }

    /** P10: called after the person confirmed what Start fresh means. */
    fun startFresh() =
        act {
            val created = m.sharing.startFresh()
            val facts = ui.value?.facts ?: return@act
            m.setupStore.update { SetupMachine.completeIdentity(it, facts.copy(hasIdentity = created || facts.hasIdentity)) }
        }

    fun completeIdentity() {
        val facts = ui.value?.facts ?: return
        record { SetupMachine.completeIdentity(it, facts) }
    }

    fun retryConflict() = act { m.sharing.retryConflict() }

    /** Restores only through an open gate, re-checked here (the facts may have changed since the screen drew). */
    fun restore(
        code: String,
        password: String?,
    ) = act {
        val u = ui.first { it != null }!!
        val gate = RestoreGate.evaluate(ownSensorLive = RestoreGate.ownSensorLive(m.hub.state.value, m.clock.now()))
        if (!gate.open) return@act
        restore.value = RestoreUi.Busy
        // an unexpected failure is "nothing changed, try again", never a stuck spinner
        val result =
            try {
                m.sharing.restore(code.trim(), password?.takeIf { it.isNotEmpty() })
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                RestoreIdentity.Result.RelaysUnreachable
            }
        restore.value = if (result is RestoreIdentity.Result.Restored) RestoreUi.Restored(result.followers) else RestoreUi.Failed(result)
        if (result is RestoreIdentity.Result.Restored) m.setupStore.update { SetupMachine.completeIdentity(it, u.facts.copy(hasIdentity = true)) }
    }

    fun clearRestoreError() {
        if (restore.value is RestoreUi.Failed) restore.value = RestoreUi.Idle
    }

    fun skip(step: SetupStep) = record { SetupMachine.skip(it, step) }

    private fun record(change: (SetupRecord) -> SetupRecord) =
        act {
            m.setupStore.update(change)
        }

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            busy.value = true
            try {
                block()
                if (!single.value) shown.update { null }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // the class only (no message: it could carry a code or a value); the screen stays on the step to retry
                android.util.Log.w("Sukrara.setup", "setup action failed: ${e.javaClass.simpleName}")
            } finally {
                busy.value = false
            }
        }
    }

    private data class Inputs(
        val record: SetupRecord,
        val role: Role?,
        val glucose: app.mauri.sukrara.core.model.GlucoseState,
        val share: OwnerShareState,
        val alerts: AlertSettings,
        val alertsConfirmed: Boolean,
    )

    private data class Locals(
        val restore: RestoreUi,
        val busy: Boolean,
        val single: Boolean,
        val editing: AlertSettings?,
        val ready: Boolean,
    )

    companion object {
        fun factory(
            module: SetupModule,
            startAt: SetupStep? = null,
        ) = viewModelFactory { initializer { SetupViewModel(module, startAt) } }
    }
}

