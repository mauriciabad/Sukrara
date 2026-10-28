// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.composeForPreview
import app.mauri.sukrara.core.model.GlucoseState
import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.SensorState
import app.mauri.sukrara.core.model.SourceId
import app.mauri.sukrara.graph.GraphConfig
import java.time.Duration
import java.time.Instant
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * The widget picker's and the pin dialog's pictures, drawn by the same renderer as the placed widgets
 * ([CardWidget.providePreview]) from a sample day: never the person's readings, and never shown as their glucose (the
 * picker and the launcher's "Add" dialog are the only places it appears). The static `previewLayout` stays for
 * launchers before Android 15.
 */

/** The sizes a kind's generated preview is composed at (the picker picks the closest one). */
internal val WidgetKind.previewSizes: Set<DpSize>
    get() =
        when (this) {
            WidgetKind.VALUE -> setOf(DpSize(110.dp, 48.dp), DpSize(250.dp, 80.dp))
            WidgetKind.GRAPH -> setOf(DpSize(250.dp, 110.dp), DpSize(350.dp, 160.dp))
            WidgetKind.COMBINED -> setOf(DpSize(250.dp, 170.dp), DpSize(350.dp, 220.dp))
        }

/** Where the sample readings come from (never stored: they exist only inside a preview picture). */
private val SAMPLE = SourceId("widget-preview")

/**
 * A sample day for the previews at [now]: in range, the newest reading 120 and slowly rising, a sensor with days
 * left. The same picture whenever it's drawn (the curve is anchored to the minute of the day).
 */
fun previewFrame(
    now: Instant,
    dark: Boolean,
): WidgetFrame {
    val end = Instant.ofEpochSecond(now.epochSecond / 60 * 60)
    val readings =
        (288 downTo 0).map { i ->
            val t = end.minusSeconds(i * 300L)
            // a 3-hour wave around 120 with a small ripple ([m] = minutes before now), rising at "now"
            val m = i * 5.0
            val v = 120 - 25 * sin(2 * PI * m / 180) + 4 * sin(2 * PI * m / 23)
            Reading(t, v.roundToInt(), 0.8, SAMPLE)
        }
    val glucose =
        GlucoseState.Initial.copy(
            role = Role.Wearer,
            latest = readings.last(),
            recent = readings,
            sensor = SensorState.Active(end.plus(Duration.ofDays(9))),
        )
    return WidgetFrame(glucose, emptyList(), GraphConfig(), WidgetPlacements(), end, dark)
}

/** The night palette is on for [context] (the picker draws the preview in the launcher's theme). */
internal fun isDark(context: Context) =
    context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

/** The kinds' receivers, the one place that maps a kind to its provider. */
internal val WidgetKind.receiver: Class<out GlanceAppWidgetReceiver>
    get() =
        when (this) {
            WidgetKind.VALUE -> ValueWidgetReceiver::class.java
            WidgetKind.GRAPH -> GraphWidgetReceiver::class.java
            WidgetKind.COMBINED -> CombinedWidgetReceiver::class.java
        }

/**
 * Android 15+: hands the picker the generated previews of every kind. The system rate-limits the call (about twice an
 * hour per provider), so a refused one just keeps the previous preview (or the static layout).
 */
suspend fun publishPreviews(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
    val manager = GlanceAppWidgetManager(context)
    for (kind in WidgetKind.entries) {
        runCatching { manager.setWidgetPreviews(kind.receiver.kotlin) }
            .onSuccess { if (it != GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_SUCCESS) Log.i(TAG, "preview ${kind.name}: $it") }
            .onFailure { Log.w(TAG, "preview ${kind.name}: $it") }
    }
}

/**
 * The pin request's extras: [kind]'s generated preview ([AppWidgetManager.EXTRA_APPWIDGET_PREVIEW]), or null when it
 * can't be composed (the launcher then shows its own default).
 */
suspend fun pinPreview(
    context: Context,
    kind: WidgetKind,
): Bundle? =
    runCatching {
        val provider = ComponentName(context, kind.receiver)
        val info =
            AppWidgetManager
                .getInstance(context)
                .getInstalledProvidersForPackage(context.packageName, null)
                .first { it.provider == provider }
        val views = kind.widget().composeForPreview(context, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN, info)
        Bundle().apply { putParcelable(AppWidgetManager.EXTRA_APPWIDGET_PREVIEW, views) }
    }.onFailure { Log.w(TAG, "pin preview: $it") }.getOrNull()

private const val TAG = "Sukrara.widgets"
