// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.home

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Home's panel as a live preview on the graph settings pages: the same card, the same readings and the same settings
 * flow, so a change on the page shows here at once (and on every other surface that reads the graph settings). No pill
 * actions: the preview only shows.
 */
@Composable
fun PanelPreview(
    viewModel: HomeViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val resources = LocalContext.current.resources
    PanelCard(
        glucose = state.glucose,
        history = state.history,
        treatments = state.chartTreatments,
        now = state.now,
        config = state.config,
        description = cardDescription(state, resources),
        modifier = modifier.padding(horizontal = 16.dp),
        pill = pillAction(state),
        pillWords = pillWords(state, resources),
    )
}
