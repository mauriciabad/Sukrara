// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.designsystem.rows

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.core.designsystem.R
import app.mauri.sukrara.designsystem.haptics.rememberHaptics

/*
 * Settings rows (AOSP Android 16 look without vendoring SettingsLib): a group is a rounded card whose rows sit on
 * surfaceBright with 2 dp between them, the group title in primary above it. Every row is at least 56 dp tall (≥ 48 dp
 * target), the whole row is the touch target, and a disabled row says why in its summary.
 */

private val RowMinHeight = 56.dp
private val GroupShape = RoundedCornerShape(20.dp)
private val RowPadding = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)

/** A group of rows with an optional [title] (a section header announced as a heading by TalkBack). */
@Composable
fun SettingsGroup(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (title != null) SettingsHeader(title)
        Column(Modifier.clip(GroupShape), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
    }
}

/** A section header above a group. */
@Composable
fun SettingsHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp).semantics { heading() },
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
    )
}

/** The row frame every row uses: surfaceBright, min height, icon, title + summary, trailing content. */
@Composable
private fun RowFrame(
    title: String,
    summary: String?,
    @DrawableRes icon: Int?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .background(MaterialTheme.colorScheme.surfaceBright)
            .then(RowPadding)
            .alpha(if (enabled) 1f else DisabledAlpha),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = titleColor, style = MaterialTheme.typography.bodyLarge)
            if (!summary.isNullOrEmpty()) {
                Text(summary, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        }
        trailing()
    }
}

private const val DisabledAlpha = 0.38f

/** A switch; the whole row toggles (TalkBack: "switch, on"). A disabled row explains why in [summary]. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
) {
    val haptics = rememberHaptics()
    RowFrame(
        title,
        summary,
        icon,
        enabled,
        modifier.toggleable(checked, enabled, Role.Switch) {
            haptics.toggle(it)
            onCheckedChange(it)
        },
    ) {
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Opens another screen; a chevron at the end. */
@Composable
fun NavigationRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
) {
    RowFrame(title, summary, icon, enabled, modifier.clickable(enabled, role = Role.Button, onClick = onClick)) {
        Icon(
            painterResource(R.drawable.ds_ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A value that opens a choice or number dialog: the current [value] below the title. */
@Composable
fun ValueRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
) {
    RowFrame(title, value, icon, enabled, modifier.clickable(enabled, role = Role.Button, onClick = onClick))
}

/** Not tappable: a fact (version, an address). */
@Composable
fun InfoRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    @DrawableRes icon: Int? = null,
) {
    RowFrame(title, summary, icon, enabled = true, modifier = modifier)
}

/** Free content (a slider, chips) as one row of a group, on the row surface with the row padding. */
@Composable
fun CardRow(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .background(MaterialTheme.colorScheme.surfaceBright)
            .then(RowPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/**
 * Something that can't be undone (forget a sensor, start fresh): red title, and a confirmation dialog saying what
 * happens ([confirmText]) before [onConfirm] runs. Cancel leaves everything as it was.
 */
@Composable
fun DangerRow(
    title: String,
    confirmTitle: String,
    confirmText: String,
    confirmAction: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
) {
    var asking by rememberSaveable { mutableStateOf(false) }
    val haptics = rememberHaptics()
    RowFrame(
        title,
        summary,
        icon,
        enabled,
        modifier.clickable(enabled, role = Role.Button) { asking = true },
        titleColor = MaterialTheme.colorScheme.error,
    )
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(confirmTitle) },
            text = { Text(confirmText) },
            confirmButton = {
                TextButton(onClick = {
                    asking = false
                    haptics.confirm()
                    onConfirm()
                }) { Text(confirmAction, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(stringResource(R.string.ds_cancel)) } },
        )
    }
}

/** Explanations at the end of a page: an info icon, then the text. */
@Composable
fun FooterText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 16.dp)) {
        Icon(
            painterResource(R.drawable.ds_ic_info),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(16.dp))
        Box(Modifier.weight(1f)) {
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
