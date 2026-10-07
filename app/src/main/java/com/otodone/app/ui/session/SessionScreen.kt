package com.otodone.app.ui.session

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.WindowManager
import android.widget.Toast
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
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
import com.otodone.app.rdp.RdpNative
import com.otodone.app.rdp.RdpState
import com.otodone.app.rdp.RdpSurfaceView
import com.otodone.app.ui.hosts.HostListViewModel

@Composable
fun SessionRoute(
    hostId: String,
    sessionOrientation: SessionOrientation,
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

    // 全屏沉浸 + 按设置决定屏幕方向 + 内容延伸到异形屏（刘海/挖孔）区域
    SessionWindowEffect(sessionOrientation)

    // 从后台切回前台自动重连：后台期间会话常常已断，回来看不该停在"连接失败"。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.reconnectIfNeeded()
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
 * - 松手点在按钮内 → [onClick]（键盘浮标弹出输入法）；
 * - 松手点在按钮外 → [onReleaseOutside]（右键浮标把这根手指补成一次右键点击）。
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
    onReleaseOutside: (Offset) -> Unit = {},
) {
    var pressed by remember { mutableStateOf(false) }
    var origin by remember { mutableStateOf(Offset.Zero) }
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
                val buttonSizePx = 44.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    pressed = true
                    onPressedChange(true)
                    var upRoot: Offset? = null
                    while (true) {
                        val event = awaitPointerEvent()
                        // 无关指针的事件忽略掉即可，不能因此解除按下/待发状态。
                        val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                        change.consume()
                        if (!change.pressed) {
                            upRoot = origin + change.position
                            break
                        }
                    }
                    pressed = false
                    onPressedChange(false)
                    // 手势被取消时 upRoot 为空：只解除状态，绝不补发点击。
                    val release = upRoot ?: return@awaitEachGesture
                    val local = release - origin
                    val inside = local.x in 0f..buttonSizePx && local.y in 0f..buttonSizePx
                    if (inside) onClick() else onReleaseOutside(release)
                }
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

/**
 * 会话期间：隐藏系统栏（沉浸）、允许内容画进异形屏区域、按设置应用屏幕方向；
 * 离开会话时把方向/系统栏/异形屏模式全部还原，主页面不会被留在横屏。
 *
 * 方向用 [LaunchedEffect] 单独应用：[orientation] 变化时立即重新下发，会话中切换设置也能实时生效；
 * 进入前的方向只在首次组合时记录一次，退出时原样还原（不被中间的切换污染）。
 */
@Composable
private fun SessionWindowEffect(orientation: SessionOrientation) {
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

@Composable
private fun StatusOverlay(state: RdpState, onRetry: () -> Unit) {
    val showOverlay = state is RdpState.Connecting ||
        state is RdpState.Failed ||
        state is RdpState.Disconnected ||
        state is RdpState.Idle
    if (!showOverlay) return

    val title = when (state) {
        is RdpState.Idle -> stringResource(R.string.session_preparing)
        is RdpState.Connecting -> stringResource(R.string.session_connecting, state.endpoint)
        is RdpState.Failed -> stringResource(R.string.session_failed_title)
        is RdpState.Disconnected -> stringResource(R.string.session_disconnected_title)
        is RdpState.Connected -> ""
    }
    val detail = when (state) {
        is RdpState.Failed -> state.message
        is RdpState.Disconnected -> state.message
        else -> ""
    }
    val busy = state is RdpState.Connecting || state is RdpState.Idle
    val canRetry = state is RdpState.Failed || state is RdpState.Disconnected

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            // color 带 alpha 后 contentColorFor() 匹配不到任何 scheme 颜色，
            // 会回退成外层内容色（深色模式下就是黑字）。必须显式给 contentColor。
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = MaterialTheme.shapes.large,
            tonalElevation = 4.dp,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(14.dp))
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                if (detail.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    RdpNative.info.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (RdpNative.info.error != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.session_native_error, RdpNative.info.error.orEmpty()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
                if (canRetry) {
                    Spacer(Modifier.height(16.dp))
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

private fun statusLine(state: RdpState, host: RdpHost): String = when (state) {
    is RdpState.Idle -> host.endpoint
    is RdpState.Connecting -> host.endpoint
    is RdpState.Connected -> "${state.width}×${state.height} · FreeRDP"
    is RdpState.Failed -> host.endpoint
    is RdpState.Disconnected -> host.endpoint
}
