// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Moving the focus between the treatment dialog's fields must not change any field's height nor the dialog's (user,
 * 2026-10-21: "the fields change height when focused/blurred … it jitters when I tap another input"). Measured on
 * narrow and wide phones in English and Spanish, empty and with a value typed. Before the fix the amount fields'
 * labels wrapped inside the empty fields (88 dp, 112 dp in Spanish) and floated onto one line when focused (68 dp).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class TreatmentDialogHeightTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val tags = listOf("treatment_rapid", "treatment_long", "treatment_carbs", "treatment_note")

    private fun heights(): List<Dp> = (tags + "treatment_card").map { compose.onNodeWithTag(it).fetchSemanticsNode().let { n ->
        with(compose.density) { n.size.height.toDp() } } }

    private fun focusWalk(width: Dp) {
        compose.setContent { MaterialTheme { Box(Modifier.width(width)) { TreatmentDialog({}, {}, onOpenApp = {}, inWindow = true) } } }
        compose.waitForIdle()
        var first = heights()
        fun check(step: String) {
            compose.waitForIdle()
            assertEquals("heights (rapid, long, carbs, note, dialog) after $step at $width", first, heights())
        }
        for (t in tags + tags.reversed()) { compose.onNodeWithTag(t).performClick(); check("focus $t") }
        // a value may add the alerts-paused line (content, not focus); from there on focus changes nothing again
        compose.onNodeWithTag("treatment_rapid").performTextInput("4")
        compose.waitForIdle()
        assertEquals("amount field heights after typing", first.take(3), heights().take(3))
        first = heights()
        for (t in tags) { compose.onNodeWithTag(t).performClick(); check("focus $t with rapid filled") }
    }

    @Test fun narrowPhone() = focusWalk(360.dp)
    @Test fun widePhone() = focusWalk(412.dp)
    @Test @Config(qualifiers = "es") fun narrowPhoneSpanish() = focusWalk(360.dp)
    @Test @Config(qualifiers = "es") fun widePhoneSpanish() = focusWalk(412.dp)
}
