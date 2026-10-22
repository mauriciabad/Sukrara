// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import android.content.Context
import androidx.annotation.StringRes
import app.mauri.sukrara.R
import app.mauri.sukrara.alerts.AlertSounds
import app.mauri.sukrara.features.HookPosition
import app.mauri.sukrara.features.LockAppearance
import app.mauri.sukrara.features.LockBackground
import app.mauri.sukrara.graph.Band
import app.mauri.sukrara.graph.GraphPlace
import app.mauri.sukrara.graph.LastPlace
import app.mauri.sukrara.graph.SlotKind
import app.mauri.sukrara.graph.TirRange

/*
 * The words of the settings' enums and option values, resolved at the UI edge (like core/Words.kt and
 * AlertNotifier.headline): the models (graph, features, alerts) keep plain values and no text, the pages pick the
 * string resource here.
 */

@StringRes fun slotKindLabel(k: SlotKind) = when (k) {
    SlotKind.TIR -> R.string.graph_slot_tir; SlotKind.GMI -> R.string.graph_slot_gmi
    SlotKind.AVERAGE -> R.string.graph_slot_average; SlotKind.CV -> R.string.graph_slot_cv
}

@StringRes fun lastPlaceLabel(p: LastPlace) = when (p) {
    LastPlace.OFF -> R.string.settings_off; LastPlace.NEAR_VALUE -> R.string.graph_last_near; LastPlace.END -> R.string.graph_last_end
}

@StringRes fun tirRangeLabel(r: TirRange) = when (r) { TirRange.IN_RANGE -> R.string.graph_tir_in_range; TirRange.TIGHT -> R.string.graph_tir_very_good }

@StringRes fun graphPlaceLabel(p: GraphPlace) = when (p) {
    GraphPlace.APP -> R.string.graph_place_app; GraphPlace.NOTIFICATION -> R.string.graph_place_notification; GraphPlace.WIDGETS -> R.string.graph_place_widgets
}

/** A ring segment with its mg/dL span: "Low (54–69)"; the format takes the band's limits. */
@StringRes fun bandLabel(b: Band) = when (b) {
    Band.VERY_LOW -> R.string.graph_band_very_low; Band.LOW -> R.string.graph_band_low; Band.TIGHT -> R.string.graph_band_tight
    Band.TARGET -> R.string.graph_band_target; Band.HIGH -> R.string.graph_band_high; Band.VERY_HIGH -> R.string.graph_band_very_high
}

@StringRes fun lockBackgroundLabel(v: String) = when (v) {
    LockBackground.NONE -> R.string.lock_bg_none; LockBackground.SHADOW -> R.string.lock_bg_shadow; LockBackground.DIM -> R.string.lock_bg_dim
    LockBackground.CARD -> R.string.lock_bg_card; else -> R.string.lock_bg_frosted
}

@StringRes fun lockBackgroundSummary(v: String) = when (v) {
    LockBackground.NONE -> R.string.lock_bg_none_summary; LockBackground.SHADOW -> R.string.lock_bg_shadow_summary
    LockBackground.DIM -> R.string.lock_bg_dim_summary; LockBackground.CARD -> R.string.lock_bg_card_summary
    else -> R.string.lock_bg_frosted_summary
}

@StringRes fun lockAppearanceLabel(v: String) = when (v) {
    LockAppearance.LIGHT -> R.string.lock_look_light; LockAppearance.DARK -> R.string.lock_look_dark; else -> R.string.lock_look_auto
}

@StringRes fun lockAppearanceSummary(v: String) = when (v) {
    LockAppearance.LIGHT -> R.string.lock_look_light_summary; LockAppearance.DARK -> R.string.lock_look_dark_summary
    else -> R.string.lock_look_auto_summary
}

/** The status bar value's placements; null for a value Settings doesn't offer. */
@StringRes fun hookPositionLabel(pos: String): Int? = when (pos) {
    HookPosition.AFTER_CLOCK -> R.string.statusbar_pos_after_clock
    HookPosition.BEFORE_CLOCK -> R.string.statusbar_pos_before_clock
    "${HookPosition.ICONS_PREFIX}0" -> R.string.statusbar_pos_left_of_icons
    HookPosition.SLOT_PREFIX -> R.string.statusbar_pos_with_icons
    "${HookPosition.SLOT_PREFIX}vpn" -> R.string.statusbar_pos_after_vpn
    "${HookPosition.SLOT_PREFIX}bluetooth" -> R.string.statusbar_pos_after_bluetooth
    HookPosition.DEFAULT -> R.string.statusbar_pos_before_mobile
    "${HookPosition.SLOT_PREFIX}mobile" -> R.string.statusbar_pos_after_mobile
    "${HookPosition.SLOT_PREFIX}wifi" -> R.string.statusbar_pos_after_wifi
    else -> null
}

/** The sensor link's BLE connection priority. */
@StringRes fun priorityLabel(p: String) = when (p) {
    "low" -> R.string.advanced_priority_low; "balanced" -> R.string.advanced_priority_balanced
    "high" -> R.string.advanced_priority_high; else -> R.string.advanced_priority_default
}

/** A sound pack with its style: "Arcade (8-bit)". */
fun soundLabel(ctx: Context, p: String): String = ctx.getString(when (p) {
    "arcade" -> R.string.sound_arcade; "kart" -> R.string.sound_kart; "quest" -> R.string.sound_quest; "soft" -> R.string.sound_soft
    AlertSounds.SYSTEM -> R.string.sound_system; else -> R.string.sound_none
})

/** A sound pack's name alone: "Arcade". */
fun soundName(ctx: Context, p: String): String = ctx.getString(when (p) {
    "arcade" -> R.string.sound_arcade_name; "kart" -> R.string.sound_kart_name; "quest" -> R.string.sound_quest_name
    "soft" -> R.string.sound_soft_name; AlertSounds.SYSTEM -> R.string.sound_system; else -> R.string.sound_none
})
