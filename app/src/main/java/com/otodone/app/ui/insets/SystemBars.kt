package com.otodone.app.ui.insets

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Point
import android.os.Build
import android.provider.Settings
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 全应用唯一的 edge-to-edge / 系统栏适配入口（otaDone 的 Compose 版）。
 *
 * 移植自 wgpro 的 `com.typheye.wgpro.utils.SystemBars`，保留其「窗口层一次改写 + 页面层
 * 只取安全高度」的分层思路，并按 Compose 的习惯改造：
 *
 * 1. [configureWindow] 对应 wgpro 的 `SystemBars.configureWindow`：一次性改写
 *    [WindowManager.LayoutParams]（挖孔模式、去掉半透明标志、系统栏透明、关闭对比度强制），
 *    再按当前主题设置状态栏/导航栏前景明暗。
 * 2. [rememberSystemBarInsets] 返回一个会随 inset 变化自动重新布局的 [WindowInsets]，
 *    内部按 [isFreeformOrSplit] 做「系统 inset 与固定 dp 配额取 max」的兜底；**自由窗口的
 *    底部是例外**——只留窗口自身的把手配额、忽略屏幕导航栏，见 [resolveFreeformBottom]。
 * 3. [resolveTop] / [resolveBottom] / [resolveSide] 是 wgpro 的 `topInset()` /
 *    `bottomInsetForView()` 的直接对应物：注意是 **max，不是相加**，所以小窗不会多出一截，
 *    也不会因为系统上报 0 而塌掉（自由窗口底部固定配额，理由同第 2 条）。
 *
 * 与 wgpro 的差异：
 * - wgpro 用 View + WindowInsetsCompat 手工 setPadding；这里用 Compose 的 [WindowInsets]，
 *   由 Scaffold / TopAppBar 在测量阶段取值，避免在 composition 里读 inset 造成每帧重组。
 * - wgpro 用 `Activity.isInMultiWindowMode()` 判定小窗；这里改为 wgpro 的
 *   `isFreeformOrSplit()` 逐级兜底（多窗口模式 → 画中画 → 窗口外框 vs 最大外框 →
 *   realSize），因为 MIUI 的小窗经常不置 `isInMultiWindowMode`。另外 Android 14 及以下
 *   自由窗口上报的是屏幕坐标 inset（浮在上面的小窗会收到整条系统栏高度），从 Android 15
 *   起才是窗口相对。见 [trustDisplayInsets]。
 */
object SystemBars {

    /**
     * 多窗口（小窗/分屏）几何配额，取值与 wgpro 完全一致。
     *
     * 小窗可以停在屏幕任意位置，系统只在小窗真的贴到屏幕边缘时才上报对应的系统栏高度，
     * 所以这一层必须「取上限」而不是「相加」：相加会让应用栏在贴顶时被撑高、在居中时被压扁。
     */
    const val FREEFORM_TOP_DP = 32
    // 小窗底部把手配额，与 wgpro 的 `SystemBars.FREEFORM_BOTTOM_DP` 完全一致（24dp）。
    // 自由窗口的底部**只**用这个配额（不再与系统 inset 取 max）：屏幕导航栏不属于小窗的
    // 安全区，见 [resolveFreeformBottom]。分屏/全屏仍走 resolveBottom 的「取 max」口径。
    const val FREEFORM_BOTTOM_DP = 24
    const val FREEFORM_SIDE_DP = 8

    /** Android 15（API 35）：多窗口/自由窗口的 inset 开始按窗口坐标上报。 */
    private val WINDOW_RELATIVE_FREEFORM_INSETS_SDK = Build.VERSION_CODES.VANILLA_ICE_CREAM

    /**
     * 让窗口进入 edge-to-edge（幂等）。对应 wgpro 的 `SystemBars.configureWindow`。
     *
     * 在 `MainActivity.onCreate` 里调用，取代 `enableEdgeToEdge()`——两者会做重复的窗口配置，
     * 所以只保留这一个。
     */
    @Suppress("DEPRECATION")
    fun configureWindow(window: Window, context: Context) {
        // 1. 一次改写 LayoutParams：允许内容进入挖孔/刘海，要求窗口自己绘制系统栏背景，
        //    并清掉旧的半透明标志（背景彻底交给内容，而不是给系统栏刷一层同色底）。
        val attributes = window.attributes
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
                attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        attributes.flags = attributes.flags or
            WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
        attributes.flags = attributes.flags and
            (WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION).inv()
        window.attributes = attributes

        // 2. 内容延伸到系统栏区域（FitsSystemWindows = false）。
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // 3. 系统栏颜色只能通过 Window 的公开接口写回 LayoutParams。
        //    Android 15+ 上这两个方法已经是 no-op（强制 edge-to-edge），低于 15 的版本由它们
        //    真正把状态栏/导航栏设成全透明。
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.navigationBarDividerColor = Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }

