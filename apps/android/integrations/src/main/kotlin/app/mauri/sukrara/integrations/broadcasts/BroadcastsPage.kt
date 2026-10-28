// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.integrations.broadcasts

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.mauri.sukrara.designsystem.rows.FooterText
import app.mauri.sukrara.designsystem.rows.InfoRow
import app.mauri.sukrara.designsystem.rows.NavigationRow
import app.mauri.sukrara.designsystem.rows.SettingsGroup
import app.mauri.sukrara.designsystem.rows.SwitchRow
import app.mauri.sukrara.integrations.R
import app.mauri.sukrara.integrations.ui.IntroText
import app.mauri.sukrara.integrations.ui.ago
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

/** Why an allowed app gets nothing. */
enum class AppProblem { NotInstalled, SignerChanged }

/** One app on the page: a receiver found on the phone, or an allowed one that is gone or changed. */
data class BcApp(
    val pkg: String,
    val label: String,
    val actions: Set<String>,
    val allowed: Boolean,
    val problem: AppProblem? = null,
)

data class BcUi(
    val wearer: Boolean,
    /** Null while the phone's apps are being looked up. */
    val apps: List<BcApp>?,
    val status: BcStatus,
)

class BcActions(
    val setAllowed: (String, Boolean) -> Unit = { _, _ -> },
    val remove: (String) -> Unit = {},
    val refresh: () -> Unit = {},
)

/** The installed apps, read off the main thread: receivers and the allowed apps' current state. */
private data class Installed(
    val receivers: Map<String, Set<String>>,
    val labels: Map<String, String>,
    val signerOk: Map<String, Boolean>,
)

