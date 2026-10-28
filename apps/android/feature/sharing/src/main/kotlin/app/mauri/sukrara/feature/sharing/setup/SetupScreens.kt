// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing.setup

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mauri.sukrara.alerts.AlertKind
import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.core.model.AlertRequirement
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.designsystem.haptics.rememberHaptics
import app.mauri.sukrara.designsystem.motion.LocalReducedMotion
import app.mauri.sukrara.designsystem.motion.Motion
import app.mauri.sukrara.designsystem.rows.nameRes
import app.mauri.sukrara.designsystem.rows.whyRes
import app.mauri.sukrara.feature.sharing.R
import app.mauri.sukrara.feature.sharing.restore.IdentityStep
import androidx.compose.animation.AnimatedContent

/**
 * The first-run setup (brief 24, binding order): welcome → role → permissions → sensor →
 * alert thresholds → sharing identity → home. Each step says "Step N of M" to TalkBack, has Back (except the first)
 * and Skip / Later where skipping is safe; skipped steps go to the home "Finish setup" card.
 * [onOpenSensorSetup] opens the new-sensor flow; [thresholdRows] are Settings' threshold rows (the alert step
 * shows the same rows as Settings › Review alert thresholds); [onDone] goes home.
 */
@Composable
fun SetupScreen(
    viewModel: SetupViewModel,
    onOpenSensorSetup: () -> Unit,
    thresholdRows: ThresholdRows,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose { }
    }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val u = ui ?: return
    val haptics = rememberHaptics()
    // opened from home after setup was finished: show the first Finish-setup row's step; finishing setup goes home
    val openedFinished = remember { u.step == null }
    if (u.step == null) {
        LaunchedEffect(Unit) {
            if (!openedFinished || !viewModel.reopenFirstUnfinished()) {
                haptics.confirm()
                onDone()
            }
        }
        return
    }
    BackHandler { viewModel.back(onDone) }
    SetupContent(u, viewModel.actions(onOpenSensorSetup, thresholdRows, onDone), modifier)
}

/**
 * The alert thresholds' rows for [AlertSettings] (one row per reviewed kind, each opening the number dialog) and the
 * edit callback. The app passes Settings' `AlertThresholdRows`: features don't depend on each other.
 */
typealias ThresholdRows = @Composable (settings: AlertSettings, onThreshold: (AlertKind, Double) -> Unit) -> Unit

/** What the step screens can do; a plain holder so screenshot tests render every step without a view model. */
data class SetupActions(
    val back: () -> Unit = {},
    val continueWelcome: () -> Unit = {},
    val chooseRole: (Role) -> Unit = {},
    val completePermissions: () -> Unit = {},
    val fix: (AlertRequirement) -> Unit = {},
    val openSensorSetup: () -> Unit = {},
    val completeSensor: () -> Unit = {},
    val thresholdRows: ThresholdRows = { _, _ -> },
    val editThreshold: (AlertKind, Double) -> Unit = { _, _ -> },
    val confirmAlerts: () -> Unit = {},
    val startFresh: () -> Unit = {},
    val completeIdentity: () -> Unit = {},
    val retryConflict: () -> Unit = {},
    val restore: (String, String?) -> Unit = { _, _ -> },
    val clearRestoreError: () -> Unit = {},
    val skip: (SetupStep) -> Unit = {},
)

@Composable
private fun SetupViewModel.actions(
    onOpenSensorSetup: () -> Unit,
    thresholdRows: ThresholdRows,
    onDone: () -> Unit,
): SetupActions {
    val context = LocalContext.current
    return remember(this) {
        SetupActions(
            back = { back(onDone) },
            continueWelcome = ::continueWelcome,
            chooseRole = ::chooseRole,
            completePermissions = ::completePermissions,
            fix = { r -> readinessFix(r) },
            openSensorSetup = onOpenSensorSetup,
            completeSensor = ::completeSensor,
            thresholdRows = thresholdRows,
            editThreshold = ::editThreshold,
            confirmAlerts = ::confirmAlerts,
            startFresh = ::startFresh,
            completeIdentity = ::completeIdentity,
            retryConflict = ::retryConflict,
            restore = ::restore,
            clearRestoreError = ::clearRestoreError,
            skip = ::skip,
        )
    }
}

