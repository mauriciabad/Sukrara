// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.R
import app.mauri.sukrara.core.SensorLife
import app.mauri.sukrara.export.Export
import app.mauri.sukrara.export.GlucoseCsv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → Export data (both roles): readings and treatments of a period as one CSV ([GlucoseCsv], LibreView's
 * layout). The period is a list preference; the two actions are pills under it: Save file (the system file picker,
 * SAF ACTION_CREATE_DOCUMENT) and Share (the share sheet). Only data already on this phone ([Export.load]).
 */
@Composable
fun ExportSettings(follower: Boolean, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sensor by SensorLife.current.collectAsState()
    var chosen by rememberSaveable { mutableStateOf(Export.DEFAULT) }
    var choosing by remember { mutableStateOf(false) }
    val period = if (chosen == "sensor" && sensor == null) Export.DEFAULT else chosen
    val idx = indexing
    val data by produceState<Export.Data?>(null, period) {
        value = null
        if (!idx) value = withContext(Dispatchers.IO) { Export.load(ctx, period) }
    }
    fun failed(e: Throwable) = Toast.makeText(ctx, ctx.getString(R.string.transfer_write_failed, e.message), Toast.LENGTH_LONG).show()
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(GlucoseCsv.MIME)) { uri ->
        val d = data
        if (uri != null && d != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(Export.csv(d).toByteArray()) } }
            }.onSuccess { Toast.makeText(ctx, R.string.export_saved, Toast.LENGTH_SHORT).show() }.onFailure(::failed)
        }
    }
    val sensorLabel = stringResource(R.string.export_period_sensor)
    fun label(p: String) = if (p == "sensor") sensorLabel else ctx.getString(R.string.export_period_days, p.toInt())
    SettingsPage(stringResource(R.string.export_title), onBack) {
        SettingsIntro(stringResource(if (follower) R.string.export_intro_follower else R.string.export_intro))
        SettingsGroup {
            value(stringResource(R.string.export_period), label(period), icon = R.drawable.ic_sym_history) { choosing = true }
            info(stringResource(R.string.export_contents), data?.let { d ->
                pluralStringResource(R.plurals.export_readings, d.readingRows, d.readingRows) + " · " +
                    pluralStringResource(R.plurals.export_treatments, d.treatmentRows, d.treatmentRows)
            } ?: stringResource(R.string.export_counting), icon = R.drawable.ic_sym_receipt_long)
        }
        if (!idx) Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.CenterHorizontally)) {
            val ready = data != null
            Button(onClick = { data?.let { saver.launch(Export.fileName(it)) } }, enabled = ready) {
                Icon(painterResource(R.drawable.ic_sym_file_export), null, Modifier.size(ButtonDefaults.IconSize))
                Text(stringResource(R.string.export_save), Modifier.padding(start = ButtonDefaults.IconSpacing))
            }
            FilledTonalButton(onClick = {
                val d = data ?: return@FilledTonalButton
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { Export.shareIntent(ctx, d, ctx.getString(R.string.export_share_chooser)) } }
                        .onSuccess { ctx.startActivity(it) }.onFailure(::failed)
                }
            }, enabled = ready) {
                Icon(painterResource(R.drawable.ic_sym_share), null, Modifier.size(ButtonDefaults.IconSize))
                Text(stringResource(R.string.export_share), Modifier.padding(start = ButtonDefaults.IconSpacing))
            }
        }
        SettingsFooter(stringResource(R.string.export_footer))
    }
    if (choosing) ChoiceDialog(stringResource(R.string.export_period),
        (listOfNotNull("sensor".takeIf { sensor != null }) + Export.DAYS.map { it.toString() }).map { Choice(label(it), it) },
        period, { choosing = false }) { chosen = it }
}
