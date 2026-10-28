// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.alerts

import android.app.NotificationManager
import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.VibrationAttributes
import android.os.VibratorManager
import android.provider.Settings
import app.mauri.sukrara.alerts.AlertKind
import app.mauri.sukrara.alerts.AlertSettings

/**
 * Settings' ways to hear an alert before it matters, only ever from the person's tap:
 * - [play]: the kind's sound for a pack (also one not saved yet, from the Sound dialog's ▶), on the alarm or the
 *   notification volume as the kind will ring, with its vibration when asked. Plays the raw file directly, never the
 *   channel's `alertsound` URI, so it doesn't use the sound picker's state that real alerts read. A sound the person
 *   chose in the channel's system settings plays instead (that's what the alert will play). [stop] ends it; the page
 *   stops it when it closes.
 * - [sendTest]: the real notification on the kind's channel, as a test ([AlertNotifier.showTest]); no alert state.
 */
class AlertTryOut(
    context: Context,
    private val notifier: AlertNotifier,
) {
    private val ctx = context.applicationContext
    private var player: MediaPlayer? = null

    fun play(
        kind: AlertKind,
        pack: String,
        alarm: Boolean,
        vibrate: Boolean,
    ) {
        stop()
        soundUri(kind, pack, alarm)?.let { uri ->
            player =
                runCatching {
                    MediaPlayer().apply {
                        setAudioAttributes(AlertChannels.audioAttributes(alarm))
                        setDataSource(ctx, uri)
                        setOnCompletionListener { it.release() }
                        prepare()
                        start()
                    }
                }.getOrNull()
        }
        if (vibrate) {
            val usage = if (alarm) VibrationAttributes.USAGE_ALARM else VibrationAttributes.USAGE_NOTIFICATION
            vibrator().vibrate(AlertHaptics.effect(ctx, kind), VibrationAttributes.createForUsage(usage))
        }
    }

    fun stop() {
        player?.let { p -> runCatching { if (p.isPlaying) p.stop() }.also { runCatching { p.release() } } }
        player = null
        vibrator().cancel()
    }

    fun sendTest(
        kind: AlertKind,
        settings: AlertSettings,
    ) = notifier.showTest(kind, settings)

    fun channelId(
        kind: AlertKind,
        alarm: Boolean,
    ): String = AlertChannels.id(kind, alarm)

    private fun vibrator() = ctx.getSystemService(VibratorManager::class.java).defaultVibrator

    /** What the alert would play: the channel's own sound when the person changed it in system settings, else the pack's. */
    private fun soundUri(
        kind: AlertKind,
        pack: String,
        alarm: Boolean,
    ): Uri? {
        val channel = ctx.getSystemService(NotificationManager::class.java).getNotificationChannel(channelId(kind, alarm))
        val own = channel?.sound
        if (own != null && own != AlertChannels.soundUri(ctx, kind)) return own
        return when (pack) {
            AlertSoundPicker.NONE -> {
                null
            }

            AlertSoundPicker.SYSTEM -> {
                Settings.System.DEFAULT_NOTIFICATION_URI
            }

            else -> {
                val name = "${pack.takeIf { it in AlertSoundPicker.PACKS } ?: AlertSoundPicker.DEFAULT_PACK}_${kind.wire}_1"
                ctx.resources
                    .getIdentifier(name, "raw", ctx.packageName)
                    .takeIf { it != 0 }
                    ?.let { Uri.parse("android.resource://${ctx.packageName}/$it") }
            }
        }
    }
}
