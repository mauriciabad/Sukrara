// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing.share

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import app.mauri.sukrara.designsystem.avatar.AvatarImage
import app.mauri.sukrara.designsystem.haptics.rememberHaptics
import app.mauri.sukrara.feature.sharing.R
import app.mauri.sukrara.share.owner.PendingRequest
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/** Whether the follower's name looks like the label the wearer gave the invite (a hint, never proof); null = no label. */
fun labelMatches(label: String, name: String): Boolean? {
    fun norm(x: String) = x.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    val l = norm(label)
    val n = norm(name)
    if (l.isEmpty()) return null
    return n.isNotEmpty() && (l == n || l.split(' ').contains(n) || n.split(' ').contains(l))
}

/** A name someone else chose, safe to show: no control or direction-changing characters, one line, trimmed. */
fun shownName(name: String): String =
    name
        .filterNot { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() }
        .trim()

/** How long Allow stays inactive after a request appears, so a tap meant for the screen below can't allow it. */
const val ALLOW_ARM_MS = 600L

/**
 * Someone used an invite and asks to follow. The follower's picture and name head it; a card shows the invite they
 * used (the wearer's own label, whether the name matches it, when it was sent and asked); then the code to compare
 * with the one on the follower's phone. When other phones also tried the same link, the wearer types that code instead.
 * Allow is armed after [ALLOW_ARM_MS] and once per tap; Deny refuses; Later (also Back) keeps the request waiting.
 * Not dismissed by tapping outside.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApproveDialog(
    request: PendingRequest,
    working: Boolean,
    onAllow: (typedCode: String?) -> Unit,
    onDeny: () -> Unit,
    onLater: () -> Unit,
    modifier: Modifier = Modifier,
    time: (Long) -> String = ::shortTime,
) {
    val haptics = rememberHaptics()
    val cs = MaterialTheme.colorScheme
    val name = shownName(request.name).ifBlank { stringResource(R.string.sh_approve_unnamed) }
    val leaked = request.others > 0
    var armed by remember(request.follower) { mutableStateOf(false) }
    var typed by remember(request.follower) { mutableStateOf("") }
    LaunchedEffect(request.follower) {
        delay(ALLOW_ARM_MS)
        armed = true
    }
    BasicAlertDialog(onDismissRequest = onLater, modifier = modifier, properties = DialogProperties(dismissOnClickOutside = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = cs.surfaceContainerHigh,
            tonalElevation = AlertDialogDefaults.TonalElevation,
        ) {
            Column(
                Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AvatarImage(request.avatarJson, name, size = 96.dp)
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.sh_approve_title, name),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(16.dp))
                InviteCard(request, name, time)
                if (leaked) {
                    Spacer(Modifier.height(12.dp))
                    Surface(shape = MaterialTheme.shapes.large, color = cs.errorContainer, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            pluralStringResource(R.plurals.sh_approve_others, request.others, request.others),
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onErrorContainer,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    val wrong = typed.length == CODE_LEN && typed != request.code
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it.filter(Char::isDigit).take(CODE_LEN) },
                        singleLine = true,
                        label = { Text(stringResource(R.string.sh_approve_type_code)) },
                        isError = wrong,
                        supportingText = { if (wrong) Text(stringResource(R.string.sh_approve_code_wrong)) },
                        textStyle =
                            MaterialTheme.typography.headlineSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                textAlign = TextAlign.Center,
                            ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.sh_approve_code_intro), style = MaterialTheme.typography.bodyMedium)
                    val spaced = request.code.toList().joinToString(" ")
                    Text(
                        request.code,
                        style = MaterialTheme.typography.headlineMedium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.semantics { contentDescription = spaced },
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.sh_approve_they_see), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(20.dp))
                // wraps at large font sizes instead of squeezing the buttons
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(
                        onClick = onLater,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.sh_approve_later)) }
                    TextButton(
                        onClick = {
                            haptics.reject()
                            onDeny()
                        },
                        enabled = !working,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.sh_approve_deny)) }
                    Button(
                        onClick = {
                            haptics.confirm()
                            onAllow(typed.takeIf { leaked })
                        },
                        enabled = armed && !working && (!leaked || typed == request.code),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.sh_approve_allow)) }
                }
            }
        }
    }
}

/** The invite the request used: the wearer's label for it, a match hint, and when it was sent and asked. */
@Composable
private fun InviteCard(
    request: PendingRequest,
    name: String,
    time: (Long) -> String,
) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.large, color = cs.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.sh_approve_your_invite), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (request.label.isNotBlank()) {
                        stringResource(R.string.sh_approve_invite_for, request.label)
                    } else {
                        stringResource(R.string.sh_approve_no_label)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // a hint only: anyone can type any name; the leaked-link case relies on the code instead
                if (request.others == 0) {
                    when (labelMatches(request.label, name)) {
                        true -> Pill(stringResource(R.string.sh_approve_label_matches), cs.primaryContainer, cs.onPrimaryContainer)
                        false -> Pill(stringResource(R.string.sh_approve_label_differs), cs.tertiaryContainer, cs.onTertiaryContainer)
                        null -> Unit
                    }
                }
            }
            Text(
                if (request.inviteSentAt > 0) {
                    stringResource(R.string.sh_approve_sent_asked, time(request.inviteSentAt), time(request.at))
                } else {
                    stringResource(R.string.sh_approve_asked, time(request.at))
                },
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Pill(
    text: String,
    bg: Color,
    fg: Color,
) {
    Surface(shape = CircleShape, color = bg) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = fg,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

private const val CODE_LEN = 4

/** Hours and minutes in the phone's format (epoch s), with the day when it isn't today. */
fun shortTime(epochS: Long): String {
    val at = epochS * 1000
    val today = DateUtils.isToday(at)
    return (if (today) DateFormat.getTimeInstance(DateFormat.SHORT) else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT))
        .format(Date(at))
}
