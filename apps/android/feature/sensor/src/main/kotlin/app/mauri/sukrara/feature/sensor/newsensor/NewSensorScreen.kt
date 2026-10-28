// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sensor.newsensor

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.net.Uri
import android.nfc.NfcAdapter
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mauri.sukrara.core.model.Durations
import app.mauri.sukrara.designsystem.haptics.rememberHaptics
import app.mauri.sukrara.designsystem.motion.Reveal
import app.mauri.sukrara.designsystem.motion.Swap
import app.mauri.sukrara.feature.sensor.R
import app.mauri.sukrara.graph.CardWords
import app.mauri.sukrara.sensor.SensorProfile
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

/**
 * The new-sensor flow (start a new Libre 3 or take over a running one), the route the home screen, setup and
 * Settings › Sensor open. [onSignIn] opens the LibreView sign-in; [onDone] leaves (back, Cancel, Close, Done).
 * Gates are re-checked every time the screen resumes (the person may come back from settings, the sign-in or the
 * other app); NFC reader mode is on only while a tap is accepted.
 */
@Composable
fun NewSensorRoute(
    viewModel: NewSensorViewModel,
    onSignIn: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val step = state.step
    val context = LocalContext.current
    val haptics = rememberHaptics()

    LifecycleResumeEffect(viewModel) {
        viewModel.start()
        onPauseOrDispose {}
    }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                NewSensorEffect.TagFound -> haptics.tick()

                // keep still: it's being read
                NewSensorEffect.TapSucceeded, NewSensorEffect.Ready -> haptics.confirm()

                NewSensorEffect.TapFailed -> haptics.reject()
            }
        }
    }
    // a phone held to the arm mustn't lock mid-tap or mid-pairing; the warm-up and the end let it sleep
    val view = LocalView.current
    DisposableEffect(view, step.keepsScreenOn) {
        view.keepScreenOn = step.keepsScreenOn
        onDispose { view.keepScreenOn = false }
    }
    val antenna = remember(context) { antennaSpot(NfcAdapter.getDefaultAdapter(context)) }
    val currentOnDone by rememberUpdatedState(onDone)
    LaunchedEffect(step) {
        if (step == NewSensorStep.Cancelled || step == NewSensorStep.LeftRunning) currentOnDone()
    }
    // on for the whole flow, also while a tag is being read (turning it off mid-read restarts polling and can cut the
    // exchange, and the system's own tag dispatch would take the tag); the view model takes a tag only when it may
    NfcReaderMode(enabled = !step.isFinal, onTag = viewModel::onTag)
    BackHandler {
        if (step.isFinal) onDone() else viewModel.onCancel() // ignored while the tap is being read
    }

    val afterPermission: (Boolean) -> Unit = {
        // a paired sensor waiting for Nearby devices connects again; before the tap the gates move on
        if (viewModel.state.value.step is NewSensorStep.BleFailed) viewModel.onRetry() else viewModel.start()
    }
    // Nearby devices: connecting and (for the families found by a scan) scanning; one system dialog for both
    val bluetoothPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            afterPermission(result.values.all { it })
        }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), afterPermission)

    val now by produceState(viewModel.now(), step) {
        value = viewModel.now()
        // the countdowns and the "Taking longer?" note
        while (step is NewSensorStep.WarmingUp || step is NewSensorStep.ConfirmUntested || step is NewSensorStep.ConnectingBle) {
            value = viewModel.now()
            delay(1_000)
        }
    }

    NewSensorContent(
        step,
        now,
        modifier,
        chosen = state.profile,
        antenna = antenna,
        onChoose = viewModel::onChoose,
        onIdChange = viewModel::onIdChange,
    ) { action ->
        when (action) {
            NewSensorAction.ContinueAnyway -> {
                viewModel.onContinue()
            }

            NewSensorAction.Pair -> {
                viewModel.onPair()
            }

            NewSensorAction.TryAgain -> {
                viewModel.onRetry()
            }

            NewSensorAction.SignIn -> {
                onSignIn()
            }

            NewSensorAction.NfcSettings -> {
                context.open(Intent(Settings.ACTION_NFC_SETTINGS))
            }

            NewSensorAction.BluetoothSettings -> {
                context.open(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }

            NewSensorAction.AllowBluetooth -> {
                bluetoothPermission.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN))
            }

            NewSensorAction.AllowNotifications -> {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }

            NewSensorAction.AppSettings -> {
                context.open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            }

            NewSensorAction.Continue, NewSensorAction.NotNow -> {
                viewModel.onContinue()
            }

            NewSensorAction.UseJuggluco -> {
                viewModel.onUseJuggluco()
            }

            NewSensorAction.Cancel, NewSensorAction.Close -> {
                viewModel.onCancel()
            }

            NewSensorAction.Done -> {
                if (step.isFinal) onDone() else viewModel.onCancel()
            }
        }
    }
}

