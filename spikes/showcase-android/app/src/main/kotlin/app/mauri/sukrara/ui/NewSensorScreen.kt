// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.mauri.sukrara.App
import app.mauri.sukrara.R
import app.mauri.sukrara.core.IdKind
import app.mauri.sukrara.core.Pairing
import app.mauri.sukrara.core.PairingInput
import app.mauri.sukrara.core.Proto
import app.mauri.sukrara.core.SensorLife
import app.mauri.sukrara.core.SensorProfile
import app.mauri.sukrara.core.SensorProfiles
import app.mauri.sukrara.direct.DriverPrefs
import app.mauri.sukrara.direct.Libre3Nfc
import app.mauri.sukrara.direct.SensorPairing
import app.mauri.sukrara.direct.ReceiverId
import app.mauri.sukrara.features.Features
import app.mauri.sukrara.graph.Freshness
import app.mauri.sukrara.graph.GraphSettings
import app.mauri.sukrara.graph.RoundedFont
import app.mauri.sukrara.graph.systemPalette
import app.mauri.sukrara.ui.NewSensorFlow.PairStep
import app.mauri.sukrara.ui.NewSensorFlow.Stage
import app.mauri.sukrara.ui.NewSensorFlow.Step
import app.mauri.sukrara.ui.NewSensorFlow.Trouble
import app.mauri.sukrara.wearer.Live
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A sensor tap that opened Sukrara (the NfcLaunch alias): the New sensor screen reads it at once, no second tap. */
object NewSensorLaunch {
    val tag = MutableStateFlow<Tag?>(null)
}

/** No tap after this long (ms): the tips open by themselves. */
private const val TIPS_AFTER_MS = 10_000L

/**
 * New sensor: one screen for the whole start of a sensor, opened from home ("Pair new sensor"), the
 * "Replace sensor" pill and notification actions, Settings → Sensor, or by tapping a sensor. It starts with the brand
 * step (the Libre 3 preselected; NFC reader mode is already on, so tapping a Libre 3 goes straight in), then waits for
 * the tap in NFC reader mode (only while it's in front) or shows an untested family's setup, then follows the
 * Bluetooth pairing and the warm-up until the first reading ([NewSensorFlow] holds the steps). Patterns: Material 3 progress indicators (determinate when the wait is
 * known: the warm-up; indeterminate otherwise), Abbott's own "hold the back of the phone near the sensor until it
 * vibrates", and NfcAntennaInfo (API 34) for where this phone's antenna is.
 */
