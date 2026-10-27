// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.alerts

import app.mauri.sukrara.alerts.AlertGroup
import app.mauri.sukrara.alerts.AlertKind
import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.alerts.Doses
import app.mauri.sukrara.alerts.Firing
import app.mauri.sukrara.alerts.Side
import app.mauri.sukrara.core.model.AlertDeliveryProblem
import app.mauri.sukrara.core.model.AlertUiState
import app.mauri.sukrara.core.model.GlucoseState
import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.SourceId
import app.mauri.sukrara.core.model.Treatment
import app.mauri.sukrara.core.model.TreatmentKind
import app.mauri.sukrara.core.model.TreatmentOrigin
import app.mauri.sukrara.core.testing.FakeClock
import app.mauri.sukrara.core.testing.InMemorySettingsRepository
import app.mauri.sukrara.core.testing.InMemoryStateStore
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant

/** What a test's fake notification shade shows: one entry per group slot. */
class FakePresenter : AlertPresenter {
    val slots = linkedMapOf<AlertGroup, String>()
    val log = mutableListOf<String>()
    var problems: Set<AlertDeliveryProblem> = emptySet()

    override fun show(firing: Firing, settings: AlertSettings, silent: Boolean, who: String?) {
        slots[firing.kind.group] = firing.kind.wire
        log += (if (silent) "resume " else "show ") + firing.kind.wire
    }

    override fun showMuted(group: AlertGroup, side: Side, kind: AlertKind, until: Long, settings: AlertSettings, now: Long) {
        slots[group] = "muted ${side.wire} until $until"
        log += "muted ${side.wire}"
    }

    override fun clear(group: AlertGroup) {
        slots.remove(group)
        log += "clear ${group.wire}"
    }

    override fun deliveryProblems(alarmKinds: Set<AlertKind>) = problems
}

class FakeAlarm : AlertAlarm {
    var at: Long? = null

    override fun set(at: Long?) {
        this.at = at
    }
}

/** One phone's alert runtime over in-memory stores that outlive it (a "process death" = a new [runtime()]). */
class AlertRig(val clock: FakeClock = FakeClock(Instant.ofEpochSecond(1_760_000_040L))) {
    val settingsRepo = InMemorySettingsRepository()
    val record = InMemoryStateStore(AlertRecord())
    val store = AlertStateStore(settingsRepo, record)
    val presenter = FakePresenter()
    val alarm = FakeAlarm()
    val treatments = MutableStateFlow<List<Treatment>>(emptyList())
    var published = AlertUiState()
    private var ids = 0

    fun runtime() = AlertRuntime(store, treatments, presenter, alarm, { }, { published = it }, clock, newId = { "id${++ids}" })

    fun reading(mgdl: Int, at: Instant = clock.now()) = Reading(at, mgdl, null, SourceId.Libre3)

    fun wearerState(latest: Reading?, monitoring: Boolean = true) = GlucoseState.Initial.copy(
        role = Role.Wearer,
        latest = latest,
        monitoringStartedAt = if (monitoring) latest?.t ?: clock.now() else null,
    )
}

/** [d] as the treatment log the runtime reads after a restart. */
fun treatmentsOf(d: Doses): List<Treatment> =
    d.carbs.mapIndexed { i, t -> treatment("c$i", TreatmentKind.Carbs, t) } + d.rapid.mapIndexed { i, t -> treatment("r$i", TreatmentKind.RapidInsulin, t) }

private fun treatment(id: String, kind: TreatmentKind, t: Long) =
    Treatment(id, kind, 1.0, Instant.ofEpochSecond(t), modifiedAt = Instant.ofEpochSecond(t), origin = TreatmentOrigin.Phone)
