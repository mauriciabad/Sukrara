// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.integrations.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.mauri.sukrara.designsystem.dialog.Choice
import app.mauri.sukrara.designsystem.dialog.ChoiceDialog
import app.mauri.sukrara.designsystem.rows.FooterText
import app.mauri.sukrara.designsystem.rows.InfoRow
import app.mauri.sukrara.designsystem.rows.SettingsGroup
import app.mauri.sukrara.designsystem.rows.ValueRow
import app.mauri.sukrara.integrations.R
import app.mauri.sukrara.integrations.ui.IntroText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the export page shows: the period, whether "This sensor" can be chosen, and the counted file (null = counting). */
data class ExportUi(
    val period: ExportPeriod = ExportPeriod.DEFAULT,
    val sensorKnown: Boolean = false,
    val data: ExportData? = null,
    /** A follower exports the followed person's data: the page says so. */
    val follower: Boolean = false,
)

class ExportViewModel(
    private val exporter: Exporter,
    follower: Boolean,
    private val io: CoroutineDispatcher,
) : ViewModel() {
    private val _ui = MutableStateFlow(ExportUi(follower = follower))
    val ui: StateFlow<ExportUi> = _ui.asStateFlow()

    init {
        choose(ExportPeriod.DEFAULT)
    }

    fun choose(period: ExportPeriod) {
        _ui.update { it.copy(period = period, data = null) }
        viewModelScope.launch {
            val sensorKnown = exporter.currentSensor() != null
            val chosen = if (period == ExportPeriod.Sensor && !sensorKnown) ExportPeriod.DEFAULT else period
            val data = withContext(io) { exporter.load(chosen) }
            _ui.update { if (it.period == period) it.copy(period = chosen, sensorKnown = sensorKnown, data = data) else it }
        }
    }

    fun fileName(d: ExportData) = exporter.fileName(d)

    /** Writes the file to [uri] (the system file picker's choice). */
    suspend fun save(
        context: Context,
        uri: Uri,
        d: ExportData,
    ): Result<Unit> =
        withContext(io) {
            runCatching {
                val out = checkNotNull(context.contentResolver.openOutputStream(uri)) { "no output stream" }
                out.use { it.write(exporter.csv(d).toByteArray()) }
            }
        }

    /**
     * The share sheet's intent: the file goes to `cache/export/` (served read-only by the FileProvider, emptied before
     * every share) and the chooser gets a read grant for this one file only.
     */
    suspend fun shareIntent(
        context: Context,
        d: ExportData,
        chooserTitle: String,
    ): Result<Intent> =
        withContext(io) {
            runCatching {
                val dir = exportDir(context).apply { mkdirs() }
                val file = File(dir, exporter.fileName(d)).apply { writeText(exporter.csv(d)) }
                val uri = FileProvider.getUriForFile(context, context.packageName + ".export", file)
                val send =
                    Intent(Intent.ACTION_SEND)
                        .setType(GlucoseCsv.MIME)
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // the chooser passes the grant on with the clip
                send.clipData = ClipData.newRawUri(file.name, uri)
                Intent.createChooser(send, chooserTitle)
            }
        }

    companion object {
        /** Empties the share folder (also at app start): a shared file never lingers in the cache. */
        fun exportDir(context: Context): File = File(context.cacheDir, "export").apply { deleteRecursively() }
    }
}

/** The stateful page: the view model, the file picker and the share sheet. */
@Composable
fun ExportPage(
    viewModel: ExportViewModel,
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved = stringResource(R.string.in_export_saved)
    val chooser = stringResource(R.string.in_export_share_chooser)
    val failedText = stringResource(R.string.in_export_failed)

    fun failed() = Toast.makeText(context, failedText, Toast.LENGTH_LONG).show()
    val saver =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(GlucoseCsv.MIME)) { uri ->
            val d = ui.data
            if (uri != null && d != null) {
                scope.launch {
                    viewModel
                        .save(
                            context,
                            uri,
                            d,
                        ).onSuccess { Toast.makeText(context, saved, Toast.LENGTH_SHORT).show() }
                        .onFailure { failed() }
                }
            }
        }
    ExportContent(
        ui,
        modifier = modifier,
        actions =
            ExportActions(
                choose = viewModel::choose,
                save = { d -> saver.launch(viewModel.fileName(d)) },
                share = { d ->
                    scope.launch { viewModel.shareIntent(context, d, chooser).onSuccess(context::startActivity).onFailure { failed() } }
                },
            ),
    )
}

class ExportActions(
    val choose: (ExportPeriod) -> Unit = {},
    val save: (ExportData) -> Unit = {},
    val share: (ExportData) -> Unit = {},
)

/** Settings › Export data (both roles): the period, what the file will hold, Save file and Share. */
@Composable
fun ExportContent(
    ui: ExportUi,
    actions: ExportActions,
    modifier: Modifier = Modifier,
) = Column(modifier) {
    var choosing by remember { mutableStateOf(false) }
    val label = periodLabel()
    IntroText(stringResource(if (ui.follower) R.string.in_export_intro_follower else R.string.in_export_intro))
    SettingsGroup {
        ValueRow(stringResource(R.string.in_export_period), label(ui.period), { choosing = true })
        InfoRow(
            stringResource(R.string.in_export_contents),
            summary =
                ui.data?.let { d ->
                    pluralStringResource(R.plurals.in_export_readings, d.readingRows, d.readingRows) + " · " +
                        pluralStringResource(R.plurals.in_export_treatments, d.treatmentRows, d.treatmentRows)
                } ?: stringResource(R.string.in_export_counting),
        )
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        val d = ui.data
        Button(onClick = { d?.let(actions.save) }, enabled = d != null) {
            Icon(painterResource(R.drawable.in_ic_file_export), null, Modifier.size(ButtonDefaults.IconSize))
            Text(stringResource(R.string.in_export_save), Modifier.padding(start = ButtonDefaults.IconSpacing))
        }
        FilledTonalButton(onClick = { d?.let(actions.share) }, enabled = d != null) {
            Icon(painterResource(R.drawable.in_ic_share), null, Modifier.size(ButtonDefaults.IconSize))
            Text(stringResource(R.string.in_export_share), Modifier.padding(start = ButtonDefaults.IconSpacing))
        }
    }
    FooterText(stringResource(R.string.in_export_footer))
    if (choosing) {
        val periods =
            listOfNotNull(ExportPeriod.Sensor.takeIf { ui.sensorKnown }) + ExportPeriod.DAYS.map(ExportPeriod::Days) + ExportPeriod.All
        ChoiceDialog(
            stringResource(R.string.in_export_period),
            periods.map { Choice(label(it), it) },
            ui.period,
            { choosing = false },
        ) { actions.choose(it) }
    }
}

@Composable
private fun periodLabel(): (ExportPeriod) -> String {
    val sensor = stringResource(R.string.in_export_period_sensor)
    val all = stringResource(R.string.in_export_period_all)
    val context = LocalContext.current
    return { p ->
        when (p) {
            ExportPeriod.Sensor -> sensor
            ExportPeriod.All -> all
            is ExportPeriod.Days -> context.resources.getQuantityString(R.plurals.in_export_period_days, p.days, p.days)
        }
    }
}
