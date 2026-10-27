// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.home

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mauri.sukrara.core.model.AlertRequirement
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.SensorState
import app.mauri.sukrara.core.model.Treatment
import app.mauri.sukrara.core.model.TreatmentKind
import app.mauri.sukrara.designsystem.banner.Banner
import app.mauri.sukrara.designsystem.banner.BannerSeverity
import app.mauri.sukrara.designsystem.banner.BannerSlot
import app.mauri.sukrara.designsystem.haptics.rememberHaptics
import app.mauri.sukrara.designsystem.motion.Reveal
import app.mauri.sukrara.designsystem.motion.Swap
import app.mauri.sukrara.designsystem.state.StateKind
import app.mauri.sukrara.designsystem.state.StateScreen
import app.mauri.sukrara.feature.home.banners.BannerKind
import app.mauri.sukrara.graph.CardWords
import app.mauri.sukrara.graph.Clock
import java.time.Duration
import java.time.Instant
import app.mauri.sukrara.core.designsystem.R as DsR

/** The home screen: [onAction] routes what home asks for (setup, sensor, settings, add treatment, …) in `:app`. */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onAction: (HomeAction) -> Unit,
    onOpenDataProblems: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HomeContent(
        state = state,
        callbacks =
            HomeCallbacks(
                onAction = onAction,
                onDismiss = viewModel::dismiss,
                onFix = viewModel::fix,
                onUnmute = viewModel::unmute,
                onDelete = viewModel::delete,
                onUndo = viewModel::undoDelete,
                onUndoGone = viewModel::undoShown,
                onOpenDataProblems = onOpenDataProblems,
            ),
        modifier = modifier,
    )
}

