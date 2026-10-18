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
import app.mauri.sukrara.features.LockAppearance
import app.mauri.sukrara.features.LockBackground
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

    /** [LockBackground]: card, frosted glass and dim are drawn here (shadow by the widget itself). */
    @Volatile var background = LockBackground.NONE
        set(v) {
            if (field == v) return
            field = v
            root?.get()?.let { r -> r.post { safe("background") { applyBackground() } } }
        }

    /** [LockAppearance]: whether the background is a dark or a light tint ("auto" follows SystemUI's night mode). */
    @Volatile var look = LockAppearance.AUTO
        set(v) {
            if (field == v) return
            field = v
            root?.get()?.let { r -> r.post { safe("look") { applyBackground() } } }
        }

    /** The tint in use, to redo the background only when the dark theme really flips. */
    private var drawnDark: Boolean? = null
    private fun dark(c: Context) = LockAppearance.dark(look, c)

    private var root: WeakReference<ViewGroup>? = null
    private var host: AppWidgetHost? = null
    private var view: AppWidgetHostView? = null
    private var scenes: Any? = null
    private var auth: Any? = null
    private var lastAlpha = -1f
    private var recheck = false
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
        (v as? GuardedView)?.onConfig = { safe("config") { if (drawnDark != dark(v.context)) applyBackground() } }
        // Glance/RemoteViews own the content; no host padding or background.
        v.setPadding(0, 0, 0, 0)
        // Bottom of the window: under every lock screen layer. The fingerprint icon, the shortcuts and the indication
        // text are in the lower Compose layers (not the scene container), so a card, blur or dim must go below them.
        r.addView(v, 0, FrameLayout.LayoutParams(0, 0))
        view = v
        lastAlpha = -1f
        applyBackground()
        place()
        follow()
        log("lock screen widget: view added (id $id)")
        // Tell Sukrara which widget id to update: AppWidgetManager doesn't list it to the provider.
        ctx.sendBroadcast(android.content.Intent(RootStatusBar.ACTION_HELLO).setPackage(RootStatusBar.PKG).putExtra("lockWidgetId", id))
    }

    private fun hide() {
        view?.let { (it.parent as? ViewGroup)?.removeView(it) }
        view = null
        scrim?.let { (it.parent as? ViewGroup)?.removeView(it) }
        scrim = null
    }

    // --- contrast behind the widget (Material "text protection": scrims) -------------------------------------

    private var scrim: View? = null
    private var blur: android.graphics.drawable.Drawable? = null

    private fun applyBackground() {
        val v = view ?: return
        val r = root?.get() ?: return
        val res = r.resources
        val d = res.displayMetrics.density
        @Suppress("DiscouragedApi")
        val radius = res.getIdentifier("notification_corner_radius", "dimen", SystemUiHook.SYSTEMUI)
            .takeIf { it != 0 }?.let(res::getDimension) ?: (28 * d)
        val dark = dark(v.context)
        drawnDark = dark
        blur = if (background == LockBackground.FROSTED) blurDrawable(r, radius, dark) else null
        v.background = when (background) {
            LockBackground.FROSTED -> blur ?: card(v.context, radius, dark)
            LockBackground.CARD -> card(v.context, radius, dark)
            else -> null
        }
        scrim?.let { r.removeView(it); scrim = null }
        if (background == LockBackground.DIM) {
            scrim = ScrimView(r.context, dark).also { s -> r.addView(s, r.indexOfChild(v), FrameLayout.LayoutParams(0, 0)) }
        }
        lastAlpha = -1f
        place()
        follow()
    }

    /**
     * Like a notification, a little see-through: dark = the dark surface colour; light = the home-screen widgets'
     * background (Glance's widgetBackground: Material You's system_accent2_50, near white).
     */
    private fun card(c: Context, radius: Float, dark: Boolean) = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = radius
        setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(c.getColor(
            if (dark) android.R.color.system_surface_container_high_dark else LIGHT_BG), 0xE0))
    }

    /**
     * Frosted glass: the wallpaper behind the widget blurred and tinted, with SystemUI's own BackgroundBlurDrawable
     * (ViewRootImpl, hidden API; the shade uses it). Dark = black 35 %; light = the home widgets' background colour
     * 60 %. Null when cross-window blur is off (battery saver, some devices) → the card instead.
     */
    private fun blurDrawable(r: View, radius: Float, dark: Boolean): android.graphics.drawable.Drawable? = runCatching {
        if (r.context.getSystemService(android.view.WindowManager::class.java)?.isCrossWindowBlurEnabled != true) return null
        val vri = View::class.java.getMethod("getViewRootImpl").invoke(r) ?: return null
        val dr = vri.javaClass.getMethod("createBackgroundBlurDrawable").invoke(vri) as android.graphics.drawable.Drawable
        val d = r.resources.displayMetrics.density
        dr.javaClass.getMethod("setBlurRadius", Int::class.javaPrimitiveType).invoke(dr, (24 * d).toInt())
        val tint = if (dark) 0x59000000 else androidx.core.graphics.ColorUtils.setAlphaComponent(r.context.getColor(LIGHT_BG), 0x99)
        dr.javaClass.getMethod("setColor", Int::class.javaPrimitiveType).invoke(dr, tint)
        dr.javaClass.getMethod("setCornerRadius", Float::class.javaPrimitiveType).invoke(dr, radius)
        dr
    }.onFailure { log("lock screen widget: no blur: $it") }.getOrNull()

    /**
     * Dim: Material's gradient scrim (dark end 60 %, its middle 3/10 towards the dark end, fading to 0) over the bottom
     * of the screen, from a little above the widget down to the bottom edge, full width. Light: the same in white.
     */
    private class ScrimView(c: Context, private val dark: Boolean) : View(c) {
        private val paint = android.graphics.Paint()
        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            val rgb = if (dark) 0 else 0xFFFFFF
            paint.shader = android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(),
                intArrayOf(rgb, 0x4D000000 or rgb, 0x99000000.toInt() or rgb), floatArrayOf(0f, 0.35f, 1f),
                android.graphics.Shader.TileMode.CLAMP)
        }
        override fun onDraw(cv: android.graphics.Canvas) { cv.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint) }
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
        scrim?.let { s ->
            val slp = s.layoutParams as FrameLayout.LayoutParams
            val sTop = (top - 64 * d).toInt()
            if (slp.width != w || slp.height != h - sTop || slp.topMargin != sTop) {
                slp.width = w; slp.height = h - sTop; slp.leftMargin = 0; slp.topMargin = sTop
                s.layoutParams = slp
            }
        }
        val gap = 6 * d
        val extras = Bundle().apply {
            putFloat(LockscreenWidget.HOLE_LEFT, (lock.left - side - gap) / d)
            putFloat(LockscreenWidget.HOLE_RIGHT, (lock.right - side + gap) / d)
            putFloat(LockscreenWidget.HOLE_BOTTOM, lock.height() / d)
        }
        // updateAppWidgetSize reports each size minus the default widget padding, but the view has none (the widget
        // draws edge to edge): add it back, or the widget draws for a smaller box and gets stretched (measured 9 %
        // taller with a test square). Only sends new options when they differ.
        val pad = AppWidgetHostView.getDefaultPaddingForWidget(r.context, v.appWidgetInfo?.provider, null)
        v.updateAppWidgetSize(extras, listOf(SizeF((width + pad.left + pad.right) / d, (height + pad.top + pad.bottom) / d)))
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
        // Mid-transition: check again next frame, so the widget never stays half-faded if SystemUI's last frame of the
        // transition came before the scene settled.
        if (a > 0f && a < 1f && !recheck) {
            recheck = true
            v.postOnAnimation { recheck = false; safe("follow") { follow() } }
        }
        if (a == lastAlpha) return
        lastAlpha = a
        for (x in listOfNotNull(v, scrim)) {
            x.alpha = a
            x.visibility = if (a > 0f) View.VISIBLE else View.INVISIBLE
        }
        // The blur is composited by the window, not with the view: fade it explicitly.
        blur?.alpha = (a * 255).toInt()
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
        /** The dark theme may have flipped: an automatic appearance redoes its tint. */
        var onConfig: (() -> Unit)? = null
        override fun dispatchTouchEvent(ev: MotionEvent) = allow && super.dispatchTouchEvent(ev)
        override fun onConfigurationChanged(c: android.content.res.Configuration?) {
            super.onConfigurationChanged(c)
            onConfig?.invoke()
        }
    }

    private class GuardedHost(c: Context) : AppWidgetHost(c, HOST_ID) {
        override fun onCreateView(c: Context, id: Int, w: android.appwidget.AppWidgetProviderInfo?): AppWidgetHostView = GuardedView(c)
    }

    // --- helpers --------------------------------------------------------------------------------------------

    private inline fun safe(what: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) { log("lock screen widget: $what failed: $t") }
    }

    companion object {
        /** Our host id inside SystemUI's uid (its glanceable hub uses its own). "SUK" in ASCII. */
        const val HOST_ID = 0x53554B
        /** Extra width on each side of the lock icon where touches are never the widget's. */
        const val LOCK_MARGIN_DP = 24
        /** The home-screen widgets' light background (Glance's widgetBackground on Android 12+). */
        val LIGHT_BG = android.R.color.system_accent2_50
    }
}
