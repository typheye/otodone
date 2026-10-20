package com.otodone.app.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.otodone.app.ui.insets.rememberSystemBarInsets

/**
 * 窗口内自绘的底部弹层（底部抽屉）。
 *
 * 为什么不用 Material3 的 [androidx.compose.material3.ModalBottomSheet]：
 * 它底层是一个**独立的 Dialog 窗口**，在自由窗口（freeform / 小窗）下不会跟随宿主 App
 * 的 bounds，而是按屏幕坐标铺开——表现就是弹层"飘到桌面中间"。这里改成在当前
 * Composable 树的顶层自绘一层（同一窗口），位置/宽度天然跟随 App 窗口，小窗里也不会越界。
 *
 * 分层（从下往上）：
 * 1. 遮罩：**铺满整个窗口**（含状态栏/导航栏/手势条区域），点击关闭；
 * 2. 面板：贴窗口底边，背景色一直铺到窗口最下沿（不再出现"导航栏那一条"的异色带），
 *    圆角只在顶部；
 * 3. 内容：底部按 [rememberSystemBarInsets]（导航栏/手势条，小窗配额取 max）留白，
 *    让按钮/文字避开手势条——**背景铺满**与**内容留白**是分开的两层。
 *
 * 其它行为：
 * - 面板从底部滑入；退场也滑出，动画结束后才回调 [onDismissRequest]（父级此时再把它移出组合）；
 * - 键盘（IME）弹出时整个面板抬到键盘之上；面板底部再按导航栏留白，两者不会叠加；
 * - 返回键：弹层在组合里时优先关闭弹层（[BackHandler]），不会触发页面返回；
 * - 宽度跟随窗口，宽屏下最多 640dp 居中（与 M3 抽屉的 sheetMaxWidth 一致）；
 * - 内容高度超过可用高度时，由**调用方**给内容加滚动（这里不套滚动，避免与
 *   SessionMenuContent / 表单自身的 verticalScroll 嵌套同向滚动而触发无限约束崩溃）。
 */
@Composable
fun InWindowBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.background,
    contentColor: Color = MaterialTheme.colorScheme.onBackground,
    shape: Shape = RoundedCornerShape(topStart = SHEET_TOP_CORNER, topEnd = SHEET_TOP_CORNER),
    content: @Composable ColumnScope.() -> Unit,
) {
    // 0 = 完全收起（面板在窗口下方、遮罩透明），1 = 完全展开。
    val progress = remember { Animatable(0f) }
    // 只允许关闭一次：退场动画期间再点遮罩/返回键不再重复触发。
    var dismissing by remember { mutableStateOf(false) }
    // 父级传进来的 lambda 每次重组都是新实例，退场协程里要拿到"最新"的那个。
    val latestOnDismiss by rememberUpdatedState(onDismissRequest)

    val requestDismiss: () -> Unit = { if (!dismissing) dismissing = true }

    // 弹层打开时返回键先关弹层，不要触发页面返回 / 两次退出会话。
    BackHandler(enabled = !dismissing, onBack = requestDismiss)

    // 入场：从底部滑入。
    LaunchedEffect(Unit) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = PANEL_SLIDE_MS, easing = FastOutSlowInEasing),
        )
    }
    // 退场：滑出结束后再通知父级移出组合（父级通常用 if (visible) Sheet(...) 承载，
    // 立即回调的话退场动画根本来不及播）。
    LaunchedEffect(dismissing) {
        if (!dismissing) return@LaunchedEffect
        progress.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = PANEL_SLIDE_MS, easing = FastOutSlowInEasing),
        )
        latestOnDismiss()
    }

    // 全窗口一层：遮罩铺满（含系统栏区域），面板单独一层贴底。
    Box(modifier = modifier.fillMaxSize()) {
        // 遮罩：整窗铺满、点击关闭。用 graphicsLayer 的 alpha 做淡入淡出，避免逐帧重组。
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { alpha = progress.value * SCRIM_ALPHA }
                .background(MaterialTheme.colorScheme.scrim)
                // 无涟漪：遮罩只是"点空白关闭"，不要弹出一圈水波纹。
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = requestDismiss,
                ),
        )

        // 面板层：只让键盘把面板顶起来（导航栏不参与定位，保证面板背景能铺到窗口最下沿）。
        // clipToBounds 保证收起/键盘弹出时面板不会画到可用区域之外。
        Box(
            Modifier
                .fillMaxSize()
                .imePadding()
                .clipToBounds(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = SHEET_MAX_WIDTH)
                    .fillMaxWidth()
                    .graphicsLayer {
                        // 从"面板高度"滑到 0：完全收起时面板整个在窗口之外。
                        translationY = (1f - progress.value) * size.height
                    }
                    // 面板吃掉落在自己身上的点击，避免穿透到遮罩把弹层关掉。
                    .pointerInput(Unit) { detectTapGestures { } },
                shape = shape,
                color = containerColor,
                contentColor = contentColor,
                tonalElevation = 0.dp,
            ) {
                // 底部安全区只留给内容：这层 padding 在 Surface 内部，
                // Surface 的背景仍然铺到窗口最下沿（包括手势条所在的一条）。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(
                            rememberSystemBarInsets().only(WindowInsetsSides.Bottom),
                        ),
                ) {
                    content()
                }
            }
        }
    }
}

/** 面板最大宽度：与 Material3 抽屉的 sheetMaxWidth 一致，宽屏/横屏时居中而不是铺满整窗。 */
private val SHEET_MAX_WIDTH = 640.dp

/** 顶部圆角：M3 抽屉标准值。 */
private val SHEET_TOP_CORNER = 28.dp

/** 遮罩不透明度：与 M3 抽屉的 Scrim 一致。 */
private const val SCRIM_ALPHA = 0.32f

/** 滑入/滑出时长。 */
private const val PANEL_SLIDE_MS = 260
