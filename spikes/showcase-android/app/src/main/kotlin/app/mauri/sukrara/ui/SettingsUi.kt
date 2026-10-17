// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.R

/*
 * Settings building blocks, after the Android 16 Settings app (Material 3 Expressive) and the settings guidelines
 * (source.android.com/docs/core/settings/settings-guidelines, developer.android.com/design/ui/mobile/guides/patterns/settings):
 *
 *  - Rows are expressive *segmented* list items (material3 1.5 SegmentedListItem + ListItemDefaults.segmentedShapes):
 *    each row is its own tappable card with a press state, large outer corners on the group's first and last row,
 *    a 2 dp gap between rows (AOSP SettingsLib: 20/4 dp corners, surfaceBright cards). Headings sit between groups.
 *  - What a row does shows at its end: › opens another screen ([link]), a switch toggles ([switch]), a split line
 *    then a switch is a row that opens a dialog AND has its own switch ([splitSwitch], AOSP "split" rows). A row that
 *    opens a dialog shows the current value below the title ([value]: "Below the title, show the status").
 *  - The settings overview uses coloured category icons ([CategoryIcon]); pages may start with an illustration or a
 *    short intro ([SettingsIntro]: "The animation or image should be presented at the top of the screen") and end with
 *    a footer ([SettingsFooter]: "Footer text can be used to add explanatory content").
 *  - A feature's own on/off on its page is a main switch bar ([SettingsMainSwitch]); turning it off disables (doesn't
 *    hide) what depends on it, each with a brief reason.
 */

/*
 * Search (SettingsSearch.kt) indexes the pages by composing them in *index mode* ([LocalSettingsIndex] set): every
 * row function below records its title, summary, group and the page it links to instead of drawing, and the pages
 * reached through [link] `to` are indexed next. The index is therefore whatever the pages really show, never a list
 * kept by hand. Visual-only parts ([SettingsIntro], [SettingsFooter], [SettingsHeader]…) draw nothing in index mode;
 * pages skip side effects (NFC, network, database) while [indexing] is true.
 */

/** A row search can find, with its icon (results show it, or else the page's category icon, like AOSP). */
class IndexedRow(val title: String, val summary: String?, val group: String?, val icon: (@Composable () -> Unit)?, val to: String? = null)

/** What index mode recorded for one page (rows by group + title, so a recomposition doesn't add them twice). */
class PageIndex(val route: String) {
    var title: String? = null
    val rows = LinkedHashMap<Pair<String?, String>, IndexedRow>()
    val links = LinkedHashSet<String>()
    /** The icon of the row that opens each linked page (the overview's category icons). */
    val linkIcons = HashMap<String, @Composable () -> Unit>()
}

val LocalSettingsIndex = androidx.compose.runtime.staticCompositionLocalOf<PageIndex?> { null }

/** True while the page is being composed for the search index (skip side effects and heavy content). */
val indexing: Boolean @Composable get() = LocalSettingsIndex.current != null

/** The in-app navigation, for [link] `to` and for the highlight a search result asks for. */
val LocalNav = androidx.compose.runtime.staticCompositionLocalOf<Nav?> { null }

/** Content at the top of a page that isn't a setting (a live preview, a status card): not composed while indexing. */
@Composable
fun SettingsHeader(content: @Composable () -> Unit) { if (!indexing) content() }

/** One settings page: a back arrow and the title (it matches the row that opened it), then [content]. */
@Composable
fun SettingsPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    LocalSettingsIndex.current?.let { idx -> idx.title = title; Column { content() }; return }
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.offset(x = (-12).dp)) {
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_sym_arrow_back), contentDescription = "Back") }
            Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 4.dp))
        }
        content()
    }
}

/** The rows of one [SettingsGroup]; each row gets its segmented shape from its position. */
class SettingsGroupScope internal constructor() {
    internal class Entry(val title: String?, val summary: String?, val to: String?, val icon: (@Composable () -> Unit)?,
                         val draw: @Composable (ListItemShapes) -> Unit)
    internal val rows = mutableListOf<Entry>()
    /** Some row has an icon: every row then keeps the icon column, so titles line up (AOSP iconSpaceReserved). */
    internal var hasIcon = false
    /**
     * Any row content (a slider, a follower with its avatar…), drawn inside the group's card shape. [title] makes it
     * findable by search; [to] is the settings page it opens.
     */
    fun row(title: String? = null, summary: String? = null, to: String? = null, icon: (@Composable () -> Unit)? = null,
            content: @Composable (ListItemShapes) -> Unit) {
        rows += Entry(title, summary, to, icon, content)
    }
}

