package com.otodone.app.ui.session

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.otodone.app.R
import com.otodone.app.rdp.RdpKeys

/**
 * 软键盘上方的快捷键浮层（对应 aFreeRDP 的键盘扩展条）。
 *
 * 定位完全交给 IME inset（会话页是 `adjustNothing`，窗口不会被键盘顶起，但 inset 照报）：
 *
 * - **收起态**：一排常用键，贴在键盘正上方（bottom padding = 当前 IME 高度）。
 *   键盘收起（inset = 0）就不渲染；横向可滚动，左右两侧有渐隐。
 * - **展开态**：主动收起系统 IME（面板自己就能打字），面板**不透明、底部锚定、限高（≤50%）**，
 *   内部是「统一网格 + 分组」的键盘布局（见下方 [MODIFIER_ROW] 等表）；横屏限宽居中。
 *
 * 本浮层只是 SessionScreen 里的一个**覆盖层**（远端画面容器的兄弟节点）：
 * 它出现/消失都不会改变远端画面的约束，也不会影响 connect() 使用的尺寸。
 *
 * 只在 [KeyboardShortcutBarOverlay] 内部读取 inset，键盘的逐帧动画不会带着远端画面重组。
 */
@Composable
internal fun KeyboardShortcutBarOverlay(
    sendVirtualKey: (Int, Boolean) -> Unit,
    /** 展开全键盘时收起系统输入法。 */
    onRequestHideIme: () -> Unit,
    /** 收起回小键盘态时重新弹出系统输入法（与右下角键盘浮标同一条路）。 */
    onRequestShowIme: () -> Unit,
    /**
     * 浮层"占着屏幕"时回调 true（系统 IME 可见 或 虚拟全键盘展开）。
     * 会话页据此临时隐藏浮标与「三」按钮，避免和键盘/面板叠在一起。
     */
    onActiveChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    // 这个值在键盘动画期间会随动画帧变化，读它就等于订阅 ime inset。
    val imeBottomPx = WindowInsets.ime.getBottom(density)

    // 展开态放在这里而不是浮层内部：收起 IME 之后浮层仍要渲染，状态必须活得比它久。
    var expanded by remember { mutableStateOf(false) }
    // 这次展开是我们主动收起了 IME：此时立刻按"底部锚定"渲染，不跟 IME 收起动画往下滑。
    var imeHiddenByUs by remember { mutableStateOf(false) }
    var prevImePx by remember { mutableIntStateOf(0) }

    SideEffect {
        // IME 在变大 = 用户又用右下角浮标把它弹出来了（或首次弹出）：交回实时值，不打架。
        if (imeBottomPx > prevImePx) imeHiddenByUs = false
        prevImePx = imeBottomPx
    }

    // 展开/收起只有这一处实现：chevron 和返回键共用，IME 的收起/恢复行为不会走岔。
    fun applyExpanded(next: Boolean) {
        if (next) {
            imeHiddenByUs = true
            onRequestHideIme()
        } else {
            // 收起回小键盘态：把系统 IME 重新弹出来（展开时是我们把它收起的）。
            imeHiddenByUs = false
            onRequestShowIme()
        }
        expanded = next
    }

    // 返回键优先级：**虚拟全键盘展开时，返回键先收起它**（连同展开时被收起的 IME 一起还原），
    // 消费本次返回，不再传给会话页的"再按一次退出"。
    // 这个 BackHandler 比会话页那个注册得晚（组合顺序在后），所以它优先；菜单又比它更晚注册，
    // 因此"最上层的 UI 先关"的顺序是：侧栏/抽屉菜单 > 虚拟全键盘 > 系统 IME > 退出会话。
    BackHandler(enabled = expanded) { applyExpanded(false) }

    val keyboardVisible = imeBottomPx > 0 && !imeHiddenByUs
    // 「键盘类 UI 正在占用屏幕」= 系统 IME 可见 或 全键盘展开。
    // 会话页拿这个状态去隐藏三个圆形浮标按钮：它们和键盘/面板叠在一起既难看又容易误触。
    val active = keyboardVisible || expanded
    val latestActiveCallback by rememberUpdatedState(onActiveChange)
    LaunchedEffect(active) { latestActiveCallback(active) }
    DisposableEffect(Unit) {
        onDispose { latestActiveCallback(false) }
    }

    val anchorPx: Int
    when {
        // 键盘可见：贴在键盘正上方（收起态、以及展开态下用户又弹出了 IME）。
        keyboardVisible -> anchorPx = imeBottomPx
        // 展开态且键盘已被我们收起（或本来就没有键盘）：底部锚定，占住原来键盘那块区域。
        expanded -> anchorPx = 0
        // 收起态且没有键盘：不渲染。
        else -> return
    }

    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.roundToPx() }
    val availablePx = if (keyboardVisible) screenHeightPx - imeBottomPx else screenHeightPx
    // 高度上限：可用高度的 50%（题目要求的 45%~55%），并且不超过键盘上方的空间。
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    // 横屏可用高度矮、展开后行数多，给更高的比例，避免一展开就要滚动。
    val heightRatio = if (isLandscape) LANDSCAPE_PANEL_MAX_HEIGHT_RATIO else PANEL_MAX_HEIGHT_RATIO
    val maxPanelPx = minOf((screenHeightPx * heightRatio).toInt(), availablePx)

    KeyboardShortcutBar(
        sendVirtualKey = sendVirtualKey,
        expanded = expanded,
        onExpandedChange = { next -> applyExpanded(next) },
        modifier = modifier.padding(bottom = with(density) { anchorPx.toDp() }),
        maxPanelHeight = with(density) { maxPanelPx.toDp() },
    )
}

