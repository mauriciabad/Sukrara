package dev.spike.glucobridge

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Scatter of the last [spanMinutes] of readings, coloured by range. */
@Composable
fun GlucoseChart(readings: List<Bridge.Reading>, spanMinutes: Int = 180, low: Int = 65, high: Int = 185) {
    Canvas(Modifier.fillMaxWidth().height(140.dp)) {
        val newest = readings.maxOfOrNull { it.epochSec } ?: return@Canvas
        val span = spanMinutes * 60L
        val points = readings.filter { newest - it.epochSec <= span }
        if (points.isEmpty()) return@Canvas

        val lo = 40f
        val hi = maxOf(250f, (points.maxOf { it.mgdl } + 10).toFloat())
        fun x(epoch: Long) = size.width * (1f - (newest - epoch).toFloat() / span)
        fun y(v: Float) = size.height * (1f - (v - lo) / (hi - lo))

        // in-range band
        drawRect(Color(0x2234C759), topLeft = Offset(0f, y(high.toFloat())),
                 size = Size(size.width, y(low.toFloat()) - y(high.toFloat())))
        for (g in listOf(100, 150, 200)) {
            drawLine(Color(0x22FFFFFF), Offset(0f, y(g.toFloat())), Offset(size.width, y(g.toFloat())), 1f)
        }
        for (p in points) {
            val c = when {
                p.mgdl < low || p.mgdl > high -> Color(0xFFFF3B30)
                p.mgdl > 145 -> Color(0xFFFF9500)
                else -> Color(0xFF34C759)
            }
            drawCircle(c, radius = 3f, center = Offset(x(p.epochSec), y(p.mgdl.toFloat())))
        }
    }
}
