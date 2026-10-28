// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sensor.newsensor

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.icu.text.MeasureFormat
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mauri.sukrara.core.model.Durations
import app.mauri.sukrara.designsystem.haptics.rememberHaptics
import app.mauri.sukrara.designsystem.motion.Swap
import app.mauri.sukrara.feature.sensor.R
import app.mauri.sukrara.graph.CardWords
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
        viewModel.effects.collect { haptics.confirm() } // the tap worked / the sensor is ready
    }
    LaunchedEffect(step) {
        if (step == NewSensorStep.Cancelled || step == NewSensorStep.LeftRunning) onDone()
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
    val bluetoothPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), afterPermission)
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), afterPermission)

    val now by produceState(viewModel.now(), step) {
        while (step is NewSensorStep.WarmingUp) {
            value = viewModel.now()
            delay(1_000)
        }
    }

    NewSensorContent(step, now, modifier) { action ->
        when (action) {
            NewSensorAction.TryAgain -> viewModel.onRetry()
            NewSensorAction.SignIn -> onSignIn()
            NewSensorAction.NfcSettings -> context.open(Intent(Settings.ACTION_NFC_SETTINGS))
            NewSensorAction.BluetoothSettings -> context.open(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            NewSensorAction.AllowBluetooth -> bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            NewSensorAction.AllowNotifications -> notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            NewSensorAction.AppSettings ->
                context.open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            NewSensorAction.Continue, NewSensorAction.NotNow -> viewModel.onContinue()
            NewSensorAction.UseJuggluco -> viewModel.onUseJuggluco()
            NewSensorAction.Cancel, NewSensorAction.Close -> viewModel.onCancel()
            NewSensorAction.Done -> if (step.isFinal) onDone() else viewModel.onCancel()
        }
    }
}

/** One page per step, cross-faded (snaps with reduced motion). Stateless: screenshots render it directly. */
@Composable
fun NewSensorContent(
    step: NewSensorStep,
    now: Instant,
    modifier: Modifier = Modifier,
    onAction: (NewSensorAction) -> Unit,
) {
    // steps change only on real transitions (the countdown moves with [now], not the step)
    Swap(step, modifier.fillMaxSize()) { shown -> StepPage(shown, now, onAction) }
}

@Composable
private fun StepPage(
    step: NewSensorStep,
    now: Instant,
    onAction: (NewSensorAction) -> Unit,
) {
    val page = step.page()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(page.title),
            // TalkBack reads each new step's title as it appears
            Modifier.semantics {
                heading()
                liveRegion = LiveRegionMode.Polite
            },
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        if (page.busy) CircularProgressIndicator(Modifier.size(40.dp))
        if (step is NewSensorStep.WarmingUp) Countdown(step.readyAt, now)
        Text(
            stringResource(page.text),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        page.note?.let {
            Text(
                stringResource(it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Actions(page.actions, onAction)
    }
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
    val spoken = MeasureFormat.getInstance(LocalConfiguration.current.locales[0], MeasureFormat.FormatWidth.WIDE)
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
private fun Actions(actions: List<NewSensorAction>, onAction: (NewSensorAction) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.forEachIndexed { i, action ->
            val label: @Composable () -> Unit = { Text(stringResource(action.label), textAlign = TextAlign.Center) }
            val wide = Modifier.fillMaxWidth()
            when {
                action.isExit -> TextButton({ onAction(action) }, wide) { label() }
                i == 0 -> Button({ onAction(action) }, wide) { label() }
                else -> OutlinedButton({ onAction(action) }, wide) { label() }
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
