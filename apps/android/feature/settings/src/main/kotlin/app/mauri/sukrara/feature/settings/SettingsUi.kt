// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.alerts.AlertKind
import app.mauri.sukrara.core.designsystem.R as DsR
import app.mauri.sukrara.graph.Clock
import app.mauri.sukrara.settings.NumberSpec
import java.time.Instant
import java.time.ZoneId

/**
 * A settings page: large collapsing title, a back button, the groups scrolling below. [scroll] false: the content
 * scrolls itself (a page from another feature, placed here by the app) and fills the space below the bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(
    title: String,
    onBack: () -> Unit,
    scroll: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val bar = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(bar.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeTopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.st_ic_arrow_back), contentDescription = stringResource(R.string.st_back))
                    }
                },
                scrollBehavior = bar,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        },
    ) { padding ->
        if (scroll) {
            Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
                content()
                Spacer(Modifier.height(24.dp))
            }
        } else {
            // the bar and the system bars are already padded: the page's own inset padding adds nothing twice
            Column(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize(), content = content)
        }
    }
}

/**
 * A persistent state with its exits (Mute all, a side muted, Remind less): what is on, until when, and the buttons that
 * end or change it. Announced politely by TalkBack when it appears.
 */
@Composable
fun StateStrip(
    text: String,
    actions: List<Pair<String, () -> Unit>>,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.forEachIndexed { i, (label, onClick) ->
                    if (i == 0) Button(onClick = onClick) { Text(label) } else TextButton(onClick = onClick) { Text(label) }
                }
            }
        }
    }
}

/** An alert kind's name ("Very low", "No data"). */
@Composable
fun kindName(kind: AlertKind): String =
    stringResource(
        when (kind) {
            AlertKind.VERY_LOW -> R.string.st_kind_very_low
            AlertKind.LOW -> R.string.st_kind_low
            AlertKind.HIGH -> R.string.st_kind_high
            AlertKind.VERY_HIGH -> R.string.st_kind_very_high
            AlertKind.EXTREMELY_HIGH -> R.string.st_kind_extremely_high
            AlertKind.FALLING_FAST -> R.string.st_kind_falling_fast
            AlertKind.RISING_FAST -> R.string.st_kind_rising_fast
            AlertKind.NO_DATA -> R.string.st_kind_no_data
        },
    )

/**
 * Labels number values as the dialogs and rows show them: the special word ("Once", "Until you turn it off") or
 * "70 mg/dL" / "30 min". A plain function (dialog option labels aren't composable), built from this locale's strings.
 */
@Composable
fun numberLabeler(): (NumberSpec, Double) -> String {
    val words =
        mapOf(
            "once" to stringResource(R.string.st_special_once),
            "off" to stringResource(R.string.st_special_off),
            "none" to stringResource(R.string.st_special_none),
            "until_turned_off" to stringResource(R.string.st_special_until_turned_off),
            "same_as_default" to stringResource(R.string.st_special_default),
        )
    val units =
        mapOf(
            "h" to stringResource(DsR.string.ds_unit_h),
            "d" to stringResource(DsR.string.ds_unit_d),
            "min" to stringResource(DsR.string.ds_unit_min),
            "s" to stringResource(DsR.string.ds_unit_s),
        )
    return { spec, value ->
        spec.specialOf(value)?.let { words[it] ?: it }
            ?: listOf(spec.text(value), units[spec.unit] ?: spec.unit).filter { it.isNotEmpty() }.joinToString(" ")
    }
}

/** One number value's label (see [numberLabeler]). */
@Composable
fun numberLabel(
    spec: NumberSpec,
    value: Double,
): String = numberLabeler()(spec, value)

/** "Below 70 mg/dL", "Above 180 mg/dL", "Falling faster than 2.0 mg/dL/min", "No reading for 20 min". */
@Composable
fun thresholdSentence(
    kind: AlertKind,
    value: Double,
): String {
    val v = numberLabel(SchemaPrefs.thresholdSpec(kind), value)
    return when {
        kind == AlertKind.NO_DATA -> stringResource(R.string.st_threshold_no_data, v)
        kind == AlertKind.FALLING_FAST -> stringResource(R.string.st_threshold_falling, v)
        kind == AlertKind.RISING_FAST -> stringResource(R.string.st_threshold_rising, v)
        kind.isBelowThreshold -> stringResource(R.string.st_threshold_below, v)
        else -> stringResource(R.string.st_threshold_above, v)
    }
}

/** A sound pack's name. */
@Composable
fun packName(pack: String): String =
    when (pack) {
        "arcade" -> stringResource(R.string.st_pack_arcade)
        "kart" -> stringResource(R.string.st_pack_kart)
        "quest" -> stringResource(R.string.st_pack_quest)
        "soft" -> stringResource(R.string.st_pack_soft)
        "system" -> stringResource(R.string.st_pack_system)
        "none" -> stringResource(R.string.st_pack_none)
        else -> pack
    }

/** A clock time with the phone's 24-hour setting ("14:30" / "2:30 PM"), through THE clock formatter. */
fun clockTime(
    at: Instant,
    zone: ZoneId,
): String = Clock.time(at.epochSecond, zone = zone)

/** Opens a web page in the browser. */
fun Context.openUrl(url: String) {
    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Opens this app's notification settings (where every alert channel's sound and Do Not Disturb override live). */
fun Context.openNotificationSettings() {
    startActivity(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/** The threshold input of [kind]: its schema format, refusing a value that would cross a neighbouring level. */
@Composable
fun thresholdInput(
    settings: app.mauri.sukrara.alerts.AlertSettings,
    kind: AlertKind,
): app.mauri.sukrara.designsystem.dialog.NumberInput {
    val spec = SchemaPrefs.thresholdSpec(kind)
    val between = stringResource(R.string.st_threshold_between)
    return app.mauri.sukrara.designsystem.dialog.NumberInput(
        spec,
        presets = spec.presets.filter { AlertEdits.thresholdConflict(settings, kind, it) == null },
        reject = { v ->
            AlertEdits.thresholdConflict(settings, kind, v)?.let { (lo, hi) ->
                between.format(lo?.let(spec::text) ?: spec.text(spec.min), hi?.let(spec::text) ?: spec.text(spec.max))
            }
        },
    )
}