class BroadcastsViewModel(
    private val broadcaster: Broadcaster,
    private val apps: AppDirectory,
    wearer: Flow<Boolean>,
    private val io: CoroutineDispatcher,
) : ViewModel() {
    private val installed = MutableStateFlow<Installed?>(null)

    val ui: StateFlow<BcUi?> =
        combine(wearer, broadcaster.settingsFlow, installed, broadcaster.status) { w, s, inst, st ->
            BcUi(w, inst?.let { rows(s, it) }, st)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Looks the apps up again (on every return to the page: one may have been installed or updated meanwhile). */
    fun refresh() {
        viewModelScope.launch {
            val allowed = broadcaster.settingsFlow.first().apps
            installed.value =
                runCatching {
                    withContext(io) {
                        val r = apps.receivers()
                        val pkgs = r.keys + allowed.map { it.pkg }
                        Installed(
                            r,
                            pkgs.associateWith { apps.label(it) ?: it },
                            allowed.associate { it.pkg to apps.signedBy(it.pkg, it.signer) },
                        )
                    }
                }.getOrElse { Installed(emptyMap(), emptyMap(), emptyMap()) }
        }
    }

    fun setAllowed(
        pkg: String,
        on: Boolean,
    ) {
        viewModelScope.launch {
            // a store failure leaves the list as it was (shown on refresh), never a crash
            runCatching { if (on) broadcaster.allow(pkg) else broadcaster.remove(pkg) }
            refresh()
        }
    }

    fun remove(pkg: String) = setAllowed(pkg, false)

    private fun rows(
        s: BcSettings,
        inst: Installed,
    ): List<BcApp> {
        val allowed = s.apps.associateBy { it.pkg }
        val found =
            inst.receivers.map { (pkg, actions) ->
                val a = allowed[pkg]
                val changed = a != null && inst.signerOk[pkg] == false
                BcApp(pkg, inst.labels[pkg] ?: pkg, actions, a != null && !changed, if (changed) AppProblem.SignerChanged else null)
            }
        val gone =
            s.apps.filter { it.pkg !in inst.receivers }.map {
                BcApp(it.pkg, inst.labels[it.pkg] ?: it.pkg, emptySet(), true, AppProblem.NotInstalled)
            }
        return (found + gone).sortedBy { it.label.lowercase() }
    }
}

@Composable
fun BroadcastsPage(
    viewModel: BroadcastsViewModel,
    now: () -> Instant,
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    ui?.let { BroadcastsContent(it, now(), BcActions(viewModel::setAllowed, viewModel::remove, viewModel::refresh), modifier) }
}

/**
 * Settings › Share with apps › *Send glucose to apps*: every app on the phone that listens for glucose broadcasts, a
 * switch each (none on by default), what was last sent or why nothing is, and allowed apps that were removed or
 * changed with their way out.
 */
@Composable
fun BroadcastsContent(
    ui: BcUi,
    now: Instant,
    actions: BcActions,
    modifier: Modifier = Modifier,
) = Column(modifier) {
    IntroText(stringResource(R.string.in_bc_intro))
    if (!ui.wearer) {
        SettingsGroup {
            InfoRow(stringResource(R.string.in_bc_follower))
            // allowed while this phone wore the sensor: still removable here
            for (a in ui.apps.orEmpty().filter { it.allowed || it.problem != null }) {
                NavigationRow(stringResource(R.string.in_bc_remove, a.label), { actions.remove(a.pkg) })
            }
        }
        FooterText(stringResource(R.string.in_bc_footer))
        return@Column
    }
    var reallow by rememberSaveable { mutableStateOf<String?>(null) }
    val anyAllowed = ui.apps.orEmpty().any { it.allowed || it.problem != null }
    SettingsGroup { InfoRow(stringResource(R.string.in_bc_status), summary = broadcastStatusText(ui.status, anyAllowed, now)) }
    val list = ui.apps
    if (list != null) {
        SettingsGroup(title = stringResource(R.string.in_bc_apps_group)) {
            if (list.isEmpty()) InfoRow(stringResource(R.string.in_bc_none), summary = stringResource(R.string.in_bc_none_summary))
            for (a in list) {
                when (a.problem) {
                    null -> {
                        SwitchRow(
                            a.label,
                            a.allowed,
                            { actions.setAllowed(a.pkg, it) },
                            summary = a.pkg + " · " + formats(a.actions),
                        )
                    }

                    AppProblem.SignerChanged -> {
                        NavigationRow(
                            a.label,
                            { reallow = a.pkg },
                            summary = stringResource(R.string.in_bc_changed, a.pkg),
                        )
                        NavigationRow(stringResource(R.string.in_bc_remove, a.label), { actions.remove(a.pkg) })
                    }

                    AppProblem.NotInstalled -> {
                        NavigationRow(
                            a.label,
                            { actions.remove(a.pkg) },
                            summary = stringResource(R.string.in_bc_gone),
                        )
                    }
                }
            }
        }
    }
    ui.apps.orEmpty().firstOrNull { it.pkg == reallow }?.let { a ->
        // a different signature can mean another app under the same name: a closed loop may dose from what it gets
        AlertDialog(
            onDismissRequest = { reallow = null },
            title = { Text(stringResource(R.string.in_bc_reallow_title, a.label)) },
            text = { Text(stringResource(R.string.in_bc_reallow_text, a.label, a.pkg)) },
            confirmButton = {
                TextButton({
                    reallow = null
                    actions.setAllowed(a.pkg, true)
                }) { Text(stringResource(R.string.in_bc_reallow_action)) }
            },
            dismissButton = { TextButton({ reallow = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    if (forwardsToLoop(ui.apps.orEmpty())) {
        SettingsGroup { InfoRow(stringResource(R.string.in_bc_loop_twice_title), summary = stringResource(R.string.in_bc_loop_twice)) }
    }
    FooterText(stringResource(R.string.in_bc_footer))
}

private val LOOPS = setOf("info.nightscout.androidaps", "app.aaps")
private val FORWARDERS = setOf("com.eveningoutpost.dexdrip", "de.michelinside.glucodatahandler")

/** AndroidAPS and an app that can pass readings on to it (xDrip+, GlucoDataHandler) are both on. */
internal fun forwardsToLoop(apps: List<BcApp>): Boolean {
    val on = apps.filter { it.allowed && it.problem == null }.map { it.pkg }.toSet()
    return on.any { it in LOOPS } && on.any { it in FORWARDERS }
}

/** The format an app gets (one per app: [BroadcastPlan.PREFERENCE]), by the name people know it by. */
private fun formats(actions: Set<String>): String =
    when (BroadcastPlan.PREFERENCE.firstOrNull { it in actions }) {
        BroadcastPayloads.XDRIP -> "xDrip+"
        BroadcastPayloads.LIBRE -> "LibreLink"
        BroadcastPayloads.GLUCODATA -> "Juggluco"
        else -> "LibreLink"
    }

/** The status line on the page and on Share with apps. */
@Composable
fun broadcastStatusText(
    status: BcStatus,
    anyAllowed: Boolean,
    now: Instant,
): String {
    if (!anyAllowed) return stringResource(R.string.in_bc_off)
    val hold =
        when (status.hold) {
            Hold.Stale -> stringResource(R.string.in_bc_hold_stale)
            Hold.Future -> stringResource(R.string.in_bc_hold_future)
            Hold.OutOfRange -> stringResource(R.string.in_bc_hold_lohi)
            Hold.NoApp -> stringResource(R.string.in_bc_hold_no_app)
            Hold.Follower -> stringResource(R.string.in_bc_follower)
            Hold.TooSoon, Hold.Repeated, null -> null
        }
    val sent = status.lastSent?.let { stringResource(R.string.in_bc_last_sent, ago(it, now)) }
    return listOfNotNull(sent ?: stringResource(R.string.in_bc_waiting), hold).joinToString(" · ")
}