        // 4. 前景明暗先按系统夜景模式给一个初值；应用内强制深/浅色时由 OtoDoneTheme 覆盖。
        setLightSystemBars(window, isLightMode(context))
    }

    /** 设置状态栏/导航栏前景明暗。页面有自定义头图时可以覆盖默认值。 */
    fun setLightSystemBars(window: Window, light: Boolean) {
        val controller: WindowInsetsControllerCompat =
            WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = light
        controller.isAppearanceLightNavigationBars = light
    }

    /** 当前系统是否处于浅色模式（只看系统夜景位，应用内强制主题由调用方覆盖）。 */
    fun isLightMode(context: Context): Boolean {
        val nightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightMode != Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * 系统设置的导航模式：**0 = 三键（三大金刚）/ 1 = 二键 / 2 = 手势**；读不到返回 -1。
     *
     * 与 wgpro 的 `SystemBars.navMode()` 同口径（AOSP / MIUI 一致，Secure 设置无需权限）。
     */
    fun navMode(context: Context): Int = runCatching {
        Settings.Secure.getInt(context.contentResolver, "navigation_mode", -1)
    }.getOrDefault(-1)

    /**
     * 当前是否三键导航。拿不准一律按「非三键」处理：手势导航必须保持原样，宁可少垫也不多垫
     * （wgpro `isThreeButtonNav()` 的注释原话）。
     */
    fun isThreeButtonNav(context: Context): Boolean = navMode(context) == 0

    /**
     * 是否处于自由窗 / 分屏（含 MIUI 的「小窗」）。对应 wgpro
     * `SystemBars.isFreeformOrSplit()`（SystemBars.java:705-747）的忠实移植。
     *
     * **不能只看 `Activity.isInMultiWindowMode()`：MIUI 的小窗经常不把它置位**，
     * 于是「三键 + 全屏」的适配会在小窗里也生效，用户看到的就是「小窗 + 三键底部还是
     * 多一条」。这里按 wgpro 的顺序逐级兜底：
     *
     * 1. 系统标记的多窗口模式（分屏 / freeform / 部分 ROM 的小窗）；
     * 2. 画中画（同样是浮在桌面上的小窗）；
     * 3. API 30+：当前窗口外框 vs 最大窗口外框，**任一方向更小**即自由窗/分屏；
     * 4. API 30 以下：物理屏幕 realSize vs 窗口 decorView 的实际尺寸。
     *
     * 第 3 步**不能用百分比阈值**：MIUI 小窗拉伸/展开后会变大，越过分分钟被误判成
     * 全屏（wgpro 注释原话）。每一步都包 try/catch，异常就退回下一级判断。
     */
    fun isFreeformOrSplit(context: Context): Boolean {
        val activity = context.findActivity() ?: return false
        // 1. 系统标记的多窗口模式。
        try {
            if (activity.isInMultiWindowMode) return true
        } catch (_: Throwable) {
            // 读不到就继续往下兜底。
        }
        // 2. 画中画。
        try {
            if (activity.isInPictureInPictureMode) return true
        } catch (_: Throwable) {
            // 读不到就继续往下兜底。
        }
        // 3. API 30+：当前窗口外框 vs 最大窗口外框，任一方向更小即自由窗/分屏。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val windowManager = activity.windowManager
                val current = windowManager.currentWindowMetrics.bounds
                val maximum = windowManager.maximumWindowMetrics.bounds
                return isWindowSmallerThanMaximum(
                    current.width(), current.height(),
                    maximum.width(), maximum.height(),
                )
            } catch (_: Throwable) {
                // 落到下面的 API 30 以下兜底。
            }
        }
        // 4. API 30 以下兜底：窗口明显小于物理屏幕。
        return legacyWindowSmallerThanScreen(activity)
    }

    /**
     * API 30 以下的自由窗兜底：物理屏幕 realSize vs decorView 实际尺寸。
     * 单独抽出来是为了把已废弃的 `Display.getRealSize` 告警收敛到一处。
     */
    @Suppress("DEPRECATION")
    private fun legacyWindowSmallerThanScreen(activity: Activity): Boolean = try {
        val real = Point()
        activity.windowManager.defaultDisplay.getRealSize(real)
        val decor = activity.window.decorView
        val width = decor.width
        val height = decor.height
        if (width <= 0 || height <= 0 || real.x <= 0 || real.y <= 0) {
            false
        } else {
            width < real.x || height < real.y
        }
    } catch (_: Throwable) {
        false
    }

    /**
     * 自由窗口里系统上报的顶部/侧边 inset 是否可信（**底部永远走 [resolveFreeformBottom]**）。
     *
     * - Android 15（API 35）起：自由窗口 inset 是窗口相对的，顶部/侧边可信。
     * - Android 14 及以下：系统会把整条系统栏（屏幕坐标）报给浮在上面的小窗，可信度为零，
     *   此时只使用固定配额，避免「凭空多补一截」。
     * - 分屏与自由窗共用 [isFreeformOrSplit] 的统一口径（wgpro 也把两者放在同一个判定里）。
     *
     * 底部是例外：不少 ROM（含三键导航的 tappableElement 兜底）在小窗里仍按屏幕坐标上报
     * 导航栏高度，导致小窗底部凭空多垫一截；小窗的底边本来就不与屏幕导航栏重叠，
     * 所以底部固定用窗口自身配额，见 [resolveFreeformBottom]。
     */
    fun trustDisplayInsets(inFreeformWindow: Boolean): Boolean =
        !inFreeformWindow || Build.VERSION.SDK_INT >= WINDOW_RELATIVE_FREEFORM_INSETS_SDK

    /** 顶部安全高度：状态栏/挖孔高度，小窗里取「系统 inset vs 小窗标题条配额」的较大值。 */
    fun resolveTop(
        systemPx: Int,
        density: Density,
        inMultiWindow: Boolean,
        trustSystem: Boolean,
    ): Int = resolve(systemPx, density, FREEFORM_TOP_DP, inMultiWindow, trustSystem)

    /**
     * 全屏的底部安全高度：导航栏高度；小窗/分屏里取「系统 inset vs 窗口底部把手配额」的
     * 较大值。自由窗口（[isFreeformOrSplit] 为 true）不走这里，见 [resolveFreeformBottom]。
     */
    fun resolveBottom(
        systemPx: Int,
        density: Density,
        inMultiWindow: Boolean,
        trustSystem: Boolean,
    ): Int = resolve(systemPx, density, FREEFORM_BOTTOM_DP, inMultiWindow, trustSystem)

    /**
     * 自由窗口的底部安全区：**只保留窗口自身的把手配额**，忽略屏幕导航栏。
     *
     * 小窗浮在桌面上时，屏幕底部的三键导航栏（48dp）不属于这个窗口的安全区。部分 ROM、
     * 以及三键导航的 tappableElement 兜底，仍会把屏幕导航栏高度上报成窗口底部 inset；
     * 直接取 max 会让小窗底部凭空多垫一截（三键 48dp vs 手势 24dp），表现为「窗口底部
     * 多了一条不沉浸的条」。这里统一只留 [FREEFORM_BOTTOM_DP] 的窗口自身配额，
     * 内容铺到窗口底边；全屏不受影响。
     */
    fun resolveFreeformBottom(density: Density): Int =
        with(density) { FREEFORM_BOTTOM_DP.dp.roundToPx() }

    /** 侧边安全高度：小窗额外补 [FREEFORM_SIDE_DP] 的圆角/把手配额。 */
    fun resolveSide(
        systemPx: Int,
        density: Density,
        inMultiWindow: Boolean,
        trustSystem: Boolean,
    ): Int = resolve(systemPx, density, FREEFORM_SIDE_DP, inMultiWindow, trustSystem)

    private fun resolve(
        systemPx: Int,
        density: Density,
        quotaDp: Int,
        inMultiWindow: Boolean,
        trustSystem: Boolean,
    ): Int {
        if (!inMultiWindow) return systemPx
        val quota = with(density) { quotaDp.dp.roundToPx() }
        return if (trustSystem) maxOf(systemPx, quota) else quota
    }
}

