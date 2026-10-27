// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.widgets

import app.mauri.sukrara.core.model.storage.StateStore
import app.mauri.sukrara.graph.PlaceOverrides
import app.mauri.sukrara.graph.stats.TirRange
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One placed widget's own settings (settings.json `widget.overrides`), each field under its schema key; null = follow
 * the Widgets place's settings.
 */
@Serializable
data class WidgetOverrides(
    @SerialName("widgetHours") val hours: Int? = null,
    @SerialName("widgetRings") val rings: Int? = null,
    @SerialName("widgetRingRange") val ringRange: TirRange? = null,
    @SerialName("widgetSide") val side: Boolean? = null,
    @SerialName("widgetSensor") val sensorTime: Boolean? = null,
) {
    fun toPlace() = PlaceOverrides(hours, rings, ringRange, side, sensorTime)
}

/** Every placed widget's overrides by appWidgetId (the typed state file "widgets"). */
@Serializable
data class WidgetPlacements(
    val byId: Map<Int, WidgetOverrides> = emptyMap(),
) {
    operator fun get(appWidgetId: Int): WidgetOverrides = byId[appWidgetId] ?: WidgetOverrides()
}

/** Reads and writes [WidgetPlacements]; a removed widget's entry is forgotten so a reused id starts clean. */
class WidgetSettingsStore(
    private val store: StateStore<WidgetPlacements>,
) {
    val placements: Flow<WidgetPlacements> = store.data

    suspend fun get(appWidgetId: Int): WidgetOverrides = store.data.first()[appWidgetId]

    suspend fun set(
        appWidgetId: Int,
        overrides: WidgetOverrides,
    ) = store.update { p ->
        p.copy(byId = if (overrides == WidgetOverrides()) p.byId - appWidgetId else p.byId + (appWidgetId to overrides))
    }

    suspend fun forget(appWidgetIds: IntArray) = store.update { p -> p.copy(byId = p.byId - appWidgetIds.toSet()) }
}
