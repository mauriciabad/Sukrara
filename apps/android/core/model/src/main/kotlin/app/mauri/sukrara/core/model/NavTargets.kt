// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core.model

/**
 * Intent actions that open a place of the app from outside a screen (notifications, widgets), so the modules that
 * post them don't depend on the module that shows them. Intents set the app's package explicitly.
 */
object NavTargets {
    /** The quick "Add treatment" dialog over any app (activity declares this action). */
    const val ACTION_QUICK_TREATMENT = "app.mauri.sukrara.action.QUICK_TREATMENT"

    /** Boolean extra of [ACTION_QUICK_TREATMENT]: open with Carbs focused (a low is going on). */
    const val EXTRA_FOCUS_CARBS = "app.mauri.sukrara.extra.FOCUS_CARBS"

    /** The new-sensor / sensor setup flow. */
    const val ACTION_SENSOR_SETUP = "app.mauri.sukrara.action.SENSOR_SETUP"
}