/** Everything the screen can ask for (one object so the stateless [HomeContent] stays testable). */
data class HomeCallbacks(
    val onAction: (HomeAction) -> Unit = {},
    val onDismiss: (BannerKind) -> Unit = {},
    val onFix: (AlertRequirement) -> Unit = {},
    val onUnmute: () -> Unit = {},
    val onDelete: (Treatment) -> Unit = {},
    val onUndo: () -> Unit = {},
    val onUndoGone: () -> Unit = {},
    val onOpenDataProblems: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    state: HomeUiState,
    callbacks: HomeCallbacks,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val snackbar = remember { SnackbarHostState() }
    val deletedText = stringResource(R.string.home_treatment_deleted)
    val undoText = stringResource(R.string.home_undo)
    LaunchedEffect(state.undo) {
        if (state.undo == null) return@LaunchedEffect
        val result = snackbar.showSnackbar(deletedText, undoText, withDismissAction = true, duration = SnackbarDuration.Short)
        if (result == SnackbarResult.ActionPerformed) {
            haptics.confirm()
            callbacks.onUndo()
        } else {
            callbacks.onUndoGone()
        }
    }
    val follower = state.glucose.role == Role.Follower
    val scroll = rememberScrollState()
    val shownBanner = banner(state, callbacks, onDismissed = { haptics.confirm() })
    // a safety banner sits right under the card (seen without scrolling); a tip goes below the glucose block
    val safetyBanner = shownBanner?.takeIf { it.severity == BannerSeverity.Safety }
    val infoBanner = shownBanner?.takeIf { it.severity == BannerSeverity.Info }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { if (follower) FollowerHeader(state) else Text(stringResource(R.string.home_title)) },
            )
        },
        floatingActionButton = {
            if (state.glucose.role != null) {
                // icon-only while a safety banner shows or the page is scrolled, so it never covers what matters
                val expanded = safetyBanner == null && scroll.value == 0
                val label = stringResource(R.string.home_add_treatment)
                ExtendedFloatingActionButton(
                    onClick = { callbacks.onAction(HomeAction.AddTreatment) },
                    expanded = expanded,
                    icon = { Icon(painterResource(R.drawable.home_ic_add), contentDescription = if (expanded) null else label) },
                    text = { Text(label) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // glucose first: the card (or, before any reading, why there is none) is always at the top
            GlucoseSection(state, callbacks, safetyBanner)
            BannerSlot(infoBanner)
            MuteStrip(state.mutedUntil, state.now) {
                haptics.confirm()
                callbacks.onUnmute()
            }
            Treatments(state.treatments) {
                haptics.confirm()
                callbacks.onDelete(it)
            }
            // room for the floating button over the last row
            Spacer(Modifier.height(88.dp))
        }
    }
}

/**
 * Who is followed and how the link is, from the same decision as the card's reason: fresh = when last heard ("just
 * now", "2 minutes ago"); silent = since when nothing came, in the error colour; removed = no longer following.
 */
@Composable
private fun FollowerHeader(state: HomeUiState) {
    val link = state.glucose.link
    val name = link?.wearerName
    Column {
        Text(
            name?.let { stringResource(R.string.home_following, it) } ?: stringResource(R.string.home_following_unnamed),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val (line, trouble) =
            when (val reason = state.reason) {
                NoDataReason.LinkRevoked -> stringResource(R.string.home_link_revoked) to true
                is NoDataReason.WearerSilent -> {
                    val since = reason.since
                    when {
                        since == null -> stringResource(R.string.home_link_never)
                        name == null -> stringResource(R.string.home_link_silent_unnamed, sinceTime(since, state.now))
                        else -> stringResource(R.string.home_link_silent, name, sinceTime(since, state.now))
                    } to true
                }
                else ->
                    (link?.lastHeardAt?.let { stringResource(R.string.home_link_heard, relative(it, state.now)) } ?: stringResource(R.string.home_link_never)) to
                        false
            }
        Text(
            line,
            style = MaterialTheme.typography.bodySmall,
            color = if (trouble) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The card, always (its chart keeps the last trajectory and the renderer draws the stale / warm-up / ended face), then
 * a safety banner, then the compact reason with its action. Only before there is anything to draw (not set up, no
 * sensor yet, no core) a full-width reason takes the card's place; that swap cross-fades (reduced motion: snaps).
 */
@Composable
private fun GlucoseSection(
    state: HomeUiState,
    callbacks: HomeCallbacks,
    safetyBanner: Banner?,
) {
    val resources = LocalContext.current.resources
    val unmuteLabel = stringResource(R.string.home_a11y_unmute)
    Swap(state.reason?.takeIf { it.fullPage }) { fullPage ->
        if (fullPage != null) {
            ReasonPage(fullPage, state.now) { callbacks.onAction(it) }
        } else {
            Column {
                PanelCard(
                    glucose = state.glucose,
                    treatments = state.treatments,
                    now = state.now,
                    description = cardDescription(state, resources),
                    modifier = Modifier.padding(horizontal = 16.dp),
                    actions =
                        if (state.mutedUntil != null) {
                            listOf(CustomAccessibilityAction(unmuteLabel) { callbacks.onUnmute().let { true } })
                        } else {
                            emptyList()
                        },
                )
                BannerSlot(safetyBanner)
                // Monitoring stopped already has its safety banner (banner 0): never say it twice
                val compact = state.reason?.takeUnless { it == NoDataReason.MonitoringStopped && state.banner == BannerKind.MonitoringStopped }
                ReasonRow(compact, state.now) { callbacks.onAction(it) }
                SensorLine(state)
            }
        }
    }
}

@Composable
private fun SensorLine(state: HomeUiState) {
    val ends = (state.glucose.sensor as? SensorState.Active)?.endsAt ?: return
    Text(
        stringResource(R.string.home_sensor_ends, relative(ends, state.now)),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
    )
}

/** Before there is anything to draw: the reason in the card's place, announced politely. */
@Composable
private fun ReasonPage(
    reason: NoDataReason,
    now: Instant,
    onAction: (HomeAction) -> Unit,
) {
    val text = reasonText(reason, now)
    StateScreen(
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        kind = StateKind.NoData,
        title = text.first,
        reason = text.second,
        action = reason.action?.let { actionLabel(reason) },
        onAction = { reason.action?.let(onAction) },
    )
}

/**
 * The reason under the card: title, one explanation, the action. No icon: the card above already draws
 * `sensors_off`, the warm-up ring or the flag. Appears / leaves with the shared reveal motion; announced politely.
 */
@Composable
private fun ReasonRow(
    reason: NoDataReason?,
    now: Instant,
    onAction: (HomeAction) -> Unit,
) {
    // keep the last reason while it animates out
    var shown by remember { mutableStateOf(reason) }
    if (reason != null) shown = reason
    Reveal(reason != null) {
        val r = shown ?: return@Reveal
        val text = reasonText(r, now)
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Column(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                Text(text.first, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                Text(text.second, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            r.action?.let { action ->
                FilledTonalButton(onClick = { onAction(action) }, modifier = Modifier.align(Alignment.End).padding(top = 4.dp)) {
                    Text(actionLabel(r))
                }
            }
        }
    }
}

/** Title and explanation for [reason]. */
@Composable
private fun reasonText(
    reason: NoDataReason,
    now: Instant,
): Pair<String, String> =
    when (reason) {
        NoDataReason.SetupIncomplete -> stringResource(R.string.reason_setup_title) to stringResource(R.string.reason_setup_body)
        is NoDataReason.NoSensorYet ->
            if (reason.follower) {
                stringResource(R.string.reason_no_wearer_title) to stringResource(R.string.reason_no_wearer_body)
            } else {
                stringResource(R.string.reason_no_sensor_title) to stringResource(R.string.reason_no_sensor_body)
            }
        NoDataReason.CoreMissing -> stringResource(R.string.reason_core_missing_title) to stringResource(R.string.reason_core_missing_body)
        NoDataReason.BluetoothOff -> stringResource(R.string.reason_bluetooth_title) to stringResource(R.string.reason_bluetooth_body)
        NoDataReason.MonitoringStopped -> stringResource(R.string.reason_stopped_title) to stringResource(R.string.reason_stopped_body)
        NoDataReason.SensorEnded -> stringResource(R.string.reason_ended_title) to stringResource(R.string.reason_ended_body)
        is NoDataReason.WarmingUp ->
            stringResource(R.string.reason_warmup_title) to stringResource(R.string.reason_warmup_body, clockTime(reason.readyAt))
        NoDataReason.LinkRevoked -> stringResource(R.string.reason_revoked_title) to stringResource(R.string.reason_revoked_body)
        is NoDataReason.WearerSilent ->
            stringResource(R.string.reason_wearer_silent_title) to
                (reason.since?.let { stringResource(R.string.reason_wearer_silent_body, relative(it, now)) } ?: stringResource(R.string.reason_wearer_silent_never))
        is NoDataReason.NoReadings ->
            stringResource(R.string.reason_no_readings_title) to
                (reason.since?.let { stringResource(R.string.reason_no_readings_body, relative(it, now)) } ?: stringResource(R.string.reason_no_readings_never))
    }

@Composable
private fun actionLabel(reason: NoDataReason): String =
    stringResource(
        when (reason) {
            NoDataReason.SetupIncomplete -> R.string.reason_setup_action
            is NoDataReason.NoSensorYet -> if (reason.follower) R.string.reason_no_wearer_action else R.string.reason_no_sensor_action
            NoDataReason.BluetoothOff -> R.string.reason_bluetooth_action
            NoDataReason.MonitoringStopped -> R.string.reason_stopped_action
            NoDataReason.SensorEnded -> R.string.reason_ended_action
            NoDataReason.LinkRevoked -> R.string.reason_revoked_action
            else -> R.string.reason_no_readings_action
        },
    )

/** The one banner for [state] (or null), with its texts and exits. */
@Composable
private fun banner(
    state: HomeUiState,
    callbacks: HomeCallbacks,
    onDismissed: () -> Unit,
): Banner? {
    val kind = state.banner ?: return null
    val dismiss: (() -> Unit)? =
        if (kind.safety) {
            null
        } else {
            {
                onDismissed()
                callbacks.onDismiss(kind)
            }
        }
    fun open(action: HomeAction): () -> Unit = { callbacks.onAction(action) }
    val severity = if (kind.safety) BannerSeverity.Safety else BannerSeverity.Info
    return when (kind) {
        BannerKind.MonitoringStopped ->
            Banner(
                kind.name, R.drawable.home_ic_warning, stringResource(R.string.reason_stopped_title), stringResource(R.string.reason_stopped_body),
                stringResource(R.string.reason_stopped_action), open(HomeAction.ResumeMonitoring), null, severity,
            )
        BannerKind.AlertsUnreliable -> {
            val problem = state.firstProblem ?: return null
            Banner(
                kind.name, R.drawable.home_ic_warning, stringResource(R.string.banner_alerts_title), requirementText(problem),
                stringResource(R.string.banner_alerts_action), { callbacks.onFix(problem) }, null, severity,
            )
        }
        BannerKind.TurnOffShowcase ->
            Banner(
                kind.name, R.drawable.home_ic_warning, stringResource(R.string.banner_showcase_title), stringResource(R.string.banner_showcase_text),
                stringResource(R.string.banner_showcase_action), open(HomeAction.OpenShowcaseInfo), null, severity,
            )
        BannerKind.DataProblem ->
            Banner(
                kind.name, DsR.drawable.ds_ic_error, stringResource(R.string.banner_data_title),
                pluralStringResource(R.plurals.banner_data_text, state.dataProblems.size, state.dataProblems.size),
                stringResource(R.string.banner_data_action), callbacks.onOpenDataProblems, null, severity,
            )
        BannerKind.FinishSetup ->
            Banner(
                kind.name, DsR.drawable.ds_ic_info, stringResource(R.string.banner_setup_title),
                stringResource(R.string.banner_setup_text, state.nextSetupStep.orEmpty()),
                stringResource(R.string.banner_setup_action), open(HomeAction.OpenSetup), dismiss, severity,
            )
        BannerKind.NotBackedUp ->
            Banner(
                kind.name, DsR.drawable.ds_ic_info, stringResource(R.string.banner_backup_title), stringResource(R.string.banner_backup_text),
                stringResource(R.string.banner_backup_action), open(HomeAction.OpenBackup), dismiss, severity,
            )
        BannerKind.AddWidget ->
            Banner(
                kind.name, DsR.drawable.ds_ic_info, stringResource(R.string.banner_widget_title), stringResource(R.string.banner_widget_text),
                stringResource(R.string.banner_widget_action), open(HomeAction.AddWidget), dismiss, severity,
            )
        BannerKind.ShareWithClinic ->
            Banner(
                kind.name, DsR.drawable.ds_ic_info, stringResource(R.string.banner_clinic_title), stringResource(R.string.banner_clinic_text),
                stringResource(R.string.banner_clinic_action), open(HomeAction.OpenClinicUpload), dismiss, severity,
            )
    }
}

@Composable
private fun requirementText(requirement: AlertRequirement): String =
    stringResource(
        when (requirement) {
            AlertRequirement.Notifications -> R.string.req_notifications
            AlertRequirement.FullScreenIntent -> R.string.req_full_screen
            AlertRequirement.ExactAlarm -> R.string.req_exact_alarm
            AlertRequirement.BatteryExemption -> R.string.req_battery
            AlertRequirement.AlertChannel -> R.string.req_channel
            AlertRequirement.Bluetooth -> R.string.req_bluetooth
        },
    )

/** Mute alerts is on: the countdown and the way out (Unmute), in the neutral secondary colours, never red. */
@Composable
private fun MuteStrip(
    until: Instant?,
    now: Instant,
    onUnmute: () -> Unit,
) {
    Reveal(until != null) {
        val end = until ?: return@Reveal
        Surface(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.home_ic_notifications_off), contentDescription = null, modifier = Modifier.size(20.dp))
                Text(
                    if (end > now.plus(FOREVER)) {
                        stringResource(R.string.home_muted_forever)
                    } else {
                        stringResource(R.string.home_muted_for, left(end, now))
                    },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                Button(onClick = onUnmute) { Text(stringResource(R.string.home_unmute)) }
            }
        }
    }
}

@Composable
private fun Treatments(
    treatments: List<Treatment>,
    onDelete: (Treatment) -> Unit,
) {
    Reveal(treatments.isNotEmpty()) {
        Text(
            stringResource(R.string.home_treatments),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp).semantics { heading() },
        )
        treatments.forEach { t ->
            val label = treatmentLabel(t)
            val time = clockTime(t.at)
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(time, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                IconButton(onClick = { onDelete(t) }) {
                    Icon(painterResource(R.drawable.home_ic_delete), contentDescription = stringResource(R.string.home_treatment_delete, label, time))
                }
            }
        }
    }
}

@Composable
private fun treatmentLabel(t: Treatment): String {
    val amount = java.text.NumberFormat.getNumberInstance(LocalContext.current.resources.configuration.locales[0]).format(t.amount)
    val kind =
        when (t.kind) {
            TreatmentKind.RapidInsulin -> stringResource(R.string.home_treatment_rapid, amount)
            TreatmentKind.LongInsulin -> stringResource(R.string.home_treatment_long, amount)
            TreatmentKind.Carbs -> stringResource(R.string.home_treatment_carbs, amount)
            TreatmentKind.Note -> stringResource(R.string.home_treatment_note)
        }
    return if (t.note.isBlank()) kind else "$kind · ${t.note}"
}

/** "just now" / "5 minutes ago" / "in 3 days", in the phone's language (never "0 minutes ago"). */
@Composable
private fun relative(
    t: Instant,
    now: Instant,
): String {
    val past = Duration.between(t, now)
    if (!past.isNegative && past < Duration.ofMinutes(1)) return stringResource(R.string.home_a11y_just_now)
    return DateUtils.getRelativeTimeSpanString(t.toEpochMilli(), now.toEpochMilli(), DateUtils.MINUTE_IN_MILLIS).toString()
}

/** "1:24 AM" within the last 12 hours, else the date and time ("Oct 26, 11:40 PM"): when a silence began. */
@Composable
private fun sinceTime(
    t: Instant,
    now: Instant,
): String {
    if (Duration.between(t, now) < Duration.ofHours(12)) return clockTime(t)
    val flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
    return DateUtils.formatDateTime(LocalContext.current, t.toEpochMilli(), flags)
}

/** Time left until [end] in the card's words ("1h 12m left"), rounded up to the minute. */
@Composable
private fun left(
    end: Instant,
    now: Instant,
): String {
    val resources = LocalContext.current.resources
    val words = remember(resources) { CardWords.of(resources) }
    return words.timeLeft(((end.epochSecond - now.epochSecond + 59) / 60).coerceAtLeast(1))
}

/** The time of day in the phone's 12/24-hour setting (the renderer's one clock formatter). */
private fun clockTime(t: Instant): String = Clock.time(t.epochSecond)

/** A mute ending further away than this is the alert engine's "until you turn it off". */
private val FOREVER = Duration.ofDays(365)
