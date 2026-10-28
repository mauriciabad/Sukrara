// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.systemui

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.mauri.sukrara.core.data.settings.HookPosition
import app.mauri.sukrara.graph.TrendAngle
import app.mauri.sukrara.graph.TrendMark
import app.mauri.sukrara.graph.ValueGlyphs
import app.mauri.sukrara.monitor.R
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.ref.WeakReference
import java.lang.reflect.Executable
import java.util.Collections
import java.util.WeakHashMap

/**
 * Sukrara's LSPosed module (libxposed API 102, entry in `META-INF/xposed/java_init.list`, scope System UI). Inside
 * System UI it
 *  - draws the value as text next to the clock or left of the system icons (the clock's font and tint, the range
 *    colour, the card's arrow, struck through / "no readings" / the flag as the card decides: [HookText]);
 *  - keeps our system icons ([SlotIcons], set by the root [SlotDaemon]) together and where the person placed them, in
 *    their range colour, and first in line for the row's space ([IconFit]: glucose wins over other icons);
 *  - drops Sukrara's own notification icon from the status bar while the value shows there.
 * The app feeds it [HookState] broadcasts only the app can send.
 *
 * Safety: System UI is sent into rescue mode if it crashes 5× in a minute, so the whole entry and every hook body
 * catches Throwable (hooks are PROTECTIVE too), and a failing piece turns itself off. Kill switch, read before
 * anything is hooked: `adb shell setprop debug.sukrara.systemui_off 1` (or `settings put global sukrara_systemui_off
 * 1`), then restart System UI.
 */
class SystemUiHook : XposedModule() {
    private lateinit var ourPkg: String

    override fun onPackageReady(param: PackageReadyParam) {
        try {
            if (param.packageName != SystemUi.PACKAGE || !param.isFirstPackage) return
            if (killedByProperty()) return log("off: ${SystemUi.KILL_PROP}")
            ourPkg = moduleApplicationInfo.packageName
            val cl = param.classLoader
            safe("hook bar") {
                // the collapsed status bar over apps and the home screen: still a classic View hierarchy on Android 17
                after(cl.loadClass("com.android.systemui.statusbar.phone.PhoneStatusBarView").getDeclaredMethod("onFinishInflate")) {
                    attach(it.thisObject as ViewGroup, Bar.MAIN)
                }
                // the lock screen's status bar (no clock there: left of the system icons)
                after(cl.loadClass("com.android.systemui.statusbar.phone.KeyguardStatusBarView").getDeclaredMethod("onFinishInflate")) {
                    attach(it.thisObject as ViewGroup, Bar.KEYGUARD)
                }
            }
            safe("hook icon order") { hookIconOrder(cl) }
            safe("hook icon fit") { hookIconFit(cl) }
            safe("hook icon tint") { hookIconTint(cl) }
            safe("hook notification icons") { hookNotificationIcons(cl) }
        } catch (t: Throwable) {
            log("module off: $t")
        }
    }

