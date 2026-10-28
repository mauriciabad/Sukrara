// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import app.mauri.sukrara.core.testing.InMemorySettingsRepository
import app.mauri.sukrara.graph.stats.GlucosePoint
import app.mauri.sukrara.graph.stats.TirRange
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** [GraphSettings.config]: the person's `graph.*` settings reach every card surface's style, and the drawn card. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GraphSettingsTest {
    private val settings = InMemorySettingsRepository()
    private val now = 1_800_000_000L

    @Before
    fun setUp() = RoundedFont.init(RuntimeEnvironment.getApplication())

    @Test fun defaultsAreTheSchemas() =
        runTest {
            assertEquals(GraphConfig(), GraphSettings.config(settings).first())
        }

    @Test fun everyReadKeyLandsInTheConfig() =
        runTest {
            settings.set(GraphSettings.Hours, 6.0)
            settings.set(GraphSettings.GridHours, 2.0)
            settings.set(GraphSettings.RingCount, 1.0)
            settings.set(GraphSettings.Side, false)
            settings.set(GraphSettings.ColumnCrown, false)
            settings.set(GraphSettings.RingMarks, false)
            settings.set(GraphSettings.RingRange, "TIGHT")
            settings.set(GraphSettings.LastPlace, "END")
            val c = GraphSettings.config(settings).first()
            assertEquals(6, c.hours(GraphPlace.APP))
            assertEquals(6, c.hours(GraphPlace.WIDGETS))
            assertEquals(2, c.gridHours)
            assertEquals(1, c.rings().size)
            assertFalse(c.side || c.columnCrown || c.ringMarks)
            assertEquals(TirRange.TIGHT, c.ringRange)
            assertEquals(LastPlace.END, c.lastPlace)
        }

    @Test fun theStructuredKeysLandInTheConfig() =
        runTest {
            settings.set(GraphSettings.Slots, "GMI:1209600:0,AVERAGE:604800:1,CV:2592000:0")
            settings.set(GraphSettings.RingOrder, "VERY_HIGH,HIGH,TARGET,TIGHT,LOW,VERY_LOW")
            settings.set(GraphSettings.Lines, "120,80")
            settings.set(GraphSettings.LastElse, "")
            settings.set(GraphSettings.Marks, "rapid:point,long:point,carbs:point+amount,note:")
            settings.set(GraphSettings.PlaceHours.getValue(GraphPlace.NOTIFICATION), 3.0)
            settings.set(GraphSettings.SensorTime.getValue(GraphPlace.WIDGETS), false)
            val c = GraphSettings.config(settings).first()
            assertEquals(listOf(SlotKind.GMI, SlotKind.AVERAGE, SlotKind.CV), c.slots.map { it.kind })
            assertFalse(c.slots[0].faded)
            assertEquals(
                "VERY_HIGH,HIGH,TARGET,TIGHT,LOW,VERY_LOW",
                GraphText.ringOrderText(c.ringOrder),
            )
            assertEquals(listOf(80, 120), c.lines)
            assertEquals(null, c.lastElse)
            assertTrue(c.marks.has("carbs", MarkShow.AMOUNT))
            assertFalse(c.marks.has("rapid", MarkShow.LINE))
            assertEquals(3, c.hours(GraphPlace.NOTIFICATION))
            assertEquals(c.defaultHours, c.hours(GraphPlace.APP))
            assertFalse(c.sensorTime(GraphPlace.WIDGETS))
            assertTrue(c.sensorTime(GraphPlace.APP))
        }

    @Test fun damagedTextReadsAsTheDefault() =
        runTest {
            settings.set(GraphSettings.Slots, "TIR:86400:1")
            settings.set(GraphSettings.RingOrder, "TIGHT,TIGHT,LOW")
            settings.set(GraphSettings.Lines, "abc,5,100,100,999")
            val c = GraphSettings.config(settings).first()
            assertEquals(GraphConfig.DEFAULT_SLOTS, c.slots)
            assertEquals(RingSpec.DEFAULT_RING_ORDER, c.ringOrder)
            assertEquals(listOf(100), c.lines)
        }

    @Test fun bandsStayInOrderWhateverIsStored() =
        runTest {
            // a high range below the low one can't be drawn: the config clamps it like the renderer does
            settings.set(GraphSettings.Bands[1], 90.0)
            settings.set(GraphSettings.Bands[3], 95.0)
            val b = GraphSettings.config(settings).first().bands
            assertTrue("$b", b.low < b.tight && b.tight <= b.high && b.high < b.veryHigh)
        }

    /** A changed range changes the card the widgets and home draw (not only the settings page). */
    @Test fun aChangedRangeChangesTheDrawnCard() =
        runTest {
            val model =
                CardModel(
                    readings = (0..60).map { GlucosePoint(now - (60 - it) * 60L, 170, 0.0) },
                    treatments = emptyList(),
                    nowS = now,
                    words = Scenes.words(),
                )

            fun draw(c: GraphConfig) =
                GlucoseCard.image(
                    200f,
                    100f,
                    2f,
                    model,
                    widgetCardStyle(CardSurface.WIDGET_VALUE, c),
                    GraphPalette.of(false),
                    CardSurface.WIDGET_VALUE,
                )

            val before = GraphSettings.config(settings).first()
            // "high above" 160: the reading of 170 is high now
            settings.set(GraphSettings.Bands[3], 160.0)
            val after = GraphSettings.config(settings).first()
            assertEquals(160, after.bands.high)
            assertEquals(after.bands, after.appPanelStyle().bands)
            assertEquals(after.bands, widgetCardStyle(CardSurface.WIDGET_GRAPH, after).graph?.bands)
            assertFalse("the value's colour follows the new range", draw(before).sameAs(draw(after)))
        }
}