/**
 * 快捷键浮层本体。
 *
 * 收起态是一排横向可滚动、右端钉着展开按钮的工具条；展开态是一块不透明面板：
 * 顶部一行小标题 + 收起按钮，下面是**统一网格**的按键区，按组排布
 * （修饰键 → 导航/编辑 → 数字 → 符号 → QWERTY 字母 → F1~F12），组间距大于组内间距。
 *
 * 修饰键是**可锁定**的按住态：点一下真的发 keydown 并高亮，再点一下才发 keyup。
 * 这样「点 Ctrl → 点 C」才是真正的 Ctrl+C，也支持同时锁 Ctrl+Shift。
 * 浮层被移除（收起态键盘收起 / 退出会话）时会补发所有锁定键的 keyup，避免远端卡住修饰键。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyboardShortcutBar(
    sendVirtualKey: (Int, Boolean) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    maxPanelHeight: Dp = 240.dp,
) {
    // 当前被锁定的修饰键（顺序无关，用 Set 去重）。
    var lockedModifiers by remember { mutableStateOf(emptySet<Int>()) }
    // 浮层被移除或回调变化时都要用「最新」的发送函数，不能用首次组合时捕获的那个。
    val latestSend by rememberUpdatedState(sendVirtualKey)

    // 浮层消失（收起态键盘收起、退出会话、重组丢弃）时把所有还按着的修饰键抬起来。
    DisposableEffect(Unit) {
        onDispose {
            lockedModifiers.forEach { latestSend(it, false) }
        }
    }

    fun toggleModifier(virtualKey: Int) {
        if (lockedModifiers.contains(virtualKey)) {
            latestSend(virtualKey, false)
            lockedModifiers = lockedModifiers - virtualKey
        } else {
            latestSend(virtualKey, true)
            lockedModifiers = lockedModifiers + virtualKey
        }
    }

    fun dispatch(key: ShortcutKey) {
        when (key) {
            is ShortcutKey.ModifierKey -> toggleModifier(key.virtualKey)
            is ShortcutKey.PlainKey -> {
                latestSend(key.virtualKey, true)
                latestSend(key.virtualKey, false)
            }
        }
    }

    val barDescription = stringResource(R.string.session_shortcut_bar)
    val landscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // 不透明面板：盖在远端画面上也要看得清，且不会让画面从按键缝里透出来。
    // 配色与设置页同一套：面板 = 页面背景色(background)，按键 = 卡片/容器色。
    val panelColor = MaterialTheme.colorScheme.background
    val shape = if (expanded) RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp) else RectangleShape

    val layoutDirection = LocalLayoutDirection.current
    val density = LocalDensity.current
    // 挖孔在横屏只可能落在一侧。若直接用 displayCutout 的单侧内边距，键盘会被整体推向另一边，
    // 看起来就是"左边留白比右边大"（真机反馈）。这里取两侧的**较大值**对称加，
    // 既躲开挖孔，又保证左右留白相等。收起态是满宽条，需要它；展开态由下面的状态栏留白覆盖。
    val cutoutInsets = WindowInsets.displayCutout
    val cutoutSideInset = with(density) {
        maxOf(
            cutoutInsets.getLeft(density, layoutDirection),
            cutoutInsets.getRight(density, layoutDirection),
        ).toDp()
    }

    // 横屏展开态：左右各留 **2 个状态栏高**。
    // 用 statusBarsIgnoringVisibility：会话页是全屏沉浸、状态栏被隐藏，
    // 普通 statusBars 会报 0，IgnoringVisibility 才能拿到真实高度（刘海机型自然更高）。
    val statusBarPx = WindowInsets.statusBarsIgnoringVisibility.getTop(density)
    val landscapeMargin = with(density) { (statusBarPx * 2).toDp() }

    Box(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                // 外层留白（margin）加在背景之外：面板本体左右各缩进 2 个状态栏高，
                // 由父 Box 的 BottomCenter 居中，左右必定相等。
                .align(Alignment.BottomCenter)
                .then(
                    if (expanded && landscape) Modifier.padding(horizontal = landscapeMargin)
                    else Modifier,
                )
                .fillMaxWidth()
                .clip(shape)
                .background(panelColor)
                // 收起态是满宽条：再躲开挖孔（对称，左右留白仍然相等）。
                .padding(horizontal = if (expanded) 0.dp else cutoutSideInset)
                .semantics { contentDescription = barDescription },
        ) {
            if (expanded) {
                ExpandedPanel(
                    maxPanelHeight = maxPanelHeight,
                    lockedModifiers = lockedModifiers,
                    onKey = ::dispatch,
                    onCollapse = { onExpandedChange(false) },
                )
            } else {
                CollapsedRow(
                    lockedModifiers = lockedModifiers,
                    onKey = ::dispatch,
                    onExpand = { onExpandedChange(true) },
                )
            }
        }
    }
}

/** 收起态：紧凑单行 + 右端钉住的展开按钮（横向滚动，两侧渐隐）。 */
@Composable
private fun CollapsedRow(
    lockedModifiers: Set<Int>,
    onKey: (ShortcutKey) -> Unit,
    onExpand: () -> Unit,
) {
    val scrollState = rememberScrollState()
    // 左右渐隐遮罩：内容滚出边界时淡出，而不是硬切。
    // 只有那一侧**确实还有内容**时才淡出，否则首/尾的按键会被无缘无故压暗。
    val fadeStart = if (scrollState.canScrollBackward) Color.Black else Color.Transparent
    val fadeEnd = if (scrollState.canScrollForward) Color.Black else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PANEL_PADDING, vertical = PANEL_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                // Offscreen 合成 + DstOut：把渐变区域"擦"成透明，露出下面的面板底色。
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.horizontalGradient(
                            0f to fadeStart,
                            FADE_FRACTION to Color.Transparent,
                            (1f - FADE_FRACTION) to Color.Transparent,
                            1f to fadeEnd,
                        ),
                        blendMode = BlendMode.DstOut,
                    )
                }
                .horizontalScroll(scrollState),
            horizontalArrangement = Arrangement.spacedBy(KEY_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            COLLAPSED_KEYS.forEach { key ->
                ShortcutKeyButton(
                    label = key.label,
                    active = key is ShortcutKey.ModifierKey &&
                        lockedModifiers.contains(key.virtualKey),
                    modifier = Modifier.widthIn(min = 44.dp).height(KEY_HEIGHT),
                    onClick = { onKey(key) },
                )
            }
        }
        Spacer(Modifier.width(KEY_GAP))
        ExpandToggleButton(expanded = false, onClick = onExpand)
    }
}