/** One setup screen for [u] (also what the screenshot tests render). */
@Composable
fun SetupContent(
    u: SetupUi,
    actions: SetupActions,
    modifier: Modifier = Modifier,
) {
    val step = u.step ?: return
    val reduced = LocalReducedMotion.current
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (u.canBack || u.single) {
                IconButton(onClick = actions.back) {
                    Icon(painterResource(R.drawable.sharing_ic_back), contentDescription = stringResource(R.string.sh_setup_back))
                }
            } else {
                Spacer(Modifier.size(48.dp))
            }
            Text(
                stringResource(R.string.sh_setup_step_of, u.index, u.count),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        AnimatedContent(step, transitionSpec = { Motion.fadeThrough(reduced) }, label = "setup-step") { s ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (s) {
                    SetupStep.Welcome -> WelcomeStep(actions)
                    SetupStep.Role -> RoleStep(u, actions)
                    SetupStep.Permissions -> PermissionsStep(u, actions)
                    SetupStep.Sensor -> SensorStep(u, actions)
                    SetupStep.Alerts -> AlertConfirmStep(u, actions)
                    SetupStep.Identity -> IdentityStep(u, actions)
                }
                Spacer(Modifier.size(24.dp))
            }
        }
    }
    }
}

/** A step's title (a heading for TalkBack) and its plain-words explanation. */
@Composable
internal fun StepHeader(
    title: String,
    body: String?,
) {
    Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
    if (body != null) Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The step's main action, then quieter ones (Skip / Later last). */
@Composable
internal fun StepButtons(
    primary: String?,
    onPrimary: () -> Unit,
    enabled: Boolean = true,
    secondary: List<Pair<String, () -> Unit>> = emptyList(),
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (primary != null) Button(onClick = onPrimary, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(primary) }
        for ((label, action) in secondary) {
            TextButton(onClick = action, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(label) }
        }
    }
}

/** A confirmation that says what a choice means before it happens (P10). */
@Composable
internal fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = rememberHaptics()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = {
                haptics.confirm()
                onDismiss()
                onConfirm()
            }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.sh_setup_cancel)) } },
    )
}

@Composable
private fun ColumnScope.WelcomeStep(actions: SetupActions) {
    StepHeader(stringResource(R.string.sh_setup_welcome_title), stringResource(R.string.sh_setup_welcome_body))
    Text(stringResource(R.string.sh_setup_welcome_steps), style = MaterialTheme.typography.bodyLarge)
    StepButtons(stringResource(R.string.sh_setup_start), actions.continueWelcome)
}

@Composable
private fun ColumnScope.RoleStep(
    u: SetupUi,
    actions: SetupActions,
) {
    var selected by rememberSaveable { mutableStateOf<Role?>(null) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    StepHeader(stringResource(R.string.sh_setup_role_title), stringResource(R.string.sh_setup_role_body))
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RoleOption(stringResource(R.string.sh_setup_role_wearer), stringResource(R.string.sh_setup_role_wearer_means), selected == Role.Wearer) { selected = Role.Wearer }
        RoleOption(stringResource(R.string.sh_setup_role_follower), stringResource(R.string.sh_setup_role_follower_means), selected == Role.Follower) {
            selected = Role.Follower
        }
    }
    if (selected == Role.Follower && !u.facts.followerAvailable) {
        NoteCard(stringResource(R.string.sh_setup_role_follower_later))
    }
    StepButtons(
        stringResource(R.string.sh_setup_continue),
        onPrimary = { confirming = true },
        enabled = selected == Role.Wearer || (selected == Role.Follower && u.facts.followerAvailable),
    )
    val role = selected
    if (confirming && role != null) {
        val name = stringResource(if (role == Role.Wearer) R.string.sh_setup_role_wearer else R.string.sh_setup_role_follower)
        ConfirmDialog(
            title = stringResource(R.string.sh_setup_role_confirm_title, name),
            text = stringResource(R.string.sh_setup_role_confirm_text),
            confirm = stringResource(R.string.sh_setup_role_confirm),
            onConfirm = { actions.chooseRole(role) },
            onDismiss = { confirming = false },
        )
    }
}