/**
 * The step bar over one page per step, cross-faded (snaps with reduced motion). Stateless but for the tips (open after
 * [TIPS_AFTER] on the tap page, or [tipsOpen] for screenshots): screenshots render it directly. [chosen] is the family
 * picked on the first step (the bar says "Set up" instead of "Scan" for a family found by a scan), [antenna] where
 * this phone's NFC antenna is.
 */
@Composable
fun NewSensorContent(
    step: NewSensorStep,
    now: Instant,
    modifier: Modifier = Modifier,
    chosen: SensorProfile? = null,
    antenna: Offset = DefaultAntenna,
    tipsOpen: Boolean = false,
    onChoose: (SensorProfile) -> Unit = {},
    onIdChange: (String) -> Unit = {},
    onAction: (NewSensorAction) -> Unit = {},
) {
    Column(modifier.fillMaxSize()) {
        step.stage?.let { stage ->
            StepBar(
                stage,
                complete = step == NewSensorStep.Ready,
                setsUp = step.setsUp(chosen),
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp),
            )
        }
        // pages change only on real transitions (the countdown, the choice and the typed id move inside a page)
        Swap(Paged(step), Modifier.weight(1f).fillMaxWidth()) { shown ->
            // the page on its way out keeps its own step; the current one follows every change
            StepPage(if (shown == Paged(step)) step else shown.step, now, antenna, tipsOpen, onChoose, onIdChange, onAction)
        }
    }
}

/** A step, equal to another when they are the same page ([pageKey]). */
private class Paged(val step: NewSensorStep) {
    override fun equals(other: Any?) = other is Paged && other.step.pageKey() == step.pageKey()

    override fun hashCode() = step.pageKey().hashCode()
}

/** What makes a new page (and its cross-fade): the step's kind, not what moves inside it. */
private fun NewSensorStep.pageKey(): Any =
    when (this) {
        is NewSensorStep.ChooseSensor -> NewSensorStep.ChooseSensor::class

        is NewSensorStep.SensorSetupStep -> NewSensorStep.SensorSetupStep::class to profile

        // a new text is a new page; the link moving the ticked pairing steps is not
        is NewSensorStep.ConnectingBle -> Triple(NewSensorStep.ConnectingBle::class, profile, page().text)

        else -> this
    }

