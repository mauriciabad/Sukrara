// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.mauri.sukrara.designsystem.theme.SukraraTheme

/** The one activity: launcher, invite links (`sukrara:` / `https://sukrara.mauri.app/join`). */
class MainActivity : ComponentActivity() {
    private var deepLink by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) deepLink = intent?.data
        val graph = graph
        setContent {
            SukraraTheme {
                SukraraNavigation(graph, deepLink, Modifier.fillMaxSize())
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        deepLink = intent.data
    }

    override fun onResume() {
        super.onResume()
        // permissions, the showcase app and the checklist change outside the app
        graph.home.refresh()
    }
}
