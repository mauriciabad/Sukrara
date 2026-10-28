// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.integrations

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.mauri.sukrara.designsystem.rows.FooterText
import app.mauri.sukrara.designsystem.rows.NavigationRow
import app.mauri.sukrara.designsystem.rows.SettingsGroup
import app.mauri.sukrara.designsystem.rows.SwitchRow
import app.mauri.sukrara.integrations.libreview.libreViewStatusText
import app.mauri.sukrara.integrations.localserver.NsApiState
import app.mauri.sukrara.integrations.localserver.XdripState
import app.mauri.sukrara.integrations.localserver.nsApiStatusText
import app.mauri.sukrara.integrations.tidepool.tidepoolStatusText
import app.mauri.sukrara.integrations.ui.IntroText
import app.mauri.sukrara.integrations.ui.ago
import app.mauri.sukrara.integrations.ui.uploadStatusText
import app.mauri.sukrara.integrations.upload.UploadStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

/** What Share with apps shows. */
data class AppsUi(
    val wearer: Boolean,
    val xdripOn: Boolean,
    val xdrip: XdripState,
    /** Installed apps that read the xDrip+ server (Garmin Connect), by label. */
    val readers: List<String>,
    /** The Nightscout upload's state (wearer only). */
    val nightscout: UploadStatus,
    /** The LibreView upload's state (wearer only). */
    val libreView: UploadStatus = UploadStatus.Off,
    /** The Nightscout API on this phone. */
    val nightscoutApi: NsApiState = NsApiState.Off,
    /** The Tidepool upload's state (both roles). */
    val tidepool: UploadStatus = UploadStatus.Off,
)

class AppsActions(
    val setXdrip: (Boolean) -> Unit = {},
    val openNightscout: () -> Unit = {},
    val openExport: () -> Unit = {},
    val openLibreView: () -> Unit = {},
    val openNightscoutApi: () -> Unit = {},
    val openTidepool: () -> Unit = {},
)

/**
 * Installed apps known to read glucose from the xDrip+ server rather than broadcasts. Garmin watch apps can't open
 * sockets: Garmin Connect (or the Connect IQ app) makes the request for them. Each package is in the manifest's
 * `<queries>`.
 */
object ServerReaders {
    val PACKAGES =
        listOf(
            // Garmin Connect
            "com.garmin.android.apps.connectmobile",
            // the Connect IQ store app (older watches / setups)
            "com.garmin.connectiq",
        )

    /** The installed ones' labels (PackageManager: call off the main thread). */
    fun installed(context: Context): List<String> {
        val pm = context.packageManager
        return PACKAGES
            .mapNotNull { p ->
                runCatching { pm.getApplicationInfo(p, PackageManager.ApplicationInfoFlags.of(0)) }
                    .getOrNull()
                    ?.let { pm.getApplicationLabel(it).toString() }
            }.sortedBy { it.lowercase() }
    }
}

class AppsViewModel(
    private val module: IntegrationsModule,
    context: Context,
    io: CoroutineDispatcher,
) : ViewModel() {
    private val readers = MutableStateFlow<List<String>>(emptyList())
    val ui: StateFlow<AppsUi?> =
        combine(
            combine(module.wearer, module.xdrip.on, module.xdrip.state) { w, on, x -> Triple(w, on, x) },
            readers,
            module.nightscout.page,
            module.libreView.page,
            combine(module.nightscoutApi.state, module.tidepool.status) { api, tp -> api to tp },
        ) { (w, on, x), r, ns, lv, (api, tp) ->
            AppsUi(
                w,
                on,
                x,
                r,
                ns.status,
                lv.status,
                api,
                tp,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        val app = context.applicationContext
        viewModelScope.launch { readers.value = withContext(io) { ServerReaders.installed(app) } }
    }

    fun setXdrip(on: Boolean) {
        viewModelScope.launch { module.xdrip.setOn(on) }
    }
}

@Composable
fun AppsPage(
    viewModel: AppsViewModel,
    now: () -> Instant,
    openNightscout: () -> Unit,
    openExport: () -> Unit,
    openLibreView: () -> Unit,
    openNightscoutApi: () -> Unit,
    openTidepool: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    ui?.let {
        AppsContent(
            it,
            now(),
            AppsActions(viewModel::setXdrip, openNightscout, openExport, openLibreView, openNightscoutApi, openTidepool),
            modifier,
        )
    }
}

/**
 * Settings › Share with apps: LibreView and the Nightscout upload (wearer), the xDrip+ server for Garmin watches with who reads it and
 * when it was last asked, and Export data.
 */
@Composable
fun AppsContent(
    ui: AppsUi,
    now: Instant,
    actions: AppsActions,
    modifier: Modifier = Modifier,
) = Column(modifier) {
    IntroText(stringResource(if (ui.wearer) R.string.in_apps_intro_wearer else R.string.in_apps_intro_follower))
    SettingsGroup(title = stringResource(R.string.in_apps_clinic_group)) {
        if (ui.wearer) {
            NavigationRow(stringResource(R.string.in_lv_title), actions.openLibreView, summary = libreViewStatusText(ui.libreView, now))
        }
        NavigationRow(stringResource(R.string.in_tp_title), actions.openTidepool, summary = tidepoolStatusText(ui.tidepool, now))
    }
    if (ui.wearer) {
        SettingsGroup(title = stringResource(R.string.in_apps_nightscout_group)) {
            NavigationRow(stringResource(R.string.in_ns_title), actions.openNightscout, summary = uploadStatusText(ui.nightscout, now))
        }
    }
    SettingsGroup(title = stringResource(R.string.in_apps_watch_group)) {
        SwitchRow(stringResource(R.string.in_apps_xdrip), ui.xdripOn, actions.setXdrip, summary = xdripSummary(ui, now))
    }
    SettingsGroup(title = stringResource(R.string.in_apps_phone_group)) {
        NavigationRow(stringResource(R.string.in_nsapi_title), actions.openNightscoutApi, summary = nsApiStatusText(ui.nightscoutApi, now))
    }
    SettingsGroup(title = stringResource(R.string.in_apps_files_group)) {
        NavigationRow(
            stringResource(R.string.in_export_title),
            actions.openExport,
            summary = stringResource(R.string.in_apps_export_summary),
        )
    }
    FooterText(stringResource(R.string.in_apps_footer))
}

@Composable
private fun xdripSummary(
    ui: AppsUi,
    now: Instant,
): String {
    val parts = ArrayList<String>()
    when (val x = ui.xdrip) {
        XdripState.Off -> {
            parts += stringResource(R.string.in_apps_xdrip_off)
        }

        XdripState.PortBusy -> {
            parts += stringResource(R.string.in_apps_xdrip_busy)
        }

        XdripState.LeftToJuggluco -> {
            parts += stringResource(R.string.in_apps_xdrip_juggluco)
        }

        is XdripState.Listening -> {
            parts += stringResource(R.string.in_apps_xdrip_on)
            parts +=
                x.lastAsked?.let { stringResource(R.string.in_apps_xdrip_last_asked, ago(it, now)) }
                    ?: stringResource(R.string.in_apps_xdrip_not_asked)
        }
    }
    if (ui.readers.isNotEmpty()) parts += stringResource(R.string.in_apps_xdrip_read_by, ui.readers.joinToString())
    return parts.joinToString(" · ")
}
