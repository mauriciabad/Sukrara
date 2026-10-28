// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.designsystem

import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.SpringSpec
import app.mauri.sukrara.designsystem.motion.Motion
import app.mauri.sukrara.designsystem.motion.isReducedMotion
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReducedMotionTest {
    @Test fun `animator scale 0 means reduced motion`() {
        assertTrue(isReducedMotion(0f))
        assertFalse(isReducedMotion(1f))
        assertFalse(isReducedMotion(0.5f))
    }

    @Test fun `every motion spec snaps when reduced`() {
        listOf(
            Motion.defaultSpatial<Float>(true),
            Motion.fastSpatial<Float>(true),
            Motion.defaultEffects<Float>(true),
            Motion.fastEffects<Float>(true),
        ).forEach { assertTrue(it is SnapSpec) }
        listOf(Motion.defaultSpatial<Float>(), Motion.defaultEffects<Float>()).forEach { assertTrue(it is SpringSpec) }
    }
}