/**
 * 当前窗口外框是否小于最大窗口外框（**任一方向更小**即视为自由窗/分屏）。
 *
 * 这是 wgpro `isFreeformOrSplit()` 里 WindowMetrics 那一步的原文口径：不能用百分比阈值，
 * 因为 MIUI 小窗拉伸/展开后会变大，越过分分钟被误判成全屏。抽成纯函数便于 JVM 单测。
 */
internal fun isWindowSmallerThanMaximum(
    currentWidth: Int,
    currentHeight: Int,
    maximumWidth: Int,
    maximumHeight: Int,
): Boolean = currentWidth < maximumWidth || currentHeight < maximumHeight

/** 从任意 Context 反查所属 Activity（对应 wgpro 的 `SystemBars.activityOf()`）。 */
fun Context.findActivity(): Activity? {
    var context: Context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        val base = context.baseContext
        if (base === context) break
        context = base
    }
    return context as? Activity
}

/**
 * 页面层统一入口：拿到一份「已经处理过小窗配额」的安全性 [WindowInsets]。
 *
 * 用法与 `WindowInsets.safeDrawing` 完全一样：
 * ```
 * val insets = rememberSystemBarInsets()
 * Scaffold(topBar = { TopAppBar(windowInsets = insets.only(Horizontal + Top)) },
 *          contentWindowInsets = insets) { padding -> ... }
 * ```
 *
 * 取值动作发生在布局/测量阶段（Scaffold、TopAppBar 调用 getTop/getBottom 时），
 * 因此系统栏或小窗状态变化会触发重新布局，而不会让整个 composition 每帧重组。
 */
