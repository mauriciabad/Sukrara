// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import androidx.compose.ui.res.stringResource
import app.mauri.sukrara.R
import android.app.NotificationManager
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.features.FeatureState
import app.mauri.sukrara.features.Features
import app.mauri.sukrara.treatments.Treat
import app.mauri.sukrara.treatments.Treatment
import app.mauri.sukrara.wearer.Live

private fun set(f: (FeatureState) -> FeatureState) { Features.update(f) }

/** Sign in (or out) of LibreView. The password is only stored; the next upload (or the Sensor screen) uses it. */
@Composable
internal fun LibreViewSignIn(s: app.mauri.sukrara.libreview.LibreViewUploader.Settings, onClose: () -> Unit) {
    val uploader = Treat.uploader
    var email by remember { mutableStateOf(s.email) }
    var password by remember { mutableStateOf("") }
    val signedIn = s.email.isNotEmpty() && s.hasPassword
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.libreview_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.libreview_dialog_text),
                    style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.libreview_email)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next))
                OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.onboarding_password)) }, placeholder = if (s.hasPassword) ({ Text(stringResource(R.string.libreview_password_saved)) }) else null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done))
            }
        },
        confirmButton = {
            Button(enabled = email.isNotBlank() && (password.isNotEmpty() || s.hasPassword), onClick = {
                uploader.configure(email = email, password = password.ifEmpty { null }); onClose()
            }) { Text(stringResource(R.string.treatment_save)) }
        },
        dismissButton = {
            Row {
                if (signedIn) TextButton(onClick = { set { it.copy(libreview = false) }; uploader.signOut(); onClose() }) { Text(stringResource(R.string.libreview_sign_out)) }
                TextButton(onClick = onClose) { Text(stringResource(R.string.treatment_cancel)) }
            }
        },
    )
}

/**
 * The owner's treatments on the home screen, the last 24 h (also entries from followers and Macs), newest first, each
 * with the time since it at the end of the row ("12m", "3h"; trailing supporting text, like a list item's trailing
 * content). Tapping one offers to delete it. *Add treatment* is on the glucose card ([GlucosePanel]).
 */
@Composable
fun TreatmentsSection() {
    val ctx = LocalContext.current
    remember { Treat.init(ctx) }
    val store = Treat.store
    val f by Features.state.collectAsState()
    var deleting by remember { mutableStateOf<Treatment?>(null) }
    val items by store.items.collectAsState()
    val now = nowSeconds(30_000)
    val since = now - 24 * 3600
    val recent = items.filter { !it.del && it.t >= since }
    val pal = app.mauri.sukrara.treatments.TreatmentMarks.palette(androidx.compose.foundation.isSystemInDarkTheme())

    SectionTitle(stringResource(R.string.treatments_title))
    Reveal(recent.isEmpty()) { Text(stringResource(R.string.treatments_empty),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
    // new entries (also from followers / the Mac) fade + expand in, deleted ones fade + shrink out
    AnimatedItems(recent, { it.id }) { tr ->
        Row(Modifier.fillMaxWidth().clickable { deleting = tr }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KindMark(tr.k, androidx.compose.ui.graphics.Color(app.mauri.sukrara.treatments.TreatmentMarks.color(pal, tr.k)))
            Text(app.mauri.sukrara.graph.Clock.time(tr.t), fontWeight = FontWeight.Medium)
            Text(describe(ctx, tr), Modifier.weight(1f), maxLines = 2)
            Text(app.mauri.sukrara.treatments.TreatmentMarks.duration(now - tr.t), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (f.libreview) androidx.compose.material3.Icon(
                androidx.compose.ui.res.painterResource(if (tr.needsUpload) app.mauri.sukrara.R.drawable.ic_sym_cloud_upload else app.mauri.sukrara.R.drawable.ic_sym_cloud_done),
                contentDescription = stringResource(if (tr.needsUpload) R.string.treatments_not_uploaded else R.string.treatments_uploaded), tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp))
        }
    }

    deleting?.let { tr ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.treatments_delete_title)) },
            text = { Text(app.mauri.sukrara.graph.Clock.time(tr.t) + " · " + describe(ctx, tr) +
                if (f.libreview && !tr.needsUpload) "\n\n" + stringResource(R.string.treatments_delete_libreview) else "") },
            confirmButton = { Button(onClick = { store.delete(tr.id); Treat.uploader.kick(); deleting = null }) { Text(stringResource(R.string.treatments_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.treatment_cancel)) } },
        )
    }
}

private fun describe(c: android.content.Context, tr: Treatment): String {
    val amount = app.mauri.sukrara.treatments.TreatmentMarks.amount(tr) ?: ""
    return when (tr.k) {
        "carbs" -> c.getString(R.string.treatments_row_carbs, amount)
        "note" -> tr.n.ifEmpty { c.getString(R.string.treatment_note) }
        "long" -> c.getString(R.string.treatments_row_long, amount)
        else -> c.getString(R.string.treatments_row_rapid, amount)
    } + (if (tr.k != "note" && tr.n.isNotEmpty()) " · ${tr.n}" else "") +
        (if (tr.o != "phone") " · " + c.getString(R.string.treatments_row_from, tr.o) else "")
}
