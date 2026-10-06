package com.otodone.app.rdp

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.freerdp.freerdpcore.application.GlobalApp
import com.freerdp.freerdpcore.domain.BookmarkBase
import com.freerdp.freerdpcore.services.LibFreeRDP
import com.otodone.app.data.RdpHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** RDP 会话状态。 */
sealed interface RdpState {
    data object Idle : RdpState
    data class Connecting(val endpoint: String) : RdpState
    data class Connected(val width: Int, val height: Int) : RdpState
    data class Failed(val endpoint: String, val message: String) : RdpState
    data class Disconnected(val endpoint: String, val message: String) : RdpState
}

/**
 * 一次 RDP 会话的引擎封装。
 *
 * 实现 FreeRDP 的 [LibFreeRDP.UIEventListener]（图形回调，native 线程）和
 * [GlobalApp.SessionEventListener]（连接事件，主线程派发）。公开的 [state]、
 * [bitmap]、[frameTick] 全部是线程安全的 StateFlow，可直接被 Compose 订阅。
 */
class RdpSession(private val appContext: Context) :
    LibFreeRDP.UIEventListener,
    GlobalApp.SessionEventListener,
    RdpSurfaceView.Listener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<RdpState>(RdpState.Idle)
    val state: StateFlow<RdpState> = _state.asStateFlow()

    private val _bitmap = MutableStateFlow<Bitmap?>(null)
    val bitmap: StateFlow<Bitmap?> = _bitmap.asStateFlow()

    private val _frameTick = MutableStateFlow(0L)
    val frameTick: StateFlow<Long> = _frameTick.asStateFlow()

    @Volatile private var instance: Long = 0L
    @Volatile private var bitmapRef: Bitmap? = null
    @Volatile private var closed = false
    @Volatile private var host: RdpHost? = null
    @Volatile private var lastWidth = 0
    @Volatile private var lastHeight = 0

    private val endpoint: String get() = host?.endpoint.orEmpty()

    fun connect(host: RdpHost, width: Int, height: Int) {
        if (instance != 0L) return
        this.host = host
        lastWidth = width
        lastHeight = height
        _state.value = RdpState.Connecting(host.endpoint)
        scope.launch {
            try {
                GlobalApp.install()
                val bookmark = buildBookmark(host, width, height)
                val session = GlobalApp.createSession(bookmark, appContext)
                instance = session.instance
                session.setUIEventListener(this@RdpSession)
                GlobalApp.registerSessionListener(instance, this@RdpSession)
                if (!LibFreeRDP.setConnectionInfo(appContext, instance, bookmark)) {
                    fail("无法解析 RDP 连接参数")
                    return@launch
                }
                if (!LibFreeRDP.connect(instance)) {
                    fail("无法启动 RDP 连接线程")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "connect failed", t)
                fail("FreeRDP 初始化失败：${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    fun close() {
        closed = true
        val inst = instance
        if (inst != 0L) {
            runCatching { LibFreeRDP.disconnect(inst) }
        }
        freeLater(0)
    }

    // ---- 输入：RdpSurfaceView.Listener ----

    override fun onPointer(x: Int, y: Int, flags: Int) {
        val inst = instance
        if (inst == 0L) return
        runCatching { LibFreeRDP.sendCursorEvent(inst, x, y, flags) }
    }

    override fun onVirtualKey(virtualKey: Int, down: Boolean) {
        val inst = instance
        if (inst == 0L) return
        runCatching { LibFreeRDP.sendKeyEvent(inst, virtualKey, down) }
    }

    override fun onUnicode(codePoint: Int) {
        val inst = instance
        if (inst == 0L) return

        // ASCII 优先走虚拟键（与真实键盘一致，兼容性最好）；
        // 中文等非 ASCII 才用 unicode 注入通道。
        val virtualKey = RdpKeys.asciiToVirtualKey(codePoint)
        if (virtualKey != null) {
            runCatching {
                LibFreeRDP.sendKeyEvent(inst, virtualKey, true)
                LibFreeRDP.sendKeyEvent(inst, virtualKey, false)
            }
            return
        }

        runCatching {
            if (LibFreeRDP.isUnicodeInputSupported(inst)) {
                LibFreeRDP.sendUnicodeKeyEvent(inst, codePoint, true)
                LibFreeRDP.sendUnicodeKeyEvent(inst, codePoint, false)
            } else {
                Log.w(TAG, "Remote does not support unicode input, dropped: $codePoint")
            }
        }
    }

    override fun onText(text: String) {
        val inst = instance
        if (inst == 0L || text.isEmpty()) return

        // 纯 ASCII：逐字符走虚拟键，最可靠
        if (text.all { it.code < 0x80 }) {
            text.forEach { onUnicode(it.code) }
            return
        }

        // 含中文等非 ASCII：GNOME 远程桌面的 unicode 注入通道传不过去，
        // 改用「写入远端剪贴板 + Ctrl+V」。后台线程是为了给剪贴板通道一点时间。
        Thread {
            runCatching {
                LibFreeRDP.sendClipboardData(inst, text)
                Thread.sleep(150)
                LibFreeRDP.sendKeyEvent(inst, RdpKeys.VK_CONTROL, true)
                LibFreeRDP.sendKeyEvent(inst, RdpKeys.VK_V, true)
                LibFreeRDP.sendKeyEvent(inst, RdpKeys.VK_V, false)
                LibFreeRDP.sendKeyEvent(inst, RdpKeys.VK_CONTROL, false)
            }.onFailure { Log.w(TAG, "clipboard paste failed", it) }
        }.start()
    }

    // ---- 连接事件（GlobalApp 派发到主线程）----

    override fun onConnectionSuccess() {
        Log.i(TAG, "RDP connection succeeded")
        if (lastWidth > 0 && lastHeight > 0) {
            _state.value = RdpState.Connected(lastWidth, lastHeight)
        }
    }

    override fun onConnectionFailure() {
        fail(lastError().ifBlank { "连接失败：目标未响应或端口不可达" })
    }

    override fun onDisconnected() {
        if (_state.value is RdpState.Failed) return
        val message = lastError().ifBlank { "会话已断开" }
        _state.value = RdpState.Disconnected(endpoint, message)
        freeLater(500)
    }

    // ---- 图形事件（native 线程）----

    override fun OnSettingsChanged(width: Int, height: Int, bpp: Int) {
        val bmp = createBitmap(width, height, bpp) ?: return
        lastWidth = width
        lastHeight = height
        bitmapRef = bmp
        _bitmap.value = bmp
        _state.value = RdpState.Connected(width, height)
        bumpFrame()
    }

    override fun OnGraphicsUpdate(x: Int, y: Int, width: Int, height: Int) {
        val inst = instance
        val bmp = bitmapRef
        if (inst == 0L || bmp == null) return
        runCatching { LibFreeRDP.updateGraphics(inst, bmp, x, y, width, height) }
        bumpFrame()
    }

    override fun OnGraphicsResize(width: Int, height: Int, bpp: Int) {
        val bmp = createBitmap(width, height, bpp) ?: return
        lastWidth = width
        lastHeight = height
        bitmapRef = bmp
        _bitmap.value = bmp
        bumpFrame()
    }

    override fun OnRemoteClipboardChanged(data: String?) = Unit

    override fun OnRemoteClipboardImageChanged(data: ByteArray?) = Unit

    override fun OnPointerSet(pixels: IntArray?, width: Int, height: Int, hotX: Int, hotY: Int) = Unit

    override fun OnPointerSetNull() = Unit

    override fun OnPointerSetDefault() = Unit

    override fun OnRailWindowUpdate(windowId: Long, width: Int, height: Int, pixels: IntArray?) = Unit

    override fun OnRailWindowMove(windowId: Long, x: Int, y: Int, w: Int, h: Int) = Unit

    override fun OnRailWindowHide(windowId: Long) = Unit

    override fun OnRailWindowDestroy(windowId: Long) = Unit

    override fun OnRailSessionEnd() = Unit

    override fun OnRailMonitoredDesktop(windowIds: LongArray?, activeWindowId: Long) = Unit

    // ---- 认证 / 证书：命令行已带 /u /d /p 与 /cert:ignore，这里兜底 ----

    override fun OnAuthenticate(
        username: StringBuilder,
        domain: StringBuilder,
        password: StringBuilder,
    ): Boolean {
        val current = host ?: return false
        if (username.length == 0) username.append(current.username)
        if (domain.length == 0) domain.append(current.domain)
        if (password.length == 0) password.append(current.password)
        return true
    }

    override fun OnGatewayAuthenticate(
        username: StringBuilder,
        domain: StringBuilder,
        password: StringBuilder,
    ): Boolean = OnAuthenticate(username, domain, password)

    override fun OnVerifiyCertificateEx(
        host: String?,
        port: Long,
        commonName: String?,
        subject: String?,
        issuer: String?,
        fingerprint: String?,
        flags: Long,
    ): Int {
        Log.w(TAG, "Certificate for $host:$port accepted (fingerprint=$fingerprint flags=$flags)")
        return 0
    }

    override fun OnVerifyChangedCertificateEx(
        host: String?,
        port: Long,
        commonName: String?,
        subject: String?,
        issuer: String?,
        fingerprint: String?,
        oldSubject: String?,
        oldIssuer: String?,
        oldFingerprint: String?,
        flags: Long,
    ): Int {
        Log.w(TAG, "Changed certificate for $host:$port accepted (flags=$flags)")
        return 0
    }

    override fun OnExperimentalFeature(feature: Int): Boolean = true

    // ---- 内部 ----

    private fun fail(message: String) {
        Log.w(TAG, "RDP failure: $message")
        _state.value = RdpState.Failed(endpoint, message)
        freeLater(1000)
    }

    private fun lastError(): String {
        val inst = instance
        if (inst == 0L) return ""
        return runCatching { LibFreeRDP.getLastError(inst).orEmpty() }.getOrDefault("")
    }

    /**
     * 释放 native 实例。freeInstance() 在会话已连接时会阻塞等待断开，
     * 因此必须放在 IO 线程；同时留一点时间让 native 连接线程收尾，避免
     * 在连接线程仍在运行时 freerdp_free 造成 use-after-free。
     */
    private fun freeLater(delayMs: Long) {
        val inst = instance
        if (inst == 0L) return
        instance = 0L
        GlobalApp.unregisterSessionListener(inst)
        scope.launch {
            if (delayMs > 0) delay(delayMs)
            runCatching { GlobalApp.freeSession(inst) }
                .onFailure { Log.e(TAG, "freeSession failed", it) }
        }
    }

    private fun bumpFrame() {
        _frameTick.update { it + 1 }
    }

    private fun createBitmap(width: Int, height: Int, bpp: Int): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val config = if (bpp > 16) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
        return runCatching { Bitmap.createBitmap(width, height, config) }
            .onFailure { Log.e(TAG, "createBitmap($width, $height, $bpp) failed", it) }
            .getOrNull()
    }

    private fun buildBookmark(host: RdpHost, width: Int, height: Int): BookmarkBase {
        val bookmark = BookmarkBase()
        bookmark.setType(BookmarkBase.TYPE_MANUAL)
        bookmark.setLabel(host.displayName)
        bookmark.setHostname(host.address)
        bookmark.setPort(host.port)
        bookmark.setUsername(host.username)
        bookmark.setDomain(host.domain)
        bookmark.setPassword(host.password)

        val screen = bookmark.getActiveScreenSettings()
        screen.setColors(32)
        screen.setResolution("custom", width.coerceAtLeast(320), height.coerceAtLeast(240))

        // 必须启用 GFX：gnome-remote-desktop 50 会检查客户端是否广告了 RDP 图形管线，
        // 没有就直接断开（日志：Client did not advertise support for the Graphics Pipeline）。
        // H.264（AVC444）需要 native 侧编入 OPENH264/FFMPEG，未编入时只开 GFX 通道本身。
        val perf = bookmark.getActivePerformanceFlags()
        perf.setRemoteFX(false)
        perf.setGfx(true)
        perf.setH264(false)
        perf.setWallpaper(false)
        perf.setTheming(false)
        perf.setFullWindowDrag(false)
        perf.setMenuAnimations(false)
        perf.setFontSmoothing(false)
        perf.setDesktopComposition(false)
        return bookmark
    }

    companion object {
        private const val TAG = "RdpSession"
    }
}
