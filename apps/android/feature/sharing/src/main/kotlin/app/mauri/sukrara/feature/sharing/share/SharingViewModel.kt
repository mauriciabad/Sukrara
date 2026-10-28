// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.mauri.sukrara.feature.sharing.Invite
import app.mauri.sukrara.feature.sharing.OwnerSharing
import app.mauri.sukrara.share.owner.OwnerShareState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The invite being made on the share screen. */
sealed interface InviteUi {
    data object None : InviteUi

    data object Creating : InviteUi

    data class Ready(val invite: Invite) : InviteUi

    /** No identity, a conflict, or the relays refused: say so, offer Try again. */
    data object Failed : InviteUi
}

/** Share screen, approve dialog and followers page: all actions go through [OwnerSharing] (chunk 13's engine). */
class SharingViewModel(private val sharing: OwnerSharing) : ViewModel() {
    val state: StateFlow<OwnerShareState> = sharing.state

    private val _invite = MutableStateFlow<InviteUi>(InviteUi.None)
    val invite: StateFlow<InviteUi> = _invite.asStateFlow()

    fun createInvite(label: String) {
        _invite.value = InviteUi.Creating
        viewModelScope.launch {
            _invite.value = sharing.createInvite(label)?.let(InviteUi::Ready) ?: InviteUi.Failed
        }
    }

    /** Another invite for someone else (the shown one stays valid until used or expired). */
    fun newInvite() {
        _invite.value = InviteUi.None
    }

    fun approve(follower: String) = viewModelScope.launch { sharing.approve(follower) }

    fun deny(follower: String) = viewModelScope.launch { sharing.deny(follower) }

    fun remove(follower: String) = viewModelScope.launch { sharing.remove(follower) }

    fun retryConflict() {
        viewModelScope.launch { sharing.retryConflict() }
    }

    companion object {
        fun factory(sharing: OwnerSharing) = viewModelFactory { initializer { SharingViewModel(sharing) } }
    }
}
