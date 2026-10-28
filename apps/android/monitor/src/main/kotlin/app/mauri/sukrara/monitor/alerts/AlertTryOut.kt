// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.alerts

import android.app.NotificationChannel
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
 * - [play]: the kind's sound for a pack (the kind's own or its pack's), on the alarm or the
 *   notification volume as the kind will ring, with its vibration when asked. Plays the raw file directly, never the
 *   channel's `alertsound` URI, so it doesn't use the sound picker's state that real alerts read. A sound the person
 *   chose in the channel's system settings plays instead (that's what the alert will play); a channel silenced there
 *   plays nothing ([silenced]). [stop] ends it; the page stops it when it closes.
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
        val channel = channel(kind, alarm)
        soundUri(kind, pack, alarm)?.let { uri ->
            val p = MediaPlayer()
            player = p
            runCatching {
                p.setAudioAttributes(AlertChannels.audioAttributes(alarm))
                p.setDataSource(ctx, uri)
                p.setOnPreparedListener { it.start() }
                p.setOnCompletionListener { done(it) }
                p.setOnErrorListener { mp, _, _ -> done(mp).let { true } }
                p.prepareAsync()
            }.onFailure { done(p) }
        }
        // the channel's vibration switch decides, like it does for the alert
        if (vibrate && (channel == null || (channel.shouldVibrate() && audible(channel)))) {
            val usage = if (alarm) VibrationAttributes.USAGE_ALARM else VibrationAttributes.USAGE_NOTIFICATION
            vibrator().vibrate(AlertHaptics.effect(ctx, kind), VibrationAttributes.createForUsage(usage))
        }
    }

    /**
     * Android's settings silence this alert: its channel's sound set to *None*, or its importance too low to make a
     * sound. The page says so instead of playing a sound the alert won't.
     */
    fun silenced(
        kind: AlertKind,
        alarm: Boolean,
    ): Boolean = channel(kind, alarm)?.let { it.sound == null || !audible(it) } ?: false

    private fun audible(c: NotificationChannel) = c.importance >= NotificationManager.IMPORTANCE_DEFAULT

    private fun channel(
        kind: AlertKind,
        alarm: Boolean,
    ): NotificationChannel? = ctx.getSystemService(NotificationManager::class.java).getNotificationChannel(channelId(kind, alarm))

    private fun done(p: MediaPlayer) {
        runCatching { p.release() }
        if (player === p) player = null
    }

    fun stop() {
        player?.let(::done)
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
        val channel = channel(kind, alarm)
        if (channel != null && silenced(kind, alarm)) return null
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
