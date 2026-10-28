// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.pen

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.designsystem.motion.LocalReducedMotion
import app.mauri.sukrara.designsystem.motion.Motion

/**
 * The read card at the bottom of the screen: title (a polite live region, amounts spoken as words), its lines, and
 * its buttons (*Undo*, the question's answers, *Turn on* / *No*, *Add treatment*, *Close*).
 */
@Composable
fun PenReadCard(
    card: PenCard,
    reading: Boolean,
    canUndo: Boolean,
    onAction: (PenAction) -> Unit,
    onClose: () -> Unit,
    onTouch: () -> Unit,
    text: PenCardText,
    modifier: Modifier = Modifier,
) {
    val paneLabel = stringResource(R.string.pen_card_label)
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = if (card.error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
        modifier =
            modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp)
                .semantics { paneTitle = paneLabel }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent()
                            onTouch()
                        }
                    }
                },
    ) {
        val reduced = LocalReducedMotion.current
        AnimatedContent(card, transitionSpec = { Motion.fadeThrough(reduced) }, label = "pen card") { c ->
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (reading) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(
                        c.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = if (c.error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
                        modifier =
                            Modifier.semantics {
                                heading()
                                liveRegion = LiveRegionMode.Polite
                                contentDescription = text.spoken(c.title)
                            },
                    )
                }
                c.lines.forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (c.error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { contentDescription = text.spoken(line) },
                    )
                }
                if (!reading) {
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(onClick = onClose) { Text(stringResource(R.string.pen_card_close)) }
                        c.actions.filter { it != PenAction.Undo || canUndo }.forEachIndexed { i, a ->
                            val label = label(a)
                            if (i == 0 && a !is PenAction.Undo && a != PenAction.No) {
                                Button(onClick = { onAction(a) }) { Text(label) }
                            } else {
                                TextButton(onClick = { onAction(a) }) { Text(label) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun label(a: PenAction): String =
    when (a) {
        PenAction.Undo -> stringResource(R.string.pen_undo)
        PenAction.AddTreatment -> stringResource(R.string.pen_add_treatment)
        PenAction.TurnOn -> stringResource(R.string.pen_turn_on)
        PenAction.No -> stringResource(R.string.pen_no)
        is PenAction.Suggest -> a.label
        is PenAction.AllPriming -> stringResource(R.string.pen_all_priming)
        is PenAction.Same -> stringResource(if (a.entry != null) R.string.pen_m_same else R.string.pen_m_different)
        is PenAction.OpenPen -> stringResource(R.string.pen_check_it)
    }
