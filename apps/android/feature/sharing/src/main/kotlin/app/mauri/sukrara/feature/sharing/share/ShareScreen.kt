// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing.share

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.designsystem.haptics.rememberHaptics
import app.mauri.sukrara.designsystem.motion.LocalReducedMotion
import app.mauri.sukrara.designsystem.motion.Motion
import app.mauri.sukrara.feature.sharing.Invite
import app.mauri.sukrara.feature.sharing.R
import app.mauri.sukrara.feature.sharing.setup.NoteCard
import app.mauri.sukrara.feature.sharing.setup.StepHeader
import app.mauri.sukrara.feature.sharing.setup.startSafely
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * "Share with someone": a label only the wearer sees, then a single-use invite as a QR (springs in) and a link to send
 * or copy, its expiry and what the follower will see. Every invite still needs the wearer's Allow.
 */
@Composable
fun ShareScreen(
    invite: InviteUi,
    onCreate: (String) -> Unit,
    onAnother: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var label by rememberSaveable { mutableStateOf("") }
    Column(
        modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StepHeader(stringResource(R.string.sh_share_title), stringResource(R.string.sh_share_body))
        when (invite) {
            InviteUi.None, InviteUi.Creating, InviteUi.Failed -> {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(LABEL_MAX) },
                    label = { Text(stringResource(R.string.sh_share_label)) },
                    supportingText = { Text(stringResource(R.string.sh_share_label_support)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (invite is InviteUi.Failed) NoteCard(stringResource(R.string.sh_share_failed), error = true)
                Button(
                    onClick = { onCreate(label) },
                    enabled = invite !is InviteUi.Creating,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(stringResource(if (invite is InviteUi.Failed) R.string.sh_share_try_again else R.string.sh_share_create)) }
            }

            is InviteUi.Ready -> {
                ReadyInvite(invite.invite, onAnother)
            }
        }
        Text(stringResource(R.string.sh_share_they_see), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.sh_share_they_see_body), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ColumnScope.ReadyInvite(
    invite: Invite,
    onAnother: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val reduced = LocalReducedMotion.current
    val qr = remember(invite.link) { qrBitmap(invite.link, 768) }
    val scale = remember(invite.link) { Animatable(if (reduced) 1f else 0.6f) }
    LaunchedEffect(invite.link) {
        haptics.confirm()
        scale.animateTo(1f, Motion.defaultSpatial(reduced))
    }
    val qrLabel = stringResource(R.string.sh_share_qr_label)
    Image(
        qr.asImageBitmap(),
        contentDescription = null,
        modifier =
            Modifier
                .align(Alignment.CenterHorizontally)
                .size(240.dp)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                }.clip(RoundedCornerShape(16.dp))
                .background(Color.White)
                .padding(12.dp)
                .semantics { contentDescription = qrLabel },
    )
    Text(stringResource(R.string.sh_share_scan), style = MaterialTheme.typography.bodyMedium)
    val expiry = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(invite.expiresAt * 1000))
    NoteCard(
        if (invite.label.isEmpty()) {
            stringResource(R.string.sh_share_note_unnamed, expiry)
        } else {
            stringResource(R.string.sh_share_note, expiry, invite.label)
        },
    )
    // the link as selectable text too, so TalkBack users and any app can copy it
    SelectionContainer { Text(invite.link, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    val chooser = stringResource(R.string.sh_share_send_chooser)
    val message = stringResource(R.string.sh_share_send_message, invite.link)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, message)
                context.startSafely(Intent.createChooser(send, chooser))
            },
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.sh_share_send)) }
        OutlinedButton(
            onClick = {
                haptics.confirm()
                scope.launch { clipboard.setClipEntry(ClipData.newPlainText(chooser, invite.link).toClipEntry()) }
            },
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.sh_share_copy)) }
    }
    OutlinedButton(onClick = onAnother, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(stringResource(R.string.sh_share_another))
    }
}

private const val LABEL_MAX = 40

/** The invite link as a black-on-white QR bitmap ([size] px square, quiet zone included). */
internal fun qrBitmap(
    text: String,
    size: Int,
): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val pixels =
        IntArray(size * size) { i -> if (matrix[i % size, i / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
