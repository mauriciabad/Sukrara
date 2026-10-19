// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.R
import com.android.settingslib.spa.framework.compose.HighlightBox
import com.android.settingslib.spa.framework.compose.LocalNavController
import com.android.settingslib.spa.framework.compose.NavControllerWrapper
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsSize
import com.android.settingslib.spa.framework.theme.SettingsSpace
import com.android.settingslib.spa.widget.banner.BannerButton
import com.android.settingslib.spa.widget.banner.BannerModel
import com.android.settingslib.spa.widget.preference.CheckboxPreference
import com.android.settingslib.spa.widget.preference.CheckboxPreferenceModel
import com.android.settingslib.spa.widget.preference.MainSwitchPreference
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spa.widget.preference.SwitchPreference
import com.android.settingslib.spa.widget.preference.SwitchPreferenceModel
import com.android.settingslib.spa.widget.preference.TwoTargetSwitchPreference
import com.android.settingslib.spa.widget.scaffold.SettingsScaffold
import com.android.settingslib.spa.widget.ui.Category
import com.android.settingslib.spa.widget.ui.Footer
import com.android.settingslib.spa.widget.ui.SettingsIcon

/*
 * Settings building blocks. Everything you see is AOSP's own Compose settings library, SettingsLib SPA
 * (frameworks/base/packages/SettingsLib/Spa, vendored as :settingslib-spa, android17-release), the code the Settings
 * app draws its Compose pages with:
 *
 *  - a page is SPA's SettingsScaffold (RegularScaffold's layout): the large title collapses into the top app bar as
 *    the page scrolls, with the expressive round back button;
 *  - a group is SPA's Category (rounded 20 dp card, 2 dp between rows, the title in primary above it);
 *  - rows are SPA's Preference / SwitchPreference / TwoTargetSwitchPreference (chevron, 40 dp line, switch with
 *    check / close thumb icons) / MainSwitchPreference, a search hit is highlighted by SPA's HighlightBox;
 *  - SPA's Footer and SettingsBanner; the page intro is the TopIntroPreference text.
 *
 * What this file adds on top: the row DSL the pages use (one call per row: [link], [value], [action], [info],
 * [switch], [splitSwitch], [card]) and the search index below. Settings guidelines: source.android.com/docs/core/
 * settings/settings-guidelines, developer.android.com/design/ui/mobile/guides/patterns/settings.
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

/**
 * SPA's navigation interface on our [Nav]: SPA's back button calls [navigateBack], `navigator(route)` opens a page,
 * and [isHighLightItem] is what SPA's HighlightBox asks once per row (the row a search result opened).
 */
private class SukraraNavController(private val nav: Nav?, private val onBack: () -> Unit) : NavControllerWrapper {
    override fun navigate(route: String, popUpCurrent: Boolean) { nav?.open(route) }
    override fun navigateBack() = onBack()
    override fun isHighLightItem(highlightItemKey: String): Boolean =
        (nav?.highlight == highlightItemKey).also { if (it) nav?.highlight = null }
}

/** Content at the top of a page that isn't a setting (a live preview, a status card): not composed while indexing. */
@Composable
fun SettingsHeader(content: @Composable () -> Unit) {
    if (indexing) return
    Box(Modifier.padding(vertical = SettingsSpace.extraSmall4)) { content() }
}

/**
 * One settings page: SPA's SettingsScaffold with the page [title] (it matches the row that opened it) as the
 * collapsing large title, then [content] in a scrolling column, laid out like SPA's RegularScaffold. Rows and groups
 * reach the screen edges the SPA way; any other content gets the 16 dp side margin of the cards.
 */
@Composable
fun SettingsPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    LocalSettingsIndex.current?.let { idx -> idx.title = title; Column { content() }; return }
    val nav = LocalNav.current
    val controller = remember(nav, onBack) { SukraraNavController(nav, onBack) }
    CompositionLocalProvider(LocalNavController provides controller) {
        SettingsScaffold(title) { padding ->
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = SettingsSpace.small1)) {
                Spacer(Modifier.height(padding.calculateTopPadding()))
                content()
                Spacer(Modifier.height(padding.calculateBottomPadding()))
            }
        }
    }
}

/** A settings screen and its dialogs in SPA's SettingsTheme: dynamic colours and the Settings typography (device fonts). */
@Composable
fun SettingsRoute(content: @Composable () -> Unit) = com.android.settingslib.spa.framework.theme.SettingsTheme(content)

