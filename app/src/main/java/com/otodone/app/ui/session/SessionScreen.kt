package com.otodone.app.ui.session

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.otodone.app.R
import com.otodone.app.data.RdpHost
import com.otodone.app.data.SessionOrientation
import com.otodone.app.rdp.RdpState
import com.otodone.app.rdp.RdpSurfaceView
import com.otodone.app.ui.hosts.HostListViewModel
import com.otodone.app.ui.insets.rememberSystemBarInsets
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun SessionRoute(
    hostId: String,
    sessionOrientation: SessionOrientation,
    keepScreenOn: Boolean,
    quickPaste: Boolean,
    showMouseButton: Boolean,
    showKeyboardButton: Boolean,
    onShowMouseButtonChange: (Boolean) -> Unit,
    onShowKeyboardButtonChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    hostListViewModel: HostListViewModel = viewModel(factory = HostListViewModel.Factory),
    sessionViewModel: RdpSessionViewModel = viewModel(factory = RdpSessionViewModel.Factory),
) {
    val hosts by hostListViewModel.hosts.collectAsState()
    val host = hosts.firstOrNull { it.id == hostId }
    if (host == null) {
        MissingHostScreen(onBack = onBack)
    } else {
        SessionScreen(
            host = host,
            sessionOrientation = sessionOrientation,
            keepScreenOn = keepScreenOn,
            quickPaste = quickPaste,
            showMouseButton = showMouseButton,
            showKeyboardButton = showKeyboardButton,
            onShowMouseButtonChange = onShowMouseButtonChange,
            onShowKeyboardButtonChange = onShowKeyboardButtonChange,
            viewModel = sessionViewModel,
            onBack = onBack,
        )
    }
}

@Composable
private fun MissingHostScreen(onBack: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.session_missing_host))
            Spacer(Modifier.height(12.dp))
            Button(onClick = onBack) { Text(stringResource(R.string.action_back)) }
        }
    }
}

