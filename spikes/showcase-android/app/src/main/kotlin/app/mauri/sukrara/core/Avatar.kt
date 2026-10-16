// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import java.security.SecureRandom
import kotlin.math.cos
import kotlin.math.sin
import org.json.JSONObject

/**
 * A generated profile picture, sent as the few numbers that draw it (PROTOCOL-v2 "Profiles"), never as an image.
 *
 * The wire form carries a renderer version `v`. Every version this app ever shipped keeps its renderer here
 * ([render] dispatches on `v`), so a picture made by an older build looks the same forever. A newer version than
 * this build knows is kept and forwarded unchanged and drawn as [fallback] until the app is updated.
 */
class Avatar private constructor(private val raw: String) {
    val json: JSONObject get() = JSONObject(raw)
    val v: Int get() = json.optInt("v")
    val known: Boolean get() = v in 1..CURRENT

    // v1 fields (meaningless for other versions)
    val colors: Int get() = json.optInt("c")
    val shape: String get() = json.optString("s")
    val angle: Int get() = json.optInt("a")
    val seed: Long get() = json.optLong("n")

    fun with(colors: Int = this.colors, shape: String = this.shape, angle: Int = this.angle, seed: Long = this.seed) =
        v1(colors, shape, angle, seed)

    /** A square picture; the UI clips it to a circle. [name] supplies the letter for the "letter" shape. */
    fun render(name: String, size: Int): Bitmap = when (v) {
        1 -> V1.render(this, name, size)
        else -> fallback(name, size)
    }

    override fun equals(other: Any?) = other is Avatar && other.raw == raw
    override fun hashCode() = raw.hashCode()
    override fun toString() = raw

    companion object {
        /** The version new avatars are made with. Bump it for a new look; never change what an old version draws. */
        const val CURRENT = 1
        private const val MAX_JSON = 512
        private val rng = SecureRandom()

        /** From the wire: any object with a positive integer `v` (an unknown version is kept as is). */
        fun of(j: JSONObject?): Avatar? {
            if (j == null || j.optInt("v") <= 0) return null
            val s = j.toString()
            return if (s.length <= MAX_JSON) Avatar(s) else null
        }

        fun v1(colors: Int, shape: String, angle: Int, seed: Long) = Avatar(JSONObject().put("v", 1)
            .put("c", Math.floorMod(colors, V1.PALETTES.size)).put("s", shape.takeIf { it in V1.SHAPES } ?: "cube")
            .put("a", Math.floorMod(angle, 360) / 15 * 15).put("n", seed and 0x7fffffffL).toString())

        /** New users' default: a random v1 picture (any shape but "letter"). */
        fun random(): Avatar = v1(rng.nextInt(V1.PALETTES.size), V1.SHAPES[rng.nextInt(V1.SHAPES.size - 1)],
            rng.nextInt(24) * 15, rng.nextInt().toLong())

        fun initial(name: String): String =
            name.trim().takeIf { it.isNotEmpty() }?.let { String(Character.toChars(it.codePointAt(0))).uppercase() } ?: "?"

        /** Missing avatar or a version this build can't draw: grey with the name's initial. */
        fun fallback(name: String, size: Int): Bitmap {
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val s = size.toFloat()
            c.drawRect(0f, 0f, s, s, Paint().apply { color = 0xFF78909C.toInt() })
            V1.letter(c, s, initial(name))
            return bmp
        }
    }

    /** Version 1 (2026-10-17). Frozen: change nothing here once released; add a V2 instead. */
    private object V1 {
        val PALETTES = listOf(0xFF1BB5C9 to 0xFF0B2E5E, 0xFFC6F432 to 0xFF2E7D32, 0xFFFF8A65 to 0xFFBF360C,
            0xFFBA68C8 to 0xFF4A148C, 0xFFFFD54F to 0xFFE65100, 0xFF4FC3F7 to 0xFF01579B, 0xFFF06292 to 0xFF880E4F,
            0xFF90A4AE to 0xFF263238).map { (a, b) -> a.toInt() to b.toInt() }
        val SHAPES = listOf("cube", "drop", "dots", "rings", "letter")

