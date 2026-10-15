package com.otodone.app.ui.session

import android.app.Application
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.otodone.app.data.PasteShortcut
import com.otodone.app.data.RdpHost
import com.otodone.app.data.ThemeSettingsRepository
import com.otodone.app.rdp.RdpSession
import com.otodone.app.rdp.RdpState
import com.otodone.app.rdp.RdpSurfaceView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 快捷粘贴（长按会话页键盘浮标）的结果。UI 据此决定弹哪句 Toast：
 * [DEBOUNCED] 不弹提示，避免连点/连按时刷屏。
 */
enum class QuickPasteResult {
    /** 已发起粘贴（真正的粘贴在后台异步完成）。 */
    PASTED,

    /** 剪贴板为空或不是文本。 */
    EMPTY_CLIPBOARD,

    /** 当前没有可用的远端会话（未连接/已断开/原生库不可用）。 */
    NO_SESSION,

    /** 距上次触发不足 [RdpSessionViewModel.QUICK_PASTE_DEBOUNCE_MS]，本次忽略。 */
    DEBOUNCED,
}

/** 会话页 ViewModel：持有一次 [RdpSession]，UI 只读 StateFlow。 */
class RdpSessionViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsRepository = ThemeSettingsRepository(app)

    /**
     * 当前的粘贴快捷键。会话运行中改设置也能立刻生效：
     * onText() 发送粘贴时读取的是这个 StateFlow 的最新值，无需重启会话。
     */
    private val pasteShortcut: StateFlow<PasteShortcut> = settingsRepository.settings
        .map { it.pasteShortcut }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PasteShortcut.CTRL_SHIFT_V)

    private val session = RdpSession(app, pasteShortcut)

    private val clipboardManager =
        app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    /** 上次真正尝试快捷粘贴的时间（elapsedRealtime），用于防抖。 */
    private var lastQuickPasteAtMs = 0L

    val state = session.state
    val bitmap = session.bitmap
    val frameTick = session.frameTick
    val inputListener: RdpSurfaceView.Listener = session

    private var startedHostId: String? = null

    // 记住最后一次连接参数，供「切回前台自动重连」使用
    private var lastHost: RdpHost? = null
    private var lastWidth = 0
    private var lastHeight = 0

    /** 页面是否处于前台（RESUMED）：后台发生的失败不自动重连，等回前台再处理。 */
    @Volatile private var isForeground = false

    /** 重连判定与退避策略（纯逻辑，见 ReconnectPolicy，已有单元测试覆盖）。 */
    private val reconnectPolicy = ReconnectPolicy()

    /** 是否已发起一次重连但尚未拿到结果（Connected/Failed/Disconnected）。 */
    private var reconnectInFlight = false

    /** 已排期但还没执行的自动重连；新请求先取消旧的，保证同一时刻只有一个在途。 */
    private var autoReconnectJob: Job? = null

    init {
        // 关键修复：失败是事件，不再依赖 ON_RESUME 那一刻的状态快照。
        // 只要在前台看到 Failed/Disconnected，就按退避策略自动重连；
        // 这样即使失败状态比 ON_RESUME 晚到，也一定会被兜住。
        viewModelScope.launch {
            session.state.collect { current ->
                when (current) {
                    is RdpState.Connected -> {
                        reconnectInFlight = false
                        reconnectPolicy.onConnected()
                    }

                    is RdpState.Failed, is RdpState.Disconnected -> {
                        reconnectInFlight = false
                        scheduleAutoReconnect()
                    }

                    else -> Unit
                }
            }
        }
    }

    fun connect(host: RdpHost, width: Int, height: Int) {
        lastHost = host
        lastWidth = width
        lastHeight = height
        if (startedHostId == host.id) return
        startedHostId = host.id
        session.connect(host, width, height)
    }

    /** 用户手动重试：重置退避计数，立即重建会话。 */
    fun retry(host: RdpHost, width: Int, height: Int) {
        lastHost = host
        lastWidth = width
        lastHeight = height
        reconnectPolicy.onManualRetry()
        reconnectNow(host, width, height)
    }

    /**
     * 快捷粘贴：读手机系统剪贴板 → 交给 [RdpSession.pasteViaClipboard] 粘到远端。
     *
     * 防抖：距上次触发不足 [QUICK_PASTE_DEBOUNCE_MS] 直接返回 [QuickPasteResult.DEBOUNCED]，
     * 既拦重复粘贴也拦重复 Toast。时间戳在读取剪贴板之前更新，所以「剪贴板为空」时反复长按
     * 同样被这个窗口吸收，不会刷屏。
     *
     * 必须在主线程调用（Compose 回调即可）：Android 10+ 只有持有焦点的前台应用才能读剪贴板。
     */
    fun quickPasteFromClipboard(): QuickPasteResult {
        val now = SystemClock.elapsedRealtime()
        val sinceLast = now - lastQuickPasteAtMs
        if (sinceLast < QUICK_PASTE_DEBOUNCE_MS) {
            Log.i(TAG, "quick paste ignored: debounced (${sinceLast}ms < ${QUICK_PASTE_DEBOUNCE_MS}ms)")
            return QuickPasteResult.DEBOUNCED
        }
        lastQuickPasteAtMs = now

        val text = readClipboardText()
        if (text.isNullOrEmpty()) {
            Log.i(TAG, "quick paste ignored: clipboard empty or non-text")
            return QuickPasteResult.EMPTY_CLIPBOARD
        }
        if (!session.pasteViaClipboard(text)) {
            Log.i(TAG, "quick paste ignored: no active session")
            return QuickPasteResult.NO_SESSION
        }
        Log.i(TAG, "quick paste started len=${text.length}")
        return QuickPasteResult.PASTED
    }

    /** 读主剪贴板里的纯文本；空、无文本（图片/URI 等）或读取失败一律返回 null。 */
    private fun readClipboardText(): String? {
        val manager = clipboardManager ?: return null
        val clip = runCatching { manager.primaryClip }.getOrNull() ?: return null
        if (clip.itemCount <= 0) return null
        val description = clip.description
        val isText = description?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true ||
            description?.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML) == true
        if (!isText) return null
        return runCatching { clip.getItemAt(0)?.coerceToText(getApplication<Application>())?.toString() }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    /** ON_STOP：进入后台。后台一律不自动重连（省电省流量），失败留到回前台再按状态补。 */
    fun onAppBackgrounded() {
        isForeground = false
    }

    /**
     * ON_RESUME：只做一件事——回到前台时如果状态确实已经是 Failed/Disconnected，
     * 就按保守退避策略补排一次自动重连（只排期，不立即重建）。
     *
     * 不再有「后台超过 N 秒就主动重建」这条：上一版 3 秒阈值会在切后台/回前台时
     * 频繁释放并重建 native 实例，是闪退的诱因之一。短暂息屏、快速切走再回来
     * 一律不动；真正的断线由失败状态监听兜底（失败是事件，不依赖 ON_RESUME 的快照）。
     */
    fun onAppForegrounded() {
        isForeground = true
        if (state.value is RdpState.Failed || state.value is RdpState.Disconnected) {
            scheduleAutoReconnect()
        }
    }

    /** 连接失败/断开：仅当前台时按退避策略自动重连，避免后台空耗电和流量。 */
    private fun scheduleAutoReconnect() {
        if (!isForeground) return
        val host = lastHost ?: return
        val width = lastWidth
        val height = lastHeight
        if (width <= 0 || height <= 0) return

        // 已有排期就不重复消耗额度：Failed 状态监听与 ON_RESUME 都可能走到这里。
        if (autoReconnectJob?.isActive == true) return
        val waitMs = reconnectPolicy.nextAutoRetryDelayMs(SystemClock.elapsedRealtime()) ?: return
        autoReconnectJob = viewModelScope.launch {
            delay(waitMs)
            if (!isActive || !isForeground) return@launch
            reconnectNow(host, width, height)
        }
    }

    /**
     * 所有重连触发路径（失败自动重连、回前台重连、手动重试）的唯一入口：
     * 先取消尚未执行的旧计划，再交给 [RdpSession.reconnect]（它会作废在途连接、
     * 释放旧实例），从 ViewModel 到 native 两侧共同保证不会叠出两个会话。
     */
    private fun reconnectNow(host: RdpHost, width: Int, height: Int) {
        autoReconnectJob?.cancel()
        autoReconnectJob = null
        if (reconnectInFlight) return
        reconnectInFlight = true
        startedHostId = host.id
        reconnectPolicy.onReconnectStarted(SystemClock.elapsedRealtime())
        session.reconnect(host, width, height)
    }

    override fun onCleared() {
        autoReconnectJob?.cancel()
        session.close()
    }

    companion object {
        private const val TAG = "QuickPaste"

        /**
         * 快捷粘贴的最小触发间隔。取 1500ms 的理由：
         * - 比 cliprdr 握手的 3s 超时短，连续两次「按住→抬起」在正常手速下很难快过 1.5s；
         * - 与已有的自动重连首次延迟（ReconnectPolicy 1500ms）同一量级，不引入新的时间手感；
         * - 足够吸收「一次长按被系统拆成多段事件」之类的抖动，又不至于让有意的连粘明显变卡。
         */
        const val QUICK_PASTE_DEBOUNCE_MS = 1500L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                RdpSessionViewModel(app)
            }
        }
    }
}
