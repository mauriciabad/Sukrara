// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.settings.statusbar

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mauri.sukrara.core.data.settings.SurfaceSettings
import app.mauri.sukrara.core.model.SystemAccessPort
import app.mauri.sukrara.core.model.SystemAccessState
import app.mauri.sukrara.core.model.storage.SettingSpec
import app.mauri.sukrara.core.model.storage.SettingsRepository
import app.mauri.sukrara.designsystem.rows.FooterText
import app.mauri.sukrara.designsystem.rows.NavigationRow
import app.mauri.sukrara.designsystem.rows.SettingsGroup
import app.mauri.sukrara.designsystem.rows.SwitchRow
import app.mauri.sukrara.feature.settings.R
import app.mauri.sukrara.feature.settings.SettingsPage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Settings › Status bar: the glucose notification's Live Update chip and number icon; with root, the value itself. */
data class StatusBarPrefs(
    val chip: Boolean,
    val numberIcon: Boolean,
)

class StatusBarViewModel(
    private val settings: SettingsRepository,
    private val systemAccess: SystemAccessPort,
) : ViewModel() {
    val root: StateFlow<RootSettings?> =
        combine(
            settings.flow(SurfaceSettings.RootValue),
            settings.flow(SurfaceSettings.RootPosition),
            settings.flow(SurfaceSettings.RootColor),
            settings.flow(SurfaceSettings.HideNotificationIcon),
            ::RootSettings,
        ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val access: StateFlow<SystemAccessState> get() = systemAccess.state

    /** A root / LSPosed action is running; [problem] = why the last one failed (shown in the banner). */
    val busy = MutableStateFlow(false)
    val problem = MutableStateFlow<String?>(null)

    val actions =
        RootActions(
            setOn = { set(SurfaceSettings.RootValue, it) },
            setPosition = { set(SurfaceSettings.RootPosition, it) },
            setColor = { set(SurfaceSettings.RootColor, it) },
            setHideIcon = { set(SurfaceSettings.HideNotificationIcon, it) },
            checkRoot = {
                run {
                    systemAccess.checkRoot()
                    null
                }
            },
            requestScope = { run { systemAccess.requestScope() } },
            restart = { run { systemAccess.restartSystemUi() } },
        )

    /** Back on the page: ask root and LSPosed again (the person may have granted root or changed the scope). */
    fun refresh() {
        viewModelScope.launch { runCatching { systemAccess.checkRoot() } }
        systemAccess.refresh()
    }

    private fun <T> set(
        spec: SettingSpec<T>,
        value: T,
    ) {
        viewModelScope.launch { settings.set(spec, value) }
    }

    private fun run(action: suspend () -> String?) {
        if (busy.value) return
        busy.value = true
        problem.value = null
        viewModelScope.launch {
            problem.value = runCatching { action() }.getOrElse { it.javaClass.simpleName }
            busy.value = false
        }
    }

    val prefs: StateFlow<StatusBarPrefs?> =
        combine(settings.flow(SurfaceSettings.StatusChip), settings.flow(SurfaceSettings.NumberIcon), ::StatusBarPrefs)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setChip(on: Boolean) {
        viewModelScope.launch { settings.set(SurfaceSettings.StatusChip, on) }
    }

    fun setNumberIcon(on: Boolean) {
        viewModelScope.launch { settings.set(SurfaceSettings.NumberIcon, on) }
    }
}

/** Whether Android lets Sukrara post Live Updates now (the person can turn them off per app; re-read on resume). */
fun Context.liveUpdatesAllowed(): Boolean =
    Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1 &&
        runCatching { getSystemService(NotificationManager::class.java).canPostPromotedNotifications() }.getOrDefault(false)

/** Android's per-app Live Updates switch. */
fun Context.openLiveUpdateSettings() {
    val intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }
}

/**
 * The page: *Live Update chip* (the value next to the clock), *Allow Live Updates for Sukrara* while the chip is on and
 * Android refuses it (the way to fix it, not a dead switch), *Number icon* (only meaningful without the chip: its
 * summary says so). The footer explains what each one changes.
 */
@Composable
fun StatusBarContent(
    prefs: StatusBarPrefs,
    liveUpdatesAllowed: Boolean,
    onChip: (Boolean) -> Unit,
    onNumberIcon: (Boolean) -> Unit,
    onAllowLiveUpdates: () -> Unit,
    onBack: () -> Unit,
    rootSection: @Composable () -> Unit = {},
) {
    SettingsPage(stringResource(R.string.st_statusbar_title), onBack) {
        SettingsGroup {
            SwitchRow(
                stringResource(R.string.st_statusbar_chip),
                prefs.chip,
                onChip,
                summary = stringResource(R.string.st_statusbar_chip_summary),
            )
            if (prefs.chip && !liveUpdatesAllowed) {
                NavigationRow(
                    stringResource(R.string.st_statusbar_allow_live_updates),
                    onAllowLiveUpdates,
                    summary = stringResource(R.string.st_statusbar_allow_live_updates_summary),
                )
            }
            val chipShows = prefs.chip && liveUpdatesAllowed
            SwitchRow(
                stringResource(R.string.st_statusbar_number_icon),
                prefs.numberIcon,
                onNumberIcon,
                summary =
                    stringResource(
                        if (chipShows) R.string.st_statusbar_number_icon_chip else R.string.st_statusbar_number_icon_summary,
                    ),
                enabled = !chipShows,
            )
        }
        FooterText(stringResource(R.string.st_statusbar_footer))
        rootSection()
    }
}