/** RDP 会话页：AndroidView 渲染远端桌面，叠加连接状态/错误。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(
    host: RdpHost,
    sessionOrientation: SessionOrientation,
    keepScreenOn: Boolean,
    quickPaste: Boolean,
    // 「三」菜单 -> 布局：两个浮标是否渲染。值存在 DataStore，改完立即生效。
    showMouseButton: Boolean,
    showKeyboardButton: Boolean,
    onShowMouseButtonChange: (Boolean) -> Unit,
    onShowKeyboardButtonChange: (Boolean) -> Unit,
    viewModel: RdpSessionViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val bitmap by viewModel.bitmap.collectAsState()
    val frameTick by viewModel.frameTick.collectAsState()
    val context = LocalContext.current
    val density = LocalDensity.current
    // 全窗口统一的系统栏安全区（含三键导航的 tappableElement 兜底、小窗配额 max）。
    // 会话页是沉浸的：系统栏隐藏时这里的 bottom 自然为 0，浮标仍贴底；
    // 三键导航露出来 / 小窗 / 横屏侧边导航时才会把浮标抬到导航栏之上。
    val safeInsets = rememberSystemBarInsets()
    val floatInsets = safeInsets.only(
        WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal,
    )
    val inputMethodManager = remember {
        context.getSystemService(InputMethodManager::class.java)
    }
    var surfaceView by remember { mutableStateOf<RdpSurfaceView?>(null) }
    // 「右键待发」：按住右上角浮标时为 true，画面上的普通单指点击改发右键。
    var rightClickArmed by remember { mutableStateOf(false) }
    // AndroidView（远端画面）在 Compose 根坐标里的原点，用于浮标坐标换算。
    var surfaceOrigin by remember { mutableStateOf(Offset.Zero) }

    // 「三」菜单：是否展开 + 当前是横屏还是竖屏（横屏走右侧栏、竖屏走底部抽屉）。
    var menuOpen by remember { mutableStateOf(false) }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // 「显示」分组只列当前会话使用的分辨率：标准 RDP 没有「枚举远端显示器」的机制
    // （disp 通道只允许客户端下发 monitor layout，详见验证报告）。
    // 已连接就用远端分辨率，否则退回当前画面尺寸，两者都没有时菜单显示「等待会话连接」。
    val display = (state as? RdpState.Connected)?.let { SessionDisplay(1, it.width, it.height) }
        ?: bitmap?.let { SessionDisplay(1, it.width, it.height) }

    // 键盘类 UI（系统 IME 可见 / 虚拟全键盘展开）占屏时，三个圆形浮标和「三」按钮临时隐藏，
    // 否则会和键盘/面板叠在一起（真机反馈的 UI 叠加 bug）。由快捷键浮层回调驱动。
    var keyboardUiActive by remember { mutableStateOf(false) }

    // 关掉「鼠标」浮标、或浮标被键盘挡住时，都不能留下「右键待发」：
    // 否则用户在画面上点一下会莫名发出右键。
    LaunchedEffect(showMouseButton, keyboardUiActive) {
        if (!showMouseButton || keyboardUiActive) {
            rightClickArmed = false
            surfaceView?.rightClickArmed = false
        }
    }

    // 全屏沉浸 + 按设置决定屏幕方向/保持亮屏 + 内容延伸到异形屏（刘海/挖孔）区域
    SessionWindowEffect(sessionOrientation, keepScreenOn)

    // 会话页前后台切换：这里只上报「进入后台 / 回到前台」两个事件。
    // 是否重连、退避多久、连续失败几次停手，全部由 ViewModel 的失败状态监听 +
    // ReconnectPolicy 判定（失败是事件，不再依赖 ON_RESUME 那一刻的状态快照）。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> viewModel.onAppBackgrounded()
                Lifecycle.Event.ON_RESUME -> viewModel.onAppForegrounded()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 分层约定（真机上出过"画面被挤成窄带"的事故，别再破坏它）：
    // 1. 这一层 Box 铺满窗口；2. BoxWithConstraints 只负责量出**窗口尺寸**并承载远端画面；
    // 3. 浮标 / 「三」按钮 / 快捷键浮层 / 菜单全是它的**兄弟节点**，只做覆盖层。
    // Box 的子节点永远拿到父级给定的固定约束，彼此不会改变对方的尺寸，
    // 所以浮层显示与否都不可能影响 AndroidView 的大小，也就不会影响 connect() 传的宽高。
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // 这个尺寸只来自窗口本身（覆盖层是兄弟节点，改不了它）。
            val widthPx = with(density) { maxWidth.roundToPx() }
            val heightPx = with(density) { maxHeight.roundToPx() }
            val hasViewport = widthPx > 0 && heightPx > 0

            // 只在 host 变化时连接一次：窗口尺寸**不进 key**。键盘弹出/收起、旋转都不会再触发
            // 「按新尺寸重连」——那正是之前把画面搞成一条窄带的元凶之一。
            LaunchedEffect(host.id, hasViewport) {
                if (hasViewport) viewModel.connect(host, widthPx, heightPx)
            }
            // 窗口尺寸真的变了（旋转 / 分屏）才通知远端，由 RdpSession 用 MS-RDPEDISP 下发新布局；
            // 键盘弹出不会改变窗口尺寸（adjustNothing），所以这里不会因键盘而抖动。
            LaunchedEffect(host.id, widthPx, heightPx) {
                if (hasViewport) viewModel.onViewportChanged(widthPx, heightPx)
            }

            // 键盘是覆盖式窗口（windowSoftInputMode=adjustNothing），窗口本身不会被顶起来。
            // 不主动让位的话画面就被 IME 整个盖住（横屏尤其致命）。这里在**布局阶段**吃掉
            // IME 高度：画面区缩到键盘上方，AndroidView 自动重排、RdpSurfaceView 自己
            // letterbox 缩放，不重连、也不改 connect 的尺寸。
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime)) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { surfaceOrigin = it.positionInRoot() },
                    factory = { ctx ->
                        RdpSurfaceView(ctx).also { view ->
                            view.listener = viewModel.inputListener
                            // 粘性右键：画面发完一次右键就自动解除，避免下一次点击误触。
                            view.onRightClickSent = {
                                rightClickArmed = false
                                view.rightClickArmed = false
                            }
                            surfaceView = view
                        }
                    },
                    update = { view ->
                        view.listener = viewModel.inputListener
                        view.rightClickArmed = rightClickArmed
                        view.setFrame(bitmap, frameTick)
                    },
                )

            }
        }

        // 不设应用栏：远端画面整屏铺满。退出走系统返回手势/按键，
        // 键盘保留一个仅 44dp 的半透明悬浮按钮，不占任何布局空间。
        //
        // 返回键优先级（从最上层往下）：
        //   1. 侧栏/底部抽屉菜单  —— SessionMenu 内部注册的 BackHandler 更晚添加，先拿到事件；
        //   2. 展开的虚拟全键盘    —— ShortcutBar 的 BackHandler（收起并恢复 IME）；
        //   3. 系统输入法可见      —— 下面这一层负责收起 IME；
        //   4. 两段式退出会话。
        // 每层都是独立的 BackHandler，enabled 互斥，一次返回只会关一层。
        val rootView = LocalView.current
        var lastBackAt by remember { mutableLongStateOf(0L) }
        BackHandler {
            // 走到这里说明菜单和全键盘都没开。系统 IME 可见时先把键盘收起来，
            // 不要直接跳到"再按一次退出"。
            val imeShowing = runCatching {
                ViewCompat.getRootWindowInsets(rootView)
                    ?.isVisible(WindowInsetsCompat.Type.ime())
            }.getOrNull() == true
            if (imeShowing) {
                surfaceView?.hideKeyboard()
                return@BackHandler
            }
            val now = System.currentTimeMillis()
            if (now - lastBackAt < 2000L) {
                onBack()
            } else {
                lastBackAt = now
                Toast.makeText(
                    context,
                    context.getString(R.string.session_exit_confirm),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }

        // 右下角键盘浮标：只在「三」菜单 -> 布局 -> 键盘 打开时渲染；
        // 键盘类 UI 占屏（IME 可见 / 全键盘展开）时隐藏，避免和键盘/面板叠加。
        if (showKeyboardButton && !keyboardUiActive) {
            SessionFloatButton(
                icon = rememberVectorPainter(Icons.Filled.Keyboard),
                contentDescription = stringResource(R.string.session_keyboard),
                onClick = { surfaceView?.showKeyboard() },
                // 长按 = 快捷粘贴；单击仍然只弹输入法，两条路径在浮标手势里互斥。
                onLongClick = {
                    if (!quickPaste) {
                        // 开关关闭：长按不粘贴、也不弹提示。理由见验证报告：
                        // 保留「长按」这个手势槽位，避免关掉后长按反而弹键盘的行为分裂；
                        // 也不给禁用态再加一条会跟着连点刷屏的 Toast。
                        Log.i(TAG, "quick paste long-press ignored: disabled in settings")
                    } else {
                        when (viewModel.quickPasteFromClipboard()) {
                            QuickPasteResult.PASTED ->
                                showToast(context, R.string.session_quick_paste_done)
                            QuickPasteResult.EMPTY_CLIPBOARD ->
                                showToast(context, R.string.session_quick_paste_empty)
                            QuickPasteResult.NO_SESSION ->
                                showToast(context, R.string.session_quick_paste_unavailable)
                            // 防抖窗口内不弹提示：Toast 也不能刷屏。
                            QuickPasteResult.DEBOUNCED -> Unit
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(floatInsets)
                    .padding(end = 14.dp, bottom = 14.dp),
            )
        }

        // 键盘浮标在右下角，右键浮标放左下角（与它左右对称，同一水平线）。
        // 粘性右键：点一下浮标进入右键模式（高亮），再点画面 = 右键，发完自动退出。
        // 刻意做成单指顺序操作——不依赖"一根手指按住浮标 + 另一根手指点画面"，
        // 因为后者要走 Compose AndroidView 的多指路由，实测在真机上会被吞掉。
        // 只在「三」菜单 -> 布局 -> 鼠标 打开时渲染；键盘类 UI 占屏时同样隐藏。
        if (showMouseButton && !keyboardUiActive) {
            SessionFloatButton(
                icon = painterResource(R.drawable.ic_mouse_right),
                contentDescription = stringResource(R.string.session_right_click),
                armed = rightClickArmed,
                onClick = {
                    val next = !rightClickArmed
                    rightClickArmed = next
                    surfaceView?.rightClickArmed = next
                },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .windowInsetsPadding(floatInsets)
                    // 必须和键盘浮标一致：padding 写在 size 之前，两圆圈的上下间距才一致。
                    .padding(start = 14.dp, bottom = 14.dp),
            )
        }

        // 右上角「三」：竖屏开底部抽屉、横屏开右侧栏（见 SessionMenu）。
        // 同样在键盘类 UI 占屏时隐藏（横屏全键盘面板很高，会和它叠在一起）。
        if (!keyboardUiActive) {
            SessionMenuButton(
                onClick = { menuOpen = true },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(floatInsets)
                    .padding(end = 14.dp, top = 14.dp),
            )
        }

        // 键盘弹出时贴在键盘正上方的快捷键浮层；收起态键盘一收起浮层就消失，
        // 展开态则主动收起系统 IME、改用「收起前的 IME 高度」占位（见 KeyboardShortcutBarOverlay）。
        // ime inset 只在这个浮层内部订阅，键盘的逐帧动画不会带着远端画面一起重组。
        KeyboardShortcutBarOverlay(
            sendVirtualKey = viewModel::sendVirtualKey,
            onRequestHideIme = { surfaceView?.hideKeyboard() },
            // 收起全键盘时把系统 IME 弹回来 —— 与右下角键盘浮标走同一条路。
            onRequestShowIme = { surfaceView?.showKeyboard() },
            // 浮层占用屏幕时把三个圆形按钮藏起来，退下（IME 收起且全键盘收起）再恢复。
            onActiveChange = { active -> keyboardUiActive = active },
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        // 菜单本身常驻组合：横屏侧边栏的滑入/滑出都由它内部的 AnimatedVisibility 负责。
        SessionMenu(
            visible = menuOpen,
            isLandscape = isLandscape,
            display = display,
            showMouseButton = showMouseButton,
            showKeyboardButton = showKeyboardButton,
            onShowMouseButtonChange = onShowMouseButtonChange,
            onShowKeyboardButtonChange = onShowKeyboardButtonChange,
            onDismiss = { menuOpen = false },
        )

        // 连接状态变化（成功/失败/断开）时把菜单关掉：它是唯一可能盖在覆盖层上面的东西。
        LaunchedEffect(state) {
            if (menuOpen && state !is RdpState.Connected) menuOpen = false
        }

        // 状态覆盖层必须是**最后一个**子节点：纯黑不透明、铺满全屏，
        // 才能把浮标、「三」按钮、快捷键浮层、菜单一起盖住（连接中/失败时不留可点的控件）。
        StatusOverlay(
            state = state,
            onRetry = {
                // 重试时沿用当前屏幕尺寸（全屏铺满 → 直接取显示尺寸即可）
                val metrics = context.resources.displayMetrics
                viewModel.retry(
                    host,
                    metrics.widthPixels.coerceAtLeast(320),
                    metrics.heightPixels.coerceAtLeast(240),
                )
            },
        )
    }
}

/**
 * 会话页浮标按钮：44dp 圆形 + 半透明黑底 + 白色图标的公用组件。
 *
 * 键盘浮标与右键浮标共用本组件，因此尺寸、底色、图标 tint、按下反馈（背景提亮）
 * 完全一致，不会再出现"两个按钮一个有点击过渡、另一个没有"。
 *
 * 交互由 [pointerInput] 统一跟踪：
 * - 按下 → [onPressedChange] true 且背景提亮；
 * - 松手/取消 → [onPressedChange] false；
 * - 松手点在按钮内且不是长按 → [onClick]（键盘浮标弹出输入法）；
 * - 按住超过系统长按时长 → [onLongClick]（键盘浮标做快捷粘贴），抬起时不再触发 onClick；
 * - 松手点在按钮外 → [onReleaseOutside]（右键浮标把这根手指补成一次右键点击）。
 *
 * [onLongClick] 为 null 时完全不检测长按：按住多久抬起都算一次点击，右键浮标沿用旧行为，
 * 不受本组件的新增长按分支影响。
 *
 * 位置与安全区由调用方通过 [modifier] 传入（`align` + `windowInsetsPadding`）。
 */
