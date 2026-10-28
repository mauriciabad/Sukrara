// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sensor.newsensor

import android.nfc.NfcAdapter
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.designsystem.motion.LocalReducedMotion
import app.mauri.sukrara.feature.sensor.R
import app.mauri.sukrara.core.designsystem.R as DsR

/** Where the antenna is when the phone doesn't say: the upper middle of the back. */
val DefaultAntenna = Offset(0.5f, 0.45f)

/**
 * Where this phone's NFC antenna is, as a fraction of the phone's back (x from the left, y from the top, back view).
 * `AvailableNfcAntenna` gives millimetres from the top left of the front in portrait, so x is mirrored.
 */
fun antennaSpot(nfc: NfcAdapter?): Offset {
    val info = runCatching { nfc?.nfcAntennaInfo }.getOrNull()
    val antenna = info?.availableNfcAntennas?.firstOrNull()
    if (info == null || antenna == null || info.deviceWidth <= 0 || info.deviceHeight <= 0) return DefaultAntenna
    return Offset(
        (1f - antenna.locationX.toFloat() / info.deviceWidth).coerceIn(0.2f, 0.8f),
        (antenna.locationY.toFloat() / info.deviceHeight).coerceIn(0.15f, 0.85f),
    )
}

/** The tap illustration's height (the hero area keeps it so the page doesn't jump between steps). */
private val HeroHeight = 220.dp

/**
 * The back of a phone with the sensor on its NFC antenna ([spot]) and rings going out from it while Sukrara listens.
 * The rings stand still with reduced motion (and in screenshots, where infinite animations don't run).
 */
@Composable
internal fun ScanArt(spot: Offset) {
    val cs = MaterialTheme.colorScheme
    val reduced = LocalReducedMotion.current
    val description = stringResource(R.string.newsensor_art_desc)
    val pulse =
        if (reduced) {
            0.3f
        } else {
            val p by rememberInfiniteTransition(label = "nfc").animateFloat(
                0f,
                1f,
                infiniteRepeatable(tween(RING_MS, easing = LinearEasing), RepeatMode.Restart),
                label = "rings",
            )
            p
        }
    Canvas(Modifier.size(width = 170.dp, height = HeroHeight).semantics { contentDescription = description }) {
        val ph = size.height * 0.96f
        val pw = ph * 0.48f
        val left = (size.width - pw) / 2
        val top = (size.height - ph) / 2
        val corner = CornerRadius(pw * 0.16f)
        drawRoundRect(cs.surfaceContainerHighest, Offset(left, top), Size(pw, ph), corner)
        drawRoundRect(cs.outline, Offset(left, top), Size(pw, ph), corner, style = Stroke(2.dp.toPx()))
        // the camera island, top left of the back
        val cam = pw * 0.28f
        val island = Offset(left + pw * 0.1f, top + pw * 0.1f)
        drawRoundRect(cs.surfaceContainerLow, island, Size(cam, cam * 1.4f), CornerRadius(cam * 0.32f))
        drawCircle(cs.outline, cam * 0.24f, Offset(island.x + cam / 2, island.y + cam * 0.4f))
        drawCircle(cs.outline, cam * 0.24f, Offset(island.x + cam / 2, island.y + cam * 1.0f))
        val center = Offset(left + pw * spot.x, top + ph * spot.y)
        val r = pw * 0.24f
        for (k in 0 until RINGS) {
            val p = (pulse + k / RINGS.toFloat()) % 1f
            drawCircle(cs.primary.copy(alpha = 0.55f * (1 - p)), r * (1f + 1.6f * p), center, style = Stroke(3.dp.toPx()))
        }
        sensorDisc(center, r)
        drawCircle(cs.primary, r, center, style = Stroke(3.dp.toPx()))
    }
}

/** A Libre seen from above: a flat disc with the filament's dot in the middle. */
private fun DrawScope.sensorDisc(center: Offset, r: Float, fill: Color = Color.White, mark: Color = Color.LightGray) {
    drawCircle(fill, r, center)
    drawCircle(mark, r * 0.32f, center)
}

/** The tag is being read: the sensor with a spinner around it. */
@Composable
internal fun ReadingArt() {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.size(HeroHeight * 0.8f).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(HeroHeight * 0.8f), strokeWidth = 8.dp, strokeCap = StrokeCap.Round)
        Canvas(Modifier.size(88.dp)) {
            sensorDisc(center, size.minDimension / 2, cs.surfaceContainerLowest, cs.outlineVariant)
            drawCircle(cs.outline, size.minDimension / 2, center, style = Stroke(size.minDimension * 0.03f))
        }
    }
}

