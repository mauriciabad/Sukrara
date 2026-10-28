// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.systemui

import app.mauri.sukrara.core.data.settings.HookPosition
import app.mauri.sukrara.core.model.GlucoseState
import app.mauri.sukrara.core.model.ModuleState
import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.SensorState
import app.mauri.sukrara.core.model.SourceId
import app.mauri.sukrara.graph.CardSurface
import app.mauri.sukrara.graph.GlucoseCard
import app.mauri.sukrara.graph.GlucoseLook
import app.mauri.sukrara.graph.GraphPalette
import app.mauri.sukrara.graph.stats.Bands
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The root status bar: icon choice per freshness state, daemon commands, placements, the module's text and state. */
class SystemUiTest {
    private val now = Instant.parse("2026-10-28T12:00:00Z")
    private val nowS = now.epochSecond

    private fun state(
        mgdl: Int? = 138,
        ageS: Long = 30,
        rate: Double? = 1.2,
        sensor: SensorState = SensorState.Active(null),
    ): GlucoseState {
        val reading = mgdl?.let { Reading(now.minusSeconds(ageS), it, rate, SourceId.Libre3) }
        return GlucoseState.Initial.copy(role = Role.Wearer, latest = reading, recent = listOfNotNull(reading), sensor = sensor)
    }

    private fun icons(s: GlucoseState) = SlotIcons.plan(RootStatusBar.look(s, nowS), "11:58")

    private val seen = mutableSetOf<CardSurface>()

    @After fun tearDown() {
        GlucoseCard.faceObserver = null
    }

    // --- system icons ------------------------------------------------------------------------------------------

    @Test fun `a fresh value is its number and its arrow`() {
        val p = icons(state(138, rate = 0.0))
        assertEquals("sb_n138", p.number)
        assertEquals("sb_t18", p.trend) // flat: straight right, the middle of 37 steps
        assertTrue(SlotIcons.isValue(p.desc))
        assertTrue(SlotIcons.isTrend(p.trendDesc))
        assertFalse(SlotIcons.isValue(p.trendDesc))
    }

    @Test fun `no rate is the no-trend ring`() = assertEquals("sb_t_none", icons(state(rate = null)).trend)

    @Test fun `from 5 minutes the number is struck beside the struck ring`() {
        val p = icons(state(138, ageS = 6 * 60))
        assertEquals("sb_s138", p.number)
        assertEquals("sb_t_none_s", p.trend)
        assertTrue(p.desc.endsWith("(old)"))
    }

    @Test fun `from 20 minutes only the no-readings icon`() {
        val p = icons(state(138, ageS = 21 * 60))
        assertNull(p.number)
        assertEquals("monitor_ic_no_readings", p.trend)
        assertTrue(SlotIcons.isOurs(p.desc))
        assertTrue(p.desc.endsWith("11:58"))
    }

    @Test fun `an ended sensor is the flag and a warm-up shows nothing`() {
        assertEquals(SlotIcons.Plan("monitor_ic_sensor_ended", null, "Sukrara: sensor ended"), icons(state(sensor = SensorState.Ended)))
        val warming = state(mgdl = null, sensor = SensorState.WarmingUp(now.plusSeconds(1800)))
        assertEquals(SlotIcons.Plan.EMPTY, icons(warming))
        assertEquals(SlotIcons.Plan.EMPTY, icons(state(mgdl = null)))
    }

    @Test fun `LO and HI have their own drawables`() {
        assertEquals("sb_lo", icons(state(39)).number)
        assertEquals("sb_hi", icons(state(501)).number)
        assertEquals("sb_shi", icons(state(501, ageS = 6 * 60)).number)
    }

    @Test fun `the arrow steps by 5 degrees and doubles from 3`() {
        assertEquals("sb_t00", SlotIcons.trendDrawable(2.99))
        assertEquals("sb_t36", SlotIcons.trendDrawable(-2.99))
        assertEquals("sb_t09", SlotIcons.trendDrawable(1.5))
        assertEquals("sb_t_up2", SlotIcons.trendDrawable(3.0))
        assertEquals("sb_t_down2", SlotIcons.trendDrawable(-4.0))
    }

    @Test fun `every drawable a plan can name exists`() {
        val res = java.io.File("src/main/res/drawable")
        val names = mutableSetOf<String>()
        for (v in 40..400) {
            names += "sb_n$v"
            names += "sb_s$v"
        }
        names += listOf("sb_lo", "sb_hi", "sb_slo", "sb_shi", "sb_t_none", "sb_t_none_s", "sb_t_up2", "sb_t_down2")
        names += (0..36).map { "sb_t%02d".format(it) }
        names += listOf("monitor_ic_no_readings", "monitor_ic_sensor_ended")
        for (n in names) assertTrue("missing drawable $n", java.io.File(res, "$n.xml").isFile)
    }

