// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.hook

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.PowerManager
import android.util.SizeF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import app.mauri.sukrara.status.RootStatusBar
import app.mauri.sukrara.widgets.LockscreenWidget
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.math.hypot

/**
 * Investigation 17: Sukrara's lock screen widget in the empty lower part of the lock screen, around the
 * fingerprint/lock icon and above the shortcuts.
 *
 * Android 17's lock screen is one Compose "scene container" (SceneWindowRootView → ComposeView), so there is no
 * layout to add a view to. Instead SystemUI itself hosts a real app widget ([LockscreenWidget], Glance): an
 * [AppWidgetHost] in SystemUI (which holds BIND_APPWIDGET, like its own glanceable hub), its [AppWidgetHostView] added
 * to the window root *under* the Compose layer, so the lock icon, the shortcuts and the notifications keep drawing on
 * top of it. Sukrara's widget pipeline updates it like any home-screen widget; a tap runs the widget's PendingIntent
 * (the treatment dialog, which shows over the lock screen).
 *
 *  - **Visibility** follows the lock screen scene: the scene interactor's transition state gives the current scene
 *    and the progress of a transition (shade pull-down, bouncer, unlock), read on every frame the window draws.
 *    Hidden while the screen is off (AOD) and when the app turns it off (`lockWidget`).
 *  - **Touches** still go to the lock screen (swipe up to unlock, long press to customise). The widget gets a copy of
 *    every gesture that starts on it, and a cancel as soon as it moves beyond the touch slop, so only taps reach it.
 *  - **Placement** from SystemUI's own geometry: the under-display fingerprint bounds (AuthController) and the
 *    shortcut dimens. The widget gets the lock icon's position as extra widget options ([LockscreenWidget.HOLE_LEFT]…)
 *    so its layout leaves that spot free.
 */
internal class LockscreenWidgetHost(private val log: (String) -> Unit) {
    @Volatile var enabled = false
        set(v) { field = v; root?.get()?.let { r -> r.post { safe("enable") { if (v) ensureView(r) else hide() } } } }

    private var root: WeakReference<ViewGroup>? = null
    private var host: AppWidgetHost? = null
    private var view: AppWidgetHostView? = null
    private var scenes: Any? = null
    private var auth: Any? = null
    private var lastAlpha = -1f
    /** Where the lock icon / fingerprint sensor is, from the last [place]. */
    private var lockRect = Rect()

    // --- hooks (installed by SystemUiHook) -------------------------------------------------------------------

    /** The scene interactor (Lockscreen / Shade / Bouncer / Gone …), captured when Dagger creates it. */
    fun onScenes(o: Any) { scenes = o }

    /** AuthController: knows the under-display fingerprint sensor's bounds = where the lock icon sits. */
    fun onAuth(o: Any) { auth = o }

