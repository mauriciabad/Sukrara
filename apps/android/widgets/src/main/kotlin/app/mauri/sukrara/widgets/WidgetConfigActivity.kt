// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.widgets

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.lifecycleScope
import app.mauri.sukrara.designsystem.dialog.Choice
import app.mauri.sukrara.designsystem.dialog.ChoiceDialog
import app.mauri.sukrara.designsystem.rows.SettingsGroup
import app.mauri.sukrara.designsystem.rows.ValueRow
import app.mauri.sukrara.designsystem.theme.SukraraTheme
import app.mauri.sukrara.graph.stats.TirRange
import kotlinx.coroutines.launch

/**
 * One placed widget's own settings: shown when it's placed (optional: back keeps the Widgets place's settings) and
 * from the launcher's Reconfigure. Every row has "Same as the app"; Save stores and redraws, Cancel (or back)
 * leaves the widget as it was.
 */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId =
            intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // OK from the start: the app's settings are valid, so leaving without saving keeps a just-placed widget (a
        // launcher that ignores configuration_optional would remove it on CANCELED)
        setResult(RESULT_OK, result(appWidgetId))
        val kind = kindOf(appWidgetId)
        if (kind == null) {
            finish()
            return
        }
        enableEdgeToEdge()
        val host = widgetsHost
        setContent {
            SukraraTheme {
                val initial by produceState<WidgetOverrides?>(null) {
                    host.awaitReady()
                    value = host.widgets.settings.get(appWidgetId)
                }
                initial?.let { start ->
                    WidgetSettingsScreen(
                        kind = kind,
                        initial = start,
                        preview = { o -> previewOf(kind, o) },
                        describe = { o -> describeCard(this, kind, host.widgets.updater.current(), o) },
                        onCancel = { finish() },
                        onSave = { o -> save(appWidgetId, kind, o) },
                    )
                }
            }
        }
    }

    private fun result(appWidgetId: Int) = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)

    private fun kindOf(appWidgetId: Int): WidgetKind? {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return null
        val provider =
            AppWidgetManager
                .getInstance(this)
                .getAppWidgetInfo(appWidgetId)
                ?.provider
                ?.className ?: return null
        return when (provider) {
            ValueWidgetReceiver::class.java.name -> WidgetKind.VALUE
            GraphWidgetReceiver::class.java.name -> WidgetKind.GRAPH
            CombinedWidgetReceiver::class.java.name -> WidgetKind.COMBINED
            else -> null
        }
    }

    private suspend fun previewOf(
        kind: WidgetKind,
        overrides: WidgetOverrides,
    ) = widgetsHost.widgets.updater
        .current()
        .let { frame -> renderWidget(this, kind, PREVIEW_W_DP, PREVIEW_H_DP, frame, overrides) }

    private fun save(
        appWidgetId: Int,
        kind: WidgetKind,
        overrides: WidgetOverrides,
    ) {
        lifecycleScope.launch {
            val widgets = widgetsHost.widgets
            widgets.saveOverrides(appWidgetId, overrides)
            // the updater redraws on the settings change; this placement now, in case the screen is off
            kind.widget().update(this@WidgetConfigActivity, GlanceAppWidgetManager(this@WidgetConfigActivity).getGlanceIdBy(appWidgetId))
            setResult(RESULT_OK, result(appWidgetId))
            finish()
        }
    }

    companion object {
        const val PREVIEW_W_DP = 320f
        const val PREVIEW_H_DP = 170f
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WidgetSettingsScreen(
    kind: WidgetKind,
    initial: WidgetOverrides,
    preview: suspend (WidgetOverrides) -> android.graphics.Bitmap,
    describe: suspend (WidgetOverrides) -> String,
    onCancel: () -> Unit,
    onSave: (WidgetOverrides) -> Unit,
) {
    var o by rememberSaveable(stateSaver = OverridesSaver) { mutableStateOf(initial) }
    val render by rememberUpdatedState(preview)
    val words by rememberUpdatedState(describe)
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val same = stringResource(R.string.widget_config_same)
    val on = stringResource(R.string.widget_config_on)
    val off = stringResource(R.string.widget_config_off)
    val hasGraph = kind != WidgetKind.VALUE
    val hasSlots = kind != WidgetKind.GRAPH
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.widget_config_title)) }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            val bitmap by produceState<android.graphics.Bitmap?>(null, o) { value = render(o) }
            val spoken by produceState("", o) { value = words(o) }
            bitmap?.let {
                Image(
                    it.asImageBitmap(),
                    contentDescription = stringResource(R.string.widget_config_preview, spoken),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                16.dp,
                            ).aspectRatio(WidgetConfigActivity.PREVIEW_W_DP / WidgetConfigActivity.PREVIEW_H_DP),
                )
            }
            SettingsGroup {
                if (hasGraph) {
                    ValueRow(stringResource(R.string.widget_config_hours), o.hours?.let { hoursLabel(it) } ?: same, { open = "hours" })
                    ValueRow(stringResource(R.string.widget_config_side), onOff(o.side, same, on, off), { open = "side" })
                }
                if (hasSlots) {
                    ValueRow(stringResource(R.string.widget_config_rings), o.rings?.toString() ?: same, { open = "rings" })
                    ValueRow(stringResource(R.string.widget_config_ring_range), rangeLabel(o.ringRange, same), { open = "range" })
                }
                ValueRow(stringResource(R.string.widget_config_sensor), onOff(o.sensorTime, same, on, off), { open = "sensor" })
            }
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End),
            ) {
                TextButton(onClick = { o = WidgetOverrides() }) { Text(stringResource(R.string.widget_config_reset)) }
                TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
                Button(onClick = { onSave(o) }) { Text(stringResource(R.string.widget_config_save)) }
            }
        }
    }
    when (open) {
        "hours" -> {
            ChoiceDialog(
                stringResource(R.string.widget_config_hours),
                listOf(Choice(same, null as Int?)) + HOURS.map { Choice(hoursLabel(it), it as Int?) },
                o.hours,
                { open = null },
            ) {
                o = o.copy(hours = it)
                open = null
            }
        }

        "rings" -> {
            ChoiceDialog(
                stringResource(R.string.widget_config_rings),
                listOf(Choice(same, null as Int?)) + (0..3).map { Choice(it.toString(), it as Int?) },
                o.rings,
                { open = null },
            ) {
                o = o.copy(rings = it)
                open = null
            }
        }

        "range" -> {
            ChoiceDialog(
                stringResource(R.string.widget_config_ring_range),
                listOf(null, TirRange.IN_RANGE, TirRange.TIGHT).map { Choice(rangeLabel(it, same), it) },
                o.ringRange,
                { open = null },
            ) {
                o = o.copy(ringRange = it)
                open = null
            }
        }

        "side" -> {
            OnOffDialog(stringResource(R.string.widget_config_side), o.side, same, on, off, { open = null }) {
                o = o.copy(side = it)
                open = null
            }
        }

        "sensor" -> {
            OnOffDialog(stringResource(R.string.widget_config_sensor), o.sensorTime, same, on, off, { open = null }) {
                o = o.copy(sensorTime = it)
                open = null
            }
        }
    }
}