    @Test fun `commands send the trend first, then the number, and remove what is gone`() {
        val ids = mapOf("sb_t18" to 7, "sb_n138" to 9)
        val cmds = SlotIcons.commands(icons(state(138, rate = 0.0)), { ids.getValue(it) })
        assertEquals("set ${SlotIcons.SLOT_TREND} 7 0 Sukrara glucose trend →", cmds[0])
        assertEquals("set ${SlotIcons.SLOT_NUMBER} 9 0 Sukrara glucose 138 mg/dL →", cmds[1])
        val gone = SlotIcons.commands(SlotIcons.Plan.EMPTY) { 1 }
        assertEquals(listOf("remove ${SlotIcons.SLOT_TREND}", "remove ${SlotIcons.SLOT_NUMBER}"), gone)
        // every command the app builds is one the daemon accepts
        (cmds + gone).forEach { assertTrue(it, DaemonProtocol.parse(it) != null) }
    }

    @Test fun `the daemon refuses other slots, resource 0 and garbage`() {
        assertEquals(
            DaemonProtocol.Command.Set(SlotIcons.SLOT_NUMBER, 5, "a b c"),
            DaemonProtocol.parse("set ${SlotIcons.SLOT_NUMBER} 5 0 a b c"),
        )
        assertNull(DaemonProtocol.parse("set glucose 5 0 x")) // another app's slot
        assertNull(DaemonProtocol.parse("set ${SlotIcons.SLOT_NUMBER} 0 0 x"))
        assertNull(DaemonProtocol.parse("set ${SlotIcons.SLOT_NUMBER} abc 0 x"))
        assertNull(DaemonProtocol.parse("remove wifi"))
        assertNull(DaemonProtocol.parse("reboot"))
        assertEquals(DaemonProtocol.Command.Order(true), DaemonProtocol.parse(DaemonProtocol.ORDER_MODULE))
        assertEquals(DaemonProtocol.Command.Ping, DaemonProtocol.parse(DaemonProtocol.PING))
    }

    // --- placements -------------------------------------------------------------------------------------------

    @Test fun `placements map to their target and needs`() {
        assertEquals("mobile" to true, HookPosition.target(HookPosition.DEFAULT))
        assertEquals("wifi" to false, HookPosition.target("slot:wifi"))
        assertNull(HookPosition.target(HookPosition.SLOT_PREFIX))
        assertNull(HookPosition.target(HookPosition.AFTER_CLOCK))
        assertFalse(HookPosition.needsModule(HookPosition.SLOT_PREFIX))
        assertTrue(HookPosition.needsModule(HookPosition.DEFAULT))
        assertTrue(HookPosition.isSystemIcons(HookPosition.DEFAULT))
        assertFalse(HookPosition.isSystemIcons(HookPosition.LEFT_OF_ICONS))
        assertTrue(HookPosition.DEFAULT in HookPosition.ALL)
        assertEquals(9, HookPosition.ALL.toSet().size)
    }

    // --- the module's text ------------------------------------------------------------------------------------

    private fun hook(
        mgdl: Int = 138,
        ageS: Long = 30,
        rate: Double? = 1.2,
        ended: Boolean = false,
        readyAt: Long = 0,
        position: String = HookPosition.AFTER_CLOCK,
    ) = HookState(true, position, true, true, Bands(), mgdl, nowS - ageS, rate, ended, readyAt)

    @Test fun `the hook text follows the card's decision`() {
        GlucoseCard.faceObserver = { s, _ -> seen += s }
        val white = 0xFFFFFFFF.toInt()
        val fresh = HookText.of(HookText.face(hook(), nowS), true, null, white)
        assertEquals(HookText.Render("138", false, 1f, white, HookText.Mark.ARROW, "Glucose 138", 1.2), fresh)
        val struck = HookText.of(HookText.face(hook(ageS = 6 * 60), nowS), true, 0xFFFF0000.toInt(), white)
        assertEquals(true, struck.strike)
        assertEquals(0.5f, struck.alpha)
        assertEquals(white, struck.color) // struck: never the range colour
        assertEquals(HookText.Mark.RING, struck.mark)
        assertEquals(HookText.Mark.NO_READINGS, HookText.of(HookText.face(hook(ageS = 25 * 60), nowS), true, null, white).mark)
        assertEquals(HookText.Mark.FLAG, HookText.of(HookText.face(hook(ended = true), nowS), true, null, white).mark)
        val warm = HookText.face(hook(mgdl = 0, readyAt = nowS + 1800), nowS)
        assertEquals(GlucoseLook.State.WARMING_UP, warm.state)
        assertTrue(CardSurface.STATUS_HOOK in seen)
        RootStatusBar.look(state(), nowS)
        assertTrue(CardSurface.STATUS_ICONS in seen)
    }