    /** The notification shade window's root view was attached: the lock screen lives in it. */
    fun onRootAttached(r: ViewGroup) = safe("attach") {
        if (root?.get() === r) return@safe
        root = WeakReference(r)
        r.viewTreeObserver.addOnPreDrawListener(ViewTreeObserver.OnPreDrawListener { safe("predraw") { follow() }; true })
        r.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> safe("layout") { place() } }
        if (enabled) ensureView(r)
        log("lock screen widget: root attached")
    }

    /** Every touch on the window, before SystemUI handles it (it always gets it too). */
    fun onTouch(ev: MotionEvent) = safe("touch") { touch(ev) }

    // --- the hosted widget ----------------------------------------------------------------------------------

    private fun ensureView(r: ViewGroup) {
        if (view?.parent === r) { place(); return }
        val ctx = r.context
        val mgr = AppWidgetManager.getInstance(ctx)
        val provider = ComponentName(RootStatusBar.PKG, LockscreenWidget.RECEIVER)
        val h = host ?: GuardedHost(ctx).also { host = it }
        h.startListening()
        // One widget id for good: reuse it across SystemUI restarts, drop leftovers.
        val ids = h.appWidgetIds.toList()
        var id = ids.firstOrNull { mgr.getAppWidgetInfo(it)?.provider == provider }
        ids.filter { it != id }.forEach { h.deleteAppWidgetId(it) }
        if (id == null) {
            val n = h.allocateAppWidgetId()
            if (!mgr.bindAppWidgetIdIfAllowed(n, provider)) {
                h.deleteAppWidgetId(n)
                log("lock screen widget: bind refused (Sukrara missing?)"); return
            }
            id = n
            log("lock screen widget: bound id $n")
        }
        val info = mgr.getAppWidgetInfo(id) ?: run { log("lock screen widget: no provider info"); return }
        val v = h.createView(ctx, id, info)
        // Glance/RemoteViews own the content; no host padding or background.
        v.setPadding(0, 0, 0, 0)
        // Under the notifications and the Compose lock screen, above the root's own background layers.
        val below = (0 until r.childCount).map(r::getChildAt).indexOfFirst { it.idName() == "shared_notification_container" }
        r.addView(v, if (below >= 0) below else 0, FrameLayout.LayoutParams(0, 0))
        view = v
        lastAlpha = -1f
        place()
        follow()
        log("lock screen widget: view added (id $id)")
    }

    private fun hide() {
        view?.let { (it.parent as? ViewGroup)?.removeView(it) }
        view = null
    }

    /** Size and position from the lock icon and the shortcuts; tells the widget where the lock icon is. */
    private fun place() {
        val r = root?.get() ?: return
        val v = view ?: return
        if (r.width == 0 || r.height == 0) return
        val res = r.resources
        val d = res.displayMetrics.density
        @Suppress("DiscouragedApi")
        fun dimen(name: String, fallbackDp: Float): Int = res.getIdentifier(name, "dimen", SystemUiHook.SYSTEMUI)
            .takeIf { it != 0 }?.let(res::getDimensionPixelSize) ?: (fallbackDp * d).toInt()
        val w = r.width
        val h = r.height
        val lock = lockIcon(r, ::dimen)
        if (lock != lockRect) log("lock screen widget: lock icon at $lock")
        lockRect = lock
        val side = (16 * d).toInt()
        val shortcutsTop = h - dimen("keyguard_affordance_vertical_offset", 32f) - dimen("keyguard_affordance_fixed_height", 48f)
        val top = lock.top
        val bottom = shortcutsTop - (8 * d).toInt()
        if (bottom - top < 80 * d) return
        val lp = v.layoutParams as FrameLayout.LayoutParams
        val width = w - 2 * side
        val height = bottom - top
        if (lp.width != width || lp.height != height || lp.leftMargin != side || lp.topMargin != top) {
            lp.width = width; lp.height = height; lp.leftMargin = side; lp.topMargin = top
            v.layoutParams = lp
        }
        val gap = 6 * d
        val extras = Bundle().apply {
            putFloat(LockscreenWidget.HOLE_LEFT, (lock.left - side - gap) / d)
            putFloat(LockscreenWidget.HOLE_RIGHT, (lock.right - side + gap) / d)
            putFloat(LockscreenWidget.HOLE_BOTTOM, lock.height() / d)
        }
        // Only sends new options when they differ (AppWidgetHostView compares them).
        v.updateAppWidgetSize(extras, listOf(SizeF(width / d, height / d)))
    }

    private fun lockIcon(r: ViewGroup, dimen: (String, Float) -> Int): Rect {
        val udfps = runCatching { authBounds?.get(auth) as? Rect }.getOrNull()
        if (udfps != null && !udfps.isEmpty) return Rect(udfps)
        // No under-display sensor: SystemUI's default lock icon, centred above lock_icon_margin_bottom.
        val size = (64 * r.resources.displayMetrics.density).toInt()
        val bottom = r.height - dimen("lock_icon_margin_bottom", 74f)
        return Rect(r.width / 2 - size / 2, bottom - size, r.width / 2 + size / 2, bottom)
    }

    // --- visibility -----------------------------------------------------------------------------------------

    private fun follow() {
        val v = view ?: return
        val a = if (enabled && interactive(v.context)) lockscreenAlpha() else 0f
        if (a == lastAlpha) return
        lastAlpha = a
        v.alpha = a
        v.visibility = if (a > 0f) View.VISIBLE else View.INVISIBLE
    }

    private fun interactive(c: Context) = c.getSystemService(PowerManager::class.java)?.isInteractive == true

    /** 1 on the idle lock screen, 0 elsewhere, in between while a transition to or from it runs. */
    private fun lockscreenAlpha(): Float {
        val s = scenes ?: return 0f
        val r = reflect(s) ?: return 0f
        val ts = r.transitionState.invoke(s) ?: return 0f
        val lock = r.lockscreen
        if (r.idle.isInstance(ts)) {
            val overlays = r.idleOverlays.invoke(ts) as? Set<*>
            return if (r.idleScene.invoke(ts) == lock && overlays.isNullOrEmpty()) 1f else 0f
        }
        if (!r.transition.isInstance(ts)) return 0f
        val p = (r.progress.invoke(ts) as Float).coerceIn(0f, 1f)
        val from = r.from.get(ts)
        val to = r.to.get(ts)
        return ((if (from == lock) 1 - p else 0f) + (if (to == lock) p else 0f)).coerceIn(0f, 1f)
    }

    /** Reflection handles for SystemUI's scene classes, resolved once. */
    private class Scenes(
        val transitionState: Method, val lockscreen: Any, val idle: Class<*>, val idleScene: Method,
        val idleOverlays: Method, val transition: Class<*>, val progress: Method, val from: Field, val to: Field,
    )

    private var scenesReflect: Scenes? = null
    private var reflectFailed = false
    private var authBounds: Field? = null

    private fun reflect(s: Any): Scenes? {
        scenesReflect?.let { return it }
        if (reflectFailed) return null
        return runCatching {
            val cl = s.javaClass.classLoader!!
            val state = "com.android.compose.animation.scene.content.state.TransitionState"
            val idle = cl.loadClass("$state\$Idle")
            val transition = cl.loadClass("$state\$Transition")
            Scenes(
                s.javaClass.getMethod("getTransitionState"),
                cl.loadClass("com.android.systemui.scene.shared.model.Scenes").getField("Lockscreen").get(null)!!,
                idle, idle.getMethod("getCurrentScene"), idle.getMethod("getCurrentOverlays"),
                transition, transition.getMethod("getProgress"),
                transition.getField("fromContent"), transition.getField("toContent"),
            )
        }.onFailure { reflectFailed = true; log("lock screen widget: scene reflection failed: $it") }.getOrNull()
            ?.also { scenesReflect = it }
    }

    fun resolveAuth(cl: ClassLoader) {
        authBounds = runCatching {
            cl.loadClass("com.android.systemui.biometrics.AuthController").getDeclaredField("mUdfpsBounds").apply { isAccessible = true }
        }.onFailure { log("lock screen widget: no AuthController.mUdfpsBounds") }.getOrNull()
    }

    // --- touches --------------------------------------------------------------------------------------------

    private var tracking = false
    private var downX = 0f
    private var downY = 0f

    private fun touch(ev: MotionEvent) {
        val v = view ?: return
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = v.visibility == View.VISIBLE && v.alpha > 0.9f && hits(v, ev)
                if (!tracking) return
                downX = ev.x; downY = ev.y
                deliver(v, ev, null)
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> if (tracking) {
                // Only taps: a drag (unlock swipe) or a press held like a long press (a finger resting on the
                // fingerprint sensor, a long press for the lock screen menu) is cancelled before it can click.
                val moved = hypot(ev.x - downX, ev.y - downY) > ViewConfiguration.get(v.context).scaledTouchSlop
                val held = ev.eventTime - ev.downTime > ViewConfiguration.getLongPressTimeout()
                if (moved || held) { deliver(v, ev, MotionEvent.ACTION_CANCEL); tracking = false }
                else { deliver(v, ev, null); if (ev.actionMasked == MotionEvent.ACTION_UP) tracking = false }
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> if (tracking) {
                deliver(v, ev, MotionEvent.ACTION_CANCEL); tracking = false
            }
        }
    }

    /**
     * On the widget, but not in the lock icon's column: the under-display fingerprint sensor sits there, and a finger
     * on it lands anywhere around the icon. That strip (the icon ± [LOCK_MARGIN_DP], top to bottom of the widget)
     * stays SystemUI's alone.
     */
    private fun hits(v: View, ev: MotionEvent): Boolean {
        if (ev.x < v.left || ev.x >= v.right || ev.y < v.top || ev.y >= v.bottom) return false
        val m = LOCK_MARGIN_DP * v.resources.displayMetrics.density
        return ev.x < lockRect.left - m || ev.x > lockRect.right + m
    }

    private fun deliver(v: View, ev: MotionEvent, action: Int?) {
        val e = MotionEvent.obtain(ev)
        if (action != null) e.action = action
        e.offsetLocation(-v.left.toFloat(), -v.top.toFloat())
        (v as? GuardedView)?.allow = true
        try { v.dispatchTouchEvent(e) } finally { (v as? GuardedView)?.allow = false; e.recycle() }
    }

    /**
     * The window would also hand the widget every touch the Compose lock screen above it doesn't consume (taps on
     * empty space, a finger on the fingerprint sensor). It only takes the filtered copies from [deliver].
     */
    private class GuardedView(c: Context) : AppWidgetHostView(c) {
        var allow = false
        override fun dispatchTouchEvent(ev: MotionEvent) = allow && super.dispatchTouchEvent(ev)
    }

    private class GuardedHost(c: Context) : AppWidgetHost(c, HOST_ID) {
        override fun onCreateView(c: Context, id: Int, w: android.appwidget.AppWidgetProviderInfo?): AppWidgetHostView = GuardedView(c)
    }

    // --- helpers --------------------------------------------------------------------------------------------

    private fun View.idName(): String? = if (id == View.NO_ID) null else runCatching { resources.getResourceEntryName(id) }.getOrNull()

    private inline fun safe(what: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) { log("lock screen widget: $what failed: $t") }
    }

    companion object {
        /** Our host id inside SystemUI's uid (its glanceable hub uses its own). "SUK" in ASCII. */
        const val HOST_ID = 0x53554B
        /** Extra width on each side of the lock icon where touches are never the widget's. */
        const val LOCK_MARGIN_DP = 24
    }
}
