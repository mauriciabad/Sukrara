// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.integrations.localserver

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.mauri.sukrara.designsystem.rows.DangerRow
import app.mauri.sukrara.designsystem.rows.FooterText
import app.mauri.sukrara.designsystem.rows.InfoRow
import app.mauri.sukrara.designsystem.rows.NavigationRow
import app.mauri.sukrara.designsystem.rows.SettingsGroup
import app.mauri.sukrara.designsystem.rows.SwitchRow
import app.mauri.sukrara.integrations.R
import app.mauri.sukrara.integrations.ui.IntroText
import app.mauri.sukrara.integrations.ui.ago
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant

/** The secret as the page shows it: hidden (default), shown, or unreadable. */
sealed interface SecretView {
    data object Hidden : SecretView

    data class Shown(val secret: String) : SecretView

    data object Unreadable : SecretView
}

data class NsApiUi(
    val on: Boolean,
    val state: NsApiState,
    val secret: SecretView = SecretView.Hidden,
    /** The home-network option's switch and where it is. */
    val lanOn: Boolean = false,
    val lan: LanState = LanState.Off,
    /** The last *turn on* / *Allow on this Wi-Fi* was refused: the phone isn't on a Wi-Fi that qualifies. */
    val lanRefused: Boolean = false,
)

class NsApiActions(
    val setOn: (Boolean) -> Unit = {},
    val showSecret: () -> Unit = {},
    val hideSecret: () -> Unit = {},
    val renewSecret: () -> Unit = {},
    /** On only after the page's confirmation (and Android 17's permission request). */
    val setLan: (Boolean) -> Unit = {},
    val allowHere: () -> Unit = {},
    val openAndroidSettings: () -> Unit = {},
)

class NightscoutApiViewModel(
    private val server: NightscoutApiServer,
    val localNetworkAllowed: () -> Boolean,
) : ViewModel() {
    private val secret = MutableStateFlow<SecretView>(SecretView.Hidden)
    private val refused = MutableStateFlow(false)
    val ui: StateFlow<NsApiUi?> =
        combine(
            combine(server.on, server.state, secret) { on, st, s -> Triple(on, st, s) },
            server.lanOn,
            server.lan,
            refused,
        ) { (on, st, s), lo, l, r -> NsApiUi(on, st, s, lo, l, r) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setOn(on: Boolean) {
        viewModelScope.launch { server.setOn(on) }
    }

    fun setLan(on: Boolean) {
        viewModelScope.launch { refused.value = guarded { server.setLan(on) } != null }
    }

    fun allowHere() {
        viewModelScope.launch { refused.value = guarded { server.allowHere() } != null }
    }

    /** A store failure reads as "not turned on" (shown), never a crash. */
    private suspend fun guarded(block: suspend () -> LanState?): LanState? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LanState.NoWifi
        }

    fun recheck() = server.recheckLan()

    fun showSecret() {
        viewModelScope.launch {
            secret.value =
                try {
                    server.secret()?.let { SecretView.Shown(it) } ?: SecretView.Hidden
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    SecretView.Unreadable
                }
        }
    }

    fun hideSecret() {
        secret.value = SecretView.Hidden
    }

    fun renewSecret() {
        viewModelScope.launch {
            secret.value =
                try {
                    SecretView.Shown(server.renewSecret())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    SecretView.Unreadable
                }
        }
    }

    override fun onCleared() {
        secret.value = SecretView.Hidden
    }
}

@Composable
fun NightscoutApiPage(
    viewModel: NightscoutApiViewModel,
    now: () -> Instant,
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Android 17: incoming connections from the home network need local network access (Nearby devices)
    val ask =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            // on either way: when Android said no, the page shows it and the way to allow it (or turn this off)
            viewModel.setLan(true)
        }
    LifecycleResumeEffect(viewModel) {
        viewModel.recheck()
        onPauseOrDispose {}
    }
    ui?.let {
        NightscoutApiContent(
            it,
            now(),
            NsApiActions(
                viewModel::setOn,
                viewModel::showSecret,
                viewModel::hideSecret,
                viewModel::renewSecret,
                setLan = { on ->
                    // the permission exists from Android 17; older Android grants local network access already
                    if (on &&
                        Build.VERSION.SDK_INT >= LanWatcher.LOCAL_NETWORK_SDK &&
                        !viewModel.localNetworkAllowed()
                    ) {
                        ask.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                    } else {
                        viewModel.setLan(on)
                    }
                },
                allowHere = viewModel::allowHere,
                openAndroidSettings = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
            ),
            modifier,
        )
    }
}

