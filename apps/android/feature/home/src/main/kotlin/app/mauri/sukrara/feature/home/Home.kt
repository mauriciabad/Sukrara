// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

/**
 * What the home screen needs from the app. `AppGraph` (`:app`) implements it; the module never reaches for a
 * global. Every feature module has one `XxxDeps` like this.
 */
interface HomeDeps {
    val appVersion: String
}

class HomeViewModel(deps: HomeDeps) : ViewModel() {
    val version: String = deps.appVersion

    companion object {
        fun factory(deps: HomeDeps) = viewModelFactory { initializer { HomeViewModel(deps) } }
    }
}

/** Placeholder home until chunk 16: says what the app is and leads to setup. */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenSetup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.home_placeholder_title, viewModel.version), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.home_placeholder_body), style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onOpenSetup) { Text(stringResource(R.string.home_open_setup)) }
    }
}