/** A heading (optional, with [titleAction] at its end, e.g. a reset button) and its rows as one segmented list. */
@Composable
fun SettingsGroup(title: String? = null, titleAction: (@Composable () -> Unit)? = null, content: SettingsGroupScope.() -> Unit) {
    val scope = SettingsGroupScope().apply(content)
    LocalSettingsIndex.current?.let { idx ->
        for (r in scope.rows) {
            r.title?.let { t -> idx.rows[title to t] = IndexedRow(t, r.summary, title, r.icon, r.to) }
            r.to?.let { idx.links += it; r.icon?.let { i -> idx.linkIcons[it] = i } }
        }
        return
    }
    val nav = LocalNav.current
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        title?.let {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp)) {
                Text(it, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f).padding(start = 16.dp, bottom = 6.dp))
                titleAction?.invoke()
            }
        }
        val n = scope.rows.size
        androidx.compose.runtime.CompositionLocalProvider(LocalReserveIcon provides scope.hasIcon) {
            scope.rows.forEachIndexed { i, r ->
                val shapes = ListItemDefaults.segmentedShapes(i, n)
                if (r.title != null && nav?.highlight == r.title) Highlighted(shapes, onDone = { nav.highlight = null }) { r.draw(shapes) }
                else r.draw(shapes)
            }
        }
    }
}

