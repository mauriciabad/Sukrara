// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing.profile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.designsystem.avatar.AvatarImage
import app.mauri.sukrara.designsystem.haptics.rememberHaptics
import app.mauri.sukrara.designsystem.motion.Reveal
import app.mauri.sukrara.feature.sharing.R
import app.mauri.sukrara.protocol.event.Avatar
import app.mauri.sukrara.protocol.event.Profile
import java.security.SecureRandom

/** The five v1 shapes, as the editor names them. */
private val SHAPES =
    listOf(
        "cube" to R.string.sh_avatar_shape_cube,
        "drop" to R.string.sh_avatar_shape_drop,
        "dots" to R.string.sh_avatar_shape_dots,
        "rings" to R.string.sh_avatar_shape_rings,
        "letter" to R.string.sh_avatar_shape_letter,
    )

/** One swatch per v1 palette (its first colour), named for TalkBack. */
private val SWATCHES =
    listOf(
        0xFF1BB5C9 to R.string.sh_avatar_colour_teal,
        0xFFC6F432 to R.string.sh_avatar_colour_lime,
        0xFFFF8A65 to R.string.sh_avatar_colour_coral,
        0xFFBA68C8 to R.string.sh_avatar_colour_purple,
        0xFFFFD54F to R.string.sh_avatar_colour_amber,
        0xFF4FC3F7 to R.string.sh_avatar_colour_sky,
        0xFFF06292 to R.string.sh_avatar_colour_pink,
        0xFF90A4AE to R.string.sh_avatar_colour_grey,
    )

private val random = SecureRandom()

/** A new person's starting picture: random, any shape but the letter. */
fun randomAvatar(): Avatar = Avatar.random(random)

/**
 * Name + generated picture. Shuffle draws a new random picture; Customize picks shape and colours (single-select chips
 * and swatches) and Turn the colours. Nothing here is published: the picture travels as a few numbers inside the
 * encrypted invite and roster.
 */
@Composable
fun ProfileEditor(
    profile: Profile,
    onChange: (Profile) -> Unit,
    modifier: Modifier = Modifier,
) {
    var customizing by rememberSaveable { mutableStateOf(false) }
    val haptics = rememberHaptics()
    // a picture from a newer app version can't be edited here: customizing starts from a fresh v1 one
    val fresh = remember { randomAvatar() }
    val avatar = profile.avatar?.takeIf { it.known } ?: fresh
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AvatarImage(profile.avatar, profile.name, size = 88.dp)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = {
                    haptics.tick()
                    onChange(profile.copy(avatar = randomAvatar()))
                }) { Text(stringResource(R.string.sh_avatar_shuffle)) }
                TextButton(onClick = { customizing = !customizing }) {
                    Text(stringResource(if (customizing) R.string.sh_avatar_done else R.string.sh_avatar_customize))
                }
            }
        }
        Reveal(customizing) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.selectableGroup()) {
                    SHAPES.forEach { (s, label) ->
                        FilterChip(
                            selected = avatar.shape == s,
                            onClick = { onChange(profile.copy(avatar = avatar.withShape(s))) },
                            label = { Text(stringResource(label)) },
                        )
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.selectableGroup(),
                ) {
                    SWATCHES.forEachIndexed { i, (color, name) ->
                        val selected = avatar.colors == i
                        val label = stringResource(name)
                        Box(
                            Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                .selectable(selected, role = Role.RadioButton) {
                                    haptics.tick()
                                    onChange(profile.copy(avatar = avatar.withColors(i)))
                                }.semantics { contentDescription = label },
                            contentAlignment = Alignment.Center,
                        ) {
                            Canvas(Modifier.size(36.dp)) { drawCircle(Color(color)) }
                        }
                    }
                }
                TextButton(onClick = { onChange(profile.copy(avatar = avatar.turned())) }) {
                    Text(stringResource(R.string.sh_avatar_turn_colours))
                }
            }
        }
        OutlinedTextField(
            value = profile.name,
            onValueChange = { onChange(profile.copy(name = it.take(Profile.MAX_NAME))) },
            label = { Text(stringResource(R.string.sh_profile_name)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.sh_profile_privacy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
