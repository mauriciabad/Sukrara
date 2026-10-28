// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.integrations.localserver

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.PersistableBundle
import android.view.WindowManager
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
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
)

class NsApiActions(
    val setOn: (Boolean) -> Unit = {},
    val showSecret: () -> Unit = {},
    val hideSecret: () -> Unit = {},
    val renewSecret: () -> Unit = {},
)

class NightscoutApiViewModel(
    private val server: NightscoutApiServer,
) : ViewModel() {
    private val secret = MutableStateFlow<SecretView>(SecretView.Hidden)
    val ui: StateFlow<NsApiUi?> =
        combine(server.on, server.state, secret) { on, st, s -> NsApiUi(on, st, s) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setOn(on: Boolean) {
        viewModelScope.launch { server.setOn(on) }
    }

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
    ui?.let {
        NightscoutApiContent(
            it,
            now(),
            NsApiActions(viewModel::setOn, viewModel::showSecret, viewModel::hideSecret, viewModel::renewSecret),
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
            stringResource(R.string.in_nsapi_on) + " · " +
                (
                    state.lastAsked?.let { stringResource(R.string.in_apps_xdrip_last_asked, ago(it, now)) }
                        ?: stringResource(R.string.in_apps_xdrip_not_asked)
                )
        }
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