@Composable
fun NewSensorScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as Activity
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val f by Features.state.collectAsState()
    val st by Live.status.collectAsState()
    val sensor by SensorLife.current.collectAsState()
    val preview by NewSensorFlow.preview.collectAsState()
    val pendingTag by NewSensorLaunch.tag.collectAsState()
    val nfc = remember { NfcAdapter.getDefaultAdapter(ctx) }
    var nfcOn by remember { mutableStateOf(nfc?.isEnabled == true) }
    var btOn by remember { mutableStateOf(bluetoothOn(ctx)) }
    var scanStage by remember { mutableStateOf<Stage>(Stage.Waiting) }
    var tap by remember { mutableStateOf<NewSensorFlow.Tapped?>(null) }
    var scanAgain by remember { mutableStateOf(false) }
    var showTips by remember { mutableStateOf(false) }
    var hasAccount by remember { mutableStateOf(ReceiverId.libreView(ctx) != null) }
    // the brand step: null = still choosing; [selected] is the radio choice (the Libre 3 until the user picks another)
    var chosen by remember { mutableStateOf<SensorProfile?>(null) }
    var selected by remember { mutableStateOf(NewSensorFlow.DEFAULT_PROFILE) }
    var typedId by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf<SensorProfile?>(null) }
    val now by produceState(Proto.nowS(), tap) { while (true) { value = Proto.nowS(); delay(1_000) } }

    val stage: Stage = preview ?: tap?.let { NewSensorFlow.afterTap(it, st.source, st.last?.t, sensor, now, st.sourceError) } ?: when {
        chosen == null -> Stage.Choose()
        chosen?.pairing != Pairing.NfcTap -> NewSensorFlow.afterChoice(chosen!!)
        nfc == null -> Stage.Problem(Trouble.NO_NFC)
        !nfcOn -> Stage.Problem(Trouble.NFC_OFF)
        else -> scanStage
    }

    // Sukrara's own LibreView setup already knows the account: learn its receiver ID up front, so a new sensor starts
    // and one started by LibreLink or Juggluco moves over with a single tap.
    LaunchedEffect(Unit) {
        if (hasAccount) return@LaunchedEffect
        val lv = ctx.getSharedPreferences("libreview", Context.MODE_PRIVATE)
        val email = lv.getString("email", null)
        val password = lv.getString("password", null)
        if (!email.isNullOrBlank() && !password.isNullOrEmpty())
            withContext(Dispatchers.IO) { runCatching { ReceiverId.fetchLibreView(ctx, email, password) } }
        hasAccount = ReceiverId.libreView(ctx) != null
    }

    fun result(r: Libre3Nfc.Result) {
        scanAgain = false
        when (r) {
            is Libre3Nfc.Result.Paired -> {
                chosen = SensorProfiles.LIBRE_3
                r.pairing.save(ctx)
                if (Features.value.source != "direct") Features.update { it.copy(source = "direct") }
                else App.owner(ctx).restartSource()
                tap = NewSensorFlow.Tapped(r.pairing.serial, r.activated, Proto.nowS())
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            }
            is Libre3Nfc.Result.WrongAccount -> scanStage = Stage.SignIn(r.serial, newSensor = false)
            is Libre3Nfc.Result.NeedsAccount -> scanStage = Stage.SignIn(r.serial, newSensor = true)
            is Libre3Nfc.Result.Libre2 -> scanStage = Stage.Problem(Trouble.LIBRE2)
            is Libre3Nfc.Result.Ended -> scanStage = Stage.Problem(Trouble.ENDED, r.serial)
            is Libre3Nfc.Result.Unsupported -> scanStage = Stage.Problem(Trouble.UNSUPPORTED, detail = r.why)
            is Libre3Nfc.Result.Failed -> {
                scanStage = Stage.Problem(Trouble.FAILED, detail = r.why)
                view.performHapticFeedback(HapticFeedbackConstants.REJECT)
            }
        }
    }

    fun scan(t: Tag) {
        chosen = SensorProfiles.LIBRE_3   // a tap on the brand step is the Libre 3's answer
        scanStage = Stage.Reading
        scope.launch {
            val r = withContext(Dispatchers.IO) { Libre3Nfc.scan(t, ReceiverId.takeoverCandidates(ctx), ReceiverId.forActivation(ctx)) }
            result(r)
        }
    }

    // The tap that opened Sukrara: read it now (the phone is still on the sensor).
    val idx = indexing
    LaunchedEffect(pendingTag) {
        val t = pendingTag ?: return@LaunchedEffect
        NewSensorLaunch.tag.value = null
        if (tap == null && preview == null && !idx) scan(t)
    }

    // Reader mode only while this screen is in front and waiting: a tap here goes to Sukrara, not to Juggluco or
    // LibreLink. Off during the sign-in and once a sensor answered.
    val listening = !indexing && preview == null && nfc != null && tap == null &&
        (chosen == null || chosen?.pairing == Pairing.NfcTap) && (scanStage is Stage.Waiting || scanStage is Stage.Problem)
    LifecycleResumeEffect(nfc, listening) {
        nfcOn = nfc?.isEnabled == true
        btOn = bluetoothOn(ctx)
        if (listening) nfc.enableReaderMode(activity, { t -> activity.runOnUiThread { scan(t) } },
            NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null)
        onPauseOrDispose { if (listening) nfc.disableReaderMode(activity) }
    }

    // The screen stays on until the flow is done: a phone held to the arm shouldn't lock mid-scan or mid-pairing.
    val done = stage is Stage.Ready || stage is Stage.WarmingUp
    DisposableEffect(view, done) {
        view.keepScreenOn = !done
        onDispose { view.keepScreenOn = false }
    }

    DisposableEffect(Unit) { onDispose { NewSensorFlow.preview.value = null } }

    // Back from a family's setup (or the NFC wait) goes to the brand step, not out of the screen.
    BackHandler(enabled = preview == null && tap == null && chosen != null) { chosen = null; scanStage = Stage.Waiting }

    /** An untested family's "Pair" (after its confirmation): save it as the sensor and start the direct source on it. */
    fun pairBrand(profile: SensorProfile) {
        val id = if (profile.pairing is Pairing.TypedId) PairingInput.normalize(typedId) else ""
        val p = SensorPairing.forBrand(profile, id)
        ctx.getSharedPreferences("direct", Context.MODE_PRIVATE).edit().remove(DriverPrefs.endedKey(p.serial)).apply()
        p.save(ctx)
        SensorLife.update(ctx, null)   // the old sensor's dates are gone; the new one's come when it says
        if (Features.value.source != "direct") Features.update { it.copy(source = "direct") }
        else App.owner(ctx).restartSource()
        tap = NewSensorFlow.Tapped(p.serial, activated = false, at = Proto.nowS(), profile = profile, label = id.ifEmpty { profile.name })
    }

    val setupProfile = (stage as? Stage.Setup)?.let { SensorProfiles.byId(it.profileId) }
    val previewConfirm = (stage as? Stage.Choose)?.confirm?.let { SensorProfiles.byId(it) }
    // the untested warning, from Continue on the brand step: Cancel stays there, accept goes on to the family's setup
    (confirming ?: previewConfirm)?.let { p ->
        UntestedConfirmDialog(p, onCancel = { confirming = null; if (previewConfirm != null) NewSensorFlow.preview.value = null }) {
            confirming = null
            if (previewConfirm != null) NewSensorFlow.preview.value = null
            chosen = p; typedId = ""; scanStage = Stage.Waiting
        }
    }

    LaunchedEffect(stage is Stage.Waiting) {
        if (stage is Stage.Waiting && !showTips) { delay(TIPS_AFTER_MS); showTips = true }
    }

    val nav = LocalNav.current
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(painterResource(R.drawable.ic_sym_close), contentDescription = stringResource(R.string.newsensor_close))
            }
            Text(stringResource(R.string.newsensor_title), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 4.dp).semantics { heading() })
        }
        StepBar(stage.step, complete = stage is Stage.Ready,
            setup = (setupProfile ?: tap?.profile ?: chosen ?: selected).pairing != Pairing.NfcTap)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(32.dp))
            if (stage is Stage.Choose) ChooseSensor(previewConfirm ?: selected) { selected = it }
            else Swap(heroKey(stage), Modifier.height(260.dp).fillMaxWidth()) { _ ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Hero(stage, nfc, now) }
            }
            Spacer(Modifier.height(24.dp))
            if (stage !is Stage.Choose) Swap(textKey(stage, scanAgain)) { _ ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title(stage, scanAgain, setupProfile), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center,
                        modifier = Modifier.semantics { heading() })
                    body(stage, now, setupProfile)?.let {
                        Text(it, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                (stage as? Stage.Pairing)?.let { PairSteps(it.pair) }
                Reveal((stage as? Stage.Pairing)?.slow == true) {
                    val vendor = (tap?.profile?.pairing as? Pairing.VendorApp)?.app
                    Note(R.drawable.ic_sym_lightbulb, stringResource(R.string.newsensor_slow_title), when {
                        tap?.profile?.pairing == Pairing.NfcTap || tap == null -> stringResource(R.string.newsensor_slow_text)
                        vendor != null -> stringResource(R.string.newsensor_slow_vendor_text, vendor)
                        else -> stringResource(R.string.newsensor_slow_brand_text)
                    })
                }
                (setupProfile?.pairing as? Pairing.TypedId)?.let { kind ->
                    OutlinedTextField(typedId, { typedId = it.take(kind.length + 4) },
                        label = { Text(stringResource(if (kind.kind == IdKind.TRANSMITTER_ID) R.string.newsensor_transmitter_label else R.string.newsensor_serial_label)) },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii,
                            capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Characters),
                        textStyle = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth())
                }
                (stage as? Stage.SignIn)?.let { s ->
                    LibreViewSignIn(onCancel = { scanStage = Stage.Waiting }) {
                        hasAccount = true; scanAgain = true; scanStage = Stage.Waiting
                    }
                }
                if (stage is Stage.Waiting) {
                    Reveal(!btOn) {
                        Note(R.drawable.ic_sym_bluetooth_disabled, stringResource(R.string.newsensor_bt_off_title),
                            stringResource(R.string.newsensor_bt_off_text), stringResource(R.string.newsensor_bt_turn_on)) { enableBluetooth(ctx) }
                    }
                    Reveal(!hasAccount) {
                        Note(R.drawable.ic_sym_account_circle, stringResource(R.string.newsensor_account_title),
                            stringResource(R.string.newsensor_account_text), stringResource(R.string.sensor_sign_in)) {
                            scanStage = Stage.SignIn("", newSensor = true)
                        }
                    }
                    if (f.source != "direct") Note(R.drawable.ic_sym_info, stringResource(R.string.newsensor_juggluco_title),
                        stringResource(R.string.newsensor_juggluco_text))
                    Reveal(showTips) {
                        Note(R.drawable.ic_sym_lightbulb, stringResource(R.string.newsensor_tips_title), stringResource(R.string.newsensor_tips_text))
                    }
                    Text(stringResource(R.string.newsensor_takeover_note), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        // the stage's action at the bottom, where the thumb is (none while waiting: the tap is the action)
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when (stage) {
                is Stage.Choose -> Primary(stringResource(R.string.newsensor_continue)) {
                    when (val c = NewSensorFlow.onContinue(selected)) {
                        is NewSensorFlow.Continue.Confirm -> confirming = c.profile
                        is NewSensorFlow.Continue.Go -> { chosen = selected; typedId = ""; scanStage = Stage.Waiting }
                    }
                }
                is Stage.Setup -> {
                    val p = setupProfile ?: SensorProfiles.LIBRE_3
                    val ok = (p.pairing as? Pairing.TypedId)?.let { PairingInput.valid(it, PairingInput.normalize(typedId)) } ?: true
                    Button(onClick = { pairBrand(p) }, enabled = ok,
                        modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Text(stringResource(R.string.newsensor_pair_button), style = MaterialTheme.typography.titleMedium)
                    }
                    TextButton(onClick = { chosen = null }) { Text(stringResource(R.string.newsensor_choose_other)) }
                }
                is Stage.Waiting -> if (!showTips) TextButton(onClick = { showTips = true }) { Text(stringResource(R.string.newsensor_tips_button)) }
                is Stage.WarmingUp, is Stage.Ready -> Primary(stringResource(R.string.newsensor_done), onClose)
                is Stage.Problem -> when (stage.trouble) {
                    Trouble.NO_NFC -> Primary(stringResource(R.string.newsensor_close), onClose)
                    Trouble.NFC_OFF -> Primary(stringResource(R.string.sensor_turn_on_nfc)) { ctx.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }
                    Trouble.BLUETOOTH_OFF -> Primary(stringResource(R.string.newsensor_bt_turn_on)) { enableBluetooth(ctx) }
                    Trouble.LIBRE2 -> {
                        Primary(stringResource(R.string.sensor_libre2_source)) { nav?.open("source") }
                        TextButton(onClick = { scanStage = Stage.Waiting }) { Text(stringResource(R.string.newsensor_scan_other)) }
                    }
                    Trouble.ENDED -> if (tap?.profile?.let { it.pairing != Pairing.NfcTap } == true)
                        Primary(stringResource(R.string.newsensor_choose_other)) { tap = null; chosen = null }
                        else Primary(stringResource(R.string.newsensor_scan_other)) { scanStage = Stage.Waiting }
                    Trouble.PAIR_FAILED -> {
                        Primary(stringResource(R.string.newsensor_try_again)) { tap = null }
                        TextButton(onClick = { tap = null; chosen = null }) { Text(stringResource(R.string.newsensor_choose_other)) }
                    }
                    Trouble.FAILED, Trouble.UNSUPPORTED -> Primary(stringResource(R.string.newsensor_try_again)) { scanStage = Stage.Waiting }
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun Primary(text: String, onClick: () -> Unit) =
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(text, style = MaterialTheme.typography.titleMedium) }

/** Scan · Pair · Readings: done and current steps filled, the current one's label in the primary colour. */
@Composable
private fun StepBar(current: Step, complete: Boolean, setup: Boolean) {
    val labels = listOf(R.string.newsensor_step_sensor, if (setup) R.string.newsensor_step_setup else R.string.newsensor_step_scan,
        R.string.newsensor_step_pair, R.string.newsensor_step_readings)
    val desc = stringResource(R.string.newsensor_step_desc, current.ordinal + 1, Step.entries.size, stringResource(labels[current.ordinal]))
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp).semantics { contentDescription = desc },
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Step.entries.forEach { s ->
            val reached = complete || s.ordinal <= current.ordinal
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape)
                    .background(if (reached) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest))
                Text(stringResource(labels[s.ordinal]), style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (s == current && !complete) FontWeight.SemiBold else FontWeight.Normal,
                    color = when {
                        s == current && !complete -> MaterialTheme.colorScheme.primary
                        reached -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    })
            }
        }
    }
}