@Composable
private fun RoleOption(
    title: String,
    means: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().selectable(selected, role = SemanticsRole.RadioButton, onClick = onClick),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null)
            Column(Modifier.padding(start = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(means, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * One step of a numbered checklist: its number, or a tick once [done] (null = the app can't tell, the number stays).
 * TalkBack reads the number (or "Done"), the text and [content] (a field, a checkbox) as one item.
 */
@Composable
internal fun NumberedItem(
    number: Int,
    text: String,
    done: Boolean? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(28.dp).background(
                if (done == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                CircleShape,
            ),
            contentAlignment = Alignment.Center,
        ) {
            if (done == true) {
                Icon(
                    painterResource(R.drawable.sharing_ic_check),
                    contentDescription = stringResource(R.string.sh_setup_check_ok),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Text("$number", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp, top = 3.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            content()
        }
    }
}

/** A calm explanatory box (not an error). */
@Composable
internal fun NoteCard(
    text: String,
    error: Boolean = false,
) {
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
            ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun ColumnScope.PermissionsStep(
    u: SetupUi,
    actions: SetupActions,
) {
    StepHeader(stringResource(R.string.sh_setup_permissions_title), stringResource(R.string.sh_setup_permissions_body))
    // runtime permissions are asked in context, the first time; after a "don't ask again" the settings page opens
    val asked = remember { mutableStateOf(setOf<AlertRequirement>()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val items = AlertRequirement.entries.filter { it != AlertRequirement.Bluetooth || u.facts.role != Role.Follower }
    for (r in items) {
        val ok = r !in u.readiness.missing
        ChecklistRow(r, ok) {
            val permission = runtimePermission(r)
            if (permission != null && r !in asked.value) {
                asked.value += r
                launcher.launch(permission)
            } else {
                actions.fix(r)
            }
        }
    }
    val allOk = items.none { it in u.readiness.missing }
    StepButtons(
        stringResource(if (allOk) R.string.sh_setup_continue else R.string.sh_setup_permissions_continue_anyway),
        actions.completePermissions,
        secondary = if (allOk) emptyList() else listOf(stringResource(R.string.sh_setup_later) to { actions.skip(SetupStep.Permissions) }),
    )
}

@Composable
private fun ChecklistRow(
    r: AlertRequirement,
    ok: Boolean,
    onFix: () -> Unit,
) {
    val title = stringResource(r.nameRes())
    val why = stringResource(r.whyRes())
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(if (ok) R.drawable.sharing_ic_check else R.drawable.sharing_ic_todo),
                contentDescription = stringResource(if (ok) R.string.sh_setup_check_ok else R.string.sh_setup_check_missing),
                tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!ok) OutlinedButton(onClick = onFix, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.sh_setup_allow)) }
        }
    }
}

/** The runtime permission the system dialog can grant for [r] (asked in context the first time), or null. */
private fun runtimePermission(r: AlertRequirement): String? =
    when (r) {
        AlertRequirement.Notifications -> android.Manifest.permission.POST_NOTIFICATIONS
        AlertRequirement.Bluetooth -> android.Manifest.permission.BLUETOOTH_CONNECT
        else -> null
    }

@Composable
private fun ColumnScope.SensorStep(
    u: SetupUi,
    actions: SetupActions,
) {
    StepHeader(stringResource(R.string.sh_setup_sensor_title), stringResource(R.string.sh_setup_sensor_body))
    if (u.facts.ownSensorLive) {
        NoteCard(stringResource(R.string.sh_setup_sensor_live))
        StepButtons(stringResource(R.string.sh_setup_continue), actions.completeSensor)
    } else {
        NoteCard(stringResource(R.string.sh_setup_sensor_not_monitoring))
        StepButtons(
            stringResource(R.string.sh_setup_sensor_open),
            actions.openSensorSetup,
            secondary = listOf(stringResource(R.string.sh_setup_later) to { actions.skip(SetupStep.Sensor) }),
        )
    }
}

/** Opens [intent] if anything can handle it (a settings page some phones lack). */
internal fun android.content.Context.startSafely(intent: Intent) {
    runCatching { startActivity(intent) }
}
