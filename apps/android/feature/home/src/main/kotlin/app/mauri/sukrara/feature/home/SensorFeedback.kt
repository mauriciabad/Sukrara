// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.designsystem.haptics.rememberHaptics
import app.mauri.sukrara.designsystem.motion.Reveal
import java.net.URLEncoder

/**
 * Feedback for a sensor family Sukrara reads but never confirmed on a real sensor: a pre-filled GitHub issue on the
 * project (`.github/ISSUE_TEMPLATE/sensor-feedback.yml`, whose field ids are the query keys). Only the family's name,
 * the app version, the Android version and the phone model go into it: no values, serials, accounts or names.
 */
object SensorFeedback {
    const val REPO = "https://github.com/mauriciabad/sukrara"
    const val TEMPLATE = "sensor-feedback.yml"
    const val LABEL = "sensor-feedback"

    /** What the issue says about the phone besides the sensor. */
    data class Device(val appVersion: String, val android: String, val phone: String) {
        companion object {
            fun of(context: Context): Device =
                Device(
                    appVersion =
                        runCatching {
                            context.packageManager
                                .getPackageInfo(
                                    context.packageName,
                                    0,
                                ).versionName
                        }.getOrNull().orEmpty(),
                    android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                    phone = "${Build.MANUFACTURER} ${Build.MODEL}",
                )
        }
    }

    /** The new-issue URL for [family] (its brand name, e.g. "Dexcom G7 / ONE+") from [device]. */
    fun issueUrl(
        family: String,
        device: Device,
    ): String {
        val q =
            linkedMapOf(
                "template" to TEMPLATE,
                "title" to "Sensor feedback: $family",
                "labels" to LABEL,
                "sensor" to family,
                "app-version" to device.appVersion,
                "android" to device.android,
                "phone" to device.phone,
            )
        return "$REPO/issues/new?" + q.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
}

/**
 * Under the card on the wearer's home while their sensor's family is untested ([family], null = hidden): a flask, *<family>
 * support is untested. Tell us how it works.* and *Send feedback* (the issue form in the browser). Not dismissible and
 * not red: it leaves when the sensor or the source changes, or the family is verified.
 */
@Composable
internal fun UntestedSensorCard(family: String?) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    // keep the last family while the card animates out
    var shown by remember { mutableStateOf(family) }
    if (family != null) shown = family
    Reveal(family != null) {
        val name = shown ?: return@Reveal
        Card(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(R.drawable.home_ic_science),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(
                        stringResource(R.string.home_untested_sensor, name),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
                Button(
                    onClick = {
                        haptics.tick()
                        val url = SensorFeedback.issueUrl(name, SensorFeedback.Device.of(context))
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }.onFailure { Toast.makeText(context, context.getString(R.string.home_no_browser), Toast.LENGTH_LONG).show() }
                    },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(stringResource(R.string.home_send_feedback))
                }
            }
        }
    }
}
