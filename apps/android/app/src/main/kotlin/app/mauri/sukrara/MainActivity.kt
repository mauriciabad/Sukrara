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
import app.mauri.sukrara.core.model.NavTargets
import app.mauri.sukrara.designsystem.theme.SukraraTheme
import app.mauri.sukrara.monitor.service.StartReason
import kotlinx.coroutines.launch

/** The one activity: launcher, invite links (`sukrara:` / `https://sukrara.mauri.app/join`). */
class MainActivity : ComponentActivity() {
    private var deepLink by mutableStateOf<Uri?>(null)

    /** A request to open the new-sensor flow, until the navigation has opened it. */
    private var openNewSensor by mutableStateOf(false)

    /** The pen whose page a pen read card asked for, until the navigation has opened it. */
    private var openPen by mutableStateOf<String?>(null)

    /** Health Connect asked for the privacy rationale, until the navigation has opened the Health Connect page. */
    private var openHealth by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            deepLink = intent?.data
            openNewSensor = opensNewSensor(intent?.action)
            openPen = penPage(intent)
            openHealth = healthRationale(intent)
        }
        val graph = graph
        setContent {
            SukraraTheme {
                SukraraNavigation(
                    graph,
                    deepLink,
                    openNewSensor,
                    { openNewSensor = false },
                    Modifier.fillMaxSize(),
                    openPen = openPen,
                    onPenShow = { openPen = null },
                    openHealthConnect = openHealth,
                    onHealthConnectShow = { openHealth = false },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        deepLink = intent.data
        if (opensNewSensor(intent.action)) openNewSensor = true
        penPage(intent)?.let { openPen = it }
        if (healthRationale(intent)) openHealth = true
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

/** The pen page a pen read card's *Check it* asks for (only the wearer's phone has pens; Settings checks again). */
private fun penPage(i: Intent?): String? =
    i?.takeIf { it.action == NavTargets.ACTION_PEN_PAGE }?.getStringExtra(NavTargets.EXTRA_PEN_SERIAL)?.takeIf { it.isNotBlank() }

/** Health Connect's link to this app's privacy rationale (the manifest's `.HealthPermissionsRationale` alias). */
private fun healthRationale(i: Intent?): Boolean =
    i?.action == Intent.ACTION_VIEW_PERMISSION_USAGE && i.hasCategory(HEALTH_PERMISSIONS_CATEGORY)

private const val HEALTH_PERMISSIONS_CATEGORY = "android.intent.category.HEALTH_PERMISSIONS"