/** Which illustration a stage shows: the cross-fade runs only when it changes, not on every tick. */
private fun heroKey(s: Stage): Any = when (s) {
    is Stage.Problem -> s.trouble
    is Stage.Pairing -> Stage.Pairing::class
    else -> s::class
}

private fun textKey(s: Stage, again: Boolean): Any = when (s) {
    is Stage.Problem -> s.trouble
    is Stage.Waiting -> again
    else -> s::class
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Hero(stage: Stage, nfc: NfcAdapter?, now: Long) {
    val cs = MaterialTheme.colorScheme
    val thick = with(LocalDensity.current) { Stroke(8.dp.toPx(), cap = StrokeCap.Round) }
    val track = thick
    when (stage) {
        is Stage.Waiting -> ScanArt(remember(nfc) { antennaSpot(nfc) })
        is Stage.Reading -> Box(contentAlignment = Alignment.Center) {
            CircularWavyProgressIndicator(Modifier.size(200.dp), stroke = thick, trackStroke = track)
            SensorDisc(Modifier.size(96.dp))
        }
        is Stage.Pairing -> Box(contentAlignment = Alignment.Center) {
            CircularWavyProgressIndicator(Modifier.size(200.dp), stroke = thick, trackStroke = track)
            Badge(R.drawable.ic_sym_bluetooth, cs.primaryContainer, cs.onPrimaryContainer, 112.dp)
        }
        is Stage.WarmingUp -> {
            val frac = ((now - stage.startEpoch).toFloat() / (stage.readyAt - stage.startEpoch).coerceAtLeast(1)).coerceIn(0f, 1f)
            val left = ((stage.readyAt - now).coerceAtLeast(0) + 59) / 60
            Box(contentAlignment = Alignment.Center) {
                CircularWavyProgressIndicator(progress = { frac }, modifier = Modifier.size(220.dp), stroke = thick, trackStroke = track)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val rounded = remember { FontFamily(RoundedFont.typeface(700)) }
                    // past the warm-up's end without a reading yet: words, never "0 min left"
                    if (now >= stage.readyAt) Text(stringResource(R.string.card_almost_ready), fontSize = 32.sp, fontWeight = FontWeight.Bold,
                        fontFamily = rounded, color = cs.onSurface)
                    else {
                        Text("$left", fontSize = 64.sp, fontWeight = FontWeight.Bold, fontFamily = rounded, color = cs.onSurface)
                        Text(stringResource(R.string.newsensor_minutes_left), style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant)
                    }
                }
            }
        }
        is Stage.Ready -> ReadyHero()
        is Stage.Choose -> Unit
        is Stage.Setup -> Badge(R.drawable.ic_sym_bluetooth_searching, cs.secondaryContainer, cs.onSecondaryContainer)
        is Stage.SignIn -> Badge(R.drawable.ic_sym_account_circle, cs.secondaryContainer, cs.onSecondaryContainer)
        is Stage.Problem -> when (stage.trouble) {
            Trouble.FAILED, Trouble.PAIR_FAILED -> Badge(R.drawable.ic_sym_error, cs.errorContainer, cs.onErrorContainer)
            Trouble.ENDED -> Badge(R.drawable.ic_stat_sensor_ended, cs.surfaceContainerHighest, cs.onSurfaceVariant)
            Trouble.NO_NFC, Trouble.NFC_OFF -> Badge(R.drawable.ic_sym_nfc_off, cs.surfaceContainerHighest, cs.onSurfaceVariant)
            Trouble.BLUETOOTH_OFF -> Badge(R.drawable.ic_sym_bluetooth_disabled, cs.surfaceContainerHighest, cs.onSurfaceVariant)
            Trouble.LIBRE2, Trouble.UNSUPPORTED -> Badge(R.drawable.ic_sym_info, cs.surfaceContainerHighest, cs.onSurfaceVariant)
        }
    }
}

