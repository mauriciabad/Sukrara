// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.App
import app.mauri.sukrara.BuildConfig
import app.mauri.sukrara.R
import app.mauri.sukrara.core.ActiveSensor
import app.mauri.sukrara.core.SensorProfile
import java.net.URLEncoder

/**
 * The untested-sensor banner (investigation 23; the user, 2026-10-19: "when users use one of it they get a permanent
 * banner under the graph … to submit feedback as a GitHub issue. When we get enough feedback we'll remove the banner
 * for that sensor"). Shown on the **wearer's** home while the active sensor's [SensorProfile.verified] is false;
 * followers and the Mac don't show it (it is about the wearer's own sensor). Not dismissible, not red.
 */
object SensorFeedback {
    const val REPO = "https://github.com/mauriciabad/cgm-reading-mac-spike"
    const val TEMPLATE = "sensor-feedback.yml"
    const val LABEL = "sensor-feedback"

    /** The family the banner is for: the wearer's ([role] "owner") active sensor when it is unverified, else null. */
    fun bannerFor(role: String?, active: SensorProfile?): SensorProfile? = active?.takeIf { role == "owner" && !it.verified }

    /** What the issue is prefilled with besides the sensor: nothing personal (no values, serials, account, names). */
    data class Device(val appVersion: String, val android: String, val phone: String) {
        companion object {
            fun current() = Device(BuildConfig.VERSION_NAME, "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                "${Build.MANUFACTURER} ${Build.MODEL}")
        }
    }

    /**
     * The pre-filled issue: `.github/ISSUE_TEMPLATE/sensor-feedback.yml`, whose field ids are the query keys
     * (docs.github.com "Creating an issue from a URL query"). Only the family's name, the app version, the Android
     * version and the phone model go into it.
     */
    fun issueUrl(profile: SensorProfile, device: Device): String {
        val q = linkedMapOf(
            "template" to TEMPLATE, "title" to "Sensor feedback: ${profile.name}", "labels" to LABEL,
            "sensor" to profile.name, "app-version" to device.appVersion, "android" to device.android, "phone" to device.phone,
        )
        return "$REPO/issues/new?" + q.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
}

/** The banner itself, directly under the glucose panel on the wearer's home (ui/OwnerScreens `OwnerHome`). */
@Composable
fun UntestedSensorBanner() {
    val ctx = LocalContext.current
    val active by ActiveSensor.profile.collectAsState()
    val profile = SensorFeedback.bannerFor(App.role(ctx), active)
    RevealValue(profile) { p ->
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_sym_science), contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                    Text(stringResource(R.string.home_untested_banner_text, p.name), style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f))
                }
                // an action: a pill, like "Eat carbs" (the visual language)
                Button(onClick = {
                    val url = SensorFeedback.issueUrl(p, SensorFeedback.Device.current())
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                }, modifier = Modifier.align(Alignment.End)) {
                    Icon(painterResource(R.drawable.ic_sym_open_in_new), contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(18.dp))
                    Text(stringResource(R.string.home_untested_banner_action))
                }
            }
        }
    }
}
