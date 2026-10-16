// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.core.Avatar
import app.mauri.sukrara.core.Profile

/** A profile picture: the generated avatar (or the grey initial fallback), clipped to a circle. */
@Composable
fun AvatarImage(avatar: Avatar?, name: String, size: Dp, modifier: Modifier = Modifier) {
    val px = with(LocalDensity.current) { size.roundToPx() }.coerceAtLeast(16)
    val bmp = remember(avatar, Avatar.initial(name), px) {
        (avatar?.render(name, px) ?: Avatar.fallback(name, px)).asImageBitmap()
    }
    Image(bmp, contentDescription = null, modifier = modifier.size(size).clip(CircleShape))
}

/** Avatar + name on one line (follower lists, the approval dialog, the follower's header). */
@Composable
fun ProfileRow(p: Profile?, fallbackName: String, size: Dp = 40.dp, content: @Composable () -> Unit = {
    Text(p?.name?.ifBlank { null } ?: fallbackName, style = MaterialTheme.typography.titleMedium)
}) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AvatarImage(p?.avatar, p?.name?.ifBlank { null } ?: fallbackName, size)
        Column { content() }
    }
}

private val SHAPES = listOf("cube" to "Cube", "drop" to "Drop", "dots" to "Dots", "rings" to "Rings", "letter" to "Letter")
private val PALETTE_SWATCHES = listOf(0xFF1BB5C9, 0xFFC6F432, 0xFFFF8A65, 0xFFBA68C8, 0xFFFFD54F, 0xFF4FC3F7, 0xFFF06292, 0xFF90A4AE)

/**
 * Name + generated picture. Shuffle draws a new random picture; Customize picks shape and colours (single-select
 * filter chips, as the Material chip guidance suggests for picking one option of a set). Nothing here is ever
 * published: the picture travels as a few numbers inside the encrypted invite, request and roster.
 */
@Composable
fun ProfileEditor(p: Profile, onChange: (Profile) -> Unit, nameLabel: String) {
    var customizing by remember { mutableStateOf(false) }
    // a picture from a newer app version can't be edited here: customizing starts from a fresh v1 one
    val fresh = remember { Avatar.random() }
    val avatar = p.avatar?.takeIf { it.known } ?: fresh
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AvatarImage(p.avatar, p.name, 88.dp)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = { onChange(p.copy(avatar = Avatar.random())) }) { Text("Shuffle") }
                TextButton(onClick = { customizing = !customizing }) { Text(if (customizing) "Done" else "Customize") }
            }
        }
        Reveal(customizing) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SHAPES.forEach { (s, label) ->
                        FilterChip(selected = avatar.shape == s, onClick = { onChange(p.copy(avatar = avatar.with(shape = s))) },
                            label = { Text(label) })
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PALETTE_SWATCHES.forEachIndexed { i, c ->
                        val selected = avatar.colors == i
                        androidx.compose.foundation.layout.Box(Modifier.size(36.dp).clip(CircleShape)
                            .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            .clickable(role = Role.RadioButton) { onChange(p.copy(avatar = avatar.with(colors = i))) }) {
                            androidx.compose.foundation.Canvas(Modifier.size(36.dp)) { drawCircle(androidx.compose.ui.graphics.Color(c)) }
                        }
                    }
                }
                TextButton(onClick = { onChange(p.copy(avatar = avatar.with(angle = avatar.angle + 45, seed = avatar.seed + 1))) }) {
                    Text("Turn the colours")
                }
            }
        }
        OutlinedTextField(p.name, { onChange(p.copy(name = it.take(Profile.MAX_NAME))) }, label = { Text(nameLabel) },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Only the people you share with see your name and picture. They travel end-to-end encrypted and are " +
            "never posted publicly.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Edit an existing profile (wearer: Home → your name; follower: Home → you appear as). */
@Composable
fun ProfileScreen(title: String, current: Profile, nameLabel: String, note: String, onSave: (Profile) -> Unit, onBack: () -> Unit) {
    var p by remember { mutableStateOf(current) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        ProfileEditor(p, { p = it }, nameLabel)
        Text(note, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(p); onBack() }, enabled = p.name.isNotBlank()) { Text("Save") }
            TextButton(onClick = onBack) { Text("Cancel") }
        }
    }
}