/** The first reading, as the home card shows it: the number in the rounded face, the trend arrow, the band's colour. */
@Composable
private fun ReadyHero() {
    val ctx = LocalContext.current
    val last = Live.status.collectAsState().value.last
    val cfg by GraphSettings.state.collectAsState()
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val pal = remember(dark) { systemPalette(ctx, dark) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Badge(R.drawable.ic_sym_check_circle, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer, 80.dp)
        last?.let { ValueNumber("${it.sgv}", it.rate, Color(pal.at(cfg.bands, it.sgv)), strike = false, scale = 0.8f) }
    }
}

/** An icon in a filled circle: the illustration of the stages that have no drawing of their own. */
@Composable
private fun Badge(@DrawableRes icon: Int, bg: Color, fg: Color, size: androidx.compose.ui.unit.Dp = 128.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = null, tint = fg, modifier = Modifier.size(size * 0.45f))
    }
}

/** A Libre 3 seen from above: a flat disc with the filament's dot in the middle. */
@Composable
private fun SensorDisc(modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Canvas(modifier) {
        val r = size.minDimension / 2
        drawCircle(cs.surfaceContainerLowest, r)
        drawCircle(cs.outline, r, style = Stroke(r * 0.06f))
        drawCircle(cs.outlineVariant, r * 0.32f)
    }
}

