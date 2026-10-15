package com.otodone.app.ui.session

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
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
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun SessionRoute(
    hostId: String,
    sessionOrientation: SessionOrientation,
    keepScreenOn: Boolean,
    quickPaste: Boolean,
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
    viewModel: RdpSessionViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val bitmap by viewModel.bitmap.collectAsState()
    val frameTick by viewModel.frameTick.collectAsState()
    val context = LocalContext.current
    val density = LocalDensity.current
    val inputMethodManager = remember {
        context.getSystemService(InputMethodManager::class.java)
    }
    var surfaceView by remember { mutableStateOf<RdpSurfaceView?>(null) }
    // 「右键待发」：按住右上角浮标时为 true，画面上的普通单指点击改发右键。
    var rightClickArmed by remember { mutableStateOf(false) }
    // AndroidView（远端画面）在 Compose 根坐标里的原点，用于浮标坐标换算。
    var surfaceOrigin by remember { mutableStateOf(Offset.Zero) }

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

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val widthPx = with(density) { maxWidth.roundToPx() }
            val heightPx = with(density) { maxHeight.roundToPx() }
            LaunchedEffect(host.id, widthPx, heightPx) {
                if (widthPx > 0 && heightPx > 0) {
                    viewModel.connect(host, widthPx, heightPx)
                }
            }
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

        StatusOverlay(
            state = state,
            onRetry = {
                // 重试时沿用当前屏幕尺寸
                val metrics = context.resources.displayMetrics
                viewModel.retry(host, metrics.widthPixels.coerceAtLeast(320), metrics.heightPixels.coerceAtLeast(240))
            },
        )

        // 不设应用栏：远端画面整屏铺满。退出走系统返回手势/按键，
        // 键盘保留一个仅 44dp 的半透明悬浮按钮，不占任何布局空间。
        var lastBackAt by remember { mutableLongStateOf(0L) }
        BackHandler {
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
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(end = 14.dp, bottom = 14.dp),
        )

        // 键盘浮标在右下角，右键浮标放左下角（与它左右对称，同一水平线）。
        // 粘性右键：点一下浮标进入右键模式（高亮），再点画面 = 右键，发完自动退出。
        // 刻意做成单指顺序操作——不依赖"一根手指按住浮标 + 另一根手指点画面"，
        // 因为后者要走 Compose AndroidView 的多指路由，实测在真机上会被吞掉。
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
                .windowInsetsPadding(WindowInsets.displayCutout)
                // 必须和键盘浮标一致：padding 写在 size 之前，两圆圈的上下间距才一致。
                .padding(start = 14.dp, bottom = 14.dp),
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
private fun SessionFloatButton(
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
    Toast.makeText(context, context.getString(stringRes), Toast.LENGTH_SHORT).show()
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
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())

        var previousCutout: Int? = null
        if (window != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            previousCutout = window.attributes.layoutInDisplayCutoutMode
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation =
                restoreOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            // 退出会话必须清掉常亮，避免主页/其他页面也一直亮屏。
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (window != null && previousCutout != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = previousCutout
                }
            }
        }
    }

    LaunchedEffect(orientation) {
        activity?.requestedOrientation = orientation.toActivityOrientation()
    }

    // 保持亮屏：会话中改设置也能立刻生效（开→加 flag，关→清 flag）。
    LaunchedEffect(keepScreenOn) {
        val window = activity?.window ?: return@LaunchedEffect
        if (keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
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
 * 会话状态浮层：**不透明**卡片 + 「变形多边形」加载动画 + 一行文案（失败时带重试按钮）。
 *
 * 与旧版相比：
 * - 背景改成实色 [MaterialTheme.colorScheme.surfaceContainerHigh]（不再 copy(alpha)），
 *   盖在远端画面上不再透底；
 * - 只保留加载动画 + 一行文字，FreeRDP 版本、原生库错误、多行错误详情全部删除；
 * - 加载动画是 Material 3 Expressive 风格的自绘变形多边形（本项目的 Compose BOM
 *   2024.12.01 / material3 1.3.x 还没有官方组件，见 [PolygonLoadingIndicator]）。
 *
 * 失败态沿用同一形状但停住动画（[PolygonLoadingIndicator] 的 running = false 分支），
 * 并保留「重试」按钮，否则三次自动重连停手后用户无路可走。
 */
@Composable
private fun StatusOverlay(state: RdpState, onRetry: () -> Unit) {
    val showOverlay = state is RdpState.Connecting ||
        state is RdpState.Failed ||
        state is RdpState.Disconnected ||
        state is RdpState.Idle
    if (!showOverlay) return

    // 只留一行：连接中给出目标地址；失败/断开用简短标题，不再堆技术细节。
    val line = when (state) {
        is RdpState.Idle -> stringResource(R.string.session_preparing)
        is RdpState.Connecting -> stringResource(R.string.session_connecting, state.endpoint)
        is RdpState.Failed -> stringResource(R.string.session_failed_title)
        is RdpState.Disconnected -> stringResource(R.string.session_disconnected_title)
        is RdpState.Connected -> ""
    }
    val busy = state is RdpState.Connecting || state is RdpState.Idle
    val canRetry = state is RdpState.Failed || state is RdpState.Disconnected

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            // 必须是不透明实色：旧版 surface.copy(alpha = 0.92f) 会透出远端画面，看不清。
            // 实色时 contentColorFor() 能正确解析，但这里仍显式给 contentColor 以免回退。
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            shadowElevation = 10.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                PolygonLoadingIndicator(
                    running = busy,
                    color = if (busy) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (canRetry) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (canRetry) {
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = onRetry) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(R.string.session_retry))
                    }
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