/**
 * Settings › Share with apps › *Nightscout API on this phone*: the switch, where it listens and when it was last read,
 * the API secret (hidden until asked; the screen can't be captured while it shows), *New secret*, and how apps
 * connect.
 */
@Composable
fun NightscoutApiContent(
    ui: NsApiUi,
    now: Instant,
    actions: NsApiActions,
    modifier: Modifier = Modifier,
) = Column(modifier) {
    IntroText(stringResource(R.string.in_nsapi_intro))
    SettingsGroup {
        SwitchRow(
            stringResource(R.string.in_nsapi_switch),
            ui.on,
            actions.setOn,
            summary = nsApiStatusText(ui.state, now),
        )
    }
    if (ui.on) LanGroup(ui, actions)
    if (ui.on || ui.secret !is SecretView.Hidden) {
        SettingsGroup(title = stringResource(R.string.in_nsapi_secret_group)) {
            when (val s = ui.secret) {
                SecretView.Hidden -> {
                    NavigationRow(
                        stringResource(R.string.in_nsapi_show_secret),
                        actions.showSecret,
                        summary = stringResource(R.string.in_nsapi_show_secret_summary),
                    )
                }

                is SecretView.Shown -> {
                    SecureWindow()
                    val context = LocalContext.current
                    val label = stringResource(R.string.in_nsapi_secret_label)
                    NavigationRow(
                        s.secret,
                        { copySensitive(context, label, s.secret) },
                        summary = stringResource(R.string.in_nsapi_copy),
                    )
                    InfoRow(
                        stringResource(R.string.in_nsapi_xdrip_url),
                        summary = "http://${s.secret}@127.0.0.1:${NightscoutApi.PORT}",
                    )
                    InfoRow(
                        stringResource(R.string.in_nsapi_token_url),
                        summary = "http://127.0.0.1:${NightscoutApi.PORT}/?token=${s.secret}",
                    )
                    (ui.lan as? LanState.Listening)?.let { l ->
                        InfoRow(
                            stringResource(R.string.in_nsapi_lan_url),
                            summary = "http://${s.secret}@${l.address}:${NightscoutApi.PORT}",
                        )
                    }
                    NavigationRow(stringResource(R.string.in_nsapi_hide_secret), actions.hideSecret)
                }

                SecretView.Unreadable -> {
                    InfoRow(stringResource(R.string.in_nsapi_secret_unreadable))
                }
            }
            DangerRow(
                stringResource(R.string.in_nsapi_new_secret),
                stringResource(R.string.in_nsapi_new_secret_title),
                stringResource(R.string.in_nsapi_new_secret_text),
                stringResource(R.string.in_nsapi_new_secret_action),
                actions.renewSecret,
                summary = stringResource(R.string.in_nsapi_new_secret_summary),
            )
        }
    }
    FooterText(stringResource(R.string.in_nsapi_footer))
}

/** The status line on the switch and on Share with apps. */
@Composable
fun nsApiStatusText(
    state: NsApiState,
    now: Instant,
): String =
    when (state) {
        NsApiState.Off -> {
            stringResource(R.string.in_nsapi_off)
        }

        NsApiState.PortBusy -> {
            stringResource(R.string.in_nsapi_busy)
        }

        NsApiState.SecretUnreadable -> {
            stringResource(R.string.in_nsapi_unreadable)
        }

        is NsApiState.Listening -> {
            listOfNotNull(
                stringResource(R.string.in_nsapi_on),
                state.lan?.let { stringResource(R.string.in_nsapi_also_lan, it) },
                state.lastAsked?.let { stringResource(R.string.in_apps_xdrip_last_asked, ago(it, now)) }
                    ?: stringResource(R.string.in_apps_xdrip_not_asked),
            ).joinToString(" · ")
        }
    }

