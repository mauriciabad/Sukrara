// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.settings

import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.core.model.AlertControls
import app.mauri.sukrara.core.model.AlertReadiness
import app.mauri.sukrara.core.model.AlertReadinessSource
import app.mauri.sukrara.core.model.AlertRequirement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeAlertSettingsStore(initial: AlertSettings = AlertSettings()) : AlertSettingsStore {
    val state = MutableStateFlow(initial)
    var writes = 0
    override val settings = state

    override suspend fun update(transform: (AlertSettings) -> AlertSettings) {
        writes++
        state.value = transform(state.value)
    }
}

class RecordingControls : AlertControls {
    val calls = mutableListOf<String>()

    override suspend fun mute(
        kind: String,
        minutes: Int,
    ) {
        calls += "mute $kind $minutes"
    }

    override suspend fun unmute(kind: String) {
        calls += "unmute $kind"
    }

    override suspend fun remindLess(kind: String) {
        calls += "remindLess $kind"
    }

    override suspend fun remindAsUsual(kind: String) {
        calls += "remindAsUsual $kind"
    }

    override suspend fun muteAll(minutes: Int?) {
        calls += "muteAll $minutes"
    }

    override suspend fun unmuteAll() {
        calls += "unmuteAll"
    }
}

class FakeReadiness(missing: Set<AlertRequirement> = emptySet()) : AlertReadinessSource {
    override val readiness: StateFlow<AlertReadiness> = MutableStateFlow(AlertReadiness(missing))
    val fixed = mutableListOf<AlertRequirement>()

    override fun refresh() = Unit

    override fun fix(requirement: AlertRequirement) {
        fixed += requirement
    }
}