/**
 * 三键导航判定（Compose 侧入口，随配置变化重新读取）。
 *
 * 页面据此决定底部安全区是「裁进视口」还是「只做滚动余量」：
 * - 三键导航（三大金刚）：把列表视口整体抬到导航栏之上，内容不会画到三颗键背后；
 * - 手势导航：保持 edge-to-edge，内容可以滚到小横条下面，只保证最后一项能完整滚出。
 *
 * 这正是 wgpro `shouldAdaptNavBar()` 的分支口径：**三键 && 非小窗/分屏**。
 * 小窗里必须返回 false——屏幕导航栏不属于小窗的安全区，否则会把「三键全屏」的适配
 * 带进小窗，用户看到的就是小窗底部多一条。
 */
@Composable
fun rememberThreeButtonNav(): Boolean {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration) {
        SystemBars.isThreeButtonNav(context) && !SystemBars.isFreeformOrSplit(context)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun rememberSystemBarInsets(): WindowInsets {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    // 自由窗判定只保留一个入口。配额（顶部/侧边/底部）与 freeform 分支都用它：
    // MIUI 小窗可能不置 isInMultiWindowMode，只看那个标志会让配额和底部修复一起失效。
    val inFreeform = remember(context, configuration) { SystemBars.isFreeformOrSplit(context) }
    val inMultiWindow = inFreeform
    val trustSystem = SystemBars.trustDisplayInsets(inFreeform)
    // systemBars 本身是「可见性感知」的：沉浸页把导航栏藏起来后 getInsets 会变成 0。
    val base = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    // 三键导航兜底（tappableElement）单独包一层，理由见 [TappableNavFallbackWindowInsets]。
    val bottomAware = TappableNavFallbackWindowInsets(
        base = base,
        navIgnoringVisibility = WindowInsets.navigationBarsIgnoringVisibility,
        tappable = WindowInsets.tappableElement,
        enabled = SystemBars.isThreeButtonNav(context),
    )
    return QuotaWindowInsets(bottomAware, inMultiWindow, trustSystem, inFreeform)
}

/**
 * 三键导航（三大金刚）底部兜底：只补「导航栏存在但系统没有通过 systemBars 上报」的 ROM。
 *
 * wgpro 的 `SystemBars.navigationBarBottom()` 取 `max(navigationBars, tappableElement)`，
 * 因为 Android 15 起部分 ROM 的三键导航只填 `tappableElement`。但 **tappableElement 不会随
 * 沉浸模式隐藏**：会话页把导航栏藏起来后它仍然上报 48dp（实测 Pixel_10_Pro / API 37）。
 * 如果直接 union 进 base，沉浸页的浮标会被凭空抬高 48dp（正是上一轮回滚的原因）。
 *
 * 与 wgpro 对齐后的口径（三键导航下）：
 * 1. 导航栏可见（navigationBarsIgnoringVisibility 为 0，或 systemBars 已经上报了高度）时，
 *    取 max(systemBars, tappableElement) —— 覆盖「三键只通过 tappableElement 上报」的 ROM；
 * 2. 导航栏被隐藏（可见 systemBars 底部为 0、但 navigationBarsIgnoringVisibility > 0）时
 *    返回 0，不让 tappableElement 的残留高度把沉浸页浮标抬高；
 * 3. 非三键导航（手势）完全不启用，保持系统原样。
 */
private class TappableNavFallbackWindowInsets(
    private val base: WindowInsets,
    private val navIgnoringVisibility: WindowInsets,
    private val tappable: WindowInsets,
    private val enabled: Boolean,
) : WindowInsets {

    override fun getLeft(density: Density, layoutDirection: LayoutDirection): Int =
        base.getLeft(density, layoutDirection)

    override fun getTop(density: Density): Int = base.getTop(density)

    override fun getRight(density: Density, layoutDirection: LayoutDirection): Int =
        base.getRight(density, layoutDirection)

    override fun getBottom(density: Density): Int {
        val visible = base.getBottom(density)
        if (!enabled) return visible
        // 导航栏真实存在但被隐藏（沉浸）：tappableElement 不会跟着隐藏，仍然上报旧高度，
        // 不能拿它补，否则沉浸页的浮标会被凭空抬高 48dp（上一轮回滚的原因）。
        if (visible == 0 && navIgnoringVisibility.getBottom(density) > 0) return 0
        // 与 wgpro 的 navigationBarBottom() 同口径：max(navigationBars, tappableElement)。
        // 既覆盖「三键只通过 tappableElement 上报」的 ROM，也不会漏掉两值不一致的情况。
        return maxOf(visible, tappable.getBottom(density))
    }
}

/**
 * 把系统 inset 与固定 dp 配额合并成一个 [WindowInsets]。
 *
 * 关键点是「max 而不是相加」：小窗居中时系统上报 0，靠配额兜底；贴边时系统上报真实高度，
 * 配额不会额外叠加。旧的 [WindowInsets] 需要在 composition 里读值再构造，这里委托给 base，
 * 让取值发生在布局阶段，顺便保证 inset 变化时自动 relayout。
 *
 * 三键导航（三大金刚）的 tappableElement 兜底由 [TappableNavFallbackWindowInsets] 负责。
 *
 * 自由窗口是唯一例外：**底部完全忽略系统上报的导航栏 inset**，只用窗口自身配额
 * （[SystemBars.resolveFreeformBottom]），避免小窗 + 三键导航时窗口底部多垫一截。
 */
private class QuotaWindowInsets(
    private val base: WindowInsets,
    private val inMultiWindow: Boolean,
    private val trustSystem: Boolean,
    private val inFreeform: Boolean,
) : WindowInsets {

    override fun getLeft(density: Density, layoutDirection: LayoutDirection): Int =
        SystemBars.resolveSide(
            base.getLeft(density, layoutDirection), density, inMultiWindow, trustSystem,
        )

    override fun getTop(density: Density): Int =
        SystemBars.resolveTop(base.getTop(density), density, inMultiWindow, trustSystem)

    override fun getRight(density: Density, layoutDirection: LayoutDirection): Int =
        SystemBars.resolveSide(
            base.getRight(density, layoutDirection), density, inMultiWindow, trustSystem,
        )

    override fun getBottom(density: Density): Int =
        if (inFreeform) {
            // 小窗：屏幕导航栏不是这个窗口的安全区（部分 ROM 会按屏幕坐标上报），
            // 固定用窗口自身配额，内容铺到窗口底边。
            SystemBars.resolveFreeformBottom(density)
        } else {
            SystemBars.resolveBottom(base.getBottom(density), density, inMultiWindow, trustSystem)
        }
}
