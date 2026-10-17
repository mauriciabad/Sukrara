// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import java.text.Normalizer

/*
 * Settings search. The index is built from the pages themselves: starting at the overview, each page is composed
 * invisibly in index mode (SettingsUi.kt), which records every row (title, summary, group) and every settings page it
 * links to; those pages are indexed next. Adding, removing or renaming a setting changes the index with it, and a new
 * page is found as soon as a row links to it: nothing to keep in sync by hand (unlike a list of pages to index, which
 * PixelXpert's SearchPreference setup keeps in HeaderFragment).
 */

/** One result: a row on a page, how to get there, and its section's icon (AOSP results show the category's). */
class SettingsHit(val path: List<String>, val crumbs: List<String>, val row: IndexedRow, val icon: (@Composable () -> Unit)?) {
    val route get() = path.last()
}

/** The live index of everything reachable from [root]; [page] draws a route like the screen host does. */
class SettingsIndex(private val root: String) {
    internal val routes = mutableStateListOf(root)
    internal val parent = HashMap<String, String>()
    internal val pages = mutableStateMapOf<String, PageSnapshot>()

    internal class PageSnapshot(val title: String?, val rows: List<IndexedRow>, val linkIcons: Map<String, @Composable () -> Unit>)

    /** The routes from [root] to [route]. */
    private fun path(route: String): List<String> = generateSequence(route) { parent[it] }.toList().reversed()

    fun hits(): List<SettingsHit> = routes.flatMap { r ->
        val p = path(r)
        // the page titles below the overview: "Share with apps › LibreView"
        val crumbs = p.drop(1).mapNotNull { pages[it]?.title }
        val category = p.getOrNull(1)?.let { pages[root]?.linkIcons?.get(it) }
        // overview rows are the sections themselves: their own icon
        pages[r]?.rows.orEmpty().map { SettingsHit(p, crumbs, it, category ?: it.icon) }
    }

    /** Composes every known page in index mode, finding linked pages as it goes. Draws nothing. */
    @Composable
    fun Run(page: @Composable (String) -> Unit) {
        Box(Modifier.size(0.dp).clipToBounds()) {
            for (r in routes.toList()) key(r) {
                val idx = remember { PageIndex(r) }
                idx.rows.clear(); idx.links.clear()
                CompositionLocalProvider(LocalSettingsIndex provides idx) { page(r) }
                SideEffect {
                    pages[r] = PageSnapshot(idx.title, idx.rows.values.toList(), HashMap(idx.linkIcons))
                    for (l in idx.links) if (l !in routes) { parent[l] = r; routes += l }
                }
            }
        }
    }
}

