// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing

import app.mauri.sukrara.feature.sharing.share.ApproveUi
import app.mauri.sukrara.feature.sharing.share.InviteUi
import app.mauri.sukrara.feature.sharing.share.LaterRequests
import app.mauri.sukrara.feature.sharing.share.SharingViewModel
import app.mauri.sukrara.feature.sharing.share.labelMatches
import app.mauri.sukrara.feature.sharing.share.requestToShow
import app.mauri.sukrara.feature.sharing.share.shownName
import app.mauri.sukrara.share.owner.ApproveResult
import app.mauri.sukrara.share.owner.OwnerShareState
import app.mauri.sukrara.share.owner.PendingRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        vm.approve(PendingRequest("k1", "A", "t", "1111", at = 1))
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

    @Test
    fun `a refused Allow says so, and a second tap while it runs does nothing`() {
        val fake = FakeOwnerSharing().apply { approveResult = ApproveResult.Gone }
        val vm = SharingViewModel(fake)
        val request = PendingRequest("k1", "A", "t", "1234", at = 1, others = 1)
        vm.approve(request, "1234")
        assertEquals(ApproveUi(request, working = false, refused = ApproveResult.Gone), vm.approve.value)
        assertEquals(listOf("approve:k1:1234"), fake.calls)
        vm.approveSeen()
        assertNull(vm.approve.value)
    }

    @Test
    fun `the name hint matches loosely and only with a label`() {
        assertEquals(true, labelMatches("Mom's phone", "Mom"))
        assertEquals(true, labelMatches("Dad", "dad"))
        assertEquals(false, labelMatches("Dad", "Anna"))
        assertEquals(null, labelMatches("  ", "Anna"))
        assertEquals(false, labelMatches("Dad", ""))
    }

    @Test
    fun `a name someone else chose loses control and direction characters`() {
        assertEquals("Mom", shownName("\u202EMo\u0000m\n"))
    }

    @Test
    fun `the oldest request not put off is shown, Later hides it until reviewed`() {
        val a = PendingRequest("a", "A", "t1", "1111", at = 20)
        val b = PendingRequest("b", "B", "t2", "2222", at = 10)
        val s = OwnerShareState.Sharing(1, emptyList(), listOf(a, b), emptyMap(), 0, 0)
        val later = LaterRequests()
        assertEquals(b, requestToShow(s, later.ids.value))
        later.later("b")
        assertEquals(a, requestToShow(s, later.ids.value))
        later.later("a")
        assertNull(requestToShow(s, later.ids.value))
        later.review("b")
        assertEquals(b, requestToShow(s, later.ids.value))
        assertNull(requestToShow(OwnerShareState.NoIdentity, emptySet()))
    }
}
