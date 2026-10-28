// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing.backup

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPasswordOption
import androidx.credentials.PasswordCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import kotlinx.coroutines.CancellationException

/**
 * The phone's password manager (Credential Manager), for the sharing identity: Backup saves it as a password, setup
 * restores from it. The system shows its own sheet; nothing is logged (an error's message could quote the request).
 */
object PasswordManager {
    enum class Saved { Saved, Cancelled, Failed }

    sealed interface Got {
        /** What the password manager returned: an nsec / ncryptsec to restore from. Never logged or saved. */
        class Secret(val text: String) : Got {
            override fun toString() = "Secret"
        }

        data object NoneSaved : Got

        data object Cancelled : Got

        data object Failed : Got
    }

    suspend fun save(
        activity: Activity,
        id: String,
        secret: String,
    ): Saved =
        try {
            CredentialManager.create(activity).createCredential(activity, CreatePasswordRequest(id, secret))
            Saved.Saved
        } catch (e: CancellationException) {
            throw e
        } catch (_: CreateCredentialCancellationException) {
            Saved.Cancelled
        } catch (_: CreateCredentialException) {
            Saved.Failed
        }

    suspend fun get(activity: Activity): Got =
        try {
            val result = CredentialManager.create(activity).getCredential(activity, GetCredentialRequest(listOf(GetPasswordOption())))
            (result.credential as? PasswordCredential)?.password?.let(Got::Secret) ?: Got.Failed
        } catch (e: CancellationException) {
            throw e
        } catch (_: NoCredentialException) {
            Got.NoneSaved
        } catch (_: GetCredentialCancellationException) {
            Got.Cancelled
        } catch (_: GetCredentialException) {
            Got.Failed
        }
}

/** The activity behind a composable's context (Credential Manager shows its sheet over it). */
tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
