// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import app.mauri.sukrara.core.model.NavTargets
import app.mauri.sukrara.feature.home.DataProblemScreen
import app.mauri.sukrara.feature.home.HomeAction
import app.mauri.sukrara.feature.home.HomeScreen
import app.mauri.sukrara.feature.home.HomeViewModel
import app.mauri.sukrara.feature.home.banners.Showcase
import app.mauri.sukrara.feature.sensor.libreview.LibreViewSignInRoute
import app.mauri.sukrara.feature.sensor.newsensor.NewSensorRoute
import app.mauri.sukrara.feature.sharing.SetupScreen
import app.mauri.sukrara.monitor.sensorend.AndroidSensorEndPresenter
import kotlinx.serialization.Serializable

/** The app's screens. Each is a key on the back stack (Navigation 3); predictive back comes with NavDisplay. */
@Serializable
data object HomeRoute : NavKey

@Serializable
data object SetupRoute : NavKey

@Serializable
data object DataProblemsRoute : NavKey

/** The new-sensor flow (chunk 18): home's Pair sensor, the "Sensor ended" notification, [NavTargets.ACTION_SENSOR_SETUP]. */
@Serializable
data object NewSensorKey : NavKey

@Serializable
data object LibreViewSignInKey : NavKey

/** Intent actions that open the new-sensor flow: the sensor-end notification's Pair new sensor, other modules' links. */
fun opensNewSensor(action: String?): Boolean =
    action == NavTargets.ACTION_SENSOR_SETUP || action == AndroidSensorEndPresenter.ACTION_PAIR_SENSOR

/** Invite links (`sukrara:` / `https://sukrara.mauri.app/join…`) open setup's join step (chunk 24). */
fun isInviteLink(uri: Uri?): Boolean =
    uri != null &&
        (uri.scheme == "sukrara" || (uri.scheme == "https" && uri.host == "sukrara.mauri.app" && uri.path.orEmpty().startsWith("/join")))

@Composable
fun SukraraNavigation(
    graph: AppGraph,
    deepLink: Uri?,
    openNewSensor: Boolean,
    onNewSensorOpened: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backStack = rememberNavBackStack(HomeRoute)
    // a request (a notification tap, also while the app is open) opens the flow once, on top of home
    LaunchedEffect(openNewSensor) {
        if (openNewSensor) {
            if (backStack.last() != NewSensorKey) backStack.add(NewSensorKey)
            onNewSensorOpened()
        }
    }
    // an invite link opens setup on top of home (Back lands on home), once per link: a recreated activity keeps it
    var handledLink by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(deepLink) {
        val link = deepLink?.toString()
        if (isInviteLink(deepLink) && link != handledLink) {
            handledLink = link
            if (backStack.last() != SetupRoute) backStack.add(SetupRoute)
        }
    }
    val context = LocalContext.current
    NavDisplay(
        backStack = backStack,
        modifier = modifier,
        onBack = { backStack.removeLastOrNull() },
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
        entryProvider =
            entryProvider {
                entry<HomeRoute> {
                    HomeScreen(
                        viewModel = viewModel(factory = HomeViewModel.factory(graph.home)),
                        onAction = { route(it, backStack, context, graph.home) },
                        onOpenDataProblems = { backStack.add(DataProblemsRoute) },
                    )
                }
                entry<SetupRoute> {
                    SetupScreen(onDone = { backStack.removeLastOrNull() })
                }
                entry<NewSensorKey> {
                    NewSensorRoute(
                        viewModel = viewModel(factory = graph.sensorUi.newSensorViewModels),
                        onSignIn = { backStack.add(LibreViewSignInKey) },
                        onDone = { backStack.remove(NewSensorKey) },
                    )
                }
                entry<LibreViewSignInKey> {
                    LibreViewSignInRoute(
                        viewModel = viewModel(factory = graph.sensorUi.libreViewViewModels),
                        onBack = { backStack.remove(LibreViewSignInKey) },
                    )
                }
                entry<DataProblemsRoute> {
                    val problems by graph.home.dataProblems.collectAsStateWithLifecycle()
                    LaunchedEffect(problems.isEmpty()) { if (problems.isEmpty()) backStack.remove(DataProblemsRoute) }
                    DataProblemScreen(
                        problems = problems,
                        onCopyReport = { copy(context, it) },
                        onAcknowledge = graph.home::acknowledge,
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
            },
    )
}

/** Where each home action leads: an in-app destination or the system / another feature's screen. */
private fun route(
    action: HomeAction,
    backStack: NavBackStack<NavKey>,
    context: Context,
    home: HomeModule,
) {
    when (action) {
        HomeAction.OpenSetup -> backStack.add(SetupRoute)

        HomeAction.PairSensor, HomeAction.OpenSensor -> backStack.add(NewSensorKey)

        HomeAction.AddTreatment -> open(context, Intent(NavTargets.ACTION_QUICK_TREATMENT)) {}

        HomeAction.EnableBluetooth -> open(context, Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) {}

        HomeAction.OpenShowcaseInfo -> open(context, Showcase.appInfo()) {}

        HomeAction.ResumeMonitoring -> home.resumeMonitoring()

        // the launcher's own dialog places the combined widget; home re-checks on resume and the pin callback
        HomeAction.AddWidget -> (context as? Activity)?.let { context.graph.widgets.presence.pin(it) }

        // their banners stay hidden until the owning chunks (13, integrations) provide the inputs and the route
        HomeAction.OpenBackup, HomeAction.OpenClinicUpload -> Unit
    }
}

/** Starts [intent] (inside this app when it names an action of ours) or runs [otherwise] when nothing handles it. */
private fun open(
    context: Context,
    intent: Intent,
    otherwise: () -> Unit,
) {
    if (intent.action?.startsWith("app.mauri.sukrara.") == true) intent.setPackage(context.packageName)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        otherwise()
    }
}

private fun copy(
    context: Context,
    text: String,
) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Sukrara", text))
}