@Composable
private fun StepPage(
    step: NewSensorStep,
    now: Instant,
    antenna: Offset,
    tipsOpen: Boolean,
    onChoose: (SensorProfile) -> Unit,
    onIdChange: (String) -> Unit,
    onAction: (NewSensorAction) -> Unit = {},
) {
    val page = step.page()
    val arg = page.arg.orEmpty()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Hero(step, now, antenna)
        Text(
            stringResource(page.title, arg),
            // TalkBack reads each new step's title as it appears
            Modifier.semantics {
                heading()
                liveRegion = LiveRegionMode.Polite
            },
            style = MaterialTheme.typography.headlineSmall,
            color = if (page.warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        // the reading has its own drawing; connecting shows its spinner on the pairing step under way
        if (page.busy && step != NewSensorStep.ReadingNfc && step !is NewSensorStep.ConnectingBle) {
            CircularProgressIndicator(Modifier.size(40.dp))
        }
        Text(
            stringResource(page.text, arg),
            style = MaterialTheme.typography.bodyLarge,
            color = if (page.warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (step is NewSensorStep.ChooseSensor) SensorChoices(step.selected, onChoose)
        if (step is NewSensorStep.SensorSetupStep) step.typedId?.let { IdField(step.id, it.length, step.canPair, onIdChange) }
        if (step is NewSensorStep.ConnectingBle) {
            PairSteps(pairProgress(step.link))
            Reveal(step.isSlow(now)) {
                Note(
                    stringResource(R.string.newsensor_slow_title),
                    stringResource(if (step.profile.needsScan) R.string.newsensor_slow_scanned_text else R.string.newsensor_slow_text),
                )
            }
        }
        if (step == NewSensorStep.WaitingForTap) Tips(tipsOpen)
        page.note?.let {
            Text(
                stringResource(it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        val waitLeft = (step as? NewSensorStep.ConfirmUntested)?.let { secondsLeft(it, now) } ?: 0
        Actions(
            page.actions,
            enabled = { action ->
                when (action) {
                    NewSensorAction.ContinueAnyway -> waitLeft == 0
                    NewSensorAction.Pair -> (step as? NewSensorStep.SensorSetupStep)?.canPair == true
                    else -> true
                }
            },
            label = { action ->
                if (action == NewSensorAction.ContinueAnyway && waitLeft > 0) {
                    stringResource(R.string.newsensor_action_continue_anyway_wait, waitLeft)
                } else {
                    stringResource(action.label)
                }
            },
            onAction = onAction,
        )
    }
}

/** The drawing over a step's title: the phone on the sensor, the read, the warm-up ring, the ready check. */
@Composable
private fun Hero(step: NewSensorStep, now: Instant, antenna: Offset) {
    when {
        step == NewSensorStep.WaitingForTap ||
            (step is NewSensorStep.NfcFailed && step.cause == NfcFailure.MovedTooSoon) -> ScanArt(antenna)

        step == NewSensorStep.ReadingNfc -> ReadingArt()

        step is NewSensorStep.WarmingUp -> WarmUpArt(warmUpDone(step.readyAt, now)) { Countdown(step.readyAt, now) }

        step == NewSensorStep.Ready -> ReadyArt()
    }
}

/** "Nothing happens?" on the tap page: the tips, open by themselves after [TIPS_AFTER] without a tap. */
@Composable
private fun Tips(open: Boolean) {
    var shown by remember { mutableStateOf(open) }
    LaunchedEffect(Unit) {
        delay(TIPS_AFTER.toMillis())
        shown = true
    }
    Reveal(shown) {
        Note(stringResource(R.string.newsensor_tips_title), stringResource(R.string.newsensor_tips_text))
    }
    if (!shown) TextButton({ shown = true }) { Text(stringResource(R.string.newsensor_tips_button)) }
}

/** Whole seconds until "Continue anyway" is enabled (rounded up; 0 = enabled). */
internal fun secondsLeft(step: NewSensorStep.ConfirmUntested, now: Instant): Int {
    val ms = step.since.plus(NewSensorMachine.UNTESTED_WAIT).toEpochMilli() - now.toEpochMilli()
    return ((ms + 999) / 1000).toInt().coerceAtLeast(0)
}

/** The families, one radio row each (the whole row is the target); the untested ones tagged. */
@Composable
private fun SensorChoices(
    selected: SensorProfile,
    onChoose: (SensorProfile) -> Unit,
) {
    val untested = stringResource(R.string.newsensor_untested_tag_description)
    Column(Modifier.fillMaxWidth().selectableGroup()) {
        SensorProfile.pairable.forEach { profile ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .selectable(selected = profile == selected, role = Role.RadioButton, onClick = { onChoose(profile) })
                    .semantics(mergeDescendants = true) {
                        if (!profile.verified) stateDescription = untested
                    }.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RadioButton(selected = profile == selected, onClick = null)
                Text(profile.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                if (!profile.verified) {
                    Text(
                        stringResource(R.string.newsensor_untested_tag),
                        Modifier
                            .clearAndSetSemantics {}
                            .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.small)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

/** The typed id: letters and digits, upper case, [length] long; the hint says the form until it is valid. */
@Composable
private fun IdField(
    id: String,
    length: Int,
    valid: Boolean,
    onIdChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = id,
        onValueChange = onIdChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.newsensor_setup_id_label)) },
        supportingText = { if (!valid) Text(stringResource(R.string.newsensor_setup_id_invalid, length)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
    )
}

/**
 * "Ready in 52 min" big (the one duration formatter); TalkBack reads "Ready in 52 minutes" (the platform's wide unit
 * names). Minutes round up, so it never says 0 while warming up.
 */
@Composable
private fun Countdown(readyAt: Instant, now: Instant) {
    val seconds = Duration.between(now, readyAt).seconds
    if (seconds <= 0) {
        Text(stringResource(R.string.newsensor_warming_almost), style = MaterialTheme.typography.headlineMedium)
        return
    }
    val minutes = (seconds + 59) / 60
    val resources = LocalContext.current.resources
    val shown = Durations.text(minutes, CardWords.of(resources).durations)
    val spoken =
        MeasureFormat
            .getInstance(LocalConfiguration.current.locales[0], MeasureFormat.FormatWidth.WIDE)
            .format(Measure(minutes, MeasureUnit.MINUTE))
    val description = stringResource(R.string.newsensor_ready_in, spoken)
    Text(
        stringResource(R.string.newsensor_ready_in, shown),
        Modifier.clearAndSetSemantics { contentDescription = description },
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
    )
}

/** The main action filled, the others outlined, the exit last and quiet. Full width so big fonts wrap, not clip. */
@Composable
private fun Actions(
    actions: List<NewSensorAction>,
    enabled: (NewSensorAction) -> Boolean = { true },
    label: @Composable (NewSensorAction) -> String = { stringResource(it.label) },
    onAction: (NewSensorAction) -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.forEachIndexed { i, action ->
            val text: @Composable () -> Unit = { Text(label(action), textAlign = TextAlign.Center) }
            val wide = Modifier.fillMaxWidth()
            val on = enabled(action)
            when {
                action.isExit -> TextButton({ onAction(action) }, wide) { text() }
                i == 0 -> Button({ onAction(action) }, wide, enabled = on) { text() }
                else -> OutlinedButton({ onAction(action) }, wide, enabled = on) { text() }
            }
        }
    }
}

private fun Context.open(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // a phone without that settings page: the step stays, with its other ways out
    }
}