/**
 * The back of a phone with the sensor on its NFC antenna (this phone's spot from NfcAntennaInfo, else the upper
 * middle) and rings going out from it while Sukrara listens. The rings stop when the system's animations are off.
 */
@Composable
private fun ScanArt(spot: Offset) {
    val cs = MaterialTheme.colorScheme
    val desc = stringResource(R.string.newsensor_art_desc)
    val pulse by rememberInfiniteTransition(label = "nfc").animateFloat(0f, 1f,
        infiniteRepeatable(tween(1_800, easing = LinearEasing), RepeatMode.Restart), label = "rings")
    Canvas(Modifier.size(width = 200.dp, height = 260.dp).semantics { contentDescription = desc }) {
        val ph = size.height * 0.96f
        val pw = ph * 0.48f
        val left = (size.width - pw) / 2
        val top = (size.height - ph) / 2
        val corner = CornerRadius(pw * 0.16f)
        drawRoundRect(cs.surfaceContainerHighest, Offset(left, top), Size(pw, ph), corner)
        drawRoundRect(cs.outline, Offset(left, top), Size(pw, ph), corner, style = Stroke(2.dp.toPx()))
        // camera island, top left of the back
        val cam = pw * 0.28f
        val ci = Offset(left + pw * 0.1f, top + pw * 0.1f)
        drawRoundRect(cs.surfaceContainerLow, ci, Size(cam, cam * 1.4f), CornerRadius(cam * 0.32f))
        drawCircle(cs.outline, cam * 0.24f, Offset(ci.x + cam / 2, ci.y + cam * 0.4f))
        drawCircle(cs.outline, cam * 0.24f, Offset(ci.x + cam / 2, ci.y + cam * 1.0f))
        val c = Offset(left + pw * spot.x, top + ph * spot.y)
        val r = pw * 0.24f
        for (k in 0 until 3) {
            val p = (pulse + k / 3f) % 1f
            drawCircle(cs.primary.copy(alpha = 0.55f * (1 - p)), r * (1f + 1.6f * p), c, style = Stroke(3.dp.toPx()))
        }
        drawCircle(cs.surfaceContainerLowest, r, c)
        drawCircle(cs.primary, r, c, style = Stroke(3.dp.toPx()))
        drawCircle(cs.outlineVariant, r * 0.32f, c)
    }
}

