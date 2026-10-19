package com.otodone.app.ui.session

import android.view.View
import android.view.Window
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.otodone.app.R
import com.otodone.app.ui.components.PreferenceCard
import com.otodone.app.ui.components.PreferenceDivider
import com.otodone.app.ui.components.PreferenceRow
import com.otodone.app.ui.components.SectionLabel
import com.otodone.app.ui.components.SwitchPreferenceRow
import com.otodone.app.ui.insets.rememberSystemBarInsets

/** 右上角「三」按钮：与左下/右下两个浮标同一套尺寸、底色与按下反馈。 */
@Composable
internal fun SessionMenuButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    SessionFloatButton(
        icon = painterResource(R.drawable.ic_menu_lines),
        contentDescription = stringResource(R.string.session_menu),
        onClick = onClick,
        modifier = modifier,
    )
}

/** 「显示」分组里列出的一个显示器条目。 */
internal data class SessionDisplay(val index: Int, val width: Int, val height: Int)

/**
 * 会话页的「三」菜单。
 *
 * 横竖屏用两套呈现，内容完全一样（同一份 [SessionMenuContent]）：
 * - 竖屏：底部抽屉 [ModalBottomSheet]，与设置页的 [com.otodone.app.ui.components.SingleChoiceSheet]
 *   同一观感（圆角、拖拽把手、点击遮罩/返回键关闭）；
 * - 横屏：从右侧滑出的侧边栏。这里刻意不用 ModalNavigationDrawer——它的边缘滑动手势
 *   会和远端画面的单指拖动/双指缩放抢事件，改成一个自绘的遮罩 + 平移面板，手势零冲突。
 *
 * 菜单本身必须常驻组合（不能 `if (!visible) return`），否则横屏侧边栏没有退场动画。
 */
@Composable
internal fun SessionMenu(
    visible: Boolean,
    isLandscape: Boolean,
    display: SessionDisplay?,
    showMouseButton: Boolean,
    showKeyboardButton: Boolean,
    onShowMouseButtonChange: (Boolean) -> Unit,
    onShowKeyboardButtonChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    if (isLandscape) {
        LandscapeSessionMenu(visible = visible, onDismiss = onDismiss) { contentPadding ->
            SessionMenuContent(
                display = display,
                showMouseButton = showMouseButton,
                showKeyboardButton = showKeyboardButton,
                onShowMouseButtonChange = onShowMouseButtonChange,
                onShowKeyboardButtonChange = onShowKeyboardButtonChange,
                contentPadding = contentPadding,
            )
        }
    } else if (visible) {
        PortraitSessionSheet(
            display = display,
            showMouseButton = showMouseButton,
            showKeyboardButton = showKeyboardButton,
            onShowMouseButtonChange = onShowMouseButtonChange,
            onShowKeyboardButtonChange = onShowKeyboardButtonChange,
            onDismiss = onDismiss,
        )
    }
}

/** 竖屏：底部抽屉（与项目其它抽屉同为 ModalBottomSheet）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PortraitSessionSheet(
    display: SessionDisplay?,
    showMouseButton: Boolean,
    showKeyboardButton: Boolean,
    onShowMouseButtonChange: (Boolean) -> Unit,
    onShowKeyboardButtonChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val windowInsets = rememberSystemBarInsets()
    // 内容不高（两个分组），直接全展开，不要停在半屏。
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // 配色必须和设置页**完全**一致：设置页 Scaffold 的容器色就是 background，
        // 抽屉默认的 surfaceContainerLow 比它深/浅一档，放在一起会显得"不是一个应用"。
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        // 与 SingleChoiceSheet 一致：小窗里系统导航栏上报不可用，统一按 SystemBars 的 max 口径取底部安全区。
        contentWindowInsets = { windowInsets.only(WindowInsetsSides.Bottom) },
    ) {
        SheetImmersiveEffect()
        SessionMenuContent(
            display = display,
            showMouseButton = showMouseButton,
            showKeyboardButton = showKeyboardButton,
            onShowMouseButtonChange = onShowMouseButtonChange,
            onShowKeyboardButtonChange = onShowKeyboardButtonChange,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        )
    }
}

/**
 * 让底部抽屉自己那个对话框窗口也保持沉浸。
 *
 * 会话页在 Activity 窗口上是全屏沉浸的，但 [ModalBottomSheet] 会新建一个 Dialog 窗口，
 * 系统栏可见性是**按窗口**记录的：不处理的话打开抽屉时状态栏会重新冒出来压住远端画面。
 * ModalBottomSheet 的根布局（DialogLayout）实现了 [DialogWindowProvider]，
 * 从 [LocalView] 顺着 parent 找到它就能拿到这个对话框自己的 Window。
 */
@Composable
private fun SheetImmersiveEffect() {
    val view = LocalView.current
    LaunchedEffect(view) {
        val dialogWindow = view.dialogWindow() ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(dialogWindow, view)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }
}

/** 顺着 View 树往上找实现了 [DialogWindowProvider] 的根布局（对话框/抽屉自己的 Window）。 */
private fun View.dialogWindow(): Window? {
    var current: View? = this
    while (current != null) {
        if (current is DialogWindowProvider) return current.window
        current = current.parent as? View
    }
    return null
}

