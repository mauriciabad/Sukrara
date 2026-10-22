// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.R
import app.mauri.sukrara.features.FeatureState
import app.mauri.sukrara.features.Features
import app.mauri.sukrara.libreview.LibreViewUploader
import app.mauri.sukrara.tidepool.Tidepool
import app.mauri.sukrara.treatments.Treat

/** When the "share with your clinic" banner shows: until LibreView or Tidepool is set up, or the user dismisses it. */
object UploadBanner {
    /** LibreView uploads: the switch on and an account with a password. */
    fun libreViewReady(f: FeatureState, lv: LibreViewUploader.Settings) = f.libreview && lv.email.isNotEmpty() && lv.hasPassword

    /** Tidepool uploads: the switch on and signed in. */
    fun tidepoolReady(tp: Tidepool.Settings) = tp.on && tp.signedIn

    fun shows(f: FeatureState, lv: LibreViewUploader.Settings, tp: Tidepool.Settings) =
        !f.uploadBannerDismissed && !libreViewReady(f, lv) && !tidepoolReady(tp)
}

/**
 * Uploading to the clinic's record matters: one card on the wearer's home and the settings overview with a pill per
 * service (its real icon, sized like AOSP Settings' app icons), each opening that service's page. [open] goes to a route.
 */
@Composable
fun ClinicUploadBanner(open: (String) -> Unit) {
    if (indexing) return
    val ctx = LocalContext.current
    remember { Tidepool.init(ctx) }
    val f by Features.state.collectAsState()
    val lv by Treat.uploader.settings.collectAsState()
    val tp by Tidepool.settings.collectAsState()
    Reveal(UploadBanner.shows(f, lv, tp)) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Column(Modifier.padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_sym_cloud_upload), contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                    Text(stringResource(R.string.upload_banner_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = { app.mauri.sukrara.features.Tips.close(ctx, app.mauri.sukrara.features.Tips.Tip.CLINIC_UPLOAD) }) {
                        Icon(painterResource(R.drawable.ic_sym_close), contentDescription = stringResource(R.string.upload_banner_dismiss),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                }
                // actions: pills, like "Eat carbs" (the visual language)
                Row(Modifier.fillMaxWidth().padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ServicePill(Service.LIBREVIEW, "LibreView", Modifier.weight(1f)) { open("libreview") }
                    ServicePill(Service.TIDEPOOL, "Tidepool", Modifier.weight(1f)) { open("tidepool") }
                }
            }
        }
    }
}

@Composable
private fun ServicePill(s: Service, name: String, modifier: Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier, contentPadding = PaddingValues(start = 6.dp, top = 4.dp, end = 16.dp, bottom = 4.dp)) {
        ServiceIcon(s)
        Text(name, modifier = Modifier.padding(start = 8.dp), maxLines = 1)
    }
}
