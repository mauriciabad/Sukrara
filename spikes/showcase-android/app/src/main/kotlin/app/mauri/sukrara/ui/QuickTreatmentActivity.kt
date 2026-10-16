// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.platform.LocalContext
import app.mauri.sukrara.App
import app.mauri.sukrara.features.Features
import app.mauri.sukrara.treatments.Treat

/**
 * Where entries from the [TreatmentDialog] go, for whichever role this phone has: the wearer's own log (followers,
 * Macs and LibreView pick it up from there), or — for a follower the wearer made a writer — sent to the wearer.
 */
object QuickEntry {
    fun canAdd(ctx: Context): Boolean = when (App.role(ctx)) {
        "owner" -> App.owner(ctx).hasIdentity()
        "follower" -> App.follower(ctx).canWrite()
        else -> false
    }

    fun save(ctx: Context, entries: List<NewEntry>) {
        when (App.role(ctx)) {
            "owner" -> {
                Treat.init(ctx)
                entries.forEach { Treat.store.add(it.k, it.a, it.at, it.note) }
                Treat.uploader.kick()
            }
            "follower" -> App.follower(ctx).let { f -> entries.forEach { f.addTreatment(it.k, it.a, it.note, it.at) } }
        }
    }

    fun saved(n: Int, role: String?) = when {
        role == "follower" -> if (n == 1) "Entry sent" else "$n entries sent"
        else -> if (n == 1) "Entry saved" else "$n entries saved"
    }
}

/**
 * The quick treatment dialog over whatever is on screen: a tap on a home-screen widget or on the glucose notification
 * opens this instead of the app (widgets: "generative functions — let the user create new content",
 * developer.android.com/develop/ui/views/appwidgets/overview). Translucent, no task of its own in Recents; when this
 * phone can't add entries (no identity yet, or a follower without "Can add entries") it opens the app instead.
 */
class QuickTreatmentActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Features.init(this)
        if (!QuickEntry.canAdd(this)) { openApp(); return }
        val role = App.role(this)
        enableEdgeToEdge()
        setContent {
            val ctx = LocalContext.current
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)) {
                // M3 dialog scrim (scrim colour at 32 %); a tap on it dismisses, like outside a dialog
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                    .clickable(interactionSource = null, indication = null, onClick = ::finish)
                    .safeDrawingPadding(), contentAlignment = Alignment.Center) {
                    // the card itself swallows taps so they don't reach the scrim
                    Box(Modifier.clickable(interactionSource = null, indication = null) {}) {
                        TreatmentDialog(
                            onDismiss = ::finish,
                            onSave = { list ->
                                QuickEntry.save(this@QuickTreatmentActivity, list)
                                Toast.makeText(this@QuickTreatmentActivity, QuickEntry.saved(list.size, role), Toast.LENGTH_SHORT).show()
                            },
                            onOpenApp = ::openApp,
                            inWindow = true,
                        )
                    }
                }
            }
        }
    }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
