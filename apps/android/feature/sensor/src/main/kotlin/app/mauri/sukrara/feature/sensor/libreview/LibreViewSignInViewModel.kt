// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sensor.libreview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mauri.sukrara.sensor.libreview.SignInResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The LibreView account (receiver ID only; it stores the email and the receiver ID, never the password). */
interface LibreViewPort {
    val email: Flow<String?>

    suspend fun signIn(email: String, password: String): SignInResult

    suspend fun signOut()
}

enum class SignInError { WrongPassword, AcceptTerms, Failed }

sealed interface SignInUi {
    /** The saved account is being read (no form flashes for a signed-in person). */
    data object Loading : SignInUi

    data class SignedOut(val error: SignInError? = null) : SignInUi

    data object Working : SignInUi

    data class SignedIn(val email: String) : SignInUi
}

/**
 * LibreView sign-in for the account receiver ID. The password exists only in the form field and the one
 * call; nothing is uploaded. Signing out keeps a running sensor going; the next start needs a sign-in again.
 */
class LibreViewSignInViewModel(private val port: LibreViewPort) : ViewModel() {
    private val _ui = MutableStateFlow<SignInUi>(SignInUi.Loading)
    val ui: StateFlow<SignInUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            port.email.collect { email ->
                if (_ui.value != SignInUi.Working) _ui.value = email?.let(SignInUi::SignedIn) ?: SignInUi.SignedOut()
            }
        }
    }

    val canSubmit: (email: String, password: String) -> Boolean = { e, p -> e.contains('@') && p.isNotEmpty() }

    fun signIn(email: String, password: String) {
        if (_ui.value == SignInUi.Working || !canSubmit(email, password)) return
        _ui.value = SignInUi.Working
        viewModelScope.launch {
            _ui.value =
                when (port.signIn(email, password)) {
                    is SignInResult.SignedIn -> SignInUi.SignedIn(email.trim())

                    SignInResult.BadCredentials -> SignInUi.SignedOut(SignInError.WrongPassword)

                    SignInResult.AcceptTerms -> SignInUi.SignedOut(SignInError.AcceptTerms)

                    // offline, a server error, an answer it can't read: "couldn't sign in", never a guess at why
                    is SignInResult.Failed -> SignInUi.SignedOut(SignInError.Failed)
                }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            port.signOut()
            _ui.value = SignInUi.SignedOut()
        }
    }
}
