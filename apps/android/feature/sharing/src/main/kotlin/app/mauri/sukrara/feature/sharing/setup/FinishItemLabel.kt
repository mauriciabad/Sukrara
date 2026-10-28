// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing.setup

import android.content.Context
import app.mauri.sukrara.feature.sharing.R

/** The words of a Finish-setup row, for home's "Finish setup" banner (home shows the first one). */
fun FinishItem.label(context: Context): String =
    context.getString(
        when (this) {
            FinishItem.AllowAlerts -> R.string.sh_finish_allow
            FinishItem.ConnectSensor -> R.string.sh_finish_sensor
            FinishItem.AlertsNotConfirmed -> R.string.sh_finish_alerts
            FinishItem.RestoreFollowers -> R.string.sh_finish_identity
        },
    )
