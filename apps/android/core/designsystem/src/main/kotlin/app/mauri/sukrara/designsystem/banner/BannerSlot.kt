// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.designsystem.banner

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.core.designsystem.R
import app.mauri.sukrara.designsystem.motion.Reveal
import app.mauri.sukrara.designsystem.motion.Swap

/** How much a banner matters: [Safety] = alerts or readings may not work (error colours, filled action); [Info] = a tip. */
enum class BannerSeverity { Safety, Info }

/**
 * One non-glucose banner (set up X, permission missing). [id] keys the cross-fade
 * from one banner to the next; [onDismiss] null = it can't be dismissed (it goes away only when fixed through
 * [action], e.g. a missing permission that makes alerts unreliable) — so every banner has an action or a dismiss.
 */
data class Banner(
    val id: String,
    @param:DrawableRes val icon: Int,
    val title: String,
    val text: String,
    val action: String?,
    val onAction: () -> Unit,
    val onDismiss: (() -> Unit)?,
    val severity: BannerSeverity = BannerSeverity.Info,
) {
    init {
        require(action != null || onDismiss != null) { "banner $id has no way out: give it an action or a dismiss" }
    }
}

/**
 * The single banner place above the content: shows at most one banner — the caller passes the highest-priority one
 * (or null) so banners never stack between the person and their glucose. Changes fade/expand (reduced motion: snap)
 * and are announced politely by TalkBack.
 */
@Composable
fun BannerSlot(
    banner: Banner?,
    modifier: Modifier = Modifier,
) {
    // keep the last banner while it animates out
    var shown by remember { mutableStateOf(banner) }
    if (banner != null) shown = banner
    Reveal(banner != null, modifier) {
        shown?.let { b -> Swap(b.id) { _ -> BannerCard(b) } }
    }
}

@Composable
private fun BannerCard(b: Banner) {
    val safety = b.severity == BannerSeverity.Safety
    val colors = MaterialTheme.colorScheme
    val container = if (safety) colors.errorContainer else colors.surfaceContainerHigh
    val content = if (safety) colors.onErrorContainer else colors.onSurface
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                // a safety banner's icon is never the informational primary blue
                Icon(painterResource(b.icon), contentDescription = null, tint = if (safety) content else colors.primary,
                    modifier = Modifier.padding(top = 12.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f).padding(top = 10.dp)) {
                    Text(b.title, style = MaterialTheme.typography.titleMedium)
                    Text(b.text, style = MaterialTheme.typography.bodyMedium, color = if (safety) content else colors.onSurfaceVariant)
                }
                b.onDismiss?.let {
                    IconButton(onClick = it) {
                        Icon(painterResource(R.drawable.ds_ic_close), contentDescription = stringResource(R.string.ds_dismiss))
                    }
                }
            }
            b.action?.let { label ->
                // a safety action starts at the text's edge, clear of the floating button in the bottom-end corner
                Row(
                    Modifier.fillMaxWidth().padding(start = 40.dp, end = 12.dp, top = 8.dp),
                    horizontalArrangement = if (safety) Arrangement.Start else Arrangement.End,
                ) {
                    if (safety) {
                        Button(onClick = b.onAction, colors = ButtonDefaults.buttonColors(containerColor = colors.error, contentColor = colors.onError)) {
                            Text(label)
                        }
                    } else {
                        FilledTonalButton(onClick = b.onAction) { Text(label) }
                    }
                }
            }
        }
    }
}
