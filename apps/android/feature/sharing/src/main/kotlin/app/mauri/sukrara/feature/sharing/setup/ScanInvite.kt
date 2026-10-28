// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing.setup

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.mauri.sukrara.designsystem.haptics.rememberHaptics
import app.mauri.sukrara.feature.sharing.R
import app.mauri.sukrara.protocol.invite.InviteLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import zxingcpp.BarcodeReader
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Where the join step's QR scanner is: closed, explaining the camera, refused for good, or looking. */
internal enum class ScanState { Closed, Rationale, Denied, Scanning, NoCamera }

/** The first decoded text that is a Sukrara invite link (trimmed), or null: any other QR code is ignored. */
internal fun firstInvite(
    texts: List<String?>,
): String? = texts.firstNotNullOfOrNull { t -> t?.trim()?.takeIf { InviteLink.parse(it) != null } }

/**
 * *Scan QR code* and *From a picture* on the join step: the invite link read from the wearer's share screen (live,
 * CameraX + zxing-cpp, decoded on the phone, nothing recorded or sent) or from a picture of it (a screenshot sent in a
 * chat, through the photo picker: no storage permission). A read link fills [onInvite]. The camera is asked for only
 * on *Scan QR code*; a refusal shows why it's needed with *Allow camera*, a permanent one *Open settings*, both with
 * *Cancel*, and the link can still be pasted or picked from a picture. [initial] is for previews and tests.
 */
@Composable
internal fun ColumnScope.ScanInvite(
    enabled: Boolean,
    onInvite: (String) -> Unit,
    initial: ScanState = ScanState.Closed,
) {
    val ctx = LocalContext.current
    val activity = LocalActivity.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    var state by rememberSaveable { mutableStateOf(initial) }
    var note by rememberSaveable { mutableStateOf<Int?>(null) }
    val granted = { ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED }
    val ask =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            state =
                when {
                    ok -> ScanState.Scanning
                    activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == true -> ScanState.Rationale
                    else -> ScanState.Denied
                }
        }
    val found = { link: String ->
        haptics.confirm()
        note = R.string.sh_scan_read
        state = ScanState.Closed
        onInvite(link)
    }
    val pick =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val link = withContext(Dispatchers.Default) { inviteInPicture(ctx, uri) }
                if (link != null) {
                    found(link)
                } else {
                    haptics.reject()
                    note = R.string.sh_scan_picture_none
                }
            }
        }
    // back from the system settings with the camera allowed: straight to scanning
    if (state == ScanState.Denied) {
        LifecycleResumeEffect(Unit) {
            if (granted()) state = ScanState.Scanning
            onPauseOrDispose {}
        }
    }
    val start = {
        note = null
        state =
            when {
                granted() -> ScanState.Scanning
                activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == true -> ScanState.Rationale
                else -> ScanState.Closed.also { ask.launch(Manifest.permission.CAMERA) }
            }
    }
    when (state) {
        ScanState.Closed -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = start, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.sh_scan_qr))
                }
                OutlinedButton(
                    onClick = {
                        note = null
                        pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    enabled = enabled,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.sh_scan_picture)) }
            }
            note?.let {
                Text(
                    stringResource(it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (it == R.string.sh_scan_read) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }

        ScanState.Rationale, ScanState.Denied, ScanState.NoCamera -> {
            ScanCard {
                Text(
                    stringResource(
                        when (state) {
                            ScanState.Rationale -> R.string.sh_scan_camera_why
                            ScanState.Denied -> R.string.sh_scan_camera_off
                            else -> R.string.sh_scan_no_camera
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { state = ScanState.Closed }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.sh_scan_cancel))
                    }
                    when (state) {
                        ScanState.Rationale -> {
                            TextButton(onClick = { ask.launch(Manifest.permission.CAMERA) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.sh_scan_allow))
                            }
                        }

                        ScanState.Denied -> {
                            TextButton(
                                onClick = {
                                    ctx.startActivity(
                                        Intent(
                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.fromParts("package", ctx.packageName, null),
                                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text(stringResource(R.string.sh_scan_settings)) }
                        }

                        else -> {}
                    }
                }
            }
        }

        ScanState.Scanning -> {
            ScanCard {
                Text(
                    stringResource(R.string.sh_scan_point),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
                CameraPreview(
                    onInvite = found,
                    onNoCamera = { state = ScanState.NoCamera },
                    modifier =
                        Modifier
                            .padding(horizontal = 16.dp)
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(16.dp)),
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { state = ScanState.Closed }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.sh_scan_cancel))
                    }
                }
            }
        }
    }
}

@Composable
private fun ScanCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.sh_scan_qr),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            content()
        }
    }
}

/** The back camera (or the only one) with every frame decoded on one thread; the first invite link read wins. */
@Composable
private fun CameraPreview(
    onInvite: (String) -> Unit,
    onNoCamera: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val invite by rememberUpdatedState(onInvite)
    val noCamera by rememberUpdatedState(onNoCamera)
    val view = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val description = stringResource(R.string.sh_scan_preview)
    AndroidView(factory = { view }, modifier = modifier.semantics { contentDescription = description })
    LaunchedEffect(owner) {
        val provider = runCatching { ProcessCameraProvider.awaitInstance(ctx) }.getOrNull() ?: return@LaunchedEffect noCamera()
        val selector =
            listOf(CameraSelector.DEFAULT_BACK_CAMERA, CameraSelector.DEFAULT_FRONT_CAMERA)
                .firstOrNull { runCatching { provider.hasCamera(it) }.getOrDefault(false) }
                ?: return@LaunchedEffect noCamera()
        val done = AtomicBoolean(false)
        val exec = Executors.newSingleThreadExecutor()
        val reader = reader(hard = false)
        val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        analysis.setAnalyzer(exec) { image ->
            image.use {
                if (done.get()) return@use
                val link = firstInvite(runCatching { reader.read(it) }.getOrDefault(emptyList()).map { r -> r.text })
                if (link != null && done.compareAndSet(false, true)) {
                    ContextCompat.getMainExecutor(ctx).execute {
                        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) invite(link)
                    }
                }
            }
        }
        try {
            if (runCatching { provider.bindToLifecycle(owner, selector, preview, analysis) }.isFailure) return@LaunchedEffect noCamera()
            awaitCancellation()
        } finally {
            runCatching { provider.unbind(preview, analysis) }
            analysis.clearAnalyzer()
            exec.shutdown()
        }
    }
}

private fun reader(hard: Boolean) =
    BarcodeReader(
        BarcodeReader.Options(formats = setOf(BarcodeReader.Format.QR_CODE), tryRotate = true, tryHarder = hard),
    )

/** The invite link in a picture (a screenshot of the share screen), decoded at most ~2000 px wide; null if none. */
private fun inviteInPicture(
    ctx: Context,
    uri: Uri,
): String? =
    runCatching {
        val bitmap =
            ImageDecoder
                .decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val side = maxOf(info.size.width, info.size.height)
                    if (side > MAX_PICTURE_SIDE) d.setTargetSampleSize(side / MAX_PICTURE_SIDE)
                }.let { if (it.config != Bitmap.Config.ARGB_8888) it.copy(Bitmap.Config.ARGB_8888, false) else it }
        firstInvite(reader(hard = true).read(bitmap).map { it.text })
    }.getOrNull()

private const val MAX_PICTURE_SIDE = 2000
