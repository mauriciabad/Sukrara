// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing.setup

import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.feature.sharing.setup.SetupStep.Alerts
import app.mauri.sukrara.feature.sharing.setup.SetupStep.Identity
import app.mauri.sukrara.feature.sharing.setup.SetupStep.Permissions
import app.mauri.sukrara.feature.sharing.setup.SetupStep.Sensor
import app.mauri.sukrara.feature.sharing.setup.SetupStep.Welcome
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupMachineTest {
    private val none = SetupFacts(role = null, followerAvailable = false, ownSensorLive = false, hasIdentity = false)
    private val facts = none.copy(role = Role.Wearer)
    private val m = SetupMachine

    private fun wearer(): SetupRecord = m.completeWelcome(SetupRecord())

    @Test
    fun `the exact step order`() {
        assertEquals(listOf(Welcome, SetupStep.Role, Permissions, Sensor, Alerts, Identity), m.steps(facts))
    }

    @Test
    fun `walk resumes at the first step neither done nor skipped`() {
        var r = SetupRecord()
        assertEquals(Welcome, m.next(r, none))
        r = m.completeWelcome(r)
        assertEquals(SetupStep.Role, m.next(r, none))
        assertEquals(Result.Done(Role.Wearer), m.chooseRole(Role.Wearer, confirmed = true, none))
        assertEquals(Permissions, m.next(r, facts))
        r = m.completePermissions(r)
        assertEquals(Sensor, m.next(r, facts))
        // after a process death the persisted record resumes at the same step
        val restored = Json.decodeFromString(SetupRecord.serializer(), Json.encodeToString(SetupRecord.serializer(), r))
        assertEquals(Sensor, m.next(restored, facts))
    }

    @Test
    fun `role needs confirmation`() {
        val r = m.completeWelcome(SetupRecord())
        assertEquals(Result.Refused(RoleRefusal.NotConfirmed), m.chooseRole(Role.Wearer, confirmed = false, none))
        assertEquals(SetupStep.Role, m.next(r, none))
    }

    @Test
    fun `follower stays on the role step while following isn't in this build`() {
        assertEquals(Result.Refused(RoleRefusal.FollowerNotAvailable), m.chooseRole(Role.Follower, confirmed = true, none))
        val f = none.copy(followerAvailable = true)
        assertEquals(Result.Done(Role.Follower), m.chooseRole(Role.Follower, confirmed = true, f))
        val follower = f.copy(role = Role.Follower)
        assertEquals(Permissions, m.next(m.completeWelcome(SetupRecord()), follower))
        assertTrue(Sensor !in m.steps(follower))
    }

    @Test
    fun `a damaged setup file never asks for the role again`() {
        assertEquals(Permissions, m.next(SetupRecord(), facts))
    }

    @Test
    fun `welcome and role can't be skipped`() {
        val r = SetupRecord()
        assertEquals(r, m.skip(r, Welcome))
        assertEquals(r, m.skip(r, SetupStep.Role))
    }

    @Test
    fun `sensor completes only with a live own reading`() {
        val r = m.completePermissions(wearer())
        assertEquals(Sensor, m.next(m.completeSensor(r, facts), facts))
        val live = facts.copy(ownSensorLive = true)
        assertEquals(Alerts, m.next(m.completeSensor(r, live), live))
    }

    @Test
    fun `alert step completes only with Confirm`() {
        var r = m.skip(m.completePermissions(wearer()), Sensor)
        assertEquals(Alerts, m.next(r, facts))
        r = m.skip(r, Alerts)
        assertTrue(FinishItem.AlertsNotConfirmed in m.finishItems(r, facts))
        r = m.confirmAlerts(m.reopen(r, Alerts))
        assertTrue(FinishItem.AlertsNotConfirmed !in m.finishItems(r, facts))
    }

    @Test
    fun `identity completes only once an identity exists`() {
        val r = m.confirmAlerts(m.skip(m.completePermissions(wearer()), Sensor))
        assertEquals(Identity, m.next(m.completeIdentity(r, facts), facts))
        val shared = facts.copy(hasIdentity = true)
        assertNull(m.next(m.completeIdentity(r, shared), shared))
    }

    @Test
    fun `skipped steps become Finish-setup items and drop out when their fact changes`() {
        var r = wearer()
        for (s in listOf(Permissions, Sensor, Alerts, Identity)) r = m.skip(r, s)
        assertNull(m.next(r, facts))
        assertEquals(FinishItem.entries, m.finishItems(r, facts))
        val later = facts.copy(ownSensorLive = true, hasIdentity = true)
        assertEquals(listOf(FinishItem.AllowAlerts, FinishItem.AlertsNotConfirmed), m.finishItems(r, later))
    }

    @Test
    fun `a reopened item shows its step again, then back to home`() {
        var r = wearer()
        for (s in listOf(Permissions, Sensor, Alerts, Identity)) r = m.skip(r, s)
        r = m.reopen(r, Alerts)
        assertEquals(Alerts, m.next(r, facts))
        r = m.skip(r, Alerts)
        assertNull(m.next(r, facts))
    }

    @Test
    fun `thresholds confirmed in Settings complete the alert step and its Finish-setup item`() {
        val confirmed = facts.copy(alertsConfirmed = true)
        val r = m.skip(m.skip(m.completePermissions(wearer()), Sensor), Alerts)
        assertTrue(FinishItem.AlertsNotConfirmed !in m.finishItems(r, confirmed))
        assertEquals(Identity, m.next(m.reopen(r, Alerts), confirmed))
    }

    @Test
    fun `no Finish-setup items before a role`() {
        assertEquals(emptyList<FinishItem>(), m.finishItems(SetupRecord(skipped = setOf(Alerts)), none))
    }
}
