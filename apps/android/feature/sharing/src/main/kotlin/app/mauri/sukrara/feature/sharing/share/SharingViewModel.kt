// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.mauri.sukrara.feature.sharing.Invite
import app.mauri.sukrara.feature.sharing.OwnerSharing
import app.mauri.sukrara.feature.sharing.profile.ProfileSave
import app.mauri.sukrara.protocol.event.Profile
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

/** An Allow in progress or refused, for the request [follower]. */
data class ApproveUi(val follower: String, val working: Boolean, val failed: Boolean)

/**
 * Share screen, approve dialog, followers page, How you appear and Backup: every action goes through [OwnerSharing]
 * (the engine). Secrets never sit in this view model: the recovery code is returned to the dialog that shows it.
 */
class SharingViewModel(private val sharing: OwnerSharing) : ViewModel() {
    val state: StateFlow<OwnerShareState> = sharing.state

    private val _invite = MutableStateFlow<InviteUi>(InviteUi.None)
    val invite: StateFlow<InviteUi> = _invite.asStateFlow()

    private val _approve = MutableStateFlow<ApproveUi?>(null)
    val approve: StateFlow<ApproveUi?> = _approve.asStateFlow()

    private val _profileSave = MutableStateFlow(ProfileSave.Idle)
    val profileSave: StateFlow<ProfileSave> = _profileSave.asStateFlow()

    /** A "Can add entries" change that was refused (the switch shows the engine's value again; a note says why). */
    private val _writerRefused = MutableStateFlow<String?>(null)
    val writerRefused: StateFlow<String?> = _writerRefused.asStateFlow()

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

    /** Allow, once: a second tap while it runs does nothing (the dialog may already show the next request). */
    fun approve(follower: String, typedCode: String? = null) {
        if (_approve.value?.working == true) return
        _approve.value = ApproveUi(follower, working = true, failed = false)
        viewModelScope.launch {
            val ok = sharing.approve(follower, typedCode)
            _approve.value = if (ok) null else ApproveUi(follower, working = false, failed = true)
        }
    }

    fun deny(follower: String) {
        _approve.value = null
        viewModelScope.launch { sharing.deny(follower) }
    }

    fun remove(follower: String) = viewModelScope.launch { sharing.remove(follower) }

    fun setWriter(follower: String, allowed: Boolean) {
        _writerRefused.value = null
        viewModelScope.launch { if (!sharing.setWriter(follower, allowed)) _writerRefused.value = follower }
    }

    /** "How you appear": [onSaved] runs once the engine took it; a refusal keeps the edit and shows why. */
    fun setProfile(profile: Profile, onSaved: () -> Unit) {
        _profileSave.value = ProfileSave.Saving
        viewModelScope.launch {
            val ok = sharing.setProfile(profile)
            _profileSave.value = if (ok) ProfileSave.Idle else ProfileSave.Failed
            if (ok) onSaved()
        }
    }

    fun retryConflict() {
        viewModelScope.launch { sharing.retryConflict() }
    }

    /** The recovery code for [password] (slow: scrypt), or null. The caller keeps it only while it is shown. */
    suspend fun recoveryCode(password: String): String? = sharing.exportRecovery(password)

    fun recoverySaved() {
        viewModelScope.launch { sharing.recoverySaved() }
    }

    /** Hands the identity to the password manager through [save] (the screen's Credential Manager call). */
    suspend fun saveToPasswordManager(save: suspend (id: String, secret: String) -> Boolean): Boolean = sharing.saveToPasswordManager(save)

    companion object {
        fun factory(sharing: OwnerSharing) = viewModelFactory { initializer { SharingViewModel(sharing) } }
    }
}
