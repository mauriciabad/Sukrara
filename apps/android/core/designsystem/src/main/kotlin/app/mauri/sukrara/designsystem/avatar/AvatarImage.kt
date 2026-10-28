// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.designsystem.avatar

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.protocol.event.Avatar

/**
 * A person's generated picture (or the grey initial when there is none / its version is newer than this build), clipped
 * to a circle. Decorative: the name shown next to it is what TalkBack reads.
 */
@Composable
fun AvatarImage(
    avatar: Avatar?,
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val drawing = remember(avatar, Avatar.initial(name)) { AvatarDrawing.of(avatar, name) }
    Canvas(modifier.size(size).clip(CircleShape)) { draw(drawing) }
}

/** [AvatarImage] from stored JSON text (a follower record, a request). */
@Composable
fun AvatarImage(
    avatarJson: String?,
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val avatar = remember(avatarJson) { Avatar.parse(avatarJson) }
    AvatarImage(avatar, name, modifier, size)
}

private fun DrawScope.draw(d: AvatarDrawing) {
    val s = size.minDimension

    fun p(pt: AvatarDrawing.Point) = Offset((pt.x * s).toFloat(), (pt.y * s).toFloat())
    when (val bg = d.background) {
        is AvatarDrawing.Background.Gradient -> {
            drawRect(Brush.linearGradient(listOf(Color(bg.a), Color(bg.b)), start = p(bg.from), end = p(bg.to)))
        }

        is AvatarDrawing.Background.Solid -> {
            drawRect(Color(bg.color))
        }
    }
    for (m in d.marks) {
        val white = Color.White.copy(alpha = m.alpha / 255f)
        when (m) {
            is AvatarDrawing.Mark.Polygon -> {
                val path =
                    Path().apply {
                        m.points.forEachIndexed { i, pt -> p(pt).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } }
                        close()
                    }
                drawPath(path, white)
            }

            is AvatarDrawing.Mark.Circle -> {
                drawCircle(white, (m.r * s).toFloat(), p(m.center))
            }

            is AvatarDrawing.Mark.Ring -> {
                drawCircle(white, (m.r * s).toFloat(), p(m.center), style = Stroke((m.width * s).toFloat()))
            }

            is AvatarDrawing.Mark.Letter -> {
                drawIntoCanvas { canvas ->
                    val paint =
                        Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = android.graphics.Color.WHITE
                            alpha = m.alpha
                            textSize = s * .5f
                            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                            textAlign = Paint.Align.CENTER
                        }
                    val bounds = Rect().also { paint.getTextBounds(m.text, 0, m.text.length, it) }
                    canvas.nativeCanvas.drawText(m.text, s / 2, s / 2 - bounds.exactCenterY(), paint)
                }
            }
        }
    }
}
