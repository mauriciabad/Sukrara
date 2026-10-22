// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.features

import android.content.Context
import app.mauri.sukrara.widgets.WidgetPresence
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Every card on home (and the settings overview) that the user can close: THE registry (round f33, the user: "closed
 * tips never come back"). Each one keeps its closed state where it always did; this is the one way in to close one
 * ([close]: also offers Undo, [justClosed]), bring one back ([restore]) and bring them all back ([restoreAll]: Settings →
 * *Show closed tips*). `TipsTest` checks that every [Tip] closes and comes back, so a new closable card must be listed
 * here to be restorable.
 */
object Tips {
    enum class Tip {
        /** "Share your data with your clinic" (`ui/UploadBanner.kt`; `FeatureState.uploadBannerDismissed`). */
        CLINIC_UPLOAD,
        /** "Put your glucose on the home screen" (`ui/WidgetBannerCard.kt`; `widget-banner` prefs). */
        ADD_WIDGET,
        /** "Restart System UI" (`ui/SystemUiBanner.kt`; `system_access` prefs, closed for one state). */
        RESTART_SYSTEM_UI,
    }

    /** What each card's closed state is stored in: the three stores as they are, or fakes in the tests. */
    interface Store {
        fun closed(t: Tip): Boolean
        fun close(t: Tip)
        fun restore(t: Tip)
    }

    /** The app's stores. */
    class AppStore(private val c: Context) : Store {
        override fun closed(t: Tip) = when (t) {
            Tip.CLINIC_UPLOAD -> Features.value.uploadBannerDismissed
            Tip.ADD_WIDGET -> WidgetPresence.dismissed(c)
            Tip.RESTART_SYSTEM_UI -> SystemAccess.closedNow()
        }
        override fun close(t: Tip) = when (t) {
            Tip.CLINIC_UPLOAD -> { Features.update { it.copy(uploadBannerDismissed = true) }; Unit }
            Tip.ADD_WIDGET -> WidgetPresence.dismiss(c)
            Tip.RESTART_SYSTEM_UI -> SystemAccess.dismiss()
        }
        override fun restore(t: Tip) = when (t) {
            Tip.CLINIC_UPLOAD -> { Features.update { it.copy(uploadBannerDismissed = false) }; Unit }
            Tip.ADD_WIDGET -> WidgetPresence.undismiss(c)
            Tip.RESTART_SYSTEM_UI -> SystemAccess.undismiss()
        }
    }

    /** Tests: another store. */
    @Volatile var storeOf: (Context) -> Store = { AppStore(it.applicationContext) }

    private val _justClosed = MutableStateFlow<Tip?>(null)
    /** The card closed last, until its Undo snackbar is gone ([undo] / [consumed]): home's snackbar host shows it. */
    val justClosed: StateFlow<Tip?> = _justClosed

    /** The cards closed now. */
    fun closed(c: Context): List<Tip> = storeOf(c).let { s -> Tip.entries.filter { s.closed(it) } }

    /** The card's close button: hides it and offers Undo. */
    fun close(c: Context, t: Tip) {
        storeOf(c).close(t)
        _justClosed.value = t
    }

    /** The snackbar's Undo: the card comes back where it was. */
    fun undo(c: Context, t: Tip) {
        storeOf(c).restore(t)
        if (_justClosed.value == t) _justClosed.value = null
    }

    /** The snackbar went away without Undo. */
    fun consumed(t: Tip) { if (_justClosed.value == t) _justClosed.value = null }

    /** One card back (debug builds' `widgetBanner --ez reset true` uses the store directly). */
    fun restore(c: Context, t: Tip) = storeOf(c).restore(t)

    /** Settings → *Show closed tips*: every closed card comes back (each shows again whenever its own conditions hold). */
    fun restoreAll(c: Context): Int {
        val s = storeOf(c)
        val closed = Tip.entries.filter { s.closed(it) }
        closed.forEach { s.restore(it) }
        _justClosed.value = null
        return closed.size
    }
}