        fun render(a: Avatar, name: String, size: Int): Bitmap {
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val s = size.toFloat()
            val (c1, c2) = PALETTES[Math.floorMod(a.colors, PALETTES.size)]
            val rad = Math.toRadians(a.angle.toDouble())
            val dx = cos(rad).toFloat() * s / 2
            val dy = sin(rad).toFloat() * s / 2
            c.drawRect(0f, 0f, s, s, Paint().apply {
                shader = LinearGradient(s / 2 - dx, s / 2 - dy, s / 2 + dx, s / 2 + dy, c1, c2, Shader.TileMode.CLAMP)
            })
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
            val m = s / 2
            fun alpha(x: Int) = p.apply { alpha = x }
            when (a.shape) {
                "cube" -> {
                    val w = s * .22f
                    fun face(al: Int, vararg pts: Float) {
                        val path = Path().apply { moveTo(pts[0], pts[1]); for (i in 2 until pts.size step 2) lineTo(pts[i], pts[i + 1]); close() }
                        c.drawPath(path, alpha(al))
                    }
                    face(235, m, m - w * 1.1f, m + w, m - w * .55f, m, m, m - w, m - w * .55f)
                    face(170, m - w, m - w * .55f, m, m, m, m + w * 1.15f, m - w, m + w * .6f)
                    face(110, m + w, m - w * .55f, m, m, m, m + w * 1.15f, m + w, m + w * .6f)
                }
                "drop" -> {
                    val r = s * .2f
                    alpha(220)
                    c.drawCircle(m, m + r * .6f, r, p)
                    c.drawPath(Path().apply { moveTo(m - r * .93f, m + r * .3f); lineTo(m, m - r * 1.5f); lineTo(m + r * .93f, m + r * .3f); close() }, p)
                }
                "dots" -> v1Dots(a.seed).forEach { (x, y, r, al) -> c.drawCircle(x * s, y * s, r * s, alpha(al.toInt())) }
                "rings" -> {
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = s * .03f
                    for (i in 4 downTo 1) c.drawCircle(m, m, s * .09f * i, alpha(60 + 40 * i))
                }
                else -> letter(c, s, initial(name))
            }
            return bmp
        }

        /** White bold letter, its glyph box centred. */
        fun letter(c: Canvas, s: Float, text: String) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                alpha = 240
                textSize = s * .5f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val b = android.graphics.Rect().also { p.getTextBounds(text, 0, text.length, it) }
            c.drawText(text, s / 2, s / 2 - b.exactCenterY(), p)
        }
    }
}

/**
 * v1 "dots": five circles `(x, y, r, alpha 0–255)` in unit coordinates from `sha256("sukrara/avatar/v1/dots:" + seed)`,
 * bytes 4k…4k+3 → r, x, y, alpha (spikes/nostr/identity/avatar-v1.json has the vectors; the Mac draws the same).
 */
internal fun v1Dots(seed: Long): List<FloatArray> {
    val h = java.security.MessageDigest.getInstance("SHA-256").digest("sukrara/avatar/v1/dots:$seed".toByteArray())
    fun u(i: Int) = (h[i].toInt() and 0xff) / 255f
    return (0 until 5).map { k ->
        floatArrayOf(0.25f + 0.5f * u(4 * k + 1), 0.25f + 0.5f * u(4 * k + 2), 0.08f + 0.12f * u(4 * k), (90 + (110 * u(4 * k + 3)).toInt()).toFloat())
    }
}

/** What other people see of you: a name and a generated picture. Sent only inside encrypted messages. */
data class Profile(val name: String, val avatar: Avatar?) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("avatar", avatar?.json ?: JSONObject.NULL)

    companion object {
        const val MAX_NAME = 40

        fun of(j: JSONObject?): Profile? = j?.let {
            Profile(it.optString("name").trim().take(MAX_NAME), Avatar.of(it.optJSONObject("avatar")))
        }
    }
}