@Composable
private fun OnOffDialog(
    title: String,
    value: Boolean?,
    same: String,
    on: String,
    off: String,
    onClose: () -> Unit,
    onChoose: (Boolean?) -> Unit,
) = ChoiceDialog(title, listOf(Choice(same, null), Choice(on, true), Choice(off, false)), value, onClose, onChoose)

private val HOURS = listOf(3, 6, 12, 24)

@Composable
private fun hoursLabel(h: Int) = pluralStringResource(R.plurals.widget_config_hours_n, h, h)

private fun onOff(
    v: Boolean?,
    same: String,
    on: String,
    off: String,
) = when (v) {
    null -> same
    true -> on
    false -> off
}

@Composable
private fun rangeLabel(
    r: TirRange?,
    same: String,
) = when (r) {
    null -> same
    TirRange.IN_RANGE -> stringResource(R.string.widget_config_range_in)
    TirRange.TIGHT -> stringResource(R.string.widget_config_range_tight)
}

/** Keeps the edited overrides across rotation (a list of the five fields). */
private val OverridesSaver =
    androidx.compose.runtime.saveable.Saver<WidgetOverrides, List<Any?>>(
        save = { listOf(it.hours, it.rings, it.ringRange?.name, it.side, it.sensorTime) },
        restore = { l ->
            WidgetOverrides(l[0] as Int?, l[1] as Int?, (l[2] as String?)?.let(TirRange::valueOf), l[3] as Boolean?, l[4] as Boolean?)
        },
    )
