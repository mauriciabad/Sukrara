// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.core.model.AlertReadiness
import app.mauri.sukrara.core.model.AlertRequirement
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.designsystem.theme.SukraraTheme
import app.mauri.sukrara.feature.sharing.backup.BackupScreen
import app.mauri.sukrara.feature.sharing.backup.SaveOutcome
import app.mauri.sukrara.feature.sharing.conflict.IdentityConflictScreen
import app.mauri.sukrara.feature.sharing.followers.FollowerDialog
import app.mauri.sukrara.feature.sharing.followers.FollowersActions
import app.mauri.sukrara.feature.sharing.followers.FollowersPage
import app.mauri.sukrara.feature.sharing.profile.ProfileSave
import app.mauri.sukrara.feature.sharing.profile.ProfileScreen
import app.mauri.sukrara.feature.sharing.setup.AlertThresholds
import app.mauri.sukrara.feature.sharing.setup.RestoreGate
import app.mauri.sukrara.feature.sharing.setup.RestoreUi
import app.mauri.sukrara.feature.sharing.setup.SetupActions
import app.mauri.sukrara.feature.sharing.setup.SetupContent
import app.mauri.sukrara.feature.sharing.setup.SetupFacts
import app.mauri.sukrara.feature.sharing.setup.SetupRecord
import app.mauri.sukrara.feature.sharing.setup.SetupStep
import app.mauri.sukrara.feature.sharing.setup.SetupUi
import app.mauri.sukrara.feature.sharing.setup.ThresholdField
import app.mauri.sukrara.feature.sharing.share.ApproveDialog
import app.mauri.sukrara.feature.sharing.share.ApproveRefusedDialog
import app.mauri.sukrara.feature.sharing.share.InviteUi
import app.mauri.sukrara.feature.sharing.share.ShareScreen
import app.mauri.sukrara.protocol.PublicKeyHex
import app.mauri.sukrara.protocol.ble.BleMessages
import app.mauri.sukrara.protocol.event.Avatar
import app.mauri.sukrara.protocol.event.Profile
import app.mauri.sukrara.protocol.owner.IdentityCheck
import app.mauri.sukrara.share.ble.NearbyStatus
import app.mauri.sukrara.share.follower.NearbyOffer
import app.mauri.sukrara.share.follower.NearbySearch
import app.mauri.sukrara.share.owner.ApproveResult
import app.mauri.sukrara.share.owner.FollowerRecord
import app.mauri.sukrara.share.owner.OwnerShareState
import app.mauri.sukrara.share.owner.PendingRequest
import app.mauri.sukrara.share.owner.usecase.RestoreIdentity
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.fontScale
import com.github.takahirom.roborazzi.size
import com.github.takahirom.roborazzi.uiMode
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale
import java.util.TimeZone