/** SPA widgets bring their own 16 dp side margin: undo the page's, so they sit exactly where SPA puts them. */
private fun Modifier.fullBleed(h: Dp = SettingsSpace.small1) = layout { m, c ->
    val px = h.roundToPx()
    val p = m.measure(c.copy(minWidth = (c.minWidth + 2 * px).coerceAtLeast(0), maxWidth = c.maxWidth + 2 * px))
    layout(p.width - 2 * px, p.height) { p.place(-px, 0) }
}

/** The rows of one [SettingsGroup]. */
class SettingsGroupScope @PublishedApi internal constructor() {
    internal class Entry(val title: String?, val summary: String?, val to: String?, val icon: (@Composable () -> Unit)?,
                         val draw: @Composable () -> Unit)
    internal val rows = mutableListOf<Entry>()
    /**
     * Any row content (a slider, a follower with its avatar…), drawn as one row of the group. [title] makes it
     * findable by search; [to] is the settings page it opens.
     */
    fun row(title: String? = null, summary: String? = null, to: String? = null, icon: (@Composable () -> Unit)? = null,
            content: @Composable () -> Unit) {
        rows += Entry(title, summary, to, icon, content)
    }
}

/**
 * A group: SPA's Category with an optional [title] (and, our addition, [titleAction] at its end, e.g. a reset
 * button). Each row can be the target of a search result (SPA's HighlightBox, keyed by the row title).
 */
@Composable
inline fun SettingsGroup(title: String? = null, noinline titleAction: (@Composable () -> Unit)? = null,
                         content: @Composable SettingsGroupScope.() -> Unit) {
    // Inline on purpose: the rows are built in the caller's recompose scope. As a plain lambda parameter, [content]
    // was a restart scope of its own: a state it read (a switch's checked value) recomposed only the lambda, which
    // filled a list nobody drew again, so rows kept showing the old value until the page was opened again.
    SettingsGroupRows(title, titleAction, SettingsGroupScope().also { it.content() })
}

/** Draws (or, in index mode, records) the rows [SettingsGroup] collected; a new [scope] instance each time. */
@Composable
fun SettingsGroupRows(title: String?, titleAction: (@Composable () -> Unit)?, scope: SettingsGroupScope) {
    LocalSettingsIndex.current?.let { idx ->
        for (r in scope.rows) {
            r.title?.let { t -> idx.rows[title to t] = IndexedRow(t, r.summary, title, r.icon, r.to) }
            r.to?.let { idx.links += it; r.icon?.let { i -> idx.linkIcons[it] = i } }
        }
        return
    }
    if (scope.rows.isEmpty()) return
    Column(Modifier.fullBleed()) {
        if (title != null && titleAction != null) CategoryTitleWithAction(title, titleAction)
        Category(title = title.takeIf { titleAction == null }) {
            for (r in scope.rows) HighlightBox(r.title ?: "") { r.draw() }
        }
    }
}

/** SPA's CategoryTitle (same padding, colour and style) with a trailing action. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CategoryTitleWithAction(title: String, action: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = SettingsSpace.small1, end = SettingsSpace.extraSmall4),
        verticalAlignment = Alignment.Bottom) {
        Text(title, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLargeEmphasized,
            modifier = Modifier.weight(1f).padding(start = SettingsSpace.extraSmall4, top = SettingsSpace.small3,
                end = SettingsSpace.extraSmall4, bottom = SettingsSpace.extraSmall4))
        action()
    }
}

/** A row's icon as SPA draws it: a 24 dp symbol in onSurfaceVariant ([SettingsIcon]), or the given composable. */
private fun iconOf(@DrawableRes icon: Int?, leading: (@Composable () -> Unit)?): (@Composable () -> Unit)? =
    leading ?: icon?.let { { SettingsIcon(ImageVector.vectorResource(it)) } }

/** The model every plain row uses. */
private fun model(title: String, summary: CharSequence?, icon: (@Composable () -> Unit)?, enabled: Boolean, onClick: (() -> Unit)?) =
    object : PreferenceModel {
        override val title = title
        override val summary = { summary ?: "" }
        override val icon = icon
        override val enabled = { enabled }
        override val onClick = onClick
    }

/**
 * Opens another screen (or, [external], a web page or the system settings). Stock rows show no chevron; [leading]
 * replaces the plain icon (category icons, avatars, app icons).
 */
fun SettingsGroupScope.link(title: String, summary: String? = null, @DrawableRes icon: Int? = null, enabled: Boolean = true,
                            external: Boolean = false, leading: (@Composable () -> Unit)? = null, to: String? = null,
                            onClick: (() -> Unit)? = null) {
    val i = iconOf(icon, leading)
    row(title, summary, to, i) {
        val nav = LocalNav.current
        Preference(model(title, summary, i, enabled) { if (onClick != null) onClick() else to?.let { nav?.open(it) } })
    }
}