/**
 * Where this phone's NFC antenna is, as a fraction of the phone's back (x from the left, y from the top, back view).
 * AvailableNfcAntenna gives millimetres from the top left of the front in portrait, so x is mirrored.
 */
private fun antennaSpot(nfc: NfcAdapter?): Offset {
    val info = runCatching { nfc?.nfcAntennaInfo }.getOrNull()
    val a = info?.availableNfcAntennas?.firstOrNull()
    if (info == null || a == null || info.deviceWidth <= 0 || info.deviceHeight <= 0) return Offset(0.5f, 0.45f)
    return Offset((1f - a.locationX.toFloat() / info.deviceWidth).coerceIn(0.2f, 0.8f),
        (a.locationY.toFloat() / info.deviceHeight).coerceIn(0.15f, 0.85f))
}

/** Connecting → pairing securely → starting readings, each ticked off as the source moves on. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PairSteps(at: PairStep) {
    val labels = listOf(R.string.newsensor_pair_connecting, R.string.newsensor_pair_securing, R.string.newsensor_pair_starting)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PairStep.entries.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val cs = MaterialTheme.colorScheme
                when {
                    s.ordinal < at.ordinal -> Icon(painterResource(R.drawable.ic_sym_check_circle), null, tint = cs.primary, modifier = Modifier.size(24.dp))
                    s == at -> CircularWavyProgressIndicator(Modifier.size(24.dp))
                    else -> Box(Modifier.size(24.dp).padding(4.dp).clip(CircleShape).background(cs.surfaceContainerHighest))
                }
                Text(stringResource(labels[s.ordinal]), style = MaterialTheme.typography.bodyLarge,
                    color = if (s.ordinal > at.ordinal) cs.onSurfaceVariant else cs.onSurface,
                    fontWeight = if (s == at) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

/** A note card under the text: an icon, a title, a line or two, and an optional action. */
@Composable
private fun Note(@DrawableRes icon: Int, title: String, text: String, action: String? = null, onAction: () -> Unit = {}) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                action?.let { TextButton(onClick = onAction, modifier = Modifier.padding(top = 4.dp)) { Text(it) } }
            }
        }
    }
}

