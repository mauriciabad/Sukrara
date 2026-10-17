// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

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
import java.text.DateFormat
import java.util.Date

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
        title = { Text("LibreView account") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("The account your doctor's clinic sees. Sukrara also learns your sensor's receiver ID from it.",
                    style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Email") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next))
                OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Password") }, placeholder = if (s.hasPassword) ({ Text("Saved · type to change") }) else null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done))
            }
        },
        confirmButton = {
            Button(enabled = email.isNotBlank() && (password.isNotEmpty() || s.hasPassword), onClick = {
                uploader.configure(email = email, password = password.ifEmpty { null }); onClose()
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (signedIn) TextButton(onClick = { set { it.copy(libreview = false) }; uploader.signOut(); onClose() }) { Text("Sign out") }
                TextButton(onClick = onClose) { Text("Cancel") }
            }
        },
    )
}

/** One option of a [ChoiceDialog]. */
data class Choice<T>(val label: String, val value: T, val sub: String? = null)

/** ListPreference: radio buttons in a dialog; choosing saves and closes, Cancel keeps the old value. */
@Composable
fun <T> ChoiceDialog(title: String, options: List<Choice<T>>, selected: T, onClose: () -> Unit, onChoose: (T) -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup()) {
                for (o in options) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
                        .selectable(selected = o.value == selected, role = Role.RadioButton, onClick = { onChoose(o.value); onClose() })
                        .padding(vertical = 6.dp)) {
                        RadioButton(selected = o.value == selected, onClick = null)
                        Spacer(Modifier.size(12.dp))
                        Column {
                            Text(o.label)
                            o.sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

/**
 * The owner's treatments on the home screen, the last 24 h (also entries from followers and Macs), newest first.
 * Tapping one offers to delete it. *Add treatment* is on the glucose card ([GlucosePanel]).
 */
@Composable
fun TreatmentsSection() {
    val ctx = LocalContext.current
    remember { Treat.init(ctx) }
    val store = Treat.store
    val f by Features.state.collectAsState()
    var deleting by remember { mutableStateOf<Treatment?>(null) }
    val items by store.items.collectAsState()
    val since = System.currentTimeMillis() / 1000 - 24 * 3600
    val recent = items.filter { !it.del && it.t >= since }
    val pal = app.mauri.sukrara.treatments.TreatmentMarks.palette(androidx.compose.foundation.isSystemInDarkTheme())

    SectionTitle("Treatments (24 h)")
    Reveal(recent.isEmpty()) { Text("Nothing in the last 24 h. Tip: tap a Sukrara widget or the glucose notification to add one from anywhere.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
    // new entries (also from followers / the Mac) fade + expand in, deleted ones fade + shrink out
    AnimatedItems(recent, { it.id }) { tr ->
        Row(Modifier.fillMaxWidth().clickable { deleting = tr }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KindMark(tr.k, androidx.compose.ui.graphics.Color(app.mauri.sukrara.treatments.TreatmentMarks.color(pal, tr.k)))
            Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(tr.t * 1000)), fontWeight = FontWeight.Medium)
            Text(describe(tr), Modifier.weight(1f), maxLines = 2)
            if (f.libreview) Text(if (tr.needsUpload) "LibreView ⇡" else "LibreView ✓", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline)
        }
    }

    deleting?.let { tr ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this entry?") },
            text = { Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(tr.t * 1000)) + " · " + describe(tr) +
                if (f.libreview && !tr.needsUpload) "\n\nIt stays in LibreView: entries can't be deleted there." else "") },
            confirmButton = { Button(onClick = { store.delete(tr.id); Treat.uploader.kick(); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

private fun units(a: Double) = if (a % 1.0 == 0.0) "${a.toLong()}" else "%.1f".format(a)

private fun describe(tr: Treatment): String = when (tr.k) {
    "carbs" -> "${tr.a.toInt()} g carbs"
    "note" -> tr.n.ifEmpty { "Note" }
    "long" -> "${units(tr.a)} U long-acting"
    else -> "${units(tr.a)} U rapid"
} + (if (tr.k != "note" && tr.n.isNotEmpty()) " · ${tr.n}" else "") + (if (tr.o != "phone") " · from ${tr.o}" else "")