/**
 * 展开态：小标题 + 收起按钮 + 统一网格键盘。
 *
 * 网格规则（宽高/间距全部统一，组间留更大的间距以示区隔）：
 * - 功能键区（修饰、导航、编辑）6 列，键宽 = (可用宽 - 5×gap) / 6；
 * - 数字/符号/字母区 10 列，键宽 = (可用宽 - 9×gap) / 10；行内键数不足 10 时整行居中，
 *   形成真实键盘的阶梯轮廓（QWERTY 三行分别是 10 / 9 / 8 键）；
 * - F1~F12 两行、每行 6 键（和其它功能键一样宽，不再挤成 12 列）。
 * 所有按键高度、圆角、字号一致；标签最长 5 个字符（Shift/PgUp/Home），不会截断。
 *
 * 上下留白刻意做成对称：面板四周统一 [PANEL_PADDING]，标题行与滚动区之间固定 [HEADER_GAP]，
 * 滚动区上下各有一条渐隐（只在还能滚的那一侧出现），避免"半行被硬切"看着像底部留白更多。
 */
@Composable
private fun ExpandedPanel(
    maxPanelHeight: Dp,
    lockedModifiers: Set<Int>,
    onKey: (ShortcutKey) -> Unit,
    onCollapse: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val fadeTop = if (scrollState.canScrollBackward) Color.Black else Color.Transparent
    val fadeBottom = if (scrollState.canScrollForward) Color.Black else Color.Transparent
    // 高度预算：面板总高 = 上下 padding + 标题行 + 标题间隔 + 滚动区。
    val gridMaxHeight = (maxPanelHeight - PANEL_PADDING * 2 - HEADER_HEIGHT - HEADER_GAP)
        .coerceAtLeast(KEY_HEIGHT)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 面板四周统一内边距：上下的视觉留白由此对称。
            .padding(PANEL_PADDING),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.session_shortcut_bar),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp),
            )
            ExpandToggleButton(expanded = true, onClick = onCollapse)
        }
        Spacer(Modifier.height(HEADER_GAP))
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
        ) {
            // 面板内边距已经把左右让出来了，这里直接用 maxWidth。
            val contentWidth = maxWidth
            // 同一列数下所有按键等宽：宽度由列数和固定 gap 反推，而不是靠 weight 瓜分，
            // 这样"行内键数不足"的行不会被撑大，整块看起来才是一个网格。
            fun keyWidth(columns: Int): Dp =
                (contentWidth - KEY_GAP * (columns - 1)) / columns

            val functionWidth = keyWidth(FUNCTION_COLUMNS)
            val characterWidth = keyWidth(CHARACTER_COLUMNS)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = gridMaxHeight)
                    // 上下渐隐必须包在滚动容器外面（先画内容再擦边缘）。
                    .verticalEdgeFade(fadeTop, fadeBottom)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(GROUP_GAP),
            ) {
                // 组 1：修饰键 + Esc/Tab
                KeyRow(
                    keys = MODIFIER_ROW,
                    keyWidth = functionWidth,
                    isFunction = true,
                    lockedModifiers = lockedModifiers,
                    onKey = onKey,
                )
                // 组 2：导航（方向 + Home/End / 翻页 + 编辑）
                Column(verticalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                    KeyRow(NAV_ROW_ARROWS, functionWidth, true, lockedModifiers, onKey)
                    KeyRow(NAV_ROW_EDIT, functionWidth, true, lockedModifiers, onKey)
                }
                // 组 3：数字 + 符号
                Column(verticalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                    KeyRow(DIGIT_ROW, characterWidth, false, lockedModifiers, onKey)
                    KeyRow(SYMBOL_ROW, characterWidth, false, lockedModifiers, onKey)
                }
                // 组 4：QWERTY 字母（10 / 9 / 8 键，居中即真实键盘的阶梯感）
                Column(verticalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                    LETTER_ROWS.forEach { row ->
                        KeyRow(row, characterWidth, false, lockedModifiers, onKey)
                    }
                }
                // 组 5：F1~F12 两行（F1-F6 / F7-F12），键宽与其它功能键一致
                Column(verticalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                    FUNCTION_ROWS.forEach { row ->
                        KeyRow(row, functionWidth, true, lockedModifiers, onKey)
                    }
                }
            }
        }
    }
}

