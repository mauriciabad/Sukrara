// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.core.model.AlertReadiness
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.feature.sharing.SetupModule
import app.mauri.sukrara.share.owner.OwnerShareState
import app.mauri.sukrara.share.owner.usecase.RestoreIdentity
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
    val latestMgdl: Int?,
    val restore: RestoreUi,
    val busy: Boolean,
    /** Opened from the Finish-setup card: one step, then home. */
    val single: Boolean,
    /** Back shows the previous step (not on the first step, nor back into the role once chosen). */
    val canBack: Boolean,
)

class SetupViewModel(private val m: SetupModule) : ViewModel() {
    /** Re-read on every resume: package installs and system settings change outside the app. */
    private val phone = MutableStateFlow(m.phoneNow())
    private val shown = MutableStateFlow<SetupStep?>(null)
    private val restore = MutableStateFlow<RestoreUi>(RestoreUi.Idle)
    private val busy = MutableStateFlow(false)
    private val single = MutableStateFlow(false)

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
        combine(inputs, phone, shown, combine(restore, busy, single, editing, ::Locals)) { i, p, override, l ->
            val now = m.clock.now()
            val facts =
                SetupFacts(
                    role = i.role,
                    showcaseInstalled = p.showcaseInstalled,
                    followerAvailable = m.followerAvailable,
                    ownSensorLive = RestoreGate.ownSensorLive(i.glucose, now),
                    hasIdentity = i.share is OwnerShareState.Sharing || i.share is OwnerShareState.AnotherAppSharingAsYou,
                    alertsConfirmed = i.alertsConfirmed,
                )
            val steps = SetupMachine.steps(facts)
            val step = override?.takeIf { it in steps } ?: SetupMachine.next(i.record, facts)
            val at = steps.indexOf(step)
            SetupUi(
                step = step,
                index = (steps.indexOf(step) + 1).coerceAtLeast(1),
                count = steps.size,
                facts = facts,
                record = i.record,
                readiness = p.readiness,
                alerts = AlertThresholds(l.editing ?: i.alerts),
                gate = RestoreGate.evaluate(facts.ownSensorLive, facts.showcaseInstalled, i.record.showcaseSharingOffConfirmed),
                share = i.share,
                latestMgdl = i.glucose.latest?.mgdl?.takeIf { facts.ownSensorLive },
                restore = l.restore,
                busy = l.busy,
                single = l.single,
                canBack = !l.single && at > 0 && !(facts.role != null && steps[at - 1] == SetupStep.Role),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun onResume() {
        m.readiness.refresh()
        phone.value = m.phoneNow()
    }

    /** Reopened from the Finish-setup card. */
    fun open(step: SetupStep) {
        single.value = true
        record { SetupMachine.reopen(it, step) }
    }

    /** Back: the previous step (its answers are kept); false on the first step (leave setup). */
    fun back(): Boolean {
        val u = ui.value ?: return false
        if (u.single) return false
        if (!u.canBack) return false
        val steps = SetupMachine.steps(u.facts)
        shown.value = steps[steps.indexOf(u.step) - 1]
        return true
    }

    fun readinessFix(r: app.mauri.sukrara.core.model.AlertRequirement) = m.readiness.fix(r)

    fun showcaseOpenIntent() = m.showcase.openIntent()

    fun showcaseUninstallIntent() = m.showcase.uninstallIntent()

    fun continueWelcome() = record(SetupMachine::completeWelcome)

    /** P10: called after the person confirmed what the role means. A changed role resets the alert runtime first. */
    fun chooseRole(role: Role) =
        act {
            val facts = ui.value?.facts ?: return@act
            val chosen = SetupMachine.chooseRole(role, confirmed = true, facts) as? Result.Done ?: return@act
            if (facts.role != chosen.value) m.resetAlerts()
            m.roles.set(chosen.value)
        }

    fun completePermissions() = record(SetupMachine::completePermissions)

    fun confirmShowcaseOff() = record(SetupMachine::confirmShowcaseOff)

    fun completeSensor() {
        val facts = ui.value?.facts ?: return
        record { SetupMachine.completeSensor(it, facts) }
    }

    fun editThreshold(
        field: ThresholdField,
        value: Double,
    ) {
        val current = ui.value?.alerts ?: return
        editing.value = current.with(field, value).settings
    }

    /** P4: saves exactly what is shown to the store the alert runtime reads, then completes the step. */
    fun confirmAlerts() =
        act {
            val shownAlerts = ui.value?.alerts ?: return@act
            if (!shownAlerts.ordered) return@act
            m.confirmAlerts(shownAlerts.settings)
            editing.value = null
            m.setupStore.update(SetupMachine::confirmAlerts)
        }

    /** P10: called after the person confirmed what Start fresh means. */
    fun startFresh() =
        act {
            m.sharing.startFresh()
            val facts = ui.value?.facts ?: return@act
            // the engine's state turns Sharing; complete against it so the step doesn't wait for a recomposition
            val has = m.sharing.state.first { it !is OwnerShareState.Loading }.let { it is OwnerShareState.Sharing }
            m.setupStore.update { SetupMachine.completeIdentity(it, facts.copy(hasIdentity = has)) }
        }

    fun completeIdentity() {
        val facts = ui.value?.facts ?: return
        record { SetupMachine.completeIdentity(it, facts) }
    }

    fun retryConflict() = act { m.sharing.retryConflict() }

    fun setShowcaseSharingOff(off: Boolean) = record { SetupMachine.confirmShowcaseSharingOff(it, off) }

    /** Restores only through an open gate, re-checked here (the facts may have changed since the screen drew). */
    fun restore(
        code: String,
        password: String?,
    ) = act {
        phone.value = m.phoneNow()
        val u = ui.first { it != null }!!
        if (!u.gate.open) return@act
        restore.value = RestoreUi.Busy
        val result = m.sharing.restore(code.trim(), password?.takeIf { it.isNotEmpty() })
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
                shown.update { null }
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
    )

    companion object {
        fun factory(module: SetupModule) = viewModelFactory { initializer { SetupViewModel(module) } }
    }
}

/** The phone facts read synchronously on resume. */
data class PhoneFacts(
    val showcaseInstalled: Boolean,
    val readiness: AlertReadiness,
)
