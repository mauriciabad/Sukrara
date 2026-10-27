// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share

import app.mauri.sukrara.core.model.AlertUiState
import app.mauri.sukrara.core.model.GlucoseHub
import app.mauri.sukrara.core.model.GlucoseState
import app.mauri.sukrara.core.model.LinkState
import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.SensorState
import app.mauri.sukrara.core.model.SourceId
import app.mauri.sukrara.core.model.SourceStatus
import kotlinx.coroutines.flow.MutableStateFlow

/** The hub as a wearer's phone sees it; readings appear in `recent`. */
class FakeHub : GlucoseHub {
    override val state = MutableStateFlow(GlucoseState.Initial.copy(role = Role.Wearer))

    fun push(r: Reading) {
        state.value = state.value.copy(latest = r, recent = state.value.recent + r)
    }

    override suspend fun ingest(readings: List<Reading>, from: SourceId) = Unit

    override fun setSource(status: SourceStatus) = Unit

    override fun setSensor(sensor: SensorState) = Unit

    override fun setAlerts(alerts: AlertUiState) = Unit

    override fun setLink(link: LinkState?) = Unit
}
