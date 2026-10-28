// Sukrara — https://github.com/mauriciabad/sukrara
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import app.mauri.sukrara.designsystem.theme.SukraraTheme
import app.mauri.sukrara.monitor.service.StartReason
import kotlinx.coroutines.launch

/** The one activity: launcher, invite links (`sukrara:` / `https://sukrara.mauri.app/join`). */
class MainActivity : ComponentActivity() {
    private var deepLink by mutableStateOf<Uri?>(null)

    /** A request to open the new-sensor flow, until the navigation has opened it. */
    private var openNewSensor by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            deepLink = intent?.data
            openNewSensor = opensNewSensor(intent?.action)
        }
        val graph = graph
        setContent {
            SukraraTheme {
                SukraraNavigation(graph, deepLink, openNewSensor, { openNewSensor = false }, Modifier.fillMaxSize())
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        deepLink = intent.data
        if (opensNewSensor(intent.action)) openNewSensor = true
    }

    override fun onResume() {
        super.onResume()
        // permissions and the checklist change outside the app
        graph.home.refresh()
        // opening the app is an allowed foreground-service start: (re)start monitoring if something is set up; not
        // once the app has gone to the background again (Android would refuse and "Monitoring stopped" would show)
        lifecycleScope.launch {
            graph.ready.await()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) graph.startMonitoring(StartReason.App)
        }
    }
}