/**
 * Roborazzi goldens of every setup step and the sharing screens (synthetic data only): one phone size, light + dark ×
 * default + largest font. `recordRoborazziDebug` writes src/test/snapshots (the orchestrator copies them to the UX review).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class SharingScreenshotTest {
    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
    }

    private val facts = SetupFacts(role = Role.Wearer, ownSensorLive = false, hasIdentity = false)

    private fun ui(
        step: SetupStep,
        index: Int,
        facts: SetupFacts = this.facts,
        missing: Set<AlertRequirement> = emptySet(),
        alerts: AlertThresholds = AlertThresholds(AlertSettings()),
        share: OwnerShareState = OwnerShareState.NoIdentity,
        restore: RestoreUi = RestoreUi.Idle,
        record: SetupRecord = SetupRecord(),
    ) = SetupUi(
        step = step,
        index = index,
        count = 6,
        facts = facts,
        record = record,
        readiness = AlertReadiness(missing),
        alerts = alerts,
        gate = RestoreGate.evaluate(facts.ownSensorLive),
        share = share,
        restore = restore,
        busy = false,
        single = false,
        canBack = index > 3,
    )

    private val live = facts.copy(ownSensorLive = true)
    private val conflict =
        OwnerShareState.AnotherAppSharingAsYou(
            IdentityCheck.Reason.entries.first(),
            followersReceiving = true,
            relays = emptyMap(),
        )
    private val followers =
        listOf(
            FollowerRecord("k1", "Anna", since = 1_761_000_000, avatarJson = Avatar.v1(1, "dots", 45, 7).toString()),
            FollowerRecord("k2", "MacBook", since = 1_759_000_000),
            FollowerRecord("k4", "Old phone", since = 1_750_000_000, revoked = 1_755_000_000),
        )
    private val request =
        PendingRequest(
            "k3",
            "Dad",
            "t",
            "4821",
            at = 1_761_500_000,
            avatarJson = Avatar.v1(4, "cube", 90, 1).toString(),
            label = "Dad's phone",
            inviteSentAt = 1_761_499_000,
        )
    private val me = Profile("Núria", Avatar.v1(0, "drop", 45, 3))
    private val sharing =
        OwnerShareState.Sharing(
            1,
            followers,
            listOf(request),
            emptyMap(),
            backupPublishedAt = 1_761_400_000,
            recoveryExportedAt = 0,
            profile = me,
            writers = setOf("k1"),
        )

    private fun scenes(): List<Pair<String, @Composable () -> Unit>> {
        // the app passes Settings' threshold rows; this module only sees their slot
        val a =
            SetupActions(thresholdRows = { settings, _ ->
                for (field in ThresholdField.entries) Text("${field.name}: ${settings.rule(field.kind).threshold}")
            })
        return listOf(
            "setup-1-welcome" to { SetupContent(ui(SetupStep.Welcome, 1, facts.copy(role = null)), a) },
            "setup-2-role" to { SetupContent(ui(SetupStep.Role, 2, facts.copy(role = null)), a) },
            "setup-3-permissions" to {
                SetupContent(
                    ui(SetupStep.Permissions, 3, missing = setOf(AlertRequirement.FullScreenIntent, AlertRequirement.BatteryExemption)),
                    a,
                )
            },
            "setup-4-sensor-waiting" to { SetupContent(ui(SetupStep.Sensor, 4), a) },
            "setup-4-sensor-live" to { SetupContent(ui(SetupStep.Sensor, 4, live), a) },
            "setup-5-alerts" to { SetupContent(ui(SetupStep.Alerts, 5, live), a) },
            "setup-5-alerts-out-of-order" to {
                SetupContent(ui(SetupStep.Alerts, 5, live, alerts = AlertThresholds(AlertSettings()).with(ThresholdField.High, 260.0)), a)
            },
            "setup-6-identity-gate-closed" to { SetupContent(ui(SetupStep.Identity, 6), a) },
            "setup-6-identity-gate-open" to {
                SetupContent(ui(SetupStep.Identity, 6, live), a)
            },
            "setup-6-restore-wrong-password" to {
                SetupContent(
                    ui(SetupStep.Identity, 6, live, restore = RestoreUi.Failed(RestoreIdentity.Result.WrongPassword)),
                    a,
                )
            },
            "setup-6-restore-busy" to {
                SetupContent(ui(SetupStep.Identity, 6, live, restore = RestoreUi.Busy), a)
            },
            "setup-6-identity-conflict" to { SetupContent(ui(SetupStep.Identity, 6, live, share = conflict), a) },
            "conflict-screen" to { IdentityConflictScreen(conflict, {}, {}) },
            "share-new" to { ShareScreen(InviteUi.None, {}, {}) },
            "share-ready" to {
                ShareScreen(
                    InviteUi.Ready(Invite("https://sukrara.mauri.app/join#c3ludGhldGljLWludml0ZS1mb3ItdGVzdHM", "Anna", 1_761_600_000)),
                    {},
                    {},
                )
            },
            "followers" to { FollowersPage(sharing, FollowersActions()) },
            "followers-pair-nearby" to {
                FollowersPage(
                    sharing,
                    FollowersActions(),
                    nearby = NearbyUi(status = NearbyStatus.Searching, pairingUntil = 1_761_600_000 + 581),
                    now = { 1_761_600_000 },
                )
            },
            "setup-join-find-nearby" to {
                val offer =
                    NearbyOffer(
                        "phone",
                        BleMessages.Pairing(PublicKeyHex("ab".repeat(32)), emptyList(), "t".repeat(22), 1_761_600_600, me),
                    )
                SetupContent(
                    ui(
                        SetupStep.Join,
                        2,
                        facts.copy(role = Role.Follower),
                    ).copy(nearby = NearbySearch(NearbyStatus.Searching, listOf(offer))),
                    a,
                )
            },
            "setup-join-find-nearby-off" to {
                SetupContent(
                    ui(
                        SetupStep.Join,
                        2,
                        facts.copy(role = Role.Follower),
                    ).copy(nearby = NearbySearch(NearbyStatus.BluetoothOff, emptyList())),
                    a,
                )
            },
            "approve-nearby" to {
                ApproveDialog(request.copy(nearby = true), working = false, onAllow = {}, onDeny = {}, onLater = {}, time = ::utcTime)
            },
            "followers-empty" to {
                FollowersPage(OwnerShareState.Sharing(1, emptyList(), emptyList(), emptyMap(), 0, 0), FollowersActions())
            },
            "follower-dialog" to {
                FollowerDialog(followers[0], writer = true, refused = false, onWriter = {}, onRemove = {}, onClose = {})
            },
            "approve" to
                { ApproveDialog(request, working = false, onAllow = {}, onDeny = {}, onLater = {}, time = ::utcTime) },
            "approve-leaked" to {
                ApproveDialog(
                    request.copy(others = 2),
                    working = false,
                    onAllow = {},
                    onDeny = {},
                    onLater = {},
                    time = ::utcTime,
                )
            },
            "approve-refused" to { ApproveRefusedDialog(request, ApproveResult.Gone, {}) },
            "profile" to { ProfileScreen(me, ProfileSave.Idle, {}, {}) },
            "profile-failed" to { ProfileScreen(Profile("", null), ProfileSave.Failed, {}, {}) },
            "backup-missing" to { BackupScreen(sharing, { SaveOutcome.Saved }, { null }, {}) },
            "backup-done" to {
                BackupScreen(
                    sharing.copy(passwordManagerSavedAt = 1_761_450_000, recoveryExportedAt = 1_761_460_000),
                    { SaveOutcome.Saved },
                    { null },
                    {},
                )
            },
        )
    }

    @Test
    fun screens() {
        for (dark in listOf(false, true)) {
            for (largest in listOf(false, true)) {
                val options =
                    RoborazziComposeOptions {
                        size(widthDp = 411, heightDp = 891)
                        uiMode(if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
                        fontScale(if (largest) 2f else 1f)
                    }
                for ((name, content) in scenes()) {
                    val suffix = (if (dark) "-dark" else "-light") + if (largest) "-largest" else ""
                    captureRoboImage("src/test/snapshots/$name$suffix.png", roborazziComposeOptions = options) {
                        SukraraTheme(dark = dark, dynamic = false) {
                            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) { content() }
                        }
                    }
                }
            }
        }
    }
}

/** Fixed-zone times for the goldens. */
private fun utcTime(epochS: Long): String =
    java.time.Instant
        .ofEpochSecond(epochS)
        .atZone(java.time.ZoneOffset.UTC)
        .toLocalTime()
        .toString()