/** Lower case, no accents: "Glucosa" finds "glucosa", "Ü" finds "u". */
internal fun fold(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

/**
 * Hits for [query], best first, ranked like the stock Settings search (SettingsIntelligence DatabaseResultTask,
 * BASE_RANKS 1/3/7/9): a word that starts the title's first word ranks 1, another title word 3, the summary 7, the page
 * or group names 9. Every word of the query must match; a hit ranks as its worst word. Ties: shallower pages first.
 */
fun search(hits: List<SettingsHit>, query: String): List<SettingsHit> {
    val words = fold(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    val split = Regex("[^\\p{L}\\p{N}+]+")
    fun words(text: String) = fold(text).split(split).filter { it.isNotEmpty() }
    return hits.mapNotNull { h ->
        val title = words(h.row.title)
        val summary = fold(h.row.summary.orEmpty())
        val where = words((h.crumbs + listOfNotNull(h.row.group)).joinToString(" "))
        val rank = words.maxOf { w ->
            when {
                title.firstOrNull()?.startsWith(w) == true -> 1
                title.any { it.startsWith(w) } -> 3
                w in summary -> 7
                where.any { it.startsWith(w) } -> 9
                else -> return@mapNotNull null
            }
        }
        h to rank
    }.sortedWith(compareBy<Pair<SettingsHit, Int>> { it.second }.thenBy { it.first.path.size }.thenBy { it.first.row.title })
        .map { it.first }
        // a page is one result: the row that opens it from the shallowest page (the list is sorted by depth)
        .distinctBy { h -> h.row.to?.let { "page:$it" } ?: "${h.route}/${h.row.title}" }
}

/** What the settings overview needs to search: the index and how to compose a page by route. */
class SettingsSearch(val index: SettingsIndex, val page: @Composable (String) -> Unit)

val LocalSettingsSearch = androidx.compose.runtime.staticCompositionLocalOf<SettingsSearch?> { null }

/** Recent searches (AOSP: up to 64 kept, 5 shown), saved when a result is opened or a search is submitted. */
private object RecentSearches {
    private const val KEEP = 64
    private fun prefs(ctx: android.content.Context) = ctx.getSharedPreferences("settings-search", android.content.Context.MODE_PRIVATE)
    fun load(ctx: android.content.Context): List<String> =
        runCatching { org.json.JSONArray(prefs(ctx).getString("recent", "[]")).let { a -> List(a.length()) { a.getString(it) } } }.getOrDefault(emptyList())
    fun add(ctx: android.content.Context, q: String): List<String> {
        val l = (listOf(q.trim()) + load(ctx).filter { !it.equals(q.trim(), ignoreCase = true) }).filter { it.isNotEmpty() }.take(KEEP)
        prefs(ctx).edit().putString("recent", org.json.JSONArray(l).toString()).apply()
        return l
    }
    fun clear(ctx: android.content.Context) { prefs(ctx).edit().remove("recent").apply() }
}

/**
 * The search pill under the settings title (stock Settings homepage) and the full-screen search it opens: the
 * Material 3 Expressive contained search bar (SearchBar + ExpandedFullScreenContainedSearchBar, material3 1.5). Empty:
 * recent searches. Typing: results as icon, title, summary and "Page > Group". Opening one goes to its page through
 * the same path the menus take and highlights the row.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsSearchBar() {
    if (indexing) return
    val search = LocalSettingsSearch.current ?: return
    val nav = LocalNav.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val state = androidx.compose.material3.rememberContainedSearchBarState()
    val text = androidx.compose.foundation.text.input.rememberTextFieldState()
    var recent by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(RecentSearches.load(ctx)) }
    val expanded = state.currentValue == androidx.compose.material3.SearchBarValue.Expanded ||
        state.targetValue == androidx.compose.material3.SearchBarValue.Expanded

    fun open(h: SettingsHit) {
        recent = RecentSearches.add(ctx, text.text.toString())
        scope.launch { state.animateToCollapsed() }
        text.clearText()
        nav?.reset(*h.path.toTypedArray())
        nav?.highlight = h.row.title
    }

    val input: @Composable () -> Unit = {
        androidx.compose.material3.SearchBarDefaults.InputField(
            textFieldState = text, searchBarState = state,
            onSearch = { q -> if (q.isNotBlank()) recent = RecentSearches.add(ctx, q) },
            placeholder = { androidx.compose.material3.Text("Search settings") },
            leadingIcon = {
                if (expanded) androidx.compose.material3.IconButton(onClick = { scope.launch { state.animateToCollapsed() } }) {
                    androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(app.mauri.sukrara.R.drawable.ic_sym_arrow_back), "Back")
                } else androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(app.mauri.sukrara.R.drawable.ic_sym_search), null)
            },
            trailingIcon = if (expanded && text.text.isNotEmpty()) ({
                androidx.compose.material3.IconButton(onClick = { text.clearText() }) {
                    androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(app.mauri.sukrara.R.drawable.ic_sym_close), "Clear")
                }
            }) else null,
        )
    }
    androidx.compose.material3.SearchBar(state, input, Modifier.fillMaxWidth())
    // the index is composed only while searching, here in the activity's composition (the expanded search is a
    // dialog window, whose context isn't the activity some pages need)
    if (expanded) search.index.Run(search.page)
    androidx.compose.material3.ExpandedFullScreenContainedSearchBar(state, input) {
        val q = text.text.toString()
        val hits = search(search.index.hits(), q)
        androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
            when {
                q.isBlank() -> if (recent.isNotEmpty()) {
                    SettingsGroup("Recent searches", titleAction = {
                        androidx.compose.material3.TextButton(onClick = { RecentSearches.clear(ctx); recent = emptyList() }) { androidx.compose.material3.Text("Clear history") }
                    }) {
                        for (r in recent.take(5)) link(r, icon = app.mauri.sukrara.R.drawable.ic_sym_history) {
                            text.setTextAndPlaceCursorAtEnd(r)
                        }
                    }
                }
                hits.isEmpty() -> androidx.compose.material3.Text("No results", style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 24.dp))
                else -> SettingsGroup {
                    for (h in hits.take(60)) result(h.row.title, h.row.summary,
                        (h.crumbs + listOfNotNull(h.row.group)).ifEmpty { listOf("Settings") }.joinToString(" > "), h.icon) { open(h) }
                }
            }
        }
    }
}