/** Cards a step brighter/darker than the page in both themes (the page background is the app's, not surfaceContainer). */
@Composable
private fun rowColors(): androidx.compose.material3.ListItemColors {
    val c = MaterialTheme.colorScheme.surfaceContainerHigh
    // a switch that is on isn't a "selected" row, and a disabled row keeps its card (only its content dims)
    return ListItemDefaults.segmentedColors(containerColor = c, selectedContainerColor = c, disabledContainerColor = c,
        selectedContentColor = MaterialTheme.colorScheme.onSurface, selectedSupportingContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        selectedLeadingContentColor = MaterialTheme.colorScheme.onSurfaceVariant, selectedTrailingContentColor = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Title(text: String) = Text(text, style = MaterialTheme.typography.bodyLarge)

@Composable
private fun Summary(text: String?) = text?.let { s -> Swap(s) { Text(it, style = MaterialTheme.typography.bodyMedium) } }

private val LocalReserveIcon = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * The icon column: 40 dp wide whatever the icon (a 24 dp symbol or brand mark, a 40 dp app icon or avatar), and kept
 * empty on rows without one when their group has icons, so every title in a group starts at the same place.
 */
@Composable
private fun lead(@DrawableRes icon: Int?, leading: (@Composable () -> Unit)?, tint: Color? = null): (@Composable () -> Unit)? {
    if (icon == null && leading == null && !LocalReserveIcon.current) return null
    return {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            when {
                leading != null -> leading()
                icon != null -> if (tint != null) Icon(painterResource(icon), contentDescription = null, tint = tint)
                    else Icon(painterResource(icon), contentDescription = null)
            }
        }
    }
}

private fun SettingsGroupScope.noteIcon(@DrawableRes icon: Int?, leading: Any?) { if (icon != null || leading != null) hasIcon = true }

private fun iconOf(@DrawableRes icon: Int?, leading: (@Composable () -> Unit)?): (@Composable () -> Unit)? =
    leading ?: icon?.let { { Icon(painterResource(it), contentDescription = null) } }

/** Free content (a slider, chips, a field) in the group's card colour and shape. */
fun SettingsGroupScope.card(title: String? = null, content: @Composable ColumnScope.() -> Unit) = row(title) { shapes ->
    androidx.compose.material3.Surface(shape = shapes.shape, color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

/**
 * Opens another screen: › at the end (Android 16 Settings), or ↗ when it leaves the app ([external]: a web page, the
 * system settings). [leading] replaces the plain icon (category icons, avatars, app icons).
 */
fun SettingsGroupScope.link(title: String, summary: String? = null, @DrawableRes icon: Int? = null, enabled: Boolean = true,
                            external: Boolean = false, leading: (@Composable () -> Unit)? = null, to: String? = null,
                            onClick: (() -> Unit)? = null) {
    noteIcon(icon, leading)
    row(title, summary, to, iconOf(icon, leading)) { shapes ->
        val nav = LocalNav.current
        SegmentedListItem(onClick = { if (onClick != null) onClick() else to?.let { nav?.open(it) } }, shapes = shapes, colors = rowColors(), enabled = enabled,
            leadingContent = lead(icon, leading),
            supportingContent = summary?.let { { Summary(it) } },
            trailingContent = {
                Icon(painterResource(if (external) R.drawable.ic_sym_open_in_new else R.drawable.ic_sym_chevron_right),
                    contentDescription = if (external) "Opens outside Sukrara" else null)
            }) { Title(title) }
    }
}

/** Opens a dialog (a choice, an editor): the current value below the title. */
fun SettingsGroupScope.value(title: String, value: String, enabled: Boolean = true, @DrawableRes icon: Int? = null,
                             leading: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    noteIcon(icon, leading)
    row(title, value, icon = iconOf(icon, leading)) { shapes ->
        SegmentedListItem(onClick = onClick, shapes = shapes, colors = rowColors(), enabled = enabled,
            leadingContent = lead(icon, leading), supportingContent = { Summary(value) }) { Title(title) }
    }
}

/** Does something now (pair, save, copy…): the title in the accent colour ("Links should use an accent color"). */
fun SettingsGroupScope.action(title: String, summary: String? = null, @DrawableRes icon: Int? = null, enabled: Boolean = true,
                              onClick: () -> Unit) {
    noteIcon(icon, null)
    row(title, summary, icon = iconOf(icon, null)) { shapes ->
        SegmentedListItem(onClick = onClick, shapes = shapes, colors = rowColors(), enabled = enabled,
            leadingContent = lead(icon, null, tint = if (enabled) MaterialTheme.colorScheme.primary else null),
            supportingContent = summary?.let { { Summary(it) } }) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.primary else Color.Unspecified)
        }
    }
}

/** Not tappable: a fact (version, an address). */
fun SettingsGroupScope.info(title: String, summary: String? = null, @DrawableRes icon: Int? = null) {
    noteIcon(icon, null)
    row(title, summary, icon = iconOf(icon, null)) { shapes ->
        SegmentedListItem(shapes = shapes, colors = rowColors(), leadingContent = lead(icon, null),
            supportingContent = summary?.let { { Summary(it) } }) { Title(title) }
    }
}

/** The whole row toggles; disabled rows are greyed out with the reason as the summary. */
fun SettingsGroupScope.switch(title: String, checked: Boolean, enabled: Boolean = true, summary: String? = null,
                              @DrawableRes icon: Int? = null, leading: (@Composable () -> Unit)? = null, onChange: (Boolean) -> Unit) {
    noteIcon(icon, leading)
    row(title, summary, icon = iconOf(icon, leading)) { shapes ->
        SegmentedListItem(checked = checked, onCheckedChange = onChange, shapes = shapes.copy(selectedShape = shapes.shape),
            colors = rowColors(), enabled = enabled, leadingContent = lead(icon, leading),
            supportingContent = summary?.let { { Summary(it) } },
            trailingContent = { Switch(checked, onCheckedChange = null, enabled = enabled) }) { Title(title) }
    }
}

/**
 * AOSP split row: the part left of the line opens [onClick] (a dialog or screen), the switch on the right toggles.
 * [rowEnabled] greys the whole row; [switchEnabled] only the switch.
 */
fun SettingsGroupScope.splitSwitch(title: String, summary: String?, checked: Boolean, rowEnabled: Boolean = true,
                                   switchEnabled: Boolean = rowEnabled, onClick: () -> Unit, onChange: (Boolean) -> Unit) = row(title, summary) { shapes ->
    SegmentedListItem(onClick = onClick, shapes = shapes, colors = rowColors(), enabled = rowEnabled,
        leadingContent = lead(null, null), supportingContent = summary?.let { { Summary(it) } },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                VerticalDivider(Modifier.height(32.dp))
                Box(Modifier.padding(start = 4.dp).clip(CircleShape)
                    .toggleable(checked, enabled = switchEnabled, role = Role.Switch, onValueChange = onChange)
                    .padding(start = 12.dp, top = 8.dp, bottom = 8.dp)) {
                    Switch(checked, onCheckedChange = null, enabled = switchEnabled)
                }
            }
        }) { Title(title) }
}

/**
 * A feature's own switch at the top of its page (AOSP MainSwitchPreference: a 72 dp pill in the primary container
 * colour): "Using the main setting to disable the feature disables all other related settings."
 */
@Composable
fun SettingsMainSwitch(title: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    LocalSettingsIndex.current?.let { idx -> idx.rows[null to title] = IndexedRow(title, null, null, null); return }
    val nav = LocalNav.current
    if (nav?.highlight == title) Highlighted(androidx.compose.material3.ListItemShapes(RoundedCornerShape(36.dp), RoundedCornerShape(36.dp),
        RoundedCornerShape(36.dp), RoundedCornerShape(36.dp), RoundedCornerShape(36.dp), RoundedCornerShape(36.dp)), onDone = { nav.highlight = null }) {
        MainSwitchBar(title, checked, enabled, onChange)
    } else MainSwitchBar(title, checked, enabled, onChange)
}

@Composable
private fun MainSwitchBar(title: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val bg = if (checked && enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
    val fg = if (checked && enabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)
        .clip(RoundedCornerShape(36.dp)).background(bg)
        .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .padding(start = 24.dp, end = 20.dp)
        .graphicsLayer { alpha = if (enabled) 1f else 0.38f }) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = fg, modifier = Modifier.weight(1f))
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