/** Opens a dialog (a choice, an editor): the current value below the title (SPA ListPreference's summary). */
fun SettingsGroupScope.value(title: String, value: String, enabled: Boolean = true, @DrawableRes icon: Int? = null,
                             leading: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val i = iconOf(icon, leading)
    row(title, value, icon = i) { Preference(model(title, value, i, enabled, onClick)) }
}

/** Does something now (pair, save, copy…). */
fun SettingsGroupScope.action(title: String, summary: String? = null, @DrawableRes icon: Int? = null, enabled: Boolean = true,
                              onClick: () -> Unit) {
    val i = iconOf(icon, null)
    row(title, summary, icon = i) { Preference(model(title, summary, i, enabled, onClick)) }
}

/** Not tappable: a fact (version, an address). */
fun SettingsGroupScope.info(title: String, summary: String? = null, @DrawableRes icon: Int? = null) {
    val i = iconOf(icon, null)
    row(title, summary, icon = i) { Preference(model(title, summary, i, true, null)) }
}

private fun switchModel(title: String, summary: String?, icon: (@Composable () -> Unit)?, checked: Boolean, enabled: Boolean,
                        onChange: (Boolean) -> Unit) = object : SwitchPreferenceModel {
    override val title = title
    override val summary = { summary ?: "" }
    override val icon = icon
    override val checked = { checked }
    override val changeable = { enabled }
    override val onCheckedChange = onChange
}

/** SPA SwitchPreference: the whole row toggles; disabled rows are greyed out with the reason as the summary. */
fun SettingsGroupScope.switch(title: String, checked: Boolean, enabled: Boolean = true, summary: String? = null,
                              @DrawableRes icon: Int? = null, leading: (@Composable () -> Unit)? = null, onChange: (Boolean) -> Unit) {
    val i = iconOf(icon, leading)
    row(title, summary, icon = i) { SwitchPreference(switchModel(title, summary, i, checked, enabled, onChange)) }
}

/**
 * SPA TwoTargetSwitchPreference (AOSP PrimarySwitchPreference): left of the chevron and line opens [to] (a page) or
 * [onClick] (a dialog), the switch toggles. [rowEnabled] greys the left part; [switchEnabled] only the switch.
 */
fun SettingsGroupScope.splitSwitch(title: String, summary: String?, checked: Boolean, rowEnabled: Boolean = true,
                                   switchEnabled: Boolean = rowEnabled, to: String? = null, onClick: (() -> Unit)? = null,
                                   onChange: (Boolean) -> Unit) = row(title, summary, to) {
    val nav = LocalNav.current
    TwoTargetSwitchPreference(switchModel(title, summary, null, checked, switchEnabled, onChange), primaryEnabled = { rowEnabled },
        primaryOnClick = { if (onClick != null) onClick() else to?.let { nav?.open(it) } })
}

/** SPA CheckboxPreference: the whole row toggles a check box at its end (an option, not a feature switch). */
fun SettingsGroupScope.checkbox(title: String, checked: Boolean, enabled: Boolean = true, summary: String? = null,
                                @DrawableRes icon: Int? = null, onChange: (Boolean) -> Unit) {
    val i = iconOf(icon, null)
    row(title, summary, icon = i) {
        CheckboxPreference(object : CheckboxPreferenceModel {
            override val title = title
            override val summary = { summary ?: "" }
            override val icon = i
            override val checked = { checked }
            override val changeable = { enabled }
            override val onCheckedChange = onChange
        })
    }
}

/** Free content (a slider, chips, a field) as one row of the group: the card colour and SPA's item padding. */
fun SettingsGroupScope.card(title: String? = null, content: @Composable ColumnScope.() -> Unit) = row(title) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceBright).padding(SettingsDimension.itemPadding),
        verticalArrangement = Arrangement.spacedBy(SettingsSpace.extraSmall2), content = content)
}

/**
 * A feature's own switch at the top of its page, SPA's MainSwitchPreference: "Using the main setting to disable the
 * feature disables all other related settings."
 */
@Composable
fun SettingsMainSwitch(title: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    LocalSettingsIndex.current?.let { idx -> idx.rows[null to title] = IndexedRow(title, null, null, null); return }
    Box(Modifier.fullBleed()) {
        HighlightBox(title) { MainSwitchPreference(switchModel(title, null, null, checked, enabled, onChange)) }
    }
}