/**
 * 竖向边缘渐隐：滚动内容被边界切断时淡出，而不是硬切（半行被切看着像"底部留白变多"）。
 * 用 Offscreen 合成 + DstOut 把边缘"擦"成透明，露出面板底色。
 */
private fun Modifier.verticalEdgeFade(fadeTop: Color, fadeBottom: Color): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.verticalGradient(
                0f to fadeTop,
                FADE_FRACTION to Color.Transparent,
                (1f - FADE_FRACTION) to Color.Transparent,
                1f to fadeBottom,
            ),
            blendMode = BlendMode.DstOut,
        )
    }

/** 一行按键：等宽、固定 gap；行内键数不足时整行居中。 */
@Composable
private fun KeyRow(
    keys: List<ShortcutKey>,
    keyWidth: Dp,
    isFunction: Boolean,
    lockedModifiers: Set<Int>,
    onKey: (ShortcutKey) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(KEY_GAP, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        keys.forEach { key ->
            ShortcutKeyButton(
                label = key.label,
                active = key is ShortcutKey.ModifierKey && lockedModifiers.contains(key.virtualKey),
                isFunction = isFunction,
                modifier = Modifier.width(keyWidth).height(KEY_HEIGHT),
                onClick = { onKey(key) },
            )
        }
    }
}

