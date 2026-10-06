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
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.otodone.app.R
import com.otodone.app.data.RdpHost
import com.otodone.app.rdp.RdpNative
import com.otodone.app.rdp.RdpState
import com.otodone.app.rdp.RdpSurfaceView
import com.otodone.app.ui.hosts.HostListViewModel

@Composable
fun SessionRoute(
    hostId: String,
    onBack: () -> Unit,
    hostListViewModel: HostListViewModel = viewModel(factory = HostListViewModel.Factory),
    sessionViewModel: RdpSessionViewModel = viewModel(factory = RdpSessionViewModel.Factory),
) {
    val hosts by hostListViewModel.hosts.collectAsState()
    val host = hosts.firstOrNull { it.id == hostId }
    if (host == null) {
        MissingHostScreen(onBack = onBack)
    } else {
        SessionScreen(host = host, viewModel = sessionViewModel, onBack = onBack)
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

    // 全屏沉浸 + 默认横屏 + 内容延伸到异形屏（刘海/挖孔）区域
    SessionWindowEffect()

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
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    RdpSurfaceView(ctx).also { view ->
                        view.listener = viewModel.inputListener
                        surfaceView = view
                    }
                },
                update = { view ->
                    view.listener = viewModel.inputListener
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

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(end = 14.dp, bottom = 14.dp)
                .size(44.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable { surfaceView?.showKeyboard() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Keyboard,
                contentDescription = stringResource(R.string.session_keyboard),
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * 会话期间：隐藏系统栏（沉浸）、允许内容画进异形屏区域、默认横屏；离开时全部还原。
 */
@Composable
private fun SessionWindowEffect() {
    val view = LocalView.current
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())

        val previousOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

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
                previousOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            if (window != null && previousCutout != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = previousCutout
                }
            }
        }
    }
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