@Composable
internal fun SessionFloatButton(
    icon: Painter,
    contentDescription: String,
    modifier: Modifier = Modifier,
    /** 粘性右键模式下保持高亮，让用户知道现在是"右键待发"。 */
    armed: Boolean = false,
    onPressedChange: (Boolean) -> Unit = {},
    onClick: () -> Unit = {},
    /** 长按动作；为 null 表示这个浮标没有长按功能（右键浮标就是 null）。 */
    onLongClick: (() -> Unit)? = null,
    onReleaseOutside: (Offset) -> Unit = {},
) {
    var pressed by remember { mutableStateOf(false) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    // 设置页可能随时切换快捷粘贴开关：手势必须调用"最新"的回调，而不是首次组合时捕获的那个。
    val latestOnLongClick by rememberUpdatedState(onLongClick)
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            // 只用干净的半透明实心圆底，不要任何描边/轮廓（用户反馈描边显脏）。
            // 按下时提亮，两个浮标都走同一套视觉反馈。
            .background(
                if (pressed || armed) {
                    Color.White.copy(alpha = PRESSED_BG_ALPHA)
                } else {
                    Color.Black.copy(alpha = IDLE_BG_ALPHA)
                },
            )
            .onGloballyPositioned { origin = it.positionInRoot() }
            .semantics { this.contentDescription = contentDescription }
            .pointerInput(Unit) {
                // 用官方 detectTapGestures：tap / longPress / press 三者天生互斥且状态机成熟。
                // 注意：**不要**在 awaitPointerEventScope 里用 withTimeout 判长按 ——
                // 超时时内部抛 PointerEventTimeoutCancellationException，即使被 withTimeoutOrNull
                // 吞掉，指针处理器也已被取消，这个节点的 pointerInput 会整体失效（两个浮标单击全哑）。
                detectTapGestures(
                    onPress = {
                        pressed = true
                        onPressedChange(true)
                        tryAwaitRelease()
                        pressed = false
                        onPressedChange(false)
                    },
                    onTap = { onClick() },
                    onLongPress = { latestOnLongClick?.invoke() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = if (pressed || armed) 1f else 0.9f),
            modifier = Modifier.size(22.dp),
        )
    }
}

/** 浮标静止 / 按下时的背景 alpha，两个浮标共用。 */
private const val IDLE_BG_ALPHA = 0.32f
private const val PRESSED_BG_ALPHA = 0.42f


/** 会话页调试日志 tag（快捷粘贴的长按/禁用分支走它，便于真机排查）。 */
private const val TAG = "SessionScreen"

/**
 * 一直读到 [id] 这根手指抬起，返回抬起时的本地坐标。
 *
 * 只关心这一根指针：无关指针的事件直接跳过，不能因此解除按下/长按状态。
 */
private suspend fun AwaitPointerEventScope.awaitUpPosition(id: PointerId): Offset? {
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == id } ?: continue
        change.consume()
        if (!change.pressed) return change.position
    }
}

