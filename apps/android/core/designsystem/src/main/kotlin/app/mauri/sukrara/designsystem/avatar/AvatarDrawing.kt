// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.designsystem.avatar

import app.mauri.sukrara.protocol.event.Avatar
import kotlin.math.cos
import kotlin.math.sin

/**
 * What a generated picture draws, in unit coordinates (y down), before any pixels: the background and white marks with
 * their alpha (PROTOCOL-v2 §Profiles). Pure, so the geometry is tested against the contract; [AvatarImage] paints it.
 *
 * Every renderer version this app ever shipped stays here unchanged ([of] dispatches on `v`); a version this build
 * doesn't know, or no avatar, draws the fallback: grey with the name's initial.
 */
data class AvatarDrawing(val background: Background, val marks: List<Mark>) {
    sealed interface Background {
        /** Linear gradient from [from] (colour [a]) to [to] (colour [b]); colours ARGB. */
        data class Gradient(val a: Long, val b: Long, val from: Point, val to: Point) : Background

        data class Solid(val color: Long) : Background
    }

    data class Point(val x: Double, val y: Double)

    /** A white mark; [alpha] 0–255. */
    sealed interface Mark {
        val alpha: Int

        data class Polygon(val points: List<Point>, override val alpha: Int) : Mark

        data class Circle(val center: Point, val r: Double, override val alpha: Int) : Mark

        /** A stroked circle, [width] in unit coordinates. */
        data class Ring(val center: Point, val r: Double, val width: Double, override val alpha: Int) : Mark

        /** Bold text at 0.5 of the size, its glyph box centred. */
        data class Letter(val text: String, override val alpha: Int) : Mark
    }

    companion object {
        const val FALLBACK = 0xFF78909C

        fun of(avatar: Avatar?, name: String): AvatarDrawing =
            when {
                avatar != null && avatar.known && avatar.version == 1 -> V1.draw(avatar, name)
                else -> fallback(name)
            }

        fun fallback(name: String) = AvatarDrawing(Background.Solid(FALLBACK), listOf(Mark.Letter(Avatar.initial(name), V1.LETTER_ALPHA)))
    }

    /** Version 1 (2026-10-17). Frozen: never change what it draws; a new look is a new version. */
    internal object V1 {
        val PALETTES =
            listOf(
                0xFF1BB5C9 to 0xFF0B2E5E,
                0xFFC6F432 to 0xFF2E7D32,
                0xFFFF8A65 to 0xFFBF360C,
                0xFFBA68C8 to 0xFF4A148C,
                0xFFFFD54F to 0xFFE65100,
                0xFF4FC3F7 to 0xFF01579B,
                0xFFF06292 to 0xFF880E4F,
                0xFF90A4AE to 0xFF263238,
            )
        const val LETTER_ALPHA = 240
        private const val M = 0.5

        fun draw(a: Avatar, name: String): AvatarDrawing {
            val (c1, c2) = PALETTES[Math.floorMod(a.colors, PALETTES.size)]
            val rad = Math.toRadians(a.angle.toDouble())
            val dx = cos(rad) / 2
            val dy = sin(rad) / 2
            val bg = Background.Gradient(c1, c2, Point(M - dx, M - dy), Point(M + dx, M + dy))
            val marks =
                when (a.shape) {
                    "cube" -> cube()
                    "drop" -> drop()
                    "dots" -> Avatar.v1Dots(a.seed).map { Mark.Circle(Point(it.x, it.y), it.r, it.alpha) }
                    "rings" -> (4 downTo 1).map { i -> Mark.Ring(Point(M, M), 0.09 * i, 0.03, 60 + 40 * i) }
                    else -> listOf(Mark.Letter(Avatar.initial(name), LETTER_ALPHA))
                }
            return AvatarDrawing(bg, marks)
        }

        private fun cube(): List<Mark> {
            val w = 0.22

            fun face(alpha: Int, vararg p: Double) = Mark.Polygon(p.toList().chunked(2) { (x, y) -> Point(x, y) }, alpha)
            return listOf(
                face(235, M, M - w * 1.1, M + w, M - w * .55, M, M, M - w, M - w * .55),
                face(170, M - w, M - w * .55, M, M, M, M + w * 1.15, M - w, M + w * .6),
                face(110, M + w, M - w * .55, M, M, M, M + w * 1.15, M + w, M + w * .6),
            )
        }

        private fun drop(): List<Mark> {
            val r = 0.2
            return listOf(
                Mark.Circle(Point(M, M + r * .6), r, 220),
                Mark.Polygon(listOf(Point(M - r * .93, M + r * .3), Point(M, M - r * 1.5), Point(M + r * .93, M + r * .3)), 220),
            )
        }
    }
}
