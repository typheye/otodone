package com.otodone.app.rdp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.freerdp.freerdpcore.application.GlobalApp
import com.freerdp.freerdpcore.domain.BookmarkBase
import com.freerdp.freerdpcore.services.LibFreeRDP
import com.otodone.app.data.PasteShortcut
import com.otodone.app.data.RdpHost
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
class RdpSession(
    private val appContext: Context,
    /**
     * 当前「粘贴快捷键」设置。用 StateFlow 而非快照值，onText() 发送时读最新值，
     * 因此会话运行中改设置也能立即生效。
     */
    private val pasteShortcut: StateFlow<PasteShortcut> = MutableStateFlow(PasteShortcut.CTRL_SHIFT_V),
) :
    LibFreeRDP.UIEventListener,
    LibFreeRDP.ClipboardSentListener,
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

    /** [bitmapRef] 属于哪个 native 实例；0 表示当前没有可用画面。 */
    @Volatile private var bitmapInstance = 0L

    /**
     * 连接生命周期串行化：connect / reconnect / close / detachAndRelease 共用这把锁，
     * 保证同一时刻只可能有一个 native 实例处于「登记中或存活」状态。
     * 持锁期间只做轻量工作（[LibFreeRDP.connect] 仅创建 native 连接线程后立即返回）。
     */
    private val connectLock = Any()

    /** 释放去重：同一个 native 实例只允许入队释放一次，避免 double free。 */
    private val releaseLock = Any()
    private val pendingRelease = mutableSetOf<Long>()

    /** 释放串行化：同一时刻只释放一个 native 实例，绝不重入。 */
    private val releaseMutex = Mutex()

    /** 连接尝试序号：reconnect() 递增它，让任何在途的旧尝试在登记实例前作废。 */
    private var connectAttempt = 0

    /** 是否有一次连接正在建立（已 setConnecting 但 native 实例尚未登记）。 */
    private var connecting = false

    // cliprdr 握手等待：native 在服务端成功取走剪贴板数据后回调 OnClipboardDataSent。
    @Volatile private var clipboardSentLatch: CountDownLatch? = null
    @Volatile private var clipboardSentInstance: Long = 0L

    private val endpoint: String get() = host?.endpoint.orEmpty()

    /**
     * 发起一次连接；已有会话、或已有连接在途时直接忽略，重复调用幂等。
     */
    fun connect(host: RdpHost, width: Int, height: Int) {
        val attempt: Int
        synchronized(connectLock) {
            if (instance != 0L || connecting) return
            connecting = true
            attempt = ++connectAttempt
        }
        startConnect(attempt, host, width, height)
    }

    /**
     * 强制重建会话：作废在途连接、释放已有 native 实例，再发起全新连接。
     *
     * 与 [close] 不同，这里**不**设置 [closed]：同一个 [RdpSession] 重连后必须继续能走
     * 中文粘贴，而 close() 会把后续 onText() 的粘贴线程永久拦下。
     */
    fun reconnect(host: RdpHost, width: Int, height: Int) {
        synchronized(connectLock) {
            ++connectAttempt // 让任何在途的旧 connect() 在登记实例前作废
            connecting = false
        }
        // 关键：这里只把旧实例「标记作废 + 交给串行释放」，绝不原地 free。
        // free 必须等 native 连接线程真正结束（见 releaseInstance）。
        detachAndRelease()
        connect(host, width, height)
    }

    private fun startConnect(attempt: Int, host: RdpHost, width: Int, height: Int) {
        this.host = host
        lastWidth = width
        lastHeight = height
        _state.value = RdpState.Connecting(host.endpoint)
        scope.launch {
            var orphanInstance = 0L
            try {
                GlobalApp.install()
                val bookmark = buildBookmark(host, width, height)
                val created = GlobalApp.createSession(bookmark, appContext)
                val inst = created.instance
                // 登记实例到真正发起连接全程持锁：reconnect()/close() 的清理也走同一把锁，
                // 不会在 setup 中途把实例释放掉造成 native use-after-free。
                val accepted = synchronized(connectLock) {
                    if (attempt != connectAttempt) {
                        false
                    } else {
                        instance = inst
                        // 每个实例一个带 id 的转发器：native 回调不带实例号，若所有实例共用
                        // this，旧实例收尾阶段的图形/连接回调会被套到新实例上（例如对一个
                        // 尚未 gdi_init 的新 context 调 updateGraphics），是崩溃来源之一。
                        created.setUIEventListener(InstanceUIListener(inst))
                        LibFreeRDP.setClipboardSentListener(this@RdpSession)
                        GlobalApp.registerSessionListener(inst, InstanceSessionListener(inst))
                        if (!LibFreeRDP.setConnectionInfo(appContext, inst, bookmark)) {
                            fail("无法解析 RDP 连接参数")
                        } else if (!LibFreeRDP.connect(inst)) {
                            fail("无法启动 RDP 连接线程")
                        }
                        true
                    }
                }
                if (!accepted) orphanInstance = inst
            } catch (t: Throwable) {
                Log.e(TAG, "connect failed", t)
                fail("FreeRDP 初始化失败：${t.message ?: t.javaClass.simpleName}")
            } finally {
                synchronized(connectLock) {
                    if (attempt == connectAttempt) connecting = false
                }
                if (orphanInstance != 0L) {
                    // 已被更新的重连取代：释放这个刚创建、尚未注册监听器的实例。
                    runCatching { GlobalApp.freeSession(orphanInstance) }
                        .onFailure { Log.e(TAG, "freeSession(orphan) failed", it) }
                }
            }
        }
    }

    fun close() {
        closed = true
        synchronized(connectLock) {
            ++connectAttempt // 页面销毁后不再接受任何在途连接结果
            connecting = false
        }
        detachAndRelease()
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

        Log.i(TAG, "onText(non-ascii) len=${text.length} text=$text")

        // 1) 先写进 Android 系统剪贴板。FreeRDP 的 cliprdr 在服务端发来
        //    CB_FORMAT_DATA_REQUEST 时最终要从这里取数据；官方客户端的系统剪贴板
        //    监听在裁剪 vendored 代码时被删掉了，所以这里手动补上这一步。
        val clipOk = runCatching {
            val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("otodone", text))
            cm != null
        }.getOrElse {
            Log.w(TAG, "set system clipboard failed", it)
            false
        }
        Log.i(TAG, "system clipboard set=$clipOk")

        // 2) 登记握手等待，然后向远端剪贴板通道广播格式列表。
        //    必须在 sendClipboardData 之前登记：回调来自 native 线程，若它抢在登记前到达，
        //    这一次就得白等超时。
        val latch = CountDownLatch(1)
        clipboardSentInstance = inst
        clipboardSentLatch = latch
        val pushed = runCatching { LibFreeRDP.sendClipboardData(inst, text) }.getOrDefault(false)
        Log.i(TAG, "sendClipboardData=$pushed")

        // 3) 等服务端来取剪贴板数据（CB_FORMAT_DATA_REQUEST）；native 在成功回送后回调
        //    OnClipboardDataSent。收到回调再发粘贴快捷键，取代原先写死的 500ms。
        //    超时兜底：服务端没来取也不能让中文输入卡死，超时后照发粘贴快捷键。
        Thread {
            val signaled = try {
                latch.await(CLIPBOARD_SENT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                false
            }
            if (clipboardSentLatch === latch) clipboardSentLatch = null
            Log.i(TAG, if (signaled) "clipboard-data-sent(callback)" else "clipboard-data-sent timeout")

            if (closed || instance != inst) return@Thread
            val ok = runCatching {
                sendPasteShortcut(inst)
                true
            }.getOrElse {
                Log.w(TAG, "paste shortcut failed", it)
                false
            }
            Log.i(TAG, "paste shortcut sent=$ok")
        }.start()
    }

    /** native 线程回调：服务端已成功取走剪贴板数据（format data response 已回送）。 */
    override fun OnClipboardDataSent(instanceId: Long) {
        if (instanceId != clipboardSentInstance) return
        clipboardSentLatch?.countDown()
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
        detachAndRelease()
    }

    // ---- 图形事件（native 线程）----

    override fun OnSettingsChanged(width: Int, height: Int, bpp: Int) =
        onSettingsChanged(instance, width, height, bpp)

    override fun OnGraphicsUpdate(x: Int, y: Int, width: Int, height: Int) {
        val bmp = bitmapRef
        val inst = instance
        // 画面必须同时属于当前实例，否则就是旧实例残留回调，直接丢弃。
        if (inst == 0L || bmp == null || bitmapInstance != inst) return
        runCatching { LibFreeRDP.updateGraphics(inst, bmp, x, y, width, height) }
        bumpFrame()
    }

    override fun OnGraphicsResize(width: Int, height: Int, bpp: Int) =
        onGraphicsResize(instance, width, height, bpp)

    private fun onSettingsChanged(id: Long, width: Int, height: Int, bpp: Int) {
        val bmp = createBitmap(width, height, bpp) ?: return
        lastWidth = width
        lastHeight = height
        bitmapRef = bmp
        bitmapInstance = id
        _bitmap.value = bmp
        _state.value = RdpState.Connected(width, height)
        bumpFrame()
    }

    private fun onGraphicsResize(id: Long, width: Int, height: Int, bpp: Int) {
        val bmp = createBitmap(width, height, bpp) ?: return
        lastWidth = width
        lastHeight = height
        bitmapRef = bmp
        bitmapInstance = id
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

    /**
     * 按当前设置发送粘贴组合键。
     *
     * 修饰键必须用 VK_LCONTROL / VK_LSHIFT：WinPR 的扫描码表里查不到
     * VK_CONTROL / VK_SHIFT 这两个通用码，会静默返回 0（这是踩过的坑，别改回去）。
     */
    private fun sendPasteShortcut(inst: Long) {
        val shortcut = pasteShortcut.value
        val keys: List<Pair<Int, Boolean>> = when (shortcut) {
            // Linux 终端（gnome-terminal 等）：Ctrl+V 在终端里无效，必须 Ctrl+Shift+V
            PasteShortcut.CTRL_SHIFT_V -> listOf(
                RdpKeys.VK_LCONTROL to true,
                RdpKeys.VK_LSHIFT to true,
                RdpKeys.VK_V to true,
                RdpKeys.VK_V to false,
                RdpKeys.VK_LSHIFT to false,
                RdpKeys.VK_LCONTROL to false,
            )
            // Windows / 大多数 GTK 应用
            PasteShortcut.CTRL_V -> listOf(
                RdpKeys.VK_LCONTROL to true,
                RdpKeys.VK_V to true,
                RdpKeys.VK_V to false,
                RdpKeys.VK_LCONTROL to false,
            )
            // 经典 X11
            PasteShortcut.SHIFT_INSERT -> listOf(
                RdpKeys.VK_LSHIFT to true,
                RdpKeys.VK_INSERT to true,
                RdpKeys.VK_INSERT to false,
                RdpKeys.VK_LSHIFT to false,
            )
        }
        Log.i(TAG, "sending paste shortcut: ${shortcut.name}")
        for ((virtualKey, down) in keys) {
            LibFreeRDP.sendKeyEvent(inst, virtualKey, down)
        }
    }

    private fun fail(message: String) {
        Log.w(TAG, "RDP failure: $message")
        _state.value = RdpState.Failed(endpoint, message)
        detachAndRelease()
    }

    private fun lastError(): String {
        val inst = instance
        if (inst == 0L) return ""
        return runCatching { LibFreeRDP.getLastError(inst).orEmpty() }.getOrDefault("")
    }

    /**
     * 把当前 native 实例标记作废（清空 [instance]、丢弃画面），再交给后台串行释放。
     *
     * 清零之后：旧实例再回调 [InstanceUIListener] / [InstanceSessionListener] 都会因为
     * 实例号不匹配被丢弃，输入事件也因 [instance] 为 0 直接返回；旧 SessionState 仍留在
     * GlobalApp 里直到真正释放，但已经影响不到新会话。
     */
    private fun detachAndRelease() {
        val inst = synchronized(connectLock) {
            val current = instance
            instance = 0L
            connecting = false
            bitmapRef = null
            bitmapInstance = 0L
            current
        }
        _bitmap.value = null
        if (inst == 0L) return
        // 裁剪版 FreeRDP 只有这一处全局剪贴板回调，摘掉后旧实例不会再触发粘贴。
        LibFreeRDP.setClipboardSentListener(null)
        GlobalApp.unregisterSessionListener(inst)
        releaseInstance(inst)
    }

    /**
     * 串行释放一个 native 实例（在 IO 线程上）。
     *
     * 为什么不会 use-after-free：
     * [LibFreeRDP.freeInstance] 在实例仍登记为「存活/正在连接」时会先 freerdp_disconnect，
     * 再阻塞等待 native 线程报告 OnConnectionFailure/OnDisconnected；只有确认连接线程已走到
     * 终点，才 freerdp_free（连接期未登记的漏洞已在 LibFreeRDP.connect 里补上）。
     * 这里再保证两点：1) 同一实例只入队一次（[pendingRelease]）杜绝重复 free；
     * 2) 释放串行进行（[releaseMutex]），绝不会在释放 A 的同时又释放/复用 A。
     */
    private fun releaseInstance(inst: Long) {
        synchronized(releaseLock) {
            if (!pendingRelease.add(inst)) return
        }
        scope.launch {
            releaseMutex.withLock {
                runCatching { GlobalApp.freeSession(inst) }
                    .onFailure { Log.e(TAG, "freeSession failed", it) }
            }
            synchronized(releaseLock) { pendingRelease.remove(inst) }
        }
    }

    /**
     * 绑定到单个 native 实例的 UI 回调转发器。
     *
     * native 图形/连接回调经 JNI 只把实例号传到 Java，而 [LibFreeRDP.UIEventListener]
     * 接口本身不带实例号；若所有实例共用 [RdpSession] 一个监听器，旧实例收尾期间的
     * OnGraphicsUpdate 会读到已指向新实例的 [instance]/[bitmapRef]（旧线程、新实例），
     * 对一个 gdi 尚未初始化的新 context 调 updateGraphics，直接空指针/越界崩溃。
     * 转发器持有创建它的实例号，一旦不再是当前实例就丢弃全部回调；图形回调还把实例号
     * 显式传进内部方法，消除「检查后字段又变了」的竞态。
     */
    private inner class InstanceUIListener(private val id: Long) :
        LibFreeRDP.UIEventListener by this@RdpSession {

        override fun OnSettingsChanged(width: Int, height: Int, bpp: Int) {
            if (instance == id) onSettingsChanged(id, width, height, bpp)
        }

        override fun OnGraphicsResize(width: Int, height: Int, bpp: Int) {
            if (instance == id) onGraphicsResize(id, width, height, bpp)
        }

        override fun OnGraphicsUpdate(x: Int, y: Int, width: Int, height: Int) {
            val bmp = bitmapRef
            if (instance != id || bitmapInstance != id || bmp == null) return
            runCatching { LibFreeRDP.updateGraphics(id, bmp, x, y, width, height) }
            bumpFrame()
        }

        override fun OnAuthenticate(
            username: StringBuilder,
            domain: StringBuilder,
            password: StringBuilder,
        ): Boolean = if (instance == id) {
            this@RdpSession.OnAuthenticate(username, domain, password)
        } else {
            false
        }

        override fun OnGatewayAuthenticate(
            username: StringBuilder,
            domain: StringBuilder,
            password: StringBuilder,
        ): Boolean = if (instance == id) {
            this@RdpSession.OnAuthenticate(username, domain, password)
        } else {
            false
        }
    }

    /** 绑定到单个 native 实例的连接事件转发器：旧实例的事件不再改动新会话的状态。 */
    private inner class InstanceSessionListener(private val id: Long) : GlobalApp.SessionEventListener {
        override fun onConnectionSuccess() {
            if (instance == id) this@RdpSession.onConnectionSuccess()
        }

        override fun onConnectionFailure() {
            if (instance == id) this@RdpSession.onConnectionFailure()
        }

        override fun onDisconnected() {
            if (instance == id) this@RdpSession.onDisconnected()
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

        /** cliprdr 握手回调的最长等待时间；超时后仍发粘贴快捷键，避免中文输入卡死。 */
        private const val CLIPBOARD_SENT_TIMEOUT_MS = 3000L
    }
}