/** 会话页轻提示：统一走 Toast.LENGTH_SHORT，文案资源由调用方给出。 */
private fun showToast(context: Context, stringRes: Int) {
    // Toast 在窗口已销毁/后台限制下可能抛（BadTokenException 等）；提示失败不值得让 App 崩。
    runCatching { Toast.makeText(context, context.getString(stringRes), Toast.LENGTH_SHORT).show() }
        .onFailure { Log.w(TAG, "showToast failed", it) }
}

/**
 * 会话期间：隐藏系统栏（沉浸）、允许内容画进异形屏区域、按设置应用屏幕方向/保持亮屏；
 * 离开会话时把方向/系统栏/异形屏模式/保持亮屏全部还原，主页面不会被留在横屏、也不会常亮。
 *
 * 方向用 [LaunchedEffect] 单独应用：[orientation] 变化时立即重新下发，会话中切换设置也能实时生效；
 * 进入前的方向只在首次组合时记录一次，退出时原样还原（不被中间的切换污染）。
 * 保持亮屏同理：开关变化时立即加/清 FLAG_KEEP_SCREEN_ON，退出时在 onDispose 里统一清掉。
 */
@Composable
private fun SessionWindowEffect(orientation: SessionOrientation, keepScreenOn: Boolean) {
    val view = LocalView.current
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val restoreOrientation = remember { activity?.requestedOrientation }

    DisposableEffect(Unit) {
        val window = activity?.window
        // 窗口/控制器在极端时机（Activity 正在销毁、多窗口切换）可能抛异常：
        // 沉浸失效顶多让系统栏露出来，不该把会话页一起带崩。
        var previousCutout: Int? = null
        runCatching {
            val controller = window?.let { WindowCompat.getInsetsController(it, view) }
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())

            if (window != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                previousCutout = window.attributes.layoutInDisplayCutoutMode
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }.onFailure { Log.w(TAG, "enter immersive/cutout failed", it) }

        onDispose {
            // 每一步都单独包住：任何一步失败都不能阻止其它还原（尤其是清常亮）。
            runCatching {
                window?.let { w ->
                    WindowCompat.getInsetsController(w, view)
                        ?.show(WindowInsetsCompat.Type.systemBars())
                }
            }.onFailure { Log.w(TAG, "restore system bars failed", it) }
            runCatching {
                activity?.requestedOrientation =
                    restoreOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }.onFailure { Log.w(TAG, "restore orientation failed", it) }
            // 退出会话必须清掉常亮，避免主页/其他页面也一直亮屏。
            runCatching {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }.onFailure { Log.w(TAG, "clear keep-screen-on failed", it) }
            if (window != null && previousCutout != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching {
                    window.attributes = window.attributes.apply {
                        layoutInDisplayCutoutMode = previousCutout
                    }
                }.onFailure { Log.w(TAG, "restore cutout mode failed", it) }
            }
        }
    }

    LaunchedEffect(orientation) {
        runCatching { activity?.requestedOrientation = orientation.toActivityOrientation() }
            .onFailure { Log.w(TAG, "apply orientation failed", it) }
    }

    // 保持亮屏：会话中改设置也能立刻生效（开→加 flag，关→清 flag）。
    LaunchedEffect(keepScreenOn) {
        val window = activity?.window ?: return@LaunchedEffect
        runCatching {
            if (keepScreenOn) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }.onFailure { Log.w(TAG, "toggle keep-screen-on failed", it) }
    }
}

