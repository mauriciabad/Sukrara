// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.designsystem.state

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.core.designsystem.R

/** Which kind of "nothing to show" a [StateScreen] explains (it picks the icon and colour). */
enum class StateKind { Empty, NoData, Error }

/**
 * A screen or panel with nothing to show yet: why ([reason], in plain words) and the way forward ([action]) — never a
 * blank area. Errors use the error colour; the icon is decorative (the title says it).
 */
@Composable
fun StateScreen(
    kind: StateKind,
    title: String,
    reason: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    val icon =
        when (kind) {
            StateKind.Empty -> R.drawable.ds_ic_empty
            StateKind.NoData -> R.drawable.ds_ic_no_data
            StateKind.Error -> R.drawable.ds_ic_error
        }
    val tint = if (kind == StateKind.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(48.dp))
        Text(
            title,
            Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            reason,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        action?.let { Button(onClick = onAction) { Text(it) } }
    }
}
