// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.treatments

import android.app.PendingIntent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.mauri.sukrara.core.model.GlucoseState
import app.mauri.sukrara.graph.CardSurface
import app.mauri.sukrara.graph.Freshness
import app.mauri.sukrara.graph.GlucoseCard
import app.mauri.sukrara.graph.GlucoseLook
import app.mauri.sukrara.graph.face
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The Quick Settings tile "Add treatment" (surface `quick-tile`): Quick Settings open from the lock screen and the
 * quick dialog shows over it, so an entry needs no unlock (developer.android.com/develop/ui/views/quicksettings-tiles).
 * An action tile, not a toggle: inactive when this phone can add entries, unavailable when it can't (a follower without
 * "Can add entries"). Its subtitle is the glucose as the card shows it ([subtitle]), kept current while the shade is
 * open.
 */
class TreatmentTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listening: Job? = null

    override fun onStartListening() {
        val module = treatmentsModule
        listening?.cancel()
        listening =
            scope.launch {
                module.glucose.collectLatest { state ->
                    // the face's words change with time too (late, no reading since): redrawn each minute
                    while (true) {
                        show(state)
                        delay(MINUTE_MS)
                    }
                }
            }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun show(state: GlucoseState) {
        val tile = qsTile ?: return
        val canAdd = treatmentsModule.canAdd()
        tile.state = if (canAdd) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
        val nowS = System.currentTimeMillis() / 1000
        val look = GlucoseCard.face(state, nowS, CardSurface.QUICK_TILE)
        // TalkBack: a struck value is read as late, not as its combining characters
        tile.stateDescription =
            if (canAdd && look.state == GlucoseLook.State.VALUE && look.strike) getString(R.string.tile_late, look.text) else null
        tile.subtitle =
            if (!canAdd) {
                getString(R.string.tile_unavailable)
            } else {
                subtitle(look, state.latest?.t?.epochSecond, nowS) { words ->
                    when (words) {
                        TileWords.SensorEnded -> getString(R.string.tile_sensor_ended)
                        is TileWords.WarmingUp -> getString(R.string.tile_warming_up, words.left)
                        is TileWords.NoReadingSince -> getString(R.string.tile_no_reading_since, words.time)
                    }
                }
            }
        tile.updateTile()
    }

    override fun onClick() {
        val intent = QuickTreatmentIntents.open(this, QuickSource.Tile)
        startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
    }

    /** The tile's words for a state without a value to show. */
    sealed interface TileWords {
        data object SensorEnded : TileWords

        data class WarmingUp(val left: String) : TileWords

        data class NoReadingSince(val time: String) : TileWords
    }

    companion object {
        private const val MINUTE_MS = 60_000L

        /**
         * The tile's plain-text line for a [look] (`GlucoseCard.face`): `123 ↗`, struck from 5 min (`1̶2̶3̶ ○`: a tile
         * can't draw a line), "No reading since 00:32" from 20 min, "Sensor ended", "Warming up · 12 min"
         * ([words]); nothing before the first reading. [latestS] is the newest reading's time.
         */
        fun subtitle(
            look: GlucoseLook,
            latestS: Long?,
            nowS: Long,
            words: (TileWords) -> String,
        ): String? =
            when (look.state) {
                GlucoseLook.State.VALUE -> listOfNotNull(look.plain, look.arrowText).joinToString(" ")
                GlucoseLook.State.ENDED -> words(TileWords.SensorEnded)
                GlucoseLook.State.WARMING_UP -> words(TileWords.WarmingUp(look.warmup.orEmpty()))
                GlucoseLook.State.NO_READINGS -> latestS?.let { words(TileWords.NoReadingSince(Freshness.timeText(it, nowS))) }
                GlucoseLook.State.NONE -> null
            }
    }
}
