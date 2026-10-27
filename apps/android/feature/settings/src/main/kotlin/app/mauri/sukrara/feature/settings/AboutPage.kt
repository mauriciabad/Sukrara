// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mauri.sukrara.designsystem.rows.FooterText
import app.mauri.sukrara.designsystem.rows.InfoRow
import app.mauri.sukrara.designsystem.rows.NavigationRow
import app.mauri.sukrara.designsystem.rows.SettingsGroup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Checks for an update once when the About page opens (and on *Check again*); never polls. */
class AboutViewModel(
    private val releases: ReleasesSource,
    val installed: String,
    private val prerelease: Boolean,
) : ViewModel() {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Checking)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    init {
        check()
    }

    fun check() {
        _state.value = UpdateState.Checking
        viewModelScope.launch { _state.value = UpdateCheck.state(releases.fetch(), installed, prerelease) }
    }
}

/** What a release file is for, from its name ("Mac", "Android"); unknown kinds show their file name. */
@Composable
private fun platformOf(name: String): String =
    when {
        name.endsWith(".apk") -> stringResource(R.string.st_asset_android)
        name.endsWith(".dmg") || (name.contains("mac", ignoreCase = true) && name.endsWith(".zip")) -> stringResource(R.string.st_asset_mac)
        else -> name
    }

/** About: version, update check, Sukrara on other devices, report a problem, the showcase note. */
@Composable
fun AboutContent(
    installed: String,
    update: UpdateState,
    onCheckAgain: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    SettingsPage(stringResource(R.string.st_about_title), onBack) {
        SettingsGroup {
            InfoRow(stringResource(R.string.st_about_version), summary = installed)
            when (update) {
                UpdateState.Checking -> InfoRow(stringResource(R.string.st_update_checking))

                is UpdateState.UpToDate ->
                    NavigationRow(stringResource(R.string.st_update_none), onCheckAgain, summary = stringResource(R.string.st_update_check_again))

                is UpdateState.Available ->
                    NavigationRow(
                        stringResource(R.string.st_update_available, update.release.tag.removePrefix("v")),
                        { context.openUrl(update.release.url) },
                        summary = stringResource(if (update.release.prerelease) R.string.st_update_prerelease else R.string.st_update_open),
                    )

                UpdateState.Offline ->
                    NavigationRow(
                        stringResource(R.string.st_update_offline),
                        { context.openUrl(UpdateCheck.RELEASES_URL) },
                        summary = stringResource(R.string.st_update_offline_summary),
                    )
            }
        }
        val latest =
            when (update) {
                is UpdateState.Available -> update.release
                is UpdateState.UpToDate -> update.latest
                else -> null
            }
        val others = latest?.assets.orEmpty().filterNot { it.name.endsWith(".apk") }
        if (others.isNotEmpty()) {
            SettingsGroup(title = stringResource(R.string.st_other_devices)) {
                for (asset in others) NavigationRow(platformOf(asset.name), { context.openUrl(asset.url) }, summary = asset.name)
            }
        }
        SettingsGroup {
            NavigationRow(stringResource(R.string.st_report_problem), { context.openUrl(UpdateCheck.ISSUES_URL) }, summary = stringResource(R.string.st_report_problem_summary))
            NavigationRow(stringResource(R.string.st_source_code), { context.openUrl(UpdateCheck.REPO_URL) }, summary = stringResource(R.string.st_license))
        }
        FooterText(stringResource(R.string.st_showcase_note))
    }
}
