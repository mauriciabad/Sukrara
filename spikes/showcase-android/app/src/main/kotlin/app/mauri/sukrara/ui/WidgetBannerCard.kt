// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import androidx.activity.compose.LocalActivity
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.mauri.sukrara.App
import app.mauri.sukrara.R
import app.mauri.sukrara.widgets.WidgetBanner
import app.mauri.sukrara.widgets.WidgetPresence

/**
 * "Put your glucose on the home screen", on the wearer's and the follower's home while no graph widget is placed
 * ([WidgetBanner.of]). One pill that pins the big widget (the launcher's own dialog, with the picker's preview), or, when the
 * launcher can't pin, where to find it by hand. A card like the other home banners (M3 cards: one coherent piece of
 * content, developer.android.com/develop/ui/compose/components/card); it never blocks the screen, as the widget
 * promotion guidance asks (developer.android.com/design/ui/mobile/guides/widgets/discovery-promotion).
 */
@Composable
fun HomeWidgetBanner() {
    val ctx = LocalContext.current
    val activity = LocalActivity.current
    // the launcher's pin dialog sits on top of the app: back on screen = check again
    LifecycleResumeEffect(Unit) { WidgetPresence.refresh(ctx); onPauseOrDispose { } }
    val s by WidgetPresence.state.collectAsState()
    val banner = WidgetBanner.of(App.role(ctx), s)
    RevealValue(banner.takeIf { it != WidgetBanner.HIDDEN }) { b ->
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Column(Modifier.padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_sym_widgets), contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                    Text(stringResource(R.string.home_widget_banner_title), style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f))
                    IconButton(onClick = { WidgetPresence.dismiss(ctx) }) {
                        Icon(painterResource(R.drawable.ic_sym_close), contentDescription = stringResource(R.string.home_widget_banner_dismiss),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                }
                if (b == WidgetBanner.PIN) {
                    // an action: a pill, like "Eat carbs" (the visual language)
                    Button(onClick = { activity?.let { WidgetPresence.pin(it) } },
                        modifier = Modifier.align(Alignment.End).padding(end = 8.dp)) {
                        Icon(painterResource(R.drawable.ic_sym_add), contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(18.dp))
                        Text(stringResource(R.string.home_widget_banner_action))
                    }
                } else {
                    Text(stringResource(R.string.home_widget_banner_manual), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 36.dp, end = 8.dp))
                }
            }
        }
    }
}
