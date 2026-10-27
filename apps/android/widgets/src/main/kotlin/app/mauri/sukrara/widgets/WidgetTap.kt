// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.widgets

import android.content.Context
import android.content.Intent
import app.mauri.sukrara.core.model.NavTargets
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.graph.GlucoseLook

/**
 * What a tap on a widget does ("The pill is an action", FEATURES.md): with a value on screen, quick Add treatment
 * (Carbs first while a low is going on); without one (no reading yet, nothing for 20 min, sensor warming up or ended,
 * setup not done) the app, where the reason and its fix are.
 */
sealed interface WidgetTap {
    data object OpenApp : WidgetTap

    data class QuickTreatment(
        val focusCarbs: Boolean,
    ) : WidgetTap

    companion object {
        private val LOW_KINDS = setOf("low", "very_low")

        /**
         * [look] = the renderer's decision about the value on the widget; [firingKinds] = the alert kinds firing;
         * [mgdl] the shown value, [lowLimit] the user's low band. A follower logs on the wearer's behalf as in the app.
         */
        fun of(
            role: Role?,
            look: GlucoseLook,
            mgdl: Int?,
            lowLimit: Int,
            firingKinds: Set<String>,
        ): WidgetTap {
            if (role == null || look.state != GlucoseLook.State.VALUE) return OpenApp
            val low = firingKinds.any { it in LOW_KINDS } || (mgdl != null && mgdl < lowLimit)
            return QuickTreatment(focusCarbs = low)
        }

        /** The activity intent for [tap]: quick Add treatment when this build has it, else the app. */
        fun intent(
            context: Context,
            tap: WidgetTap,
        ): Intent {
            val launch =
                context.packageManager.getLaunchIntentForPackage(context.packageName)
                    ?: Intent(Intent.ACTION_MAIN).setPackage(context.packageName)
            if (tap !is QuickTreatment) return launch
            val quick =
                Intent(NavTargets.ACTION_QUICK_TREATMENT)
                    .setPackage(context.packageName)
                    .putExtra(NavTargets.EXTRA_FOCUS_CARBS, tap.focusCarbs)
            // until chunk 19's dialog is in the build the tap opens the app instead
            return if (quick.resolveActivity(context.packageManager) != null) quick else launch
        }
    }
}
