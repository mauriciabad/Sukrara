// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.settings

import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.alerts.KindCfg
import app.mauri.sukrara.alerts.Kind
import app.mauri.sukrara.core.SensorLife
import app.mauri.sukrara.graph.Band
import app.mauri.sukrara.graph.Bands
import app.mauri.sukrara.graph.GraphConfig
import app.mauri.sukrara.graph.GraphPlace
import app.mauri.sukrara.graph.GraphSettings
import app.mauri.sukrara.graph.LastPlace
import app.mauri.sukrara.graph.RingSpec
import app.mauri.sukrara.graph.SlotKind
import app.mauri.sukrara.graph.TirRange
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Round f28: the wearer's settings event (PROTOCOL-v2 "Settings") and a follower's effective settings per
 * "Settings from" (spikes/settings/settings.json rows with `"mirror": true`). Robolectric: the stores touch
 * `core.Range` (android.graphics.Color) and the copy / reset helpers write the prefs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WearerSettingsTest {
    @Before @After fun reset() { WearerSettings.resetForTest(); GraphSettings.resetForTest() }

    /** Every mirrored field away from its default (bands still in order). */
    private val wearerGraph = GraphConfig(
        bands = Bands(50, 65, 120, 160, 220, 350), defaultHours = 7, ringCount = 3,
        slots = listOf(RingSpec(3 * 86400L, faded = false), RingSpec(10 * 86400L), RingSpec(30 * 86400L, kind = SlotKind.TIR)),
        ringRange = TirRange.TIGHT, ringOrder = Band.entries.reversed(), side = false, columnCrown = false, ringMarks = false,
        lines = listOf(90, 150), gridHours = 3, lastPlace = LastPlace.END, lastElse = RingSpec(5 * 86400L, kind = SlotKind.GMI),
        marks = app.mauri.sukrara.treatments.MarkShow.parse("rapid:amount+time,long:point,carbs:point+line+amount+time,note:"),
    )
    private val wearerCountdown = 13
    private val wearerAlerts = AlertSettings().let { d ->
        d.copy(kinds = d.kinds.mapValues { (k, c) ->
            if (k == Kind.NO_DATA) c else KindCfg(!c.on, if (k in Kind.RATES) 1.5 else c.value + 3, c.repeatMin + 1, c.alarm)
        }, muteInsulin = false, muteInsulinMin = 90, muteFalling = false, muteFallingMin = 33, muteCarbs = false, muteCarbsMin = 21)
    }

    /** The follower's own: local fields set, mirrored fields elsewhere again. */
    private val followerGraph = GraphConfig(defaultHours = 24, gridHours = 6, placeHours = mapOf(GraphPlace.WIDGETS to 5),
        sensorHidden = setOf(GraphPlace.APP))
    private val followerAlerts = AlertSettings().let { d ->
        d.copy(on = false, kinds = d.kinds + (Kind.NO_DATA to KindCfg(true, 37.0, 17, alarm = true)) +
            (Kind.LOW to d.cfg(Kind.LOW).copy(value = 80.0, alarm = true)), pack = "kart", shuffle = false, actionMuteMin = 61, relaxMin = 45)
    }

    private fun wearerContent(u: Long) = MirroredSettings.content(MirroredSettings.body(wearerGraph, wearerCountdown, wearerAlerts), u)

    private fun path(o: JSONObject, p: List<String>): Any? = p.fold(o as Any?) { cur, k -> (cur as? JSONObject)?.opt(k) }

    @Test fun everyMirroredRowTravelsAndIsApplied() {
        val c = JSONObject(wearerContent(100).toString())   // through text, as on the wire
        assertEquals(2, c.getInt("v")); assertEquals("settings", c.getString("type")); assertEquals(100L, c.getLong("u"))
        // every mirrored schema row is in the content, and this test moves each one away from its default
        val defaults = MirroredSettings.defaults()
        val rows = SettingsSpec.SETTINGS.filter { it.mirror }
        assertTrue(rows.isNotEmpty())
        for (k in MirroredSettings.GRAPH_KEYS) {
            assertTrue("graph.$k missing", c.getJSONObject("graph").has(k))
            assertNotEquals("graph.$k is the default in this test", defaults.getJSONObject("graph").get(k).toString(), c.getJSONObject("graph").get(k).toString())
        }
        for (p in MirroredSettings.ALERT_PATHS) {
            assertTrue("alerts.$p missing", path(c.getJSONObject("alerts"), p) != null)
            assertNotEquals("alerts.$p is the default in this test", path(defaults.getJSONObject("alerts"), p).toString(), path(c.getJSONObject("alerts"), p).toString())
        }
        // no local key travels
        assertFalse(c.getJSONObject("graph").has("placeHours")); assertFalse(c.getJSONObject("graph").has("sensorHidden"))
        assertFalse(c.getJSONObject("alerts").getJSONObject("alerts").has(Kind.NO_DATA))
        assertFalse(c.getJSONObject("alerts").has("sounds")); assertFalse(c.getJSONObject("alerts").has("on"))
        assertFalse(c.getJSONObject("alerts").getJSONObject("alerts").getJSONObject(Kind.LOW).has("alarm"))

        WearerSettings.setFollower(true)
        assertTrue(WearerSettings.received(c))
        val g = WearerSettings.effectiveGraph(followerGraph)
        assertEquals(wearerGraph.copy(placeHours = followerGraph.placeHours, sensorHidden = followerGraph.sensorHidden), g)
        assertEquals(wearerCountdown, WearerSettings.effectiveCountdown(4))
        val a = WearerSettings.effectiveAlerts(followerAlerts)
        for (k in Kind.ALL - Kind.NO_DATA) {
            assertEquals(k, wearerAlerts.cfg(k).copy(alarm = followerAlerts.cfg(k).alarm), a.cfg(k))
        }
        assertEquals(followerAlerts.cfg(Kind.NO_DATA), a.cfg(Kind.NO_DATA))
        assertEquals(listOf(false, 90, false, 33, false, 21),
            listOf(a.muteInsulin, a.muteInsulinMin, a.muteFalling, a.muteFallingMin, a.muteCarbs, a.muteCarbsMin))
        // local alert fields stay this phone's
        assertEquals(listOf(false, "kart", false, 61, 45), listOf(a.on, a.pack, a.shuffle, a.actionMuteMin, a.relaxMin))
        assertEquals("wearer", a.source)
    }

    @Test fun effectiveSettingsPerMode() {
        WearerSettings.setFollower(true)
        WearerSettings.received(wearerContent(100))
        WearerSettings.setMode(WearerSettings.DEFAULT)
        val d = GraphConfig()
        assertEquals(d.copy(placeHours = followerGraph.placeHours, sensorHidden = followerGraph.sensorHidden), WearerSettings.effectiveGraph(followerGraph))
        assertEquals(SensorLife.DEFAULT_COUNTDOWN_HOURS, WearerSettings.effectiveCountdown(4))
        val a = WearerSettings.effectiveAlerts(followerAlerts)
        assertEquals(AlertSettings.DEFAULTS.getValue(Kind.LOW).copy(alarm = true), a.cfg(Kind.LOW))
        assertEquals(followerAlerts.cfg(Kind.NO_DATA), a.cfg(Kind.NO_DATA))
        assertEquals("own", a.source)

        WearerSettings.setMode(WearerSettings.CUSTOM)
        assertEquals(followerGraph, WearerSettings.effectiveGraph(followerGraph))
        assertEquals(4, WearerSettings.effectiveCountdown(4))
        assertEquals(followerAlerts.copy(source = "own"), WearerSettings.effectiveAlerts(followerAlerts))

        // Wearer mode before anything arrived: the defaults
        WearerSettings.forgetWearer()
        WearerSettings.setMode(WearerSettings.WEARER)
        assertEquals(d.copy(placeHours = followerGraph.placeHours, sensorHidden = followerGraph.sensorHidden), WearerSettings.effectiveGraph(followerGraph))
        assertEquals("wearer", WearerSettings.effectiveAlerts(followerAlerts).source)
    }

    @Test fun theWearersRoleUsesItsOwn() {
        WearerSettings.received(wearerContent(100))
        for (m in WearerSettings.MODES) {
            WearerSettings.setMode(m)
            assertEquals(followerGraph, WearerSettings.effectiveGraph(followerGraph))
            assertEquals(4, WearerSettings.effectiveCountdown(4))
        }
    }

    @Test fun graphSettingsStateIsEffectiveAndUpdateEditsOwn() {
        GraphSettings.update { followerGraph }
        assertEquals(followerGraph, GraphSettings.value)   // the wearer's role
        WearerSettings.setFollower(true)
        WearerSettings.received(wearerContent(100))       // recomputes
        assertEquals(wearerGraph.defaultHours, GraphSettings.value.defaultHours)
        assertEquals(followerGraph, GraphSettings.own.value)
        GraphSettings.update { it.copy(placeHours = mapOf(GraphPlace.APP to 3)) }   // a local field, in Wearer mode
        assertEquals(mapOf(GraphPlace.APP to 3), GraphSettings.value.placeHours)
        assertEquals(wearerGraph.bands, GraphSettings.value.bands)
        WearerSettings.setMode(WearerSettings.CUSTOM)
        assertEquals(GraphSettings.own.value, GraphSettings.value)
    }

    @Test fun anOlderOrSameUIsIgnored() {
        assertTrue(WearerSettings.received(wearerContent(100)))
        assertFalse(WearerSettings.received(MirroredSettings.content(MirroredSettings.defaults(), 99)))
        assertFalse(WearerSettings.received(MirroredSettings.content(MirroredSettings.defaults(), 100)))
        assertEquals(100L, WearerSettings.wearer.value!!.u)
        assertFalse(WearerSettings.received(JSONObject(wearerContent(200).toString()).put("type", "alert")))
        assertTrue(WearerSettings.received(MirroredSettings.content(MirroredSettings.defaults(), 101)))
        assertEquals(GraphConfig().defaultHours, WearerSettings.wearer.value!!.graph.getInt("defaultHours"))
    }

    @Test fun outgoingMovesUOnlyWhenTheBodyChanges() {
        val a = WearerSettings.outgoing(wearerGraph, 8, AlertSettings(), nowS = 1000)
        assertEquals(1000L, a.getLong("u"))
        assertEquals(1000L, WearerSettings.outgoing(wearerGraph, 8, AlertSettings(), nowS = 2000).getLong("u"))
        // a local field isn't part of it
        assertEquals(1000L, WearerSettings.outgoing(wearerGraph.copy(placeHours = mapOf(GraphPlace.APP to 3)), 8, AlertSettings(), nowS = 2000).getLong("u"))
        assertEquals(3000L, WearerSettings.outgoing(wearerGraph, 9, AlertSettings(), nowS = 3000).getLong("u"))
        assertEquals(3001L, WearerSettings.outgoing(wearerGraph, 10, AlertSettings(), nowS = 3000).getLong("u"))   // strictly newer
    }

    /** The owner engine publishes on every [WearerSettings.ownBodies] emission whose `u` moved (OwnerEngine.publishSettings). */
    @Test fun anOwnChangeIsANewBodyToPublish() = runBlocking {
        val before = WearerSettings.ownBodies().first()
        val u1 = WearerSettings.outgoing(nowS = 1000).getLong("u")
        GraphSettings.update { it.copy(gridHours = 4) }
        val after = WearerSettings.ownBodies().first()
        assertNotEquals(before, after)
        assertEquals(4, JSONObject(after).getJSONObject("graph").getInt("gridHours"))
        assertEquals(2000L, WearerSettings.outgoing(nowS = 2000).getLong("u").also { assertNotEquals(u1, it) })
        GraphSettings.update { it.copy(placeHours = mapOf(GraphPlace.NOTIFICATION to 2)) }   // local: nothing new
        assertEquals(after, WearerSettings.ownBodies().first())
    }

    @Test fun specValidCustomValuesSurviveTheEvent() {
        // custom (non-preset) values on step, in range: kept through body → content → effective
        val g = GraphConfig(defaultHours = 37, gridHours = 12, lines = listOf(41, 399),
            slots = listOf(RingSpec(89 * 86400L), RingSpec(43200L), RingSpec(2 * 86400L)))
        val a = AlertSettings().let { d -> d.copy(kinds = d.kinds + (Kind.FALLING_FAST to d.cfg(Kind.FALLING_FAST).copy(value = 4.9, repeatMin = 239)) +
            (Kind.VERY_LOW to d.cfg(Kind.VERY_LOW).copy(value = 41.0, repeatMin = 0)), muteInsulinMin = 359) }
        WearerSettings.setFollower(true)
        WearerSettings.received(JSONObject(MirroredSettings.content(MirroredSettings.body(g, 23, a), 5).toString()))
        val eg = WearerSettings.effectiveGraph(GraphConfig())
        assertEquals(listOf(37, 12), listOf(eg.defaultHours, eg.gridHours))
        assertEquals(listOf(41, 399), eg.lines)
        assertEquals(listOf(89 * 86400L, 43200L, 2 * 86400L), eg.slots.map { it.windowS })
        assertEquals(23, WearerSettings.effectiveCountdown(8))
        val ea = WearerSettings.effectiveAlerts(AlertSettings())
        assertEquals(4.9, ea.cfg(Kind.FALLING_FAST).value, 1e-9); assertEquals(239, ea.cfg(Kind.FALLING_FAST).repeatMin)
        assertEquals(41.0, ea.cfg(Kind.VERY_LOW).value, 1e-9); assertEquals(0, ea.cfg(Kind.VERY_LOW).repeatMin)
        assertEquals(359, ea.muteInsulinMin)
        // and a wearer's out-of-spec value comes back valid
        WearerSettings.received(JSONObject(MirroredSettings.content(MirroredSettings.body(g, 23, a), 6).toString())
            .also { it.getJSONObject("graph").put("defaultHours", 99).put("countdownHours", 0) })
        assertEquals(48, WearerSettings.effectiveGraph(GraphConfig()).defaultHours)
        assertEquals(1, WearerSettings.effectiveCountdown(8))
    }

    @Test fun copyWearerToCustomAndResetTouchOnlyMirroredFields() {
        val ctx = RuntimeEnvironment.getApplication()
        app.mauri.sukrara.alerts.Alerts.init(ctx)
        SensorLife.init(ctx)
        GraphSettings.init(ctx)
        WearerSettings.init(ctx)
        WearerSettings.setFollower(true)
        GraphSettings.update { followerGraph }
        app.mauri.sukrara.alerts.Alerts.update(ctx) { followerAlerts }
        SensorLife.setCountdownHours(ctx, 4)
        assertFalse("nothing to copy yet", WearerSettings.copyWearerToCustom(ctx))
        WearerSettings.received(wearerContent(100))
        WearerSettings.setMode(WearerSettings.CUSTOM)
        assertEquals(followerGraph, GraphSettings.value)

        assertTrue(WearerSettings.copyWearerToCustom(ctx))
        assertEquals(wearerGraph.copy(placeHours = followerGraph.placeHours, sensorHidden = followerGraph.sensorHidden), GraphSettings.own.value)
        assertEquals(GraphSettings.own.value, GraphSettings.value)   // Custom = own
        assertEquals(wearerCountdown, SensorLife.ownCountdown.value)
        val a = app.mauri.sukrara.alerts.Alerts.own.value
        assertEquals(wearerAlerts.cfg(Kind.HIGH).copy(alarm = followerAlerts.cfg(Kind.HIGH).alarm), a.cfg(Kind.HIGH))
        assertEquals(followerAlerts.cfg(Kind.NO_DATA), a.cfg(Kind.NO_DATA))
        assertEquals(listOf(false, "kart", 61), listOf(a.on, a.pack, a.actionMuteMin))
        assertEquals(90, app.mauri.sukrara.alerts.Alerts.settings.value.muteInsulinMin)

        WearerSettings.resetCustomToDefault(ctx)
        assertEquals(GraphConfig().copy(placeHours = followerGraph.placeHours, sensorHidden = followerGraph.sensorHidden), GraphSettings.own.value)
        assertEquals(SensorLife.DEFAULT_COUNTDOWN_HOURS, SensorLife.ownCountdown.value)
        val r = app.mauri.sukrara.alerts.Alerts.own.value
        assertEquals(AlertSettings.DEFAULTS.getValue(Kind.LOW).copy(alarm = true), r.cfg(Kind.LOW))
        assertEquals(followerAlerts.cfg(Kind.NO_DATA), r.cfg(Kind.NO_DATA))
        assertEquals(listOf(false, "kart", 61, 45), listOf(r.on, r.pack, r.actionMuteMin, r.relaxMin))

        // the alert source follows the mode, for own and effective alike
        WearerSettings.setMode(WearerSettings.WEARER)
        assertEquals("wearer", app.mauri.sukrara.alerts.Alerts.settings.value.source)
        assertEquals(wearerCountdown, SensorLife.countdown.value)
        assertEquals(SensorLife.DEFAULT_COUNTDOWN_HOURS, SensorLife.ownCountdown.value)
        WearerSettings.setMode(WearerSettings.DEFAULT)
        assertEquals("own", app.mauri.sukrara.alerts.Alerts.settings.value.source)
        WearerSettings.setMode(WearerSettings.WEARER)
        app.mauri.sukrara.alerts.Alerts.update(ctx) { AlertSettings() }
    }
}
