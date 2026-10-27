// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mauri.sukrara.alerts.AlertKind
import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.alerts.QuietRule
import app.mauri.sukrara.core.model.AlertQuietSide
import app.mauri.sukrara.core.model.AlertUiState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The alert pages' state: the stored alert settings and what is muted / quiet now. */
data class AlertsUi(
    val settings: AlertSettings,
    val alerts: AlertUiState,
) {
    /** The quiet state of [kind]'s side (a mute, a treatment window, Remind less), or null. */
    fun quietOf(kind: AlertKind): AlertQuietSide? =
        alerts.quiet.firstOrNull { q -> AlertKind.entries.firstOrNull { it.wire == q.kind }?.side == kind.side }
}

/** The three quiet rules (SPEC §2) as the pages name them. */
enum class QuietKind { AfterInsulin, AfterCarbs, FallingHigh }

/** Edits of the alert settings, pure (tested without Android). */
object AlertEdits {
    fun kindOn(
        kind: AlertKind,
        on: Boolean,
    ): (AlertSettings) -> AlertSettings = { s -> s.withRule(kind, s.rule(kind).copy(on = on)) }

    fun threshold(
        kind: AlertKind,
        value: Double,
    ): (AlertSettings) -> AlertSettings = { s -> s.withRule(kind, s.rule(kind).copy(threshold = SchemaPrefs.thresholdSpec(kind).coerce(value))) }

    fun repeat(
        kind: AlertKind,
        minutes: Double,
    ): (AlertSettings) -> AlertSettings = { s -> s.withRule(kind, s.rule(kind).copy(repeatMin = SchemaPrefs.RepeatSpec.coerce(minutes).roundToInt())) }

    fun alarm(
        kind: AlertKind,
        alarm: Boolean,
    ): (AlertSettings) -> AlertSettings = { s -> s.withRule(kind, s.rule(kind).copy(alarm = alarm)) }

    /** [pack] null = the kind follows the main pack again. */
    fun sound(
        kind: AlertKind,
        pack: String?,
    ): (AlertSettings) -> AlertSettings = { s ->
        s.copy(sounds = s.sounds.copy(perKind = if (pack == null) s.sounds.perKind - kind else s.sounds.perKind + (kind to pack)))
    }

    fun quiet(
        which: QuietKind,
        transform: (QuietRule) -> QuietRule,
    ): (AlertSettings) -> AlertSettings = { s ->
        when (which) {
            QuietKind.AfterInsulin -> s.copy(afterInsulin = transform(s.afterInsulin))
            QuietKind.AfterCarbs -> s.copy(afterCarbs = transform(s.afterCarbs))
            QuietKind.FallingHigh -> s.copy(fallingHigh = transform(s.fallingHigh))
        }
    }

    fun quietRule(
        s: AlertSettings,
        which: QuietKind,
    ): QuietRule =
        when (which) {
            QuietKind.AfterInsulin -> s.afterInsulin
            QuietKind.AfterCarbs -> s.afterCarbs
            QuietKind.FallingHigh -> s.fallingHigh
        }

    fun quietSpecId(which: QuietKind): String =
        when (which) {
            QuietKind.AfterInsulin -> "alerts.muteAfterInsulin.min"
            QuietKind.AfterCarbs -> "alerts.muteAfterCarbs.min"
            QuietKind.FallingHigh -> "alerts.muteFallingHigh.min"
        }
}

/** Feeds the Alerts page and every alert's page: one source of truth for the alert settings they edit. */
class AlertsViewModel(
    private val deps: SettingsDeps,
) : ViewModel() {
    val ui: StateFlow<AlertsUi?> =
        combine(deps.alertSettings.settings, deps.alertState, ::AlertsUi)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), null)

    fun edit(transform: (AlertSettings) -> AlertSettings) {
        viewModelScope.launch { deps.alertSettings.update(transform) }
    }

    /** Mute all for [minutes] (0 = until turned off: the schema's special value). */
    fun muteAll(minutes: Double) {
        viewModelScope.launch { deps.alertControls.muteAll(if (minutes <= 0.0) null else minutes.roundToInt()) }
    }

    fun unmuteAll() {
        viewModelScope.launch { deps.alertControls.unmuteAll() }
    }

    /** Ends [kind]'s side's mute or treatment / falling quiet ("Unmute", "Alert me anyway"). */
    fun unmute(kind: AlertKind) {
        viewModelScope.launch { deps.alertControls.unmute(kind.wire) }
    }

    fun remindAsUsual(kind: AlertKind) {
        viewModelScope.launch { deps.alertControls.remindAsUsual(kind.wire) }
    }

    private companion object {
        const val STOP_MS = 5_000L
    }
}