/** The colour of a category on the settings overview: AOSP Settings' homepage icon colours. */
enum class Category(val bg: Int, val fg: Int) {
    RED(R.color.homepage_red_bg, R.color.homepage_red_fg), ORANGE(R.color.homepage_orange_bg, R.color.homepage_orange_fg),
    GREEN(R.color.homepage_green_bg, R.color.homepage_green_fg), TEAL(R.color.homepage_cyan_bg, R.color.homepage_cyan_fg),
    BLUE(R.color.homepage_blue_bg, R.color.homepage_blue_fg), INDIGO(R.color.homepage_blue_variant_bg, R.color.homepage_blue_variant_fg),
    PURPLE(R.color.homepage_purple_bg, R.color.homepage_purple_fg), GREY(R.color.homepage_grey_bg, R.color.homepage_grey_fg),
    PINK(R.color.homepage_pink_bg, R.color.homepage_pink_fg), YELLOW(R.color.homepage_yellow_bg, R.color.homepage_yellow_fg),
}

/**
 * The device's adaptive icon mask (what SettingsLib's AdaptiveIconShapeDrawable fills): a circle, squircle… as the
 * launcher shapes icons. [android.graphics.drawable.AdaptiveIconDrawable.getIconMask] is in a 100 × 100 box.
 */
private object AdaptiveIconShape : Shape {
    private val mask by lazy {
        android.graphics.drawable.AdaptiveIconDrawable(android.graphics.drawable.ColorDrawable(), android.graphics.drawable.ColorDrawable()).iconMask
    }
    override fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                               density: androidx.compose.ui.unit.Density): Outline {
        val p = android.graphics.Path(mask)
        p.transform(android.graphics.Matrix().apply { setScale(size.width / 100f, size.height / 100f) })
        return Outline.Generic(p.asComposePath())
    }
}

/**
 * Settings-homepage category icon (AOSP Settings ic_homepage_*): the 24 dp glyph in the hue's tone 30 on the 40 dp
 * icon-mask shape in tone 90 (80 in dark theme), from the Material reference palettes.
 */
@Composable
fun CategoryIcon(@DrawableRes icon: Int, c: Category) {
    Box(Modifier.size(SettingsSize.medium3).clip(AdaptiveIconShape).background(colorResource(c.bg)), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = null, tint = colorResource(c.fg), modifier = Modifier.size(SettingsDimension.itemIconSize))
    }
}

/**
 * The top of a page, AOSP TopIntroPreference: one or two sentences on what the page is for, in body large, on the
 * page background (SPA's SettingsIntro text; TopIntro's 16 dp padding inside the list, top 8, bottom 16).
 */
@Composable
fun SettingsIntro(text: String) {
    if (indexing) return
    Box(Modifier.padding(start = SettingsSpace.extraSmall4, end = SettingsSpace.extraSmall4, top = SettingsSpace.extraSmall4,
        bottom = SettingsSpace.small1)) {
        com.android.settingslib.spa.widget.ui.SettingsIntro(text)
    }
}

/** Explanations at the end of a page: SPA's Footer (an info icon, then the text). */
@Composable
fun SettingsFooter(text: String) {
    if (indexing) return
    Box(Modifier.fullBleed()) { Footer(text) }
}

/**
 * Something the user should set up, dismissible: SPA's SettingsBanner (expressive card, icon in the accent colour,
 * title, one sentence, then the actions). Shown at the top of the home screen and of the settings overview.
 */
@Composable
fun SettingsBanner(@DrawableRes icon: Int, title: String, text: String, action: String?, onAction: () -> Unit, onDismiss: (() -> Unit)? = null,
                   containerColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified) {
    if (indexing) return
    Box(Modifier.fullBleed()) {
        com.android.settingslib.spa.widget.banner.SettingsBanner(BannerModel(title = title, text = text,
            imageVector = ImageVector.vectorResource(icon), onDismiss = onDismiss,
            buttons = listOfNotNull(action?.let { BannerButton(it, onClick = onAction) }), containerColor = containerColor))
    }
}

/** A search result: icon, title, summary and where it is ("Page > Group", in the accent colour). */
fun SettingsGroupScope.result(title: String, summary: String?, where: String, icon: (@Composable () -> Unit)?, onClick: () -> Unit) = row {
    val accent = MaterialTheme.colorScheme.primary
    val text = buildAnnotatedString {
        summary?.takeIf { it.isNotBlank() }?.let { append(it); append("\n") }
        withStyle(SpanStyle(color = accent)) { append(where) }
    }
    Preference(model(title, text, icon, true, onClick))
}
