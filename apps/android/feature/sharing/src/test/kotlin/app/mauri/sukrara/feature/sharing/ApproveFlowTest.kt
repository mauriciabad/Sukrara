// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing

import app.mauri.sukrara.feature.sharing.share.InviteUi
import app.mauri.sukrara.feature.sharing.share.SharingViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ApproveFlowTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `allow, deny and remove go to the engine for that follower only`() {
        val fake = FakeOwnerSharing()
        val vm = SharingViewModel(fake)
        vm.approve("k1")
        vm.deny("k2")
        vm.remove("k3")
        assertEquals(listOf("approve:k1", "deny:k2", "remove:k3"), fake.calls)
    }

    @Test
    fun `an invite shows ready, or failed with a way to try again`() {
        val fake = FakeOwnerSharing()
        val vm = SharingViewModel(fake)
        vm.createInvite("Anna")
        assertEquals(InviteUi.Ready(fake.invite!!), vm.invite.value)
        vm.newInvite()
        assertEquals(InviteUi.None, vm.invite.value)
        fake.invite = null
        vm.createInvite("")
        assertEquals(InviteUi.Failed, vm.invite.value)
    }
}