/** 设置项 -> ActivityInfo 方向的映射：自适应必须用 UNSPECIFIED 覆盖 Manifest 里的 portrait。 */
private fun SessionOrientation.toActivityOrientation(): Int = when (this) {
    SessionOrientation.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    SessionOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    SessionOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** 多边形加载指示器直径。 */
private val PolygonLoaderSize = 44.dp

/** 圆周率 Float 版本，避免每帧在 Canvas 里来回转换。 */
private val PI_F = PI.toFloat()

/**
 * 会话状态覆盖层：**纯黑不透明、铺满全屏**，只留中央的「变形多边形 + 一行文案」
 * （失败时多一个「重试」按钮）。
 *
 * 它被放在根 Box 的**最后一个**子节点，所以会把远端画面、左右浮标、「三」按钮、
 * 快捷键浮层一起盖住 —— 连接中/失败时用户不会点到任何看不见的控件。
 * 纯黑是刻意的：浅色主题下不能用主题 surface，否则会变成一块白板、还会被误认成"页面"。
 *
 * 自动重连已经关掉（见 RdpSessionViewModel.AUTO_RECONNECT_ENABLED），失败后停在这里，
 * 只有用户点「重试」才会重新连接。
 */
@Composable
private fun StatusOverlay(state: RdpState, onRetry: () -> Unit) {
    val showOverlay = state is RdpState.Connecting ||
        state is RdpState.Failed ||
        state is RdpState.Disconnected ||
        state is RdpState.Idle
    if (!showOverlay) return

    // 一行标题：连接中给出目标地址；失败/断开用简短标题，不堆技术细节。
    val line = when (state) {
        is RdpState.Idle -> stringResource(R.string.session_preparing)
        is RdpState.Connecting -> stringResource(R.string.session_connecting, state.endpoint)
        is RdpState.Failed -> stringResource(R.string.session_failed_title)
        is RdpState.Disconnected -> stringResource(R.string.session_disconnected_title)
        is RdpState.Connected -> ""
    }
    // 失败/断开再补一句人话原因（不要原生错误码那种技术细节）。
    val hint = when (state) {
        is RdpState.Failed -> stringResource(R.string.session_failed_hint)
        is RdpState.Disconnected -> stringResource(R.string.session_disconnected_hint)
        else -> null
    }
    val busy = state is RdpState.Connecting || state is RdpState.Idle
    val canRetry = state is RdpState.Failed || state is RdpState.Disconnected

    // 纯黑不透明铺满：盖住画面 + 所有浮层控件。
    // pointerInput 消费掉全部触摸事件：只画不挡的话，点在黑屏上仍会穿透到下面看不见的浮标/按钮。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            PolygonLoadingIndicator(
                running = busy,
                // 黑底上固定用白色系，**不跟随 colorScheme**（浅色主题的 onSurface 近黑，黑底看不见）。
                color = if (busy) Color.White else Color.White.copy(alpha = 0.55f),
            )
            Spacer(Modifier.height(18.dp))
            Text(
                text = line,
                style = MaterialTheme.typography.titleMedium,
                // 固定白色：覆盖层永远是纯黑底，深浅主题下都必须可读。
                color = Color.White,
                textAlign = TextAlign.Center,
                // 窄屏/长地址（"正在连接 xxx:3389…"）最多两行，超出省略而不是把按钮挤出去。
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!hint.isNullOrEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.72f),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (canRetry) {
                Spacer(Modifier.height(20.dp))
                // 白底黑字实心按钮：与主题无关，在黑底上永远最高对比；48dp 最小高度保证好点。
                Button(
                    onClick = onRetry,
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = stringResource(R.string.session_retry),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

/**
 * Material 3 Expressive 风格的「变形多边形」加载指示器（Compose 自绘，无新依赖）。
 *
 * 项目用的 Compose BOM 是 2024.12.01（material3 1.3.x），官方还没有 Expressive 的
 * 多边形 LoadingIndicator，因此这里用 Canvas 实现：六边形绕中心匀速旋转，同时每个
 * 顶点的半径、圆角比例与线宽各按不同相位做呼吸式起伏，观感上一边转一边被「揉捏」。
 *
 * [running] 为 false 时停在固定姿态（失败态的静态图标），此时不注册任何动画，
 * 也就不会持续请求重绘。
 */
@Composable
private fun PolygonLoadingIndicator(
    running: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    if (running) {
        val transition = rememberInfiniteTransition(label = "polygon-loader")
        val rotation by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1600, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "rotation",
        )
        val breath by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 950, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "breath",
        )
        PolygonShape(rotation = rotation, breath = breath, color = color, modifier = modifier)
    } else {
        PolygonShape(rotation = 0f, breath = 0.5f, color = color, modifier = modifier)
    }
}

/** 绘制一帧变形六边形；[rotation] 0..1 为一圈，[breath] 0..1 为呼吸相位。 */
@Composable
private fun PolygonShape(rotation: Float, breath: Float, color: Color, modifier: Modifier) {
    Canvas(modifier = modifier.size(PolygonLoaderSize)) {
        val vertices = 6
        val shortSide = size.minDimension
        val strokeWidth = shortSide * 0.085f * (0.85f + 0.35f * breath)
        val maxRadius = shortSide / 2f - strokeWidth
        val center = Offset(size.width / 2f, size.height / 2f)
        val baseAngle = rotation * 2f * PI_F - PI_F / 2f

        val points = List(vertices) { index ->
            val angle = baseAngle + index * 2f * PI_F / vertices
            // 顶点半径按各自相位呼吸，相邻顶点错开，形成不规则「揉捏」形变。
            val pulse = 0.80f + 0.20f * (0.5f + 0.5f * sin(breath * 2f * PI_F + index * 1.1f))
            val radius = maxRadius * pulse
            Offset(
                x = center.x + cos(angle).toFloat() * radius,
                y = center.y + sin(angle).toFloat() * radius,
            )
        }

        // 圆角比例也随呼吸变化：在偏尖锐的六边形与圆润的环之间过渡。
        val corner = 0.18f + 0.30f * breath
        val path = Path()
        points.forEachIndexed { index, point ->
            val previous = points[(index - 1 + vertices) % vertices]
            val next = points[(index + 1) % vertices]
            val start = Offset(
                x = point.x + (previous.x - point.x) * corner,
                y = point.y + (previous.y - point.y) * corner,
            )
            if (index == 0) path.moveTo(start.x, start.y) else path.lineTo(start.x, start.y)
            val end = Offset(
                x = point.x + (next.x - point.x) * corner,
                y = point.y + (next.y - point.y) * corner,
            )
            path.quadraticTo(point.x, point.y, end.x, end.y)
        }
        path.close()
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