    @Test fun `range colour only out of range, from the palette that reads on the bar`() {
        val bands = Bands()
        val white = 0xFFFFFFFF.toInt()
        assertNull(HookText.rangeColor(120, bands, white))
        assertEquals(GraphPalette.DARK.at(bands, 55), HookText.rangeColor(55, bands, white))
        assertEquals(GraphPalette.LIGHT.at(bands, 250), HookText.rangeColor(250, bands, 0xFF000000.toInt()))
        // system icons: only fresh values, only with colour on and a slot placement
        val icons = hook(mgdl = 250, position = HookPosition.DEFAULT)
        assertEquals(GraphPalette.DARK.at(bands, 250), HookText.iconColor(icons, nowS, white))
        assertNull(HookText.iconColor(icons.copy(color = false), nowS, white))
        assertNull(HookText.iconColor(icons.copy(atS = nowS - 6 * 60), nowS, white))
        assertNull(HookText.iconColor(icons.copy(position = HookPosition.AFTER_CLOCK), nowS, white))
    }

    @Test fun `the state sent to System UI is the hub's`() {
        val p = RootPrefs(on = true, position = HookPosition.DEFAULT, color = false, hideIcon = true)
        val s = RootStatusBar.hookState(state(138, rate = -1.0), p, Bands())
        assertEquals(138, s.mgdl)
        assertEquals(-1.0, s.rate!!, 0.0)
        assertTrue(s.systemIcons)
        assertFalse(s.color)
        val w = RootStatusBar.hookState(state(mgdl = null, sensor = SensorState.WarmingUp(now.plusSeconds(60))), p, Bands())
        assertEquals(nowS + 60, w.readyAtS)
        assertTrue(RootStatusBar.hookState(state(sensor = SensorState.Ended), p, Bands()).sensorEnded)
    }

    @Test fun `the value never vanishes from the bar when the icons can't show`() {
        val slot = hook(position = HookPosition.DEFAULT)
        // icons on screen: no text, the notification icon may go
        assertFalse(slot.copy(iconsShown = true).text)
        assertTrue(slot.copy(iconsShown = true).valueShown)
        // no root / daemon failed: the module's text stands in, so hiding the notification icon is still fine
        assertTrue(slot.text)
        assertTrue(slot.valueShown)
        // no reading and no icons: nothing shows, so the notification icon stays
        assertFalse(slot.copy(mgdl = 0, atS = 0).valueShown)
        assertFalse(slot.copy(enabled = false).valueShown)
        assertTrue(hook().text)
    }

    // --- root and the module ------------------------------------------------------------------------------------

    @Test fun `the module state comes from the hello first, then LSPosed`() {
        val inScope = SystemAccess.Lsposed("LSPosed 2", inScope = true)
        assertEquals(ModuleState.Active, SystemAccess.decide(inScope, "abc", "abc"))
        assertEquals(ModuleState.Update, SystemAccess.decide(inScope, "old", "abc"))
        assertEquals(ModuleState.Restart, SystemAccess.decide(inScope, null, "abc"))
        assertEquals(ModuleState.NoScope, SystemAccess.decide(inScope.copy(inScope = false), null, "abc"))
        assertEquals(ModuleState.Missing, SystemAccess.decide(null, null, "abc"))
        assertEquals(ModuleState.Checking, SystemAccess.decide(null, null, "abc", waiting = true))
        // a hello without LSPosed's service still counts: System UI runs it
        assertEquals(ModuleState.Active, SystemAccess.decide(null, "abc", "abc"))
        assertTrue(ModuleState.Update.needsRestart && ModuleState.Update.active)
        assertFalse(ModuleState.Active.needsRestart)
    }

    @Test fun `a failed restart says why`() {
        assertNull(SystemAccess.restartError(0, ""))
        assertEquals(SystemAccess.TIMED_OUT, SystemAccess.restartError(null, ""))
        assertEquals("Permission denied", SystemAccess.restartError(1, "su: x\nPermission denied\n"))
        assertEquals("su exit 2", SystemAccess.restartError(2, ""))
    }

    @Test fun `the log gate lets 40 lines a minute through`() {
        val g = LogGate()
        assertEquals(40, (1..100).count { g.allow(1_000L) })
        assertTrue(g.allow(62_000L))
    }
}
