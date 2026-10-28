// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.settings.lock

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mauri.sukrara.core.data.settings.SchemaSettings
import app.mauri.sukrara.core.model.storage.SettingsRepository
import app.mauri.sukrara.designsystem.dialog.NumberDialog
import app.mauri.sukrara.designsystem.dialog.NumberInput
import app.mauri.sukrara.designsystem.rows.FooterText
import app.mauri.sukrara.designsystem.rows.SettingsGroup
import app.mauri.sukrara.designsystem.rows.ValueRow
import app.mauri.sukrara.feature.settings.R
import app.mauri.sukrara.feature.settings.SettingsPage
import app.mauri.sukrara.feature.settings.numberLabeler
import app.mauri.sukrara.settings.SettingsSpec
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.mauri.sukrara.core.designsystem.R as DsR

/** Seconds the quick treatment dialog stays over the lock screen before it closes unsaved (`lock.treatmentTime`). */
val LockTreatmentTime = SchemaSettings.number("lock.treatmentTime")

class LockScreenViewModel(
    private val settings: SettingsRepository,
) : ViewModel() {
    val seconds: StateFlow<Double?> =
        settings.flow(LockTreatmentTime).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun set(value: Double) {
        viewModelScope.launch { settings.set(LockTreatmentTime, value) }
    }
}

/** Settings › Lock screen: *Time to save* (10 / 20 / 30 / 60 s or a custom 10–120 s). */
@Composable
fun LockScreenContent(
    seconds: Double,
    onSet: (Double) -> Unit,
    onBack: () -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    val label = numberLabeler()
    val spec = SettingsSpec.LOCK_TREATMENT_TIME
    SettingsPage(stringResource(R.string.st_lock_title), onBack) {
        SettingsGroup(title = stringResource(R.string.st_lock_treatment)) {
            ValueRow(stringResource(R.string.st_lock_time), label(spec, seconds), { editing = true }, icon = DsR.drawable.ds_ic_timer)
        }
        FooterText(stringResource(R.string.st_lock_footer))
    }
    if (editing) {
        NumberDialog(
            stringResource(R.string.st_lock_time),
            NumberInput(spec),
            seconds,
            { label(spec, it) },
            { editing = false },
            onChoose = onSet,
        )
    }
}