/**
 * *On your home Wi-Fi*: the switch (turning it on asks first, with the warning), where it listens or why it doesn't,
 * and each blocked state's way on: *Allow on this Wi-Fi* for another network, Android settings when local network
 * access was refused. The switch is always the way off (never Android settings: Nearby devices also runs the sensor).
 */
@Composable
private fun LanGroup(
    ui: NsApiUi,
    actions: NsApiActions,
) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    SettingsGroup(title = stringResource(R.string.in_nsapi_lan_group)) {
        SwitchRow(
            stringResource(R.string.in_nsapi_lan_switch),
            ui.lanOn,
            { on -> if (on) confirm = true else actions.setLan(false) },
            summary =
                when {
                    ui.lanOn -> lanStatusText(ui.lan)
                    ui.lanRefused -> stringResource(R.string.in_nsapi_lan_refused)
                    else -> stringResource(R.string.in_nsapi_lan_off)
                },
        )
        if (ui.lanOn) {
            when (ui.lan) {
                LanState.OtherWifi -> {
                    NavigationRow(
                        stringResource(R.string.in_nsapi_lan_allow_here),
                        actions.allowHere,
                        summary =
                            stringResource(
                                if (ui.lanRefused) R.string.in_nsapi_lan_refused else R.string.in_nsapi_lan_allow_here_summary,
                            ),
                    )
                }

                LanState.NoPermission -> {
                    NavigationRow(
                        stringResource(R.string.in_nsapi_lan_settings),
                        actions.openAndroidSettings,
                        summary = stringResource(R.string.in_nsapi_lan_settings_summary),
                    )
                }

                else -> {}
            }
        }
        InfoRow(stringResource(R.string.in_nsapi_lan_warning_title), summary = stringResource(R.string.in_nsapi_lan_warning))
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.in_nsapi_lan_confirm_title)) },
            text = { Text(stringResource(R.string.in_nsapi_lan_warning)) },
            confirmButton = {
                TextButton({
                    confirm = false
                    actions.setLan(true)
                }) { Text(stringResource(R.string.in_nsapi_lan_confirm_action)) }
            },
            dismissButton = { TextButton({ confirm = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

@Composable
private fun lanStatusText(lan: LanState): String =
    when (lan) {
        LanState.Off -> stringResource(R.string.in_nsapi_lan_starting)
        LanState.NoWifi -> stringResource(R.string.in_nsapi_lan_no_wifi)
        LanState.NotPrivate -> stringResource(R.string.in_nsapi_lan_not_private)
        LanState.OpenWifi -> stringResource(R.string.in_nsapi_lan_open)
        LanState.OtherWifi -> stringResource(R.string.in_nsapi_lan_other)
        LanState.NoPermission -> stringResource(R.string.in_nsapi_lan_no_permission)
        LanState.PortBusy -> stringResource(R.string.in_nsapi_busy)
        LanState.ApiStopped -> stringResource(R.string.in_nsapi_lan_api_stopped)
        is LanState.Listening -> stringResource(R.string.in_nsapi_lan_on, lan.address)
    }

/** No screenshots or recents thumbnail while the secret is on screen. */
@Composable
private fun SecureWindow() {
    val activity = LocalContext.current.activity() ?: return
    DisposableEffect(activity) {
        val flag = WindowManager.LayoutParams.FLAG_SECURE
        // only clear what this added: another screen may have set it too
        val added = activity.window.attributes.flags and flag == 0
        if (added) activity.window.addFlags(flag)
        onDispose { if (added) activity.window.clearFlags(flag) }
    }
}

private tailrec fun Context.activity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activity()
        else -> null
    }

/** Copies [text] marked sensitive: the clipboard preview hides it. */
private fun copySensitive(
    context: Context,
    label: String,
    text: String,
) {
    val clip = ClipData.newPlainText(label, text)
    clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}