/** 展开/收起按钮：矢量 chevron，收起态箭头朝上（展开语义），展开态箭头朝下（收起语义）。 */
@Composable
private fun ExpandToggleButton(expanded: Boolean, onClick: () -> Unit) {
    val icon: Painter = painterResource(
        if (expanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_up,
    )
    val description = stringResource(
        if (expanded) R.string.session_shortcut_collapse else R.string.session_shortcut_expand,
    )
    Box(
        modifier = Modifier
            .size(HEADER_HEIGHT)
            .clip(RoundedCornerShape(KEY_CORNER))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * 单个快捷键按键：统一的圆角、字号与内边距。
 *
 * 视觉层次：[active]（锁定态的修饰键）用 primary；功能键用 surfaceContainerHighest、
 * 字符键用 secondaryContainer，一眼能区分"功能"和"字符"。
 */
@Composable
private fun ShortcutKeyButton(
    label: String,
    active: Boolean,
    isFunction: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val background = when {
        active -> MaterialTheme.colorScheme.primary
        isFunction -> MaterialTheme.colorScheme.surfaceContainerHighest
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val foreground = when {
        active -> MaterialTheme.colorScheme.onPrimary
        isFunction -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(KEY_CORNER))
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = foreground,
            fontSize = KEY_FONT_SIZE,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** 浮层里的一项按键。 */
private sealed interface ShortcutKey {
    /** 显示文本（Esc / ← / F1 等）。 */
    val label: String

    /** 单发按键：按下立刻抬起。 */
    data class PlainKey(override val label: String, val virtualKey: Int) : ShortcutKey

    /** 可锁定的修饰键：点一下按下并高亮，再点一下抬起。 */
    data class ModifierKey(override val label: String, val virtualKey: Int) : ShortcutKey
}

/**
 * 修饰键一律用左右具体码（VK_LCONTROL / VK_LSHIFT / VK_LMENU / VK_LWIN）：
 * 通用的 VK_CONTROL / VK_MENU 在 WinPR 的扫描码表里查不到，会静默变成 0
 * （RdpSession.sendPasteShortcut 里踩过同一个坑）。
 */
private val MODIFIER_ROW: List<ShortcutKey> = listOf(
    ShortcutKey.ModifierKey("Ctrl", RdpKeys.VK_LCONTROL),
    ShortcutKey.ModifierKey("Alt", RdpKeys.VK_LMENU),
    ShortcutKey.ModifierKey("Shift", RdpKeys.VK_LSHIFT),
    ShortcutKey.ModifierKey("Win", RdpKeys.VK_LWIN),
    ShortcutKey.PlainKey("Esc", RdpKeys.VK_ESCAPE),
    ShortcutKey.PlainKey("Tab", RdpKeys.VK_TAB),
)

private val NAV_ROW_ARROWS: List<ShortcutKey> = listOf(
    ShortcutKey.PlainKey("←", RdpKeys.VK_LEFT),
    ShortcutKey.PlainKey("↑", RdpKeys.VK_UP),
    ShortcutKey.PlainKey("↓", RdpKeys.VK_DOWN),
    ShortcutKey.PlainKey("→", RdpKeys.VK_RIGHT),
    ShortcutKey.PlainKey("Home", RdpKeys.VK_HOME),
    ShortcutKey.PlainKey("End", RdpKeys.VK_END),
)

private val NAV_ROW_EDIT: List<ShortcutKey> = listOf(
    ShortcutKey.PlainKey("PgUp", RdpKeys.VK_PRIOR),
    ShortcutKey.PlainKey("PgDn", RdpKeys.VK_NEXT),
    ShortcutKey.PlainKey("Ins", RdpKeys.VK_INSERT),
    ShortcutKey.PlainKey("Del", RdpKeys.VK_DELETE),
    // 编辑键用通行符号（和键盘上的印字一致），比写 "Backspace"/"Enter" 省空间。
    ShortcutKey.PlainKey("⌫", RdpKeys.VK_BACK),
    ShortcutKey.PlainKey("⏎", RdpKeys.VK_RETURN),
)

/** 数字行：1 2 3 4 5 6 7 8 9 0（与真实键盘一致，0 在最后）。 */
private val DIGIT_ROW: List<ShortcutKey> =
    ('1'..'9').map { ShortcutKey.PlainKey(it.toString(), it.code) } +
        ShortcutKey.PlainKey("0", '0'.code)

/** 符号行：与美式键盘的 OEM 键一一对应。 */
private val SYMBOL_ROW: List<ShortcutKey> = listOf(
    ShortcutKey.PlainKey("`", RdpKeys.VK_OEM_3),
    ShortcutKey.PlainKey("-", RdpKeys.VK_OEM_MINUS),
    ShortcutKey.PlainKey("=", RdpKeys.VK_OEM_PLUS),
    ShortcutKey.PlainKey("[", RdpKeys.VK_OEM_4),
    ShortcutKey.PlainKey("]", RdpKeys.VK_OEM_6),
    ShortcutKey.PlainKey(";", RdpKeys.VK_OEM_1),
    ShortcutKey.PlainKey("'", RdpKeys.VK_OEM_7),
    ShortcutKey.PlainKey(",", RdpKeys.VK_OEM_COMMA),
    ShortcutKey.PlainKey(".", RdpKeys.VK_OEM_PERIOD),
    ShortcutKey.PlainKey("/", RdpKeys.VK_OEM_2),
)

/** QWERTY 三行：10 / 9 / 8 键，行内居中后就是真实键盘的阶梯轮廓。 */
private val LETTER_ROWS: List<List<ShortcutKey>> = listOf(
    letterKeys("qwertyuiop"),
    letterKeys("asdfghjkl"),
    letterKeys("zxcvbnm") + ShortcutKey.PlainKey("\\", RdpKeys.VK_OEM_5),
)

/** F1~F12：VK_F1 = 0x70 … VK_F12 = 0x7B，连续 12 个。 */
private val FUNCTION_KEYS: List<ShortcutKey> = (1..12).map { index ->
    ShortcutKey.PlainKey("F" + index, RdpKeys.VK_F1 + (index - 1))
}

/** F1~F12 分两行、每行 6 键（和 6 列的功能键同宽，比一行 12 列好按也好看）。 */
private val FUNCTION_ROWS: List<List<ShortcutKey>> = listOf(
    FUNCTION_KEYS.take(6),
    FUNCTION_KEYS.drop(6),
)

/** 收起态：功能键 + 最常用的一组，末尾由 [ExpandToggleButton] 收尾。 */
private val COLLAPSED_KEYS: List<ShortcutKey> = MODIFIER_ROW.take(4) + listOf(
    ShortcutKey.PlainKey("Esc", RdpKeys.VK_ESCAPE),
    ShortcutKey.PlainKey("Tab", RdpKeys.VK_TAB),
    ShortcutKey.PlainKey("←", RdpKeys.VK_LEFT),
    ShortcutKey.PlainKey("↑", RdpKeys.VK_UP),
    ShortcutKey.PlainKey("↓", RdpKeys.VK_DOWN),
    ShortcutKey.PlainKey("→", RdpKeys.VK_RIGHT),
    ShortcutKey.PlainKey("⌫", RdpKeys.VK_BACK),
    ShortcutKey.PlainKey("⏎", RdpKeys.VK_RETURN),
)

/** a-z：Windows 虚拟键码是**大写**字母的 ASCII（VK_KEY_A = 0x41），与 RdpKeys.asciiToVirtualKey 一致。 */
private fun letterKeys(letters: String): List<ShortcutKey> = letters.map { letter ->
    ShortcutKey.PlainKey(letter.toString(), letter.uppercaseChar().code)
}

/** 面板内边距（左右/上下）。 */
private val PANEL_PADDING = 8.dp

/** 同一组内按键的间距。 */
private val KEY_GAP = 5.dp

/** 组与组之间的间距：比组内大一倍，靠留白分组。 */
private val GROUP_GAP = 8.dp

/** 按键高度 / 圆角 / 字号：整块面板统一。 */
private val KEY_HEIGHT = 36.dp
private val KEY_CORNER = 8.dp
private val KEY_FONT_SIZE = 11.sp

/** 展开态顶部标题行高度（收起按钮也在这一行）。 */
private val HEADER_HEIGHT = 32.dp

/** 标题行与键盘区之间的固定间隔：与面板内边距一起决定上下留白。 */
private val HEADER_GAP = 6.dp

/** 功能键区列数（修饰/导航/编辑键，键更宽才能放下 Shift/PgUp 这类标签）。 */
private const val FUNCTION_COLUMNS = 6

/** 字符区列数（数字/符号/字母）。 */
private const val CHARACTER_COLUMNS = 10

/** 横屏时展开面板的最大宽度（不必满宽，居中）。 */
private val LANDSCAPE_PANEL_MAX_WIDTH = 880.dp

/** 展开面板高度上限占可用高度的比例（题目要求的 45%~55%，取中间值）。 */
private const val PANEL_MAX_HEIGHT_RATIO = 0.5f

/** 横屏展开态的可用高度比例：横屏屏幕本来就矮，用更高的比例否则一展开就得滚。 */
private const val LANDSCAPE_PANEL_MAX_HEIGHT_RATIO = 0.86f

/** 收起态左右渐隐遮罩占整行宽度的比例（每侧）。 */
private const val FADE_FRACTION = 0.06f
