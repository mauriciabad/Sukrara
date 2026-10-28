// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sensor.libreview

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mauri.sukrara.designsystem.haptics.rememberHaptics
import app.mauri.sukrara.designsystem.motion.Swap
import app.mauri.sukrara.feature.sensor.R

/** The LibreView sign-in route (from the new-sensor flow and Settings › Sensor). [onBack] leaves it. */
@Composable
fun LibreViewSignInRoute(
    viewModel: LibreViewSignInViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    BackHandler(onBack = onBack)
    LibreViewSignInContent(
        ui,
        modifier,
        canSubmit = viewModel.canSubmit,
        onSignIn = viewModel::signIn,
        onSignOut = {
            haptics.confirm()
            viewModel.signOut()
        },
        onBack = onBack,
    )
}

/** Stateless (screenshots render it). The password lives only in this form (never saved state) and the one call. */
@Composable
fun LibreViewSignInContent(
    ui: SignInUi,
    modifier: Modifier = Modifier,
    canSubmit: (String, String) -> Boolean,
    onSignIn: (String, String) -> Unit,
    onSignOut: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.libreview_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
        Swap(ui.page) { page ->
            when {
                page == Page.Loading -> CircularProgressIndicator()
                page == Page.SignedIn && ui is SignInUi.SignedIn -> SignedIn(ui.email, onSignOut, onBack)
                else -> Form(ui, canSubmit, onSignIn, onBack)
            }
        }
    }
}

@Composable
private fun SignedIn(email: String, onSignOut: () -> Unit, onBack: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            stringResource(R.string.libreview_signed_in_as, email),
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.libreview_sign_out_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onBack, Modifier.fillMaxWidth()) { Text(stringResource(R.string.newsensor_action_done)) }
        OutlinedButton(onSignOut, Modifier.fillMaxWidth()) { Text(stringResource(R.string.libreview_sign_out)) }
    }
}

@Composable
private fun Form(
    ui: SignInUi,
    canSubmit: (String, String) -> Boolean,
    onSignIn: (String, String) -> Unit,
    onBack: () -> Unit,
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    val working = ui == SignInUi.Working
    val submit = {
        if (canSubmit(email, password)) {
            onSignIn(email, password)
            password = ""
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            stringResource(R.string.libreview_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            email,
            { email = it },
            Modifier.fillMaxWidth().semantics { contentType = ContentType.EmailAddress },
            enabled = !working,
            label = { Text(stringResource(R.string.libreview_email)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        )
        OutlinedTextField(
            password,
            { password = it },
            Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
            enabled = !working,
            label = { Text(stringResource(R.string.libreview_password)) },
            singleLine = true,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
        )
        // under the field, not inside it: the largest font would squeeze the field's label
        TextButton({ showPassword = !showPassword }) {
            Text(stringResource(if (showPassword) R.string.libreview_hide_password else R.string.libreview_show_password))
        }
        (ui as? SignInUi.SignedOut)?.error?.let { error ->
            Text(
                stringResource(error.message),
                Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Button(submit, Modifier.fillMaxWidth(), enabled = !working && canSubmit(email, password)) {
            Text(stringResource(if (working) R.string.libreview_signing_in else R.string.libreview_sign_in))
        }
        TextButton(onBack, Modifier.fillMaxWidth()) { Text(stringResource(R.string.libreview_back)) }
    }
}

private val SignInError.message: Int
    get() = when (this) {
        SignInError.WrongPassword -> R.string.libreview_error_wrong_password
        SignInError.AcceptTerms -> R.string.libreview_error_accept_terms
        SignInError.Failed -> R.string.libreview_error_failed
    }

/** One page per kind of state (the form keeps its fields across Working and an error). */
private enum class Page { Loading, Form, SignedIn }

private val SignInUi.page: Page
    get() = when (this) {
        SignInUi.Loading -> Page.Loading
        is SignInUi.SignedIn -> Page.SignedIn
        else -> Page.Form
    }