/** The colour of a category on the settings overview. */
enum class Category(val light: Long, val dark: Long) {
    RED(0xFFC5221F, 0xFFF28B82), ORANGE(0xFFB45F06, 0xFFFDBA74), GREEN(0xFF137333, 0xFF81C995), TEAL(0xFF00796B, 0xFF80CBC4),
    BLUE(0xFF1A73E8, 0xFF8AB4F8), INDIGO(0xFF3F51B5, 0xFF9FA8DA), PURPLE(0xFF8430CE, 0xFFC58AF9), GREY(0xFF5F6368, 0xFFBDC1C6),
}

/** Settings-app style category icon: a white glyph on a coloured circle (AOSP ic_homepage_*). */
@Composable
fun CategoryIcon(@DrawableRes icon: Int, c: Category) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    Box(Modifier.size(40.dp).clip(CircleShape).background(Color(if (dark) c.dark else c.light)), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = null, tint = if (dark) Color(0xFF202124) else Color.White,
            modifier = Modifier.size(24.dp))
    }
}

/**
 * The top of a page (AOSP IllustrationPreference + TopIntroPreference): the page's symbol in a slowly turning
 * Material 3 Expressive shape, then one or two sentences on what the page is for.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsIntro(@DrawableRes icon: Int, text: String) {
    if (indexing) return
    val turn by rememberInfiniteTransition(label = "intro").animateFloat(0f, 360f,
        infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Restart), label = "turn")
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
            val shape = MaterialShapes.Cookie9Sided.toShape()
            Box(Modifier.size(112.dp).graphicsLayer { rotationZ = turn }.clip(shape).background(MaterialTheme.colorScheme.primaryContainer))
            Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(48.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp))
    }
}

/** Explanations at the end of a page (AOSP FooterPreference: an info icon, then the text). */
@Composable
fun SettingsFooter(text: String) {
    if (indexing) return
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(R.drawable.ic_sym_info), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Something the user should set up, dismissible (AOSP BannerMessagePreference): an icon, a title, one sentence, then
 * "Not now" and the action. Shown at the top of the home screen and of the settings overview.
 */
@Composable
fun SettingsBanner(icon: @Composable () -> Unit, title: String, text: String, action: String, onAction: () -> Unit, onDismiss: () -> Unit) {
    if (indexing) return
    androidx.compose.material3.Card(colors = androidx.compose.material3.CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer),
        shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 20.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                icon()
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Not now") }
                androidx.compose.material3.TextButton(onClick = onAction) { Text(action) }
            }
        }
    }
}

/**
 * Another app's own icon in its colours (xDrip+, Juggluco, LibreView), round like the launcher shows it, at the size
 * of the 24 dp icons beside it.
 */
@Composable
fun AppIcon(@DrawableRes res: Int, size: androidx.compose.ui.unit.Dp = 24.dp) =
    androidx.compose.foundation.Image(painterResource(res), contentDescription = null, modifier = Modifier.size(size).clip(CircleShape))


/**
 * The row a search result points at, as AOSP's HighlightablePreferenceGroupAdapter does: after 600 ms it scrolls into
 * view, then its background pulses in the accent colour at 26 % (200 ms fades, reversed 4 times), stays 15 s and fades
 * out over 500 ms. Leaving the page earlier drops it ([Nav.open] / [Nav.back] clear the highlight).
 */
@Composable
private fun Highlighted(shapes: ListItemShapes, onDone: () -> Unit, content: @Composable () -> Unit) {
    val bring = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    val glow = remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(600)
        bring.bringIntoView()
        repeat(5) { glow.animateTo(if (it % 2 == 0) 1f else 0f, tween(200)) }
        kotlinx.coroutines.delay(15_000)
        glow.animateTo(0f, tween(500))
        onDone()
    }
    val c = MaterialTheme.colorScheme.primary
    Box(Modifier.bringIntoViewRequester(bring).drawWithContent {
        drawContent()
        drawOutline(shapes.shape.createOutline(size, layoutDirection, this), c.copy(alpha = 0.26f * glow.value))
    }) { content() }
}

/** A search result: icon, title, summary and where it is ("Page > Group"). */
fun SettingsGroupScope.result(title: String, summary: String?, where: String, icon: (@Composable () -> Unit)?, onClick: () -> Unit) {
    hasIcon = true
    row { shapes ->
        SegmentedListItem(onClick = onClick, shapes = shapes, colors = rowColors(), leadingContent = lead(null, icon),
            supportingContent = {
                Column {
                    summary?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2) }
                    Text(where, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }) { Title(title) }
    }
}
