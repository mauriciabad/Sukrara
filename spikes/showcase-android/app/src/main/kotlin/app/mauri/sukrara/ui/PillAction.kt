// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.alerts.AlertStatus
import app.mauri.sukrara.alerts.Kind
import kotlin.math.roundToInt

/**
 * What a tap on the status pill under the value does (round f33, the user: "tapping Eat carbs does nothing"): the
 * pill is an action (the visual language), so every pill does something. The Mac decides the same
 * (`PillAction` in Panel.swift).
 * - *Eat carbs* / *Take insulin* (Needed) → Add treatment with the carbs / rapid field focused, where this device may
 *   add entries ([canAdd]: the wearer, a follower that's a writer); a follower that can't add entries gets none
 *   (its home says why).
 * - *45m left* / *Muted* on one side (Taken, Paused) → that side's ways out (the quiet sheet).
 * - *Muted · 1h* (Mute all) → Mute all's lengths (Unmute / Change).
 */
sealed interface PillAction {
    /** Open Add treatment with [field] (`carbs` / `rapid`) focused. */
    data class Treat(val field: String) : PillAction
    /** Open the quiet sheet of [kind]. */
    data class Quiet(val kind: String) : PillAction
    data object MuteAll : PillAction
    data object None : PillAction

    companion object {
        fun of(s: AlertStatus, canAdd: Boolean): PillAction = when (s) {
            AlertStatus.None -> None
            is AlertStatus.Needed -> if (canAdd) Treat(field(s.kind)) else None
            is AlertStatus.Taken -> Quiet(s.kind)
            is AlertStatus.Paused -> Quiet(s.kind)
            is AlertStatus.Muted -> MuteAll
        }

        /** The field that answers a level: carbs for the low side, rapid insulin for the high side. */
        fun field(kind: String) = if (kind in Kind.LOW_SIDE) "carbs" else "rapid"
    }
}

/**
 * The pill's button over the renderer's drawing (the card draws the pill; this only makes it act): a node at [box]
 * (px, the header's coordinates) with the Button role, the pill's words as its description and [actionLabel] as the
 * click label (TalkBack: "Eat carbs, Button, double-tap to Add treatment"), a capsule ripple the pill's size and a touch
 * target of at least 48 dp (M3 / Android accessibility: minimum touch target).
 */
@androidx.compose.runtime.Composable
fun PillButton(box: android.graphics.RectF, description: String, actionLabel: String, onClick: () -> Unit) {
    val d = androidx.compose.ui.platform.LocalDensity.current
    val min = with(d) { 48.dp.toPx() }
    val padX = with(d) { 8.dp.toPx() }
    val w = box.width() + 2 * padX
    val h = maxOf(box.height(), min)
    val left = box.left - padX
    val top = box.centerY() - h / 2
    val interaction = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    androidx.compose.foundation.layout.Box(
        androidx.compose.ui.Modifier
            .offset { androidx.compose.ui.unit.IntOffset(left.roundToInt(), top.roundToInt()) }
            .size(with(d) { w.toDp() }, with(d) { h.toDp() })
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClickLabel = actionLabel, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag("status_pill"),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.size(with(d) { box.width().toDp() }, with(d) { box.height().toDp() })
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(percent = 50))
                .indication(interaction, androidx.compose.material3.ripple()))
    }
}
