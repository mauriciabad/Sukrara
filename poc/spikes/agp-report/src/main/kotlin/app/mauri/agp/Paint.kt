// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.agp

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToLong

/**
 * Java2D painter for the JVM renders. Each op maps 1:1 onto android.graphics.Canvas (drawRect/drawRoundRect, drawLine
 * with a DashPathEffect, drawPath, drawText with Paint.Align, clipRect inside save/restore), which is what the app's
 * PdfDocument page would get.
 */
object Java2dPainter {
    fun png(page: Page, file: File, scale: Double = 2.0) {
        val img = BufferedImage((page.w * scale).toInt(), (page.h * scale).toInt(), BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.color = Color.WHITE
        g.fillRect(0, 0, img.width, img.height)
        g.scale(scale, scale)
        val saved = ArrayList<java.awt.Shape?>()
        for (op in page.ops) when (op) {
            is Op.Rect -> {
                g.color = Color(op.fill, true)
                g.fill(if (op.r > 0) RoundRectangle2D.Double(op.x, op.y, op.w, op.h, op.r * 2, op.r * 2) else Rectangle2D.Double(op.x, op.y, op.w, op.h))
            }
            is Op.Line -> {
                g.color = Color(op.color, true)
                g.stroke = if (op.dash > 0) BasicStroke(op.width.toFloat(), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, floatArrayOf(op.dash.toFloat(), op.dash.toFloat()), 0f)
                else BasicStroke(op.width.toFloat())
                g.draw(java.awt.geom.Line2D.Double(op.x1, op.y1, op.x2, op.y2))
            }
            is Op.Poly -> {
                val path = Path2D.Double()
                path.moveTo(op.pts[0], op.pts[1])
                for (i in 2 until op.pts.size step 2) path.lineTo(op.pts[i], op.pts[i + 1])
                if (op.closed) path.closePath()
                op.fill?.let { g.color = Color(it, true); g.fill(path) }
                op.stroke?.let { g.color = Color(it, true); g.stroke = BasicStroke(op.width.toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); g.draw(path) }
            }
            is Op.Text -> {
                g.font = Font("Helvetica", if (op.bold) Font.BOLD else Font.PLAIN, 1).deriveFont(op.size.toFloat())
                g.color = Color(op.color, true)
                val tw = g.fontMetrics.getStringBounds(op.text, g).width
                val x = when (op.align) { 0 -> op.x - tw / 2; 1 -> op.x - tw; else -> op.x }
                g.drawString(op.text, x.toFloat(), op.y.toFloat())
            }
            is Op.Clip -> { saved += g.clip; g.clip(Rectangle2D.Double(op.x, op.y, op.w, op.h)) }
            Op.Unclip -> g.clip = saved.removeAt(saved.lastIndex)
        }
        g.dispose()
        file.parentFile?.mkdirs()
        ImageIO.write(img, "png", file)
    }
}

/** The display list as JSON, for the Mac painter (`mac/`, `agp-mac paint`). */
object PageJson {
    private fun r2(x: Double) = JsonPrimitive((x * 100).roundToLong() / 100.0)
    private fun hex(c: Int) = "#%08X".format(c)

    fun write(page: Page, file: File) {
        val o = buildJsonObject {
            put("w", page.w); put("h", page.h)
            put("ops", buildJsonArray {
                for (op in page.ops) add(when (op) {
                    is Op.Rect -> buildJsonObject { put("k", "rect"); put("x", r2(op.x)); put("y", r2(op.y)); put("w", r2(op.w)); put("h", r2(op.h)); put("fill", hex(op.fill)); put("r", op.r) }
                    is Op.Line -> buildJsonObject { put("k", "line"); put("x1", r2(op.x1)); put("y1", r2(op.y1)); put("x2", r2(op.x2)); put("y2", r2(op.y2)); put("color", hex(op.color)); put("width", op.width); put("dash", op.dash) }
                    is Op.Poly -> buildJsonObject {
                        put("k", "poly"); put("pts", buildJsonArray { op.pts.forEach { add(r2(it)) } }); put("closed", op.closed)
                        op.fill?.let { put("fill", hex(it)) }; op.stroke?.let { put("stroke", hex(it)) }; put("width", op.width)
                    }
                    is Op.Text -> buildJsonObject { put("k", "text"); put("x", r2(op.x)); put("y", r2(op.y)); put("text", op.text); put("size", op.size); put("bold", op.bold); put("color", hex(op.color)); put("align", op.align) }
                    is Op.Clip -> buildJsonObject { put("k", "clip"); put("x", r2(op.x)); put("y", r2(op.y)); put("w", r2(op.w)); put("h", r2(op.h)) }
                    Op.Unclip -> buildJsonObject { put("k", "unclip") }
                })
            })
        }
        file.parentFile?.mkdirs()
        file.writeText(o.toString())
    }
}