@Composable
private fun title(s: Stage, again: Boolean, setup: SensorProfile?): String = when (s) {
    is Stage.Choose -> stringResource(R.string.newsensor_choose_title)
    is Stage.Setup -> when (val k = setup?.pairing) {
        is Pairing.VendorApp -> stringResource(R.string.newsensor_setup_vendor_title, k.app)
        is Pairing.TypedId -> stringResource(if (k.kind == IdKind.TRANSMITTER_ID) R.string.newsensor_setup_transmitter_title else R.string.newsensor_setup_serial_title)
        else -> stringResource(R.string.newsensor_setup_pin_title)
    }
    is Stage.Waiting -> stringResource(if (again) R.string.newsensor_wait_again_title else R.string.newsensor_wait_title)
    is Stage.Reading -> stringResource(R.string.newsensor_reading_title)
    is Stage.SignIn -> stringResource(R.string.newsensor_signin_title)
    is Stage.Pairing -> stringResource(R.string.newsensor_pair_title)
    is Stage.WarmingUp -> stringResource(R.string.newsensor_warmup_title)
    is Stage.Ready -> stringResource(R.string.newsensor_ready_title)
    is Stage.Problem -> when (s.trouble) {
        Trouble.NO_NFC -> stringResource(R.string.newsensor_no_nfc_title)
        Trouble.NFC_OFF -> stringResource(R.string.newsensor_nfc_off_title)
        Trouble.ENDED -> stringResource(R.string.newsensor_ended_title)
        Trouble.LIBRE2 -> stringResource(R.string.sensor_libre2_title)
        Trouble.UNSUPPORTED -> stringResource(R.string.sensor_unsupported)
        Trouble.FAILED -> stringResource(R.string.newsensor_failed_title)
        Trouble.BLUETOOTH_OFF -> stringResource(R.string.newsensor_bt_off_title)
        Trouble.PAIR_FAILED -> stringResource(R.string.newsensor_pair_failed_title)
    }
}

@Composable
private fun body(s: Stage, now: Long, setup: SensorProfile?): String? = when (s) {
    is Stage.Choose -> null
    is Stage.Setup -> when (val k = setup?.pairing) {
        is Pairing.VendorApp -> stringResource(R.string.newsensor_setup_vendor_text, k.app)
        is Pairing.TypedId -> stringResource(if (k.kind == IdKind.TRANSMITTER_ID) R.string.newsensor_setup_transmitter_text
            else R.string.newsensor_setup_serial_text, k.length)
        is Pairing.BlePasskey -> stringResource(R.string.newsensor_setup_pin_text, k.digits)
        else -> null
    }
    is Stage.Waiting -> stringResource(R.string.newsensor_wait_text)
    is Stage.Reading -> stringResource(R.string.newsensor_reading_text)
    is Stage.SignIn -> stringResource(if (s.newSensor) R.string.sensor_new_account_text else R.string.sensor_other_app_text)
    is Stage.Pairing -> stringResource(R.string.newsensor_pair_text, s.serial)
    is Stage.WarmingUp -> if (now >= s.readyAt) stringResource(R.string.card_waiting_reading)
        else stringResource(R.string.newsensor_warmup_text, Freshness.timeText(s.readyAt, now))
    is Stage.Ready -> s.endEpoch?.let { stringResource(R.string.newsensor_ready_text, s.serial, endDate(it)) }
        ?: stringResource(R.string.sensor_ready_text)
    is Stage.Problem -> when (s.trouble) {
        Trouble.NO_NFC -> stringResource(R.string.newsensor_no_nfc_text)
        Trouble.NFC_OFF -> stringResource(R.string.newsensor_nfc_off_text)
        Trouble.ENDED -> stringResource(R.string.newsensor_ended_text, s.serial ?: "")
        Trouble.LIBRE2 -> stringResource(R.string.sensor_libre2_text)
        Trouble.UNSUPPORTED -> s.detail
        Trouble.FAILED -> stringResource(R.string.newsensor_failed_text, s.detail ?: "")
        Trouble.BLUETOOTH_OFF -> stringResource(R.string.newsensor_bt_off_text)
        Trouble.PAIR_FAILED -> s.detail
    }
}

private fun endDate(t: Long) = app.mauri.sukrara.graph.Clock.dayTime(t)

private fun bluetoothOn(ctx: Context) = ctx.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