/**
 * 横屏：右侧滑出侧边栏（自绘，不用 ModalNavigationDrawer）。
 *
 * 遮罩只吃掉自己的点击（点一下关闭），面板本身是独立的 Surface，不含任何拖拽/滑动手势，
 * 因此不会和远端画面的单指拖动、双指缩放/滚动抢事件。
 */
@Composable
private fun LandscapeSessionMenu(
    visible: Boolean,
    onDismiss: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    // 菜单打开时返回键先关菜单，而不是走「再按一次退出会话」。
    BackHandler(enabled = visible, onBack = onDismiss)

    val scrimInteraction = remember { MutableInteractionSource() }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(durationMillis = SCRIM_FADE_MS)),
        exit = fadeOut(animationSpec = tween(durationMillis = SCRIM_FADE_MS)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = SCRIM_ALPHA))
                // 无涟漪：遮罩只是"点空白关闭"，不要弹出一圈水波纹。
                .clickable(
                    interactionSource = scrimInteraction,
                    indication = null,
                    onClick = onDismiss,
                ),
        )
    }

    // 侧边栏宽度按屏幕宽度取比例，手机横屏约 340dp，平板也不会铺满半个屏幕。
    val configuration = LocalConfiguration.current
    val panelWidth = (configuration.screenWidthDp * LANDSCAPE_PANEL_WIDTH_RATIO)
        .coerceIn(LANDSCAPE_PANEL_MIN_DP, LANDSCAPE_PANEL_MAX_DP)
        .dp

    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.fillMaxSize(),
        enter = slideInHorizontally(
            animationSpec = tween(durationMillis = PANEL_SLIDE_MS, easing = FastOutSlowInEasing),
            initialOffsetX = { it },
        ),
        exit = slideOutHorizontally(
            animationSpec = tween(durationMillis = PANEL_SLIDE_MS, easing = FastOutSlowInEasing),
            targetOffsetX = { it },
        ),
    ) {
        // AnimatedVisibility 的内容默认按 TopStart 摆放，这里用一个满屏 Box 把它推到右侧。
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            Surface(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(panelWidth)
                    .windowInsetsPadding(WindowInsets.displayCutout)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)),
                // 和设置页 Scaffold 同色（background），不要再叠 tonalElevation ——
                // 那会把这层面板染成介于二者之间的颜色，卡片就"看不出底"了。
                color = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
                tonalElevation = 0.dp,
                shadowElevation = 12.dp,
            ) {
                // 不再套一层 verticalScroll：正文自己有滚动，嵌套同向滚动会在测量时
                // 拿到无限高约束而崩溃。
                content(PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp))
            }
        }
    }
}

/**
 * 菜单正文：设置页同款分组（SectionLabel + PreferenceCard + Row）。
 *
 * 「显示」分组按 RDP 协议的能力如实展示：标准 RDP 的方向是「客户端把 monitor layout
 * 下发给服务端」，协议里没有「枚举服务端显示器」的通用机制（见报告里的 disp 通道调研），
 * 所以这里只列出当前会话使用的分辨率，不编造多显示器。
 */
@Composable
private fun SessionMenuContent(
    display: SessionDisplay?,
    showMouseButton: Boolean,
    showKeyboardButton: Boolean,
    onShowMouseButtonChange: (Boolean) -> Unit,
    onShowKeyboardButtonChange: (Boolean) -> Unit,
    contentPadding: PaddingValues,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = stringResource(R.string.session_menu_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 12.dp),
        )

        SectionLabel(stringResource(R.string.session_section_display))
        PreferenceCard {
            PreferenceRow(
                icon = Icons.Filled.Monitor,
                title = stringResource(R.string.session_display_item, display?.index ?: 1),
                subtitle = if (display != null) {
                    stringResource(
                        R.string.session_display_current,
                        display.width,
                        display.height,
                    )
                } else {
                    stringResource(R.string.session_display_pending)
                },
            )
        }
        Text(
            text = stringResource(R.string.session_display_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp),
        )

        SectionLabel(stringResource(R.string.session_section_layout), topSpace = true)
        PreferenceCard {
            SwitchPreferenceRow(
                icon = Icons.Filled.Mouse,
                title = stringResource(R.string.session_layout_mouse),
                subtitle = stringResource(R.string.session_layout_mouse_desc),
                checked = showMouseButton,
                onCheckedChange = onShowMouseButtonChange,
            )
            PreferenceDivider()
            SwitchPreferenceRow(
                icon = Icons.Filled.Keyboard,
                title = stringResource(R.string.session_layout_keyboard),
                subtitle = stringResource(R.string.session_layout_keyboard_desc),
                checked = showKeyboardButton,
                onCheckedChange = onShowKeyboardButtonChange,
            )
        }
    }
}

/** 横屏侧边栏遮罩透明度。 */
private const val SCRIM_ALPHA = 0.45f

/** 侧边栏宽度：屏幕宽度的比例，并限制在 [LANDSCAPE_PANEL_MIN_DP]..[LANDSCAPE_PANEL_MAX_DP]。 */
private const val LANDSCAPE_PANEL_WIDTH_RATIO = 0.42f
private const val LANDSCAPE_PANEL_MIN_DP = 288f
private const val LANDSCAPE_PANEL_MAX_DP = 380f

/** 侧边栏滑入/滑出时长。 */
private const val PANEL_SLIDE_MS = 220

/** 遮罩淡入/淡出时长。 */
private const val SCRIM_FADE_MS = 160
