// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.settings.search

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.designsystem.haptics.rememberHaptics
import app.mauri.sukrara.designsystem.rows.InfoRow
import app.mauri.sukrara.designsystem.rows.NavigationRow
import app.mauri.sukrara.designsystem.rows.SettingsGroup
import app.mauri.sukrara.feature.settings.R
import app.mauri.sukrara.feature.settings.SettingsRoute
import java.text.Normalizer
import app.mauri.sukrara.core.designsystem.R as DsR

/** A [SearchEntry] with its words in the current language. */
data class SearchItem(
    val title: String,
    val summary: String?,
    val page: String,
    val target: SearchTarget,
)

/**
 * Settings search, pure: case and accents don't matter ("alarma" finds "Alarma", "ultimo" finds "Último"), every
 * word of the query must start a word of the row (title, summary or page). Rows whose title matches come first, then
 * those matched by their summary, then by their page; in index order within each.
 */
object SettingsSearch {
    /** Lower case without accents: "Último Azúcar" → "ultimo azucar". */
    fun fold(text: String): String =
        Normalizer
            .normalize(text, Normalizer.Form.NFD)
            .replace(Marks, "")
            .lowercase()

    private val Marks = Regex("\\p{Mn}+")
    private val Separators = Regex("[^\\p{L}\\p{N}]+")

    private fun words(text: String?): List<String> = text?.let { fold(it).split(Separators).filter(String::isNotEmpty) }.orEmpty()

    fun match(
        query: String,
        items: List<SearchItem>,
    ): List<SearchItem> {
        val q = words(query)
        if (q.isEmpty()) return emptyList()

        fun covers(ws: List<String>) = q.all { part -> ws.any { it.startsWith(part) } }
        return items
            .mapNotNull { item ->
                val title = words(item.title)
                val summary = title + words(item.summary)
                val all = summary + words(item.page)
                when {
                    covers(title) -> 0 to item
                    covers(summary) -> 1 to item
                    covers(all) -> 2 to item
                    else -> null
                }
            }.sortedBy { it.first } // stable: index order within a rank
            .map { it.second }
            .distinctBy { Triple(it.title, it.page, it.target) }
    }
}

/**
 * The search field at the top of the settings overview, with its results below it: "Page › row" rows that open the
 * page ([onOpen]) or another feature's page ([onOpenOther]). [onActiveChange] tells the overview to hide its rows
 * while a query is typed. Exits: the clear button, Back (clears the query first), opening a result.
 */
@Composable
fun SettingsSearchBar(
    onOpen: (SettingsRoute) -> Unit,
    onOpenOther: (OtherPage) -> Unit,
    modifier: Modifier = Modifier,
    pens: Boolean = false,
    onActiveChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val haptics = rememberHaptics()
    var query by rememberSaveable { mutableStateOf("") }
    val items =
        remember(context, pens) {
            SettingsIndex.entries.filter { pens || !it.pensOnly }.map { e ->
                SearchItem(context.getString(e.title), e.summary?.let(context::getString), context.getString(e.page), e.target)
            }
        }
    val results = remember(query, items) { SettingsSearch.match(query, items) }
    val active = query.isNotBlank()
    val activeChanged by rememberUpdatedState(onActiveChange)
    LaunchedEffect(active) { activeChanged(active) }
    BackHandler(enabled = active) {
        query = ""
        focus.clearFocus()
    }
    val clear = stringResource(R.string.st_search_clear)
    Column(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.st_search_hint)) },
            leadingIcon = { Icon(painterResource(DsR.drawable.ds_ic_search), contentDescription = null) },
            trailingIcon = {
                if (active) {
                    IconButton(onClick = {
                        haptics.confirm()
                        query = ""
                    }) { Icon(painterResource(DsR.drawable.ds_ic_close), contentDescription = clear) }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            colors =
                OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceBright,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceBright,
                    unfocusedBorderColor = MaterialTheme.colorScheme.surfaceBright,
                ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        )
        AnimatedVisibility(active) {
            SettingsGroup(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                if (results.isEmpty()) {
                    InfoRow(stringResource(R.string.st_search_none, query.trim()))
                }
                for (r in results) {
                    NavigationRow(
                        r.title,
                        {
                            haptics.confirm()
                            focus.clearFocus()
                            when (val t = r.target) {
                                is SearchTarget.Page -> onOpen(t.route)
                                is SearchTarget.Other -> onOpenOther(t.page)
                            }
                        },
                        // the breadcrumb first ("Alerts › Mute all" reads as page, then row), then what the row does
                        summary = listOfNotNull(stringResource(R.string.st_search_in, r.page), r.summary).joinToString(" · "),
                    )
                }
            }
        }
    }
}
