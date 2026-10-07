package com.otodone.app.ui.insets

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
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
 *    内部按 [isInMultiWindow] 做「系统 inset 与固定 dp 配额取 max」的兜底。
 * 3. [resolveTop] / [resolveBottom] / [resolveSide] 是 wgpro 的 `topInset()` /
 *    `bottomInsetForView()` 的直接对应物：注意是 **max，不是相加**，所以小窗不会多出一截，
 *    也不会因为系统上报 0 而塌掉。
 *
 * 与 wgpro 的差异：
 * - wgpro 用 View + WindowInsetsCompat 手工 setPadding；这里用 Compose 的 [WindowInsets]，
 *   由 Scaffold / TopAppBar 在测量阶段取值，避免在 composition 里读 inset 造成每帧重组。
 * - wgpro 用 `Activity.isInMultiWindowMode()` 判定小窗；这里额外用
 *   `WindowMetrics`（窗口实际外框 < 屏幕最大外框）把「自由窗口」和「分屏」区分开，原因是
 *   Android 14 及以下自由窗口上报的是屏幕坐标 inset（浮在上面的小窗会收到整条系统栏高度），
 *   从 Android 15 起才是窗口相对。见 [trustDisplayInsets]。
 */
object SystemBars {

    /**
     * 多窗口（小窗/分屏）几何配额，取值与 wgpro 完全一致。
     *
     * 小窗可以停在屏幕任意位置，系统只在小窗真的贴到屏幕边缘时才上报对应的系统栏高度，
     * 所以这一层必须「取上限」而不是「相加」：相加会让应用栏在贴顶时被撑高、在居中时被压扁。
     */
    const val FREEFORM_TOP_DP = 32
    // 小窗底部不再预留配额（用户选定方案 C）：只吃系统上报的真实 inset，
    // 不叠加、也不兜底加高，避免小窗里 FAB/内容下方凭空多出一截。
    const val FREEFORM_BOTTOM_DP = 0
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

    /** 当前页面是否处于多窗口/自由窗（对应 wgpro 的 `isInMultiWindow()`）。 */
    fun isInMultiWindow(context: Context): Boolean =
        context.findActivity()?.isInMultiWindowMode == true

    /**
     * 是否处于真正的自由窗口（区别于分屏）。
     *
     * 优先用 `isInMultiWindowMode()` 筛掉全屏；自由窗口浮在屏幕中间，四边都留边，
     * 分屏则至少有一条边贴住屏幕。API 30 以下拿不到 WindowMetrics，返回 false，
     * 即退回 wgpro 的「取 max」口径（保守但不会更差）。
     */
    fun isFreeform(context: Context): Boolean {
        val activity = context.findActivity() ?: return false
        if (!activity.isInMultiWindowMode) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val windowManager = activity.getSystemService(WindowManager::class.java) ?: return false
        val current = windowManager.currentWindowMetrics.bounds
        val maximum = windowManager.maximumWindowMetrics.bounds
        return current.width() < maximum.width() && current.height() < maximum.height()
    }

    /**
     * 自由窗口里系统上报的 inset 是否可信。
     *
     * - Android 15（API 35）起：自由窗口 inset 是窗口相对的，可信。
     * - Android 14 及以下：系统会把整条系统栏（屏幕坐标）报给浮在上面的小窗，可信度为零，
     *   此时只使用固定配额，避免「凭空多补一截」。
     * - 分屏窗口是贴边停靠的，即使旧版本上报也基本正确，所以不进入这条分支。
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

    /** 底部安全高度：导航栏高度，小窗里取「系统 inset vs 小窗底部把手配额」的较大值。 */
    fun resolveBottom(
        systemPx: Int,
        density: Density,
        inMultiWindow: Boolean,
        trustSystem: Boolean,
    ): Int = resolve(systemPx, density, FREEFORM_BOTTOM_DP, inMultiWindow, trustSystem)

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
@Composable
fun rememberSystemBarInsets(): WindowInsets {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val inMultiWindow = remember(context, configuration) { SystemBars.isInMultiWindow(context) }
    val inFreeform = remember(context, configuration) { SystemBars.isFreeform(context) }
    val trustSystem = SystemBars.trustDisplayInsets(inFreeform)
    val base = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    return QuotaWindowInsets(base, inMultiWindow, trustSystem)
}

/**
 * 把系统 inset 与固定 dp 配额合并成一个 [WindowInsets]。
 *
 * 关键点是「max 而不是相加」：小窗居中时系统上报 0，靠配额兜底；贴边时系统上报真实高度，
 * 配额不会额外叠加。旧的 [WindowInsets] 需要在 composition 里读值再构造，这里委托给 base，
 * 让取值发生在布局阶段，顺便保证 inset 变化时自动 relayout。
 */
private class QuotaWindowInsets(
    private val base: WindowInsets,
    private val inMultiWindow: Boolean,
    private val trustSystem: Boolean,
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
        SystemBars.resolveBottom(base.getBottom(density), density, inMultiWindow, trustSystem)
}