/** The warm-up ring: how much of it is done ([done] 0..1), the countdown text in the middle. */
@Composable
internal fun WarmUpArt(done: Float, content: @Composable () -> Unit) {
    val reduced = LocalReducedMotion.current
    val shown by animateFloatAsState(done, if (reduced) tween(0) else tween(600), label = "warmup")
    Box(Modifier.size(HeroHeight * 0.9f), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = { shown },
            modifier = Modifier.size(HeroHeight * 0.9f).clearAndSetSemantics {},
            strokeWidth = 8.dp,
            strokeCap = StrokeCap.Round,
        )
        Box(Modifier.padding(24.dp), contentAlignment = Alignment.Center) { content() }
    }
}

/** A check in a filled circle: the sensor is ready (and a ticked pairing step, small). */
@Composable
internal fun CheckBadge(size: Dp, background: Color = MaterialTheme.colorScheme.primaryContainer) {
    val mark = if (background == Color.Transparent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimaryContainer
    Canvas(Modifier.size(size).clip(CircleShape).background(background)) {
        val w = this.size.width
        val path =
            Path().apply {
                moveTo(w * 0.28f, w * 0.52f)
                lineTo(w * 0.44f, w * 0.68f)
                lineTo(w * 0.74f, w * 0.36f)
            }
        drawPath(path, mark, style = Stroke(w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** The ready step's hero: a big check. */
@Composable
internal fun ReadyArt() {
    Box(Modifier.height(HeroHeight * 0.6f), contentAlignment = Alignment.Center) { CheckBadge(112.dp) }
}

/** Sensor · Scan (Set up) · Pair · Readings: reached stages filled, the current label in the primary colour. */
@Composable
internal fun StepBar(current: FlowStage, complete: Boolean, setsUp: Boolean, modifier: Modifier = Modifier) {
    val labels =
        FlowStage.entries.map {
            when (it) {
                FlowStage.Sensor -> R.string.newsensor_step_sensor
                FlowStage.Scan -> if (setsUp) R.string.newsensor_step_setup else R.string.newsensor_step_scan
                FlowStage.Pair -> R.string.newsensor_step_pair
                FlowStage.Readings -> R.string.newsensor_step_readings
            }
        }
    val description =
        stringResource(R.string.newsensor_step_desc, current.ordinal + 1, FlowStage.entries.size, stringResource(labels[current.ordinal]))
    val reduced = LocalReducedMotion.current
    // with big fonts four labels don't fit side by side: the current one alone, under the bar
    val large = LocalDensity.current.fontScale >= LARGE_FONT
    Column(modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowStage.entries.forEach { s ->
                val reached = complete || s.ordinal <= current.ordinal
                val now = s == current && !complete
                val fill by animateFloatAsState(if (reached) 1f else 0f, if (reduced) tween(0) else tween(400), label = "bar")
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val cs = MaterialTheme.colorScheme
                    Canvas(
                        Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(cs.surfaceContainerHighest),
                    ) {
                        drawRect(cs.primary, size = Size(size.width * fill, size.height))
                    }
                    if (!large) {
                        Text(
                            stringResource(labels[s.ordinal]),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (now) FontWeight.SemiBold else FontWeight.Normal,
                            color =
                                when {
                                    now -> cs.primary
                                    reached -> cs.onSurface
                                    else -> cs.onSurfaceVariant
                                },
                        )
                    }
                }
            }
        }
        if (large) {
            Text(
                stringResource(labels[current.ordinal]),
                Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private const val LARGE_FONT = 1.5f

/** Connecting → pairing securely → starting readings, each ticked off as the link moves on. */
@Composable
internal fun PairSteps(at: PairProgress) {
    val labels = listOf(R.string.newsensor_pair_connecting, R.string.newsensor_pair_securing, R.string.newsensor_pair_starting)
    val done = stringResource(R.string.newsensor_pair_state_done)
    val inProgress = stringResource(R.string.newsensor_pair_state_now)
    val next = stringResource(R.string.newsensor_pair_state_next)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PairProgress.entries.forEach { s ->
            val cs = MaterialTheme.colorScheme
            Row(
                Modifier.semantics(mergeDescendants = true) {
                    stateDescription =
                        when {
                            s.ordinal < at.ordinal -> done
                            s == at -> inProgress
                            else -> next
                        }
                },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    s.ordinal < at.ordinal -> {
                        CheckBadge(24.dp, Color.Transparent)
                    }

                    s == at -> {
                        CircularProgressIndicator(Modifier.size(24.dp).padding(2.dp), strokeWidth = 3.dp)
                    }

                    else -> {
                        Box(
                            Modifier
                                .size(24.dp)
                                .padding(6.dp)
                                .clip(CircleShape)
                                .background(cs.surfaceContainerHighest),
                        )
                    }
                }
                Text(
                    stringResource(labels[s.ordinal]),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (s.ordinal > at.ordinal) cs.onSurfaceVariant else cs.onSurface,
                    fontWeight = if (s == at) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/** A note card under the text: an icon, a title and a line or two. */
@Composable
internal fun Note(title: String, text: String) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(
                painterResource(DsR.drawable.ds_ic_info),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private const val RINGS = 3
private const val RING_MS = 1_800