    private fun killedByProperty(): Boolean =
        runCatching {
            Class
                .forName("android.os.SystemProperties")
                .getMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType)
                .invoke(null, SystemUi.KILL_PROP, false) as Boolean
        }.getOrDefault(false)

    private fun killedBySetting(ctx: Context) = Settings.Global.getInt(ctx.contentResolver, SystemUi.KILL_SETTING, 0) == 1

    // --- the notification icon ---------------------------------------------------------------------------------

    /**
     * Drops Sukrara's notification icon from the status bar while the value shows there (`hideIcon`); the
     * notification stays in the shade, on the lock screen and on AOD. System UI maps the set of status-bar icons
     * (ActiveNotificationIconModel) in a `map` collector that belongs to the status bar only, so the set is filtered
     * there. Takes effect on System UI's next rebuild of that set: the app re-posts its notification after the hello.
     */
    private fun hookNotificationIcons(cl: ClassLoader) {
        val vm = "com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerStatusBarViewModel"
        val collector = cl.find("$vm\$special\$\$inlined\$map\$1\$2") ?: return log("no $vm map collector")
        val emit =
            collector.declaredMethods.firstOrNull { it.name == "emit" && it.parameterCount == 2 }
                ?: return log("no $vm map collector emit()")
        val model =
            cl.find("com.android.systemui.statusbar.notification.icon.domain.interactor.ActiveNotificationIconModel")
                ?: return log("no ActiveNotificationIconModel")
        val key =
            runCatching { model.getDeclaredField("notifKey") }.getOrNull()?.apply { isAccessible = true }
                ?: return log("no ActiveNotificationIconModel.notifKey")

        // notification keys are "user|package|id|tag|uid"
        fun ours(o: Any?) = model.isInstance(o) && (key.get(o) as? String)?.split('|')?.getOrNull(1) == ourPkg
        hook(emit).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
            // the same collector class also maps the chips' keys (not a set of icon models): untouched
            val set = chain.getArg(0) as? Set<*>
            val st = state
            val filtered =
                try {
                    if (set != null && st.enabled && st.hideIcon && st.mgdl > 0 && set.any(::ours)) {
                        LinkedHashSet(set.filterNot(::ours))
                    } else {
                        null
                    }
                } catch (t: Throwable) {
                    log("icon filter failed: $t")
                    null
                }
            if (filtered == null) chain.proceed() else chain.proceed(arrayOf(filtered, chain.getArg(1)))
        }
        log("notification icon hook: ok")
    }

    // --- our system icons: colour, order, fit -----------------------------------------------------------------------

    /**
     * Range colour for our number icons: System UI's Compose `StatusBarIcon` tints every icon with one colour filter
     * and draws it with `Image(painter, contentDescription, …, colorFilter, …)`. Ours are recognised by their
     * description ([SlotIcons.isValue]); their filter gets the range colour (in range: System UI's own tint).
     */
    private fun hookIconTint(cl: ClassLoader) {
        val image =
            cl.find("androidx.compose.foundation.ImageKt")?.declaredMethods?.firstOrNull {
                it.name == "Image" &&
                    it.parameterCount == 10 &&
                    it.parameterTypes[1] == String::class.java &&
                    it.parameterTypes[6].name == "androidx.compose.ui.graphics.ColorFilter"
            } ?: return log("no Compose Image(…, ColorFilter, …)")
        val filterClass = cl.find("androidx.compose.ui.graphics.BlendModeColorFilter") ?: return log("no Compose BlendModeColorFilter")
        // StatusBarIcon makes a fresh filter object per draw, so that object is recoloured: its native filter lives in
        // the base class
        val native =
            filterClass.superclass
                ?.declaredFields
                ?.firstOrNull { it.type == ColorFilter::class.java }
                ?.apply { isAccessible = true } ?: return log("no ColorFilter.nativeColorFilter")
        val colorField = runCatching { filterClass.getDeclaredField("color").apply { isAccessible = true } }.getOrNull()
        hook(image).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
            val desc = chain.getArg(1) as? String
            val filter = chain.getArg(6)
            if (desc != null && SlotIcons.isValue(desc) && filterClass.isInstance(filter)) {
                runCatching {
                    val color = HookText.iconColor(state, nowS(), baseColor) ?: return@runCatching
                    native.set(filter, BlendModeColorFilter(color, BlendMode.SRC_IN))
                    // Compose's own copy of the colour (packed sRGB: ARGB in the high 32 bits), for equals()
                    colorField?.setLong(filter, (color.toLong() and 0xFFFFFFFFL) shl 32)
                }
            }
            chain.proceed()
        }
        log("icon tint hook: ok")
    }

    /** The clock's colour, last seen (decides which palette reads on this status bar). */
    @Volatile private var baseColor = 0xFFFFFFFF.toInt()

    /**
     * Our icons' place among the right-side icons: those are Compose, so the list System UI renders is reordered:
     * ours together (number, then trend: after a System UI restart they come back in hash order) and next to the
     * chosen icon ([IconFit.arrange]). The list is built in an R8-named lambda, found by shape, not name.
     */
    private fun hookIconOrder(cl: ClassLoader) {
        val vm = "com.android.systemui.statusbar.systemstatusicons.ui.viewmodel.SystemStatusIconsViewModelImpl"
        val vmClass = cl.find(vm) ?: return log("no $vm")
        var hooked = 0
        for (n in 0..15) {
            val c = cl.find("$vm\$\$ExternalSyntheticLambda$n") ?: continue
            if (c.interfaces.none { it.name == "kotlin.jvm.functions.Function0" }) continue
            if (c.declaredFields.none { it.type == vmClass }) continue
            val invoke = c.declaredMethods.firstOrNull { it.name == "invoke" && it.parameterCount == 0 } ?: continue
            hook(invoke).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                val result = chain.proceed()
                try {
                    reorder(result) ?: result
                } catch (t: Throwable) {
                    log("reorder failed: $t")
                    result
                }
            }
            hooked++
        }
        iconOrderHooked = hooked > 0
        log("icon order hooks: $hooked")
    }

    /** The icon order hook is in place: the hello tells the app, so the slot daemon leaves the order to us. */
    @Volatile private var iconOrderHooked = false

    private fun reorder(result: Any?): List<*>? {
        val list = result as? List<*> ?: return null
        val st = state
        val after = st.position.takeIf { st.systemIcons }?.removePrefix(HookPosition.SLOT_PREFIX)
        return IconFit.arrange(list.map(::slotName), SlotIcons.ORDER, after, slotOrder)?.map { list[it] }
    }

    /** The platform's status-bar slot order (`config_statusBarIcons`, what System UI sorts its own icons by). */
    @get:SuppressLint("DiscouragedApi")
    private val slotOrder: List<String> by lazy {
        runCatching {
            val res =
                android.content.res.Resources
                    .getSystem()
            res.getStringArray(res.getIdentifier("config_statusBarIcons", "array", "android")).toList()
        }.getOrElse {
            log("no config_statusBarIcons: $it")
            emptyList()
        }
    }

    /**
     * Glucose wins the space: System UI's `EllipsizingRow` measure policy fits the icons from the right and folds the
     * first one that doesn't fit, and everything left of it, into an overflow dot; ours were leftmost, so the number
     * went first. This hands System UI's own measurement the row with ours at the priority end ([IconFit]), then puts
     * the shown icons back in their on-screen order and keeps number + trend together (both, or both in the dot). The
     * classes are found by shape (R8 keeps their names on Pixel builds, but they move between releases).
     */
    private fun hookIconFit(cl: ClassLoader) {
        val policyIface = cl.find("androidx.compose.ui.layout.MeasurePolicy") ?: return log("no MeasurePolicy")
        val policies =
            listOf("com.android.systemui.common.ui.compose", "com.android.systemui.statusbar.icons.ui.compose")
                .flatMap { pkg -> (1..3).flatMap { a -> (1..3).map { b -> "$pkg.EllipsizingRowKt\$EllipsizingRow\$$a\$$b" } } }
                .mapNotNull { cl.find(it) }
                .filter { policyIface.isAssignableFrom(it) }
        var hooked = 0
        var pulls = 0
        for (c in policies) {
            val measure =
                c.declaredMethods.firstOrNull {
                    it.name.startsWith("measure") &&
                        it.parameterCount == 3 &&
                        it.parameterTypes[1] == List::class.java &&
                        it.parameterTypes[2] == Long::class.javaPrimitiveType
                } ?: continue
            val fit = RowFit(c, cl)
            // System UI's placement block for this policy (a synthetic lambda next to it), for the number → trend gap
            (0..5)
                .mapNotNull { cl.find("${c.name}\$\$ExternalSyntheticLambda$it") }
                .firstOrNull { b ->
                    b.interfaces.any { it.name == "kotlin.jvm.functions.Function1" } && b.declaredFields.any { it.type == List::class.java }
                }?.let { b -> safe("hook icon pull") { fit.hookPull(b) } }
            if (fit.pullHooked) pulls++
            hook(measure).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                val list = chain.getArg(1) as? List<*>
                // decided before System UI measures: the children may be measured only once per pass
                val plan =
                    try {
                        if (fit.broken || list == null || !state.systemIcons) {
                            null
                        } else {
                            fit.plan(chain.thisObject!!, chain.getArg(0)!!, list, chain.getArg(2) as Long)
                        }
                    } catch (t: Throwable) {
                        fit.fail("plan", t)
                        null
                    }
                if (plan == null) return@intercept chain.proceed()
                val result = chain.proceed(arrayOf(chain.getArg(0), plan.permuted, plan.constraints))
                try {
                    fit.apply(chain.thisObject!!, chain.getArg(0)!!, chain.getArg(2) as Long, result, plan)
                } catch (t: Throwable) {
                    fit.fail("apply", t)
                }
                result
            }
            hooked++
        }
        log("icon fit hooks: $hooked, gap hooks: $pulls")
    }

    /** The reflection behind [hookIconFit] for one measure-policy class; [broken] after a failure (System UI's own fit). */
    private inner class RowFit(
        private val policy: Class<*>,
        cl: ClassLoader,
    ) {
        @Volatile var broken = false
        private val fromStart =
            policy.declaredFields
                .firstOrNull { it.type.isEnum && it.type.simpleName == "FillDirection" }
                ?.apply { isAccessible = true }
        private val spacingDp =
            policy.declaredFields
                .firstOrNull {
                    it.type == Float::class.javaPrimitiveType
                }?.apply { isAccessible = true }
        private val nodeClass = cl.find("androidx.compose.ui.node.LayoutNode")
        private val placeableWidth = cl.find("androidx.compose.ui.layout.Placeable")?.getMethod("getWidth")
        private val placeableHeight = cl.find("androidx.compose.ui.layout.Placeable")?.getMethod("getHeight")
        private val density = cl.find("androidx.compose.ui.unit.Density")?.getMethod("getDensity")
        private val fontScale by lazy {
            cl.find("androidx.compose.ui.unit.Density")?.methods?.firstOrNull { it.name == "getFontScale" && it.parameterCount == 0 }
        }
        private val constraintsClass = cl.find("androidx.compose.ui.unit.Constraints")
        private val minWidth =
            constraintsClass?.declaredMethods?.firstOrNull { it.name.startsWith("getMinWidth-impl") && it.parameterCount == 1 }
        private val maxWidthOf =
            constraintsClass?.declaredMethods?.firstOrNull {
                it.name.startsWith("getMaxWidth-impl") &&
                    !it.name.endsWith("\$default") &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0] == Long::class.javaPrimitiveType
            }

        /** `Constraints.copy(maxWidth = …)` as compiled: its `$default` bridge (R8 may drop the plain copy's receiver). */
        private val copyDefault =
            constraintsClass?.declaredMethods?.firstOrNull {
                it.name.startsWith("copy-") &&
                    it.name.endsWith("\$default") &&
                    it.parameterCount == 7 &&
                    it.parameterTypes[0] == Long::class.javaPrimitiveType
            }
        private val placeableWidthField =
            cl
                .find("androidx.compose.ui.layout.Placeable")
                ?.declaredFields
                ?.firstOrNull { it.name == "width" && it.type == Int::class.javaPrimitiveType }
                ?.apply { isAccessible = true }
        private val layoutDirection =
            cl.find("androidx.compose.ui.layout.IntrinsicMeasureScope")?.methods?.firstOrNull {
                it.name == "getLayoutDirection" && it.parameterCount == 0
            }

        /** The last pull from real icon heights; before the first, estimated from System UI's 13 sp icon height. */
        @Volatile private var lastPull = 0

        /** The number's placeable and how many px System UI's placement block moves everything after it left. */
        inner class Pull(
            val number: Any,
            val px: Int,
        )

        /** Per placement block (a new one per measurement; Compose may run one several times): the pull to apply. */
        val pulls: MutableMap<Any, Pull> = Collections.synchronizedMap(WeakHashMap())

        @Volatile var pullHooked = false

        /**
         * Our icons' places in the row System UI measures ([permuted]); [number] / [trend] = the pair's, when both are
         * there; [extend] px more room handed to System UI's fit ([constraints]) because the pull gives them back.
         */
        inner class Plan(
            val permuted: List<Any?>,
            val perm: IntArray,
            val oursPermuted: Set<Int>,
            val number: Int?,
            val trend: Int?,
            val extend: Int,
            val constraints: Long,
        )

        fun fail(
            what: String,
            t: Throwable,
        ) {
            broken = true
            log("icon fit $what failed, System UI's own fit from now on: $t")
        }

        fun plan(
            thiz: Any,
            scope: Any,
            list: List<*>,
            constraints: Long,
        ): Plan? {
            checkNotNull(semantics) { "no LayoutNode.getSemanticsConfiguration" }
            val kinds = list.map { kindOf(it) }
            val ours = list.indices.filter { kinds[it] != null }.toSet()
            if (ours.isEmpty()) return null
            val fromEnd = (fromStart?.get(thiz) as? Enum<*>)?.name != "FromStart"
            val perm = IconFit.permutation(list.size, ours, fromEnd) ?: IntArray(list.size) { it }
            val at = { k: Kind -> perm.indices.singleOrNull { kinds[perm[it]] == k } }
            val trend = at(Kind.TREND)
            val number = if (trend != null) at(Kind.OURS) else null
            // the pull narrows our pair after System UI's fit: offer that room up front, so another icon can take it
            // instead of the dot; only with number right before trend (then, both shown, they're side by side)
            val pair = number != null && trend != null && perm[number] + 1 == perm[trend]
            val d = density?.invoke(scope) as? Float ?: return null
            val extend =
                if (pair && pullHooked && placeableWidthField != null && isLtr(scope)) {
                    val spacing = Math.round((spacingDp?.getFloat(thiz) ?: 6f) * d)
                    lastPull.takeIf { it > 0 } ?: IconFit.pull(spacing, Math.round(13f * ((fontScale?.invoke(scope) as? Float) ?: 1f) * d))
                } else {
                    0
                }
            val wider = if (extend > 0) widen(constraints, extend) else null
            return Plan(
                perm.map { list[it] },
                perm,
                perm.indices.filter { perm[it] in ours }.toSet(),
                number,
                trend,
                if (wider != null) extend else 0,
                wider ?: constraints,
            )
        }

        private fun isLtr(scope: Any) = (layoutDirection?.invoke(scope) as? Enum<*>)?.name == "Ltr"

        /** [c] with [px] more maximum width (`copy(maxWidth = …)`, mask 0b1101), or null when unbounded / not found. */
        private fun widen(
            c: Long,
            px: Int,
        ): Long? {
            val max = maxWidthOf?.invoke(null, c) as? Int ?: return null
            if (max == Int.MAX_VALUE) return null
            return copyDefault?.invoke(null, c, 0, max + px, 0, 0, 0b1101, null) as? Long
        }

        /** After System UI's measurement: rewrite its decision (read by its placement block, which runs later). */
        fun apply(
            thiz: Any,
            scope: Any,
            constraints: Long,
            result: Any?,
            plan: Plan,
        ) {
            val blockField =
                result?.javaClass?.declaredFields?.firstOrNull {
                    it.type.name == "kotlin.jvm.functions.Function1" &&
                        it
                            .apply { isAccessible = true }
                            .get(result)
                            ?.javaClass
                            ?.name
                            ?.startsWith(policy.name.substringBefore("\$")) == true
                } ?: error("no placement block in $result")
            val block = blockField.get(result)!!
            val decision =
                block.javaClass.declaredFields
                    .map { it.apply { isAccessible = true }.get(block) }
                    .firstOrNull { o ->
                        o != null &&
                            o.javaClass.name.substringBeforeLast('.') == policy.name.substringBeforeLast('.') &&
                            o.javaClass.declaredFields.any { it.type == List::class.java } &&
                            o.javaClass.declaredFields.any { it.type == Integer::class.java }
                    } ?: error("no MeasureResult in ${block.javaClass.name}")
            val fields = decision.javaClass.declaredFields.onEach { it.isAccessible = true }
            val visibleField = fields.first { it.type == List::class.java }
            val overflowField = fields.first { it.type == Integer::class.java }

            @Suppress("UNCHECKED_CAST")
            val visible = visibleField.get(decision) as MutableList<Int>
            val before = IconFit.Fit(visible.toList(), overflowField.get(decision) as Int?)
            val after = IconFit.repair(before, plan.perm, plan.oursPermuted)
            if (after != before) {
                visible.clear()
                visible.addAll(after.visible)
                overflowField.set(decision, after.overflow)
            }
            val widths = plan.permuted.map { (placeableWidth?.invoke(it) as? Int) ?: 0 }
            val d = density?.invoke(scope) as? Float ?: return
            val spacing = Math.round((spacingDp?.getFloat(thiz) ?: 6f) * d)
            // number + trend next to each other: the trend at the card's gap from the number (the block moves it)
            val n = plan.number
            val t = plan.trend
            val adjacent = n != null && t != null && after.visible.indexOf(n).let { it >= 0 && after.visible.getOrNull(it + 1) == t }
            val measured =
                if (n != null &&
                    t != null
                ) {
                    IconFit.pull(spacing, (placeableHeight?.invoke(plan.permuted[n]) as? Int) ?: 0)
                } else {
                    0
                }
            if (measured > 0) lastPull = measured
            val pull =
                if (adjacent && isLtr(scope) && pullHooked && placeableWidthField != null) {
                    if (plan.extend > 0) plan.extend else measured
                } else {
                    0
                }
            if (after.visible.size == before.visible.size && pull == 0 && plan.extend == 0) return // at most the order changed
            // our pair folded into the dot, or pulled together: the row is narrower than System UI measured it
            val old = fields.filter { it.type == Int::class.javaPrimitiveType && it.name.contains("idth", true) }
            val oldTotal = old.firstOrNull()?.getInt(decision) ?: return
            val total = oldTotal - IconFit.width(widths, before, spacing, 0) + IconFit.place(widths, after, spacing, 0, n, pull).second
            old.forEach { it.setInt(decision, total) }
            // never wider than the real room (System UI was offered plan.extend more)
            val real = maxWidthOf?.invoke(null, constraints) as? Int ?: Int.MAX_VALUE
            val w = maxOf(total, (minWidth?.invoke(null, constraints) as? Int) ?: 0).coerceAtMost(real)
            result.javaClass.declaredFields
                .filter { it.type == Int::class.javaPrimitiveType && it.name.removePrefix("\$") == "width" }
                .forEach {
                    it.isAccessible = true
                    it.setInt(result, w)
                }
            if (pull > 0 && n != null) pulls[block] = Pull(plan.permuted[n]!!, pull)
        }

        /**
         * System UI's placement block moves on by each placed icon's width: while it runs, our number reports
         * [Pull.px] less, so the trend and everything after it sit that much closer. Restored right after.
         */
        fun hookPull(blockClass: Class<*>) {
            val invoke = blockClass.declaredMethods.firstOrNull { it.name == "invoke" && it.parameterCount == 1 } ?: return
            val field = placeableWidthField ?: return
            hook(invoke).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                val p = if (broken) null else pulls[chain.thisObject]
                if (p == null) return@intercept chain.proceed()
                val w = field.getInt(p.number)
                field.setInt(p.number, w - p.px)
                try {
                    chain.proceed()
                } finally {
                    field.setInt(p.number, w)
                }
            }
            pullHooked = true
        }

        /** Which of ours a row child is (its LayoutNode's TalkBack text); null = not ours. */
        private fun kindOf(measurable: Any?): Kind? =
            nodeOf(measurable)
                ?.let { ourDescription(it, 4) }
                ?.let { if (SlotIcons.isTrend(it)) Kind.TREND else Kind.OURS }

        private val nodeOfDelegate = HashMap<Class<*>, (Any) -> Any?>()

        /** The LayoutNode behind a measurable (MeasurePassDelegate → LayoutNodeLayoutDelegate → LayoutNode). */
        private fun nodeOf(o: Any?): Any? {
            if (o == null || nodeClass == null) return null
            return nodeOfDelegate.getOrPut(o.javaClass) {
                val direct =
                    o.javaClass.declaredFields
                        .firstOrNull { it.type == nodeClass }
                        ?.apply { isAccessible = true }
                val viaDelegate =
                    o.javaClass.declaredFields.firstOrNull { it.type.name.endsWith("LayoutNodeLayoutDelegate") }?.apply {
                        isAccessible =
                            true
                    }
                val inner =
                    viaDelegate
                        ?.type
                        ?.declaredFields
                        ?.firstOrNull { it.type == nodeClass }
                        ?.apply { isAccessible = true }
                when {
                    direct != null -> { x -> direct.get(x) }
                    viaDelegate != null && inner != null -> { x -> viaDelegate.get(x)?.let(inner::get) }
                    else -> { _ -> null }
                }
            }(o)
        }

        private val semantics by lazy {
            nodeClass?.methods?.firstOrNull { it.name == "getSemanticsConfiguration" && it.parameterCount == 0 }
        }
        private val children by lazy { nodeClass?.methods?.firstOrNull { it.name.startsWith("getChildren\$") && it.parameterCount == 0 } }

        private fun ourDescription(
            node: Any,
            depth: Int,
        ): String? {
            (semantics?.invoke(node) as? Iterable<*>)?.forEach { e ->
                when (val v = (e as? Map.Entry<*, *>)?.value) {
                    is String -> {
                        if (SlotIcons.isOurs(v) || SlotIcons.isTrend(v)) return v
                    }

                    is List<*> -> {
                        v
                            .firstOrNull {
                                it is String && (
                                    SlotIcons.isOurs(
                                        it,
                                    ) || SlotIcons.isTrend(it)
                                )
                            }?.let { return it as String }
                    }
                }
            }
            if (depth == 0) return null
            val kids = children?.invoke(node) as? List<*> ?: return null
            return kids.firstNotNullOfOrNull { it?.let { k -> ourDescription(k, depth - 1) } }
        }
    }

    /** Our icon in System UI's row: the trend beside the number, or the number / the single no-readings / ended icon. */
    private enum class Kind { OURS, TREND }

    private fun slotName(o: Any?): String? =
        o?.let {
            runCatching { it.javaClass.getMethod("getSlotName").invoke(it) as String }.getOrNull()
                ?: runCatching { it.javaClass.getField("slotName").get(it) as String }.getOrNull()
        }

    // --- the text beside the clock ------------------------------------------------------------------------------

    private enum class Bar { MAIN, KEYGUARD }

    /** One glucose view per status bar instance; System UI may inflate several (e.g. per display). */
    private class Slot(
        val bar: Bar,
        root: ViewGroup,
        val view: GlucoseView,
    ) {
        val root = WeakReference(root)
    }

    private fun attach(
        root: ViewGroup,
        bar: Bar,
    ) = safe("attach $bar") {
        val ctx = root.context
        if (killedBySetting(ctx)) return@safe log("off: ${SystemUi.KILL_SETTING}")
        ensureReceiver(ctx)
        val view = GlucoseView(ctx)
        val slot = Slot(bar, root, view)
        slots.add(slot)
        // the clock's look; on the lock screen (no clock) the carrier text has the same style
        (root.findText("clock") ?: root.findText("keyguard_carrier_text"))?.let { ref ->
            view.reference = WeakReference(ref)
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, ref.textSize)
            view.typeface = ref.typeface
        }
        place(slot)
        view.render()
        log("attached to $bar")
    }

    @SuppressLint("DiscouragedApi")
    private fun View.findText(name: String): TextView? = findViewById(resources.getIdentifier(name, "id", SystemUi.PACKAGE)) as? TextView

    @SuppressLint("DiscouragedApi")
    private fun ViewGroup.findGroup(
        name: String,
    ): ViewGroup? = findViewById(resources.getIdentifier(name, "id", SystemUi.PACKAGE)) as? ViewGroup

    /** (Re)inserts the view where the placement says; on attach and when the placement changes. */
    private fun place(slot: Slot) =
        safe("place") {
            val root = slot.root.get() ?: return@safe
            val view = slot.view
            (view.parent as? ViewGroup)?.removeView(view)
            val lp = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
            val pad = (root.resources.displayMetrics.density * 4).toInt()
            if (slot.bar == Bar.KEYGUARD) {
                // status_icon_area: [system_icons_container (Compose icons), …]: first
                val area = root.findGroup("status_icon_area") ?: return@safe
                view.setPadding(0, 0, pad, 0)
                area.addView(view, 0, lp)
                return@safe
            }
            val clock = root.findText("clock")
            val end = root.findGroup("status_bar_end_side_content")
            val pos = state.position
            when {
                pos == HookPosition.BEFORE_CLOCK && clock != null -> {
                    view.setPadding(0, 0, pad, 0)
                    (clock.parent as ViewGroup).addView(view, (clock.parent as ViewGroup).indexOfChild(clock), lp)
                }

                pos.startsWith(HookPosition.ICONS_PREFIX) && end != null -> {
                    view.setPadding(0, 0, pad, 0)
                    // at onFinishInflate the Compose icons aren't added yet: post so the indexes are final
                    end.post {
                        safe("place end") {
                            (view.parent as? ViewGroup)?.removeView(view)
                            val compose = (0 until end.childCount).map(end::getChildAt).lastOrNull { it.visibility == View.VISIBLE }
                            end.addView(view, if (compose == null) end.childCount else end.indexOfChild(compose), lp)
                        }
                    }
                }

                clock != null -> {
                    view.setPadding(pad, 0, 0, 0)
                    (clock.parent as ViewGroup).addView(view, (clock.parent as ViewGroup).indexOfChild(clock) + 1, lp)
                }
            }
        }

    // --- data from the app ----------------------------------------------------------------------------------------

    /** Written on the main thread (receiver), read by the Compose hooks on System UI's background threads. */
    @Volatile private var state = HookState()
    private val slots = mutableListOf<Slot>()
    private var receiverRegistered = false
    private val main by lazy { Handler(Looper.getMainLooper()) }

    private fun ensureReceiver(ctx: Context) {
        if (receiverRegistered) return
        receiverRegistered = true
        val app = ctx.applicationContext
        app.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(
                    c: Context,
                    i: Intent,
                ) = safe("receive") {
                    // only the app itself (the permission is the app's signature permission; this is the second lock)
                    if (sentFromPackage != ourPkg) return@safe log("state from ${sentFromPackage ?: "an unknown sender"}: ignored")
                    onState(i)
                    if (i.getBooleanExtra(SystemUi.EXTRA_ASK, false)) hello(app)
                }
            },
            IntentFilter(SystemUi.actionState(ourPkg)),
            SystemUi.permission(ourPkg),
            main,
            Context.RECEIVER_EXPORTED,
        )
        hello(app)
        // re-render every 30 s so the value ages (struck, no readings) even when no data arrives
        main.postDelayed(
            object : Runnable {
                override fun run() {
                    safe("tick") { renderAll() }
                    main.postDelayed(this, 30_000)
                }
            },
            30_000,
        )
    }

    /** Asks the app for the current state and tells it the code this System UI runs. */
    private fun hello(app: Context) =
        safe("hello") {
            app.sendBroadcast(
                Intent(SystemUi.actionHello(ourPkg))
                    .setPackage(ourPkg)
                    .putExtra(SystemUi.EXTRA_CODE, HookCode.VERSION)
                    .putExtra(SystemUi.EXTRA_ICON_ORDER, iconOrderHooked),
            )
        }

    private fun onState(i: Intent) {
        val old = state
        state = HookState.from(i)
        slots.removeAll { it.root.get() == null }
        if (state.position != old.position) slots.forEach { place(it) }
        renderAll()
    }

    private fun renderAll() = slots.forEach { it.view.render() }

    private fun nowS() = System.currentTimeMillis() / 1000

    // --- the view ---------------------------------------------------------------------------------------------

    @SuppressLint("ViewConstructor", "AppCompatCustomView")
    private inner class GlucoseView(
        ctx: Context,
    ) : TextView(ctx) {
        var reference: WeakReference<TextView>? = null
        private val arrow = ArrowDrawable()
        private val ours = runCatching { ctx.createPackageContext(ourPkg, 0) }.getOrNull()
        private val flag = ours?.getDrawable(R.drawable.monitor_ic_sensor_ended)?.mutate()
        private val noReadings = ours?.getDrawable(R.drawable.monitor_ic_no_readings)?.mutate()
        private var lastRefColor = 0
        private var followAlpha = 1f
        private var staleAlpha = 1f

        init {
            gravity = Gravity.CENTER_VERTICAL
            isSingleLine = true
            includeFontPadding = false
            compoundDrawablePadding = (ctx.resources.displayMetrics.density * 1).toInt()
            // follow the clock: its light / dark tint and its fading (lock screen, heads-up notifications)
            viewTreeObserver.addOnPreDrawListener {
                safe("predraw") {
                    val ref = reference?.get() ?: return@safe
                    if (ref.currentTextColor != lastRefColor) {
                        lastRefColor = ref.currentTextColor
                        baseColor = lastRefColor
                        render()
                    }
                    val a = if (ref.isShown) ref.alpha else 0f
                    if (a != followAlpha) {
                        followAlpha = a
                        alpha = a * staleAlpha
                    }
                }
                true
            }
        }

        fun render() =
            safe("render") {
                val st = state
                // `slot:` placements are System UI's own icons (the slot daemon's), ordered and coloured above
                if (!st.enabled || st.mgdl <= 0 || HookPosition.isSystemIcons(st.position)) {
                    visibility = GONE
                    return@safe
                }
                visibility = VISIBLE
                val base = reference?.get()?.currentTextColor ?: currentTextColor
                val v = HookText.of(HookText.face(st, nowS()), st.color, HookText.rangeColor(st.mgdl, st.bands, base), base)
                text = v.text
                paintFlags = if (v.strike) paintFlags or Paint.STRIKE_THRU_TEXT_FLAG else paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                setTextColor(v.color)
                staleAlpha = v.alpha
                alpha = followAlpha * staleAlpha
                val drawable: Drawable? =
                    when (v.mark) {
                        HookText.Mark.ARROW, HookText.Mark.RING -> {
                            arrow.also {
                                it.rate = if (v.mark == HookText.Mark.ARROW) v.rate else null
                                it.color = v.color
                            }
                        }

                        HookText.Mark.FLAG -> {
                            flag?.also { it.setTint(v.color) }
                        }

                        HookText.Mark.NO_READINGS -> {
                            noReadings?.also { it.setTint(v.color) }
                        }

                        HookText.Mark.NONE -> {
                            null
                        }
                    }
                val s = (textSize * if (v.mark == HookText.Mark.ARROW || v.mark == HookText.Mark.RING) 0.95f else 1.05f).toInt()
                drawable?.setBounds(0, 0, s, s)
                setCompoundDrawables(null, null, drawable, null)
                contentDescription = v.desc
            }
    }

    /** The card's arrow ([ValueGlyphs.arrow]) or "no trend" ring, as a Drawable. */
    private class ArrowDrawable : Drawable() {
        var rate: Double? = null
        var color = 0
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun draw(cv: Canvas) {
            val b = bounds
            paint.color = color
            val rate = rate
            cv.save()
            cv.translate(b.exactCenterX(), b.exactCenterY())
            if (rate == null) {
                val p =
                    Paint(paint).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = TrendMark.ringStroke(b.width().toFloat())
                    }
                cv.drawCircle(0f, 0f, TrendMark.ringRadius(b.width().toFloat()), p)
            } else {
                cv.rotate(TrendAngle.deg(rate))
                ValueGlyphs.arrow(cv, b.width().toFloat(), paint, TrendAngle.isDouble(rate))
            }
            cv.restore()
        }

        override fun setAlpha(alpha: Int) = Unit

        override fun setColorFilter(cf: ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    // --- safety -------------------------------------------------------------------------------------------------

    /** Runs [block] after [m]; PROTECTIVE: a throw from our side can't break the original call. */
    private fun after(
        m: Executable,
        block: (XposedInterface.Chain) -> Unit,
    ) {
        hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
            val result = chain.proceed()
            safe("after ${m.name}") { block(chain) }
            result
        }
    }

    private inline fun safe(
        what: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (t: Throwable) {
            log("$what failed: $t")
        }
    }

    private val logGate = LogGate()

    private fun log(s: String) {
        if (logGate.allow(SystemClock.elapsedRealtime())) runCatching { log(Log.INFO, "Sukrara", s) }
    }

    private fun ClassLoader.find(name: String): Class<*>? = runCatching { Class.forName(name, false, this) }.getOrNull()
}

/** At most 40 log lines a minute: a hook failing on every frame must not flood LSPosed's log. */
internal class LogGate {
    private var window = 0L
    private var count = 0

    @Synchronized
    fun allow(nowMs: Long): Boolean {
        if (nowMs - window > 60_000) {
            window = nowMs
            count = 0
        }
        return ++count <= 40
    }
}
