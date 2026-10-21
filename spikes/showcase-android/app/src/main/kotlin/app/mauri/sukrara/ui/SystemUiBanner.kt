// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.mauri.sukrara.R
import app.mauri.sukrara.features.Features
import app.mauri.sukrara.features.SystemAccess
import app.mauri.sukrara.status.RootStatusBar
import kotlinx.coroutines.launch

/**
 * "Restart System UI" on the home screen ([SystemAccess.homeBanner]): only while a status bar or lock screen feature
 * that runs in System UI is on and System UI doesn't run this build's code. A card like the other home banners
 * ([HomeWidgetBanner], [ClinicUploadBanner]): icon, title, close, one line, the action as a pill (it needs root; without
 * root the line says what to do instead). Settings → Status bar / Lock screen show the same state as a banner.
 */
@Composable
fun SystemUiRestartBanner() {
    LifecycleResumeEffect(Unit) { SystemAccess.refresh(); onPauseOrDispose { } }
    val s by SystemAccess.status.collectAsState()
    val f by Features.state.collectAsState()
    val dismissed by SystemAccess.dismissed.collectAsState()
    val root by RootStatusBar.root.collectAsState()
    var restarting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Reveal(SystemAccess.homeBanner(s, f, dismissed)) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Column(Modifier.padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_sym_restart_alt), contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                    Text(stringResource(R.string.home_restart_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = SystemAccess::dismiss) {
                        Icon(painterResource(R.drawable.ic_sym_close), contentDescription = stringResource(R.string.home_restart_dismiss),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                }
                Text(stringResource(when {
                    root != true -> R.string.home_restart_text_no_root
                    s.module == SystemAccess.Module.UPDATE -> R.string.home_restart_text_updated
                    else -> R.string.home_restart_text
                }), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 36.dp, end = 8.dp))
                if (root == true) Button(onClick = {
                    restarting = true
                    scope.launch { SystemAccess.restartSystemUi(); restarting = false }
                }, enabled = !restarting, modifier = Modifier.align(Alignment.End).padding(end = 8.dp)) {
                    Icon(painterResource(R.drawable.ic_sym_restart_alt), contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(18.dp))
                    Text(stringResource(R.string.home_restart_action))
                }
            }
        }
    }
}
