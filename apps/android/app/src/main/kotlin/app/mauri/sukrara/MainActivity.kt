// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import app.mauri.sukrara.monitor.service.StartReason
import kotlinx.coroutines.launch

/** The one activity: launcher, invite links (`sukrara:` / `https://sukrara.mauri.app/join`). */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = graph
        setContent {
            // Theme stub until :core:designsystem (chunk 08): dark first, light when the phone is light.
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    SukraraNavigation(graph, Modifier.fillMaxSize(), padding)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // opening the app is an allowed foreground-service start: (re)start monitoring if something is set up
        lifecycleScope.launch {
            graph.ready.await()
            graph.monitor.starter.start(StartReason.App)
        }
    }
}