/** The system's "turn on Bluetooth?" dialog (no global toggle from the app). */
private fun enableBluetooth(ctx: Context) {
    runCatching { ctx.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
        .onFailure { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
}

/**
 * The sensor was started by another app under a LibreView account, or a new sensor needs the account it will be
 * tied to: learn that account's receiver ID once. Sukrara keeps only the derived number, never the password; nothing
 * is uploaded and the other app's LibreView link stays.
 */
@Composable
private fun LibreViewSignIn(onCancel: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf(ctx.getSharedPreferences("libreview", Context.MODE_PRIVATE).getString("email", "")!!) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(email, { email = it }, label = { Text(stringResource(R.string.sensor_libreview_email)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.onboarding_password)) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Button(enabled = !busy && email.isNotBlank() && password.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(56.dp), onClick = {
            busy = true; error = null
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { ReceiverId.fetchLibreView(ctx, email, password) } }
                busy = false
                r.onSuccess { password = ""; onDone() }.onFailure { error = it.message ?: it.javaClass.simpleName }
            }
        }) { Text(stringResource(if (busy) R.string.sensor_signing_in else R.string.sensor_sign_in), style = MaterialTheme.typography.titleMedium) }
        TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.treatment_cancel)) }
    }
}

/**
 * The brand step: every family [NewSensorFlow.choices] lists, as a radio list (Material 3 radio button pattern:
 * the whole row selects, `selectableGroup` for accessibility). Untested families carry the red [UntestedTag].
 */
@Composable
private fun ChooseSensor(selected: SensorProfile, onSelect: (SensorProfile) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.newsensor_choose_title), style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() })
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            NewSensorFlow.choices.forEach { p ->
                Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                    .selectable(selected = p == selected, role = Role.RadioButton, onClick = { onSelect(p) })
                    .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = p == selected, onClick = null)
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        Text(p.name, style = MaterialTheme.typography.bodyLarge)
                        Text(p.brand, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    }
                    if (!p.verified) UntestedTag()
                }
            }
        }
    }
}

/**
 * The warning when choosing an untested family: an AlertDialog with the warning icon (developer.android.com/develop/
 * ui/compose/components/dialog: "Confirming user action"), in the error colour roles (icon, title, accept button): the
 * user asked for red here, the one exception to "red only for errors", because it is a safety warning. "Continue
 * anyway" is disabled for [UntestedConfirm.WAIT_S] seconds with the count in its label, so the text gets read; Cancel
 * is always enabled, the emphasised (tonal) button and what back / a tap outside do. The wait restarts every time the
 * dialog opens (state lives in the dialog).
 */
@Composable
internal fun UntestedConfirmDialog(profile: SensorProfile, onCancel: () -> Unit, onPair: () -> Unit) {
    val opened = remember(profile) { System.currentTimeMillis() }
    var elapsed by remember(profile) { mutableStateOf(0L) }
    LaunchedEffect(profile) {
        while (!UntestedConfirm.enabled(elapsed)) { delay(200); elapsed = System.currentTimeMillis() - opened }
    }
    val left = UntestedConfirm.secondsLeft(elapsed)
    AlertDialog(
        onDismissRequest = onCancel,
        iconContentColor = MaterialTheme.colorScheme.error,
        titleContentColor = MaterialTheme.colorScheme.error,
        icon = { Icon(painterResource(R.drawable.ic_sym_warning), contentDescription = null) },
        title = { Text(stringResource(R.string.untested_confirm_title)) },
        text = { Text(stringResource(R.string.untested_confirm_text, profile.name)) },
        confirmButton = {
            TextButton(onClick = onPair, enabled = left == 0,
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text(if (left > 0) stringResource(R.string.untested_confirm_wait, left) else stringResource(R.string.untested_confirm_accept))
            }
        },
        dismissButton = { FilledTonalButton(onClick = onCancel) { Text(stringResource(R.string.untested_confirm_cancel)) } },
    )
}

/**
 * "Untested": a family whose support was never tried on a real sensor (`SensorProfile.verified` false). Red (the error colour on
 * the error container) with the Material Symbols `science` flask, as the user asked (2026-10-19), everywhere the tag appears:
 * the brand step and Settings → Glucose source.
 */
@Composable
fun UntestedTag() {
    val cs = MaterialTheme.colorScheme
    Surface(shape = CircleShape, color = cs.errorContainer) {
        Row(Modifier.padding(start = 8.dp, end = 10.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(painterResource(R.drawable.ic_sym_science), contentDescription = null, tint = cs.error, modifier = Modifier.size(16.dp))
            Text(stringResource(R.string.newsensor_untested_tag), style = MaterialTheme.typography.labelMedium, color = cs.error)
        }
    }
}
