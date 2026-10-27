// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.widgets

import app.mauri.sukrara.core.model.AlertUiState
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.GlucoseState
import app.mauri.sukrara.core.model.LinkState
import app.mauri.sukrara.core.model.SourceStatus
import app.mauri.sukrara.core.model.Treatment
import app.mauri.sukrara.graph.Freshness
import app.mauri.sukrara.graph.GraphConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import java.time.Instant

/** Everything a widget's picture is drawn from, at [now]; [dark] = the night palette. */
data class WidgetFrame(
    val glucose: GlucoseState,
    val treatments: List<Treatment>,
    val config: GraphConfig,
    val placements: WidgetPlacements,
    val now: Instant,
    val dark: Boolean,
)

/** The inputs of a frame, before the clock and the theme are read. */
data class WidgetInputs(
    val glucose: GlucoseState,
    val treatments: List<Treatment>,
    val config: GraphConfig,
    val placements: WidgetPlacements,
)

/**
 * Redraws the widgets only when what they show changes: the latest reading, its freshness step (monitor's
 * FreshnessTicker alarm sends [freshnessTicks]), the sensor, *Mute all*, the treatments, the settings or the theme.
 * While the screen is off nothing is drawn (no launcher shows it); when it comes back on, one redraw catches up if
 * anything changed. No polling: it reacts only to its inputs.
 */
class WidgetUpdater(
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val glucose: Flow<GlucoseState>,
    private val treatments: Flow<List<Treatment>>,
    private val config: Flow<GraphConfig>,
    private val placements: Flow<WidgetPlacements>,
    private val freshnessTicks: Flow<Unit>,
    private val screenOn: Flow<Boolean>,
    private val dark: () -> Boolean,
    private val redraw: suspend () -> Unit,
) {
    private val latestInputs = MutableStateFlow<WidgetInputs?>(null)
    private val drawnFrame = MutableStateFlow<WidgetFrame?>(null)
    private var drawnKey: DrawKey? = null

    /** The frame the widgets show; set just before every redraw. */
    val frame: StateFlow<WidgetFrame?> = drawnFrame.asStateFlow()

    private var job: Job? = null

    fun start() {
        if (job != null) return
        job =
            scope.launch {
                val inputs = combine(glucose, treatments, config, placements, ::WidgetInputs)
                combine(inputs, freshnessTicks.onStart { emit(Unit) }, screenOn) { i, _, on -> i to on }
                    .collect { (i, on) -> onChange(i, on) }
            }
    }

    private suspend fun onChange(
        inputs: WidgetInputs,
        screenOn: Boolean,
    ) {
        latestInputs.value = inputs
        val now = clock.now()
        val night = dark()
        val key = DrawKey.of(inputs, now, night)
        if (key == drawnKey || !screenOn) return
        drawnKey = key
        drawnFrame.value = WidgetFrame(inputs.glucose, inputs.treatments, inputs.config, inputs.placements, now, night)
        redraw()
    }

    /**
     * The frame to compose now, also when nothing was drawn yet in this process (a system update after a restart,
     * possibly with the screen off): built from the latest inputs.
     */
    suspend fun current(): WidgetFrame {
        drawnFrame.value?.let { return it }
        val i = latestInputs.filterNotNull().first()
        return WidgetFrame(i.glucose, i.treatments, i.config, i.placements, clock.now(), dark())
    }

    /** What a picture depends on: equal keys draw the same picture. */
    internal data class DrawKey(
        val inputs: WidgetInputs,
        val freshnessStep: Long?,
        val dark: Boolean,
    ) {
        companion object {
            fun of(
                inputs: WidgetInputs,
                now: Instant,
                dark: Boolean,
            ): DrawKey {
                // the next step's time is constant within one step, so it identifies the step the reading is in
                val step =
                    Freshness.nextChangeAt(
                        inputs.glucose.latest
                            ?.t
                            ?.epochSecond,
                        now.epochSecond,
                    )
                return DrawKey(inputs.copy(glucose = inputs.glucose.drawn()), step, dark)
            }

            /** Only what the picture, its description and its tap use (not the link's heartbeat or the service). */
            private fun GlucoseState.drawn() =
                GlucoseState(
                    role = role,
                    latest = latest,
                    recent = recent,
                    sensor = sensor,
                    source = SourceStatus.NotStarted,
                    alerts = AlertUiState(firingKinds = alerts.firingKinds, mutedUntil = alerts.mutedUntil),
                    link = link?.let { LinkState(it.wearerName, lastHeardAt = null, revoked = it.revoked) },
                )
        }
    }
}
