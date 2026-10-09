package com.otodone.app.ui.session

/**
 * 「连接失败/断开」后自动重连的纯逻辑策略。
 *
 * 不依赖任何 Android API，便于用 JVM 单元测试覆盖状态机；真正发起重连由
 * [RdpSessionViewModel] 负责。所有时间点都由调用方以毫秒传入，测试可完全控制。
 *
 * 设计取向是**保守**：上一版回前台 3 秒就主动重建、失败后每 2 秒释放/重建一次，
 * 重连过于频繁且容易与仍在收尾的 native 连接线程相撞。现在：
 * - 只有状态确实变成 Failed/Disconnected 才会触发自动重连（不看回前台时长）；
 * - 首次失败等 [AUTO_RETRY_DELAY_MS]，之后每次重连之间至少间隔 [MIN_RECONNECT_INTERVAL_MS]；
 * - 连续自动重试达到 [MAX_AUTO_RETRIES] 次后彻底停手，只留「重试」按钮；
 * - 用户手动点「重试」才会清零计数重新开始。
 */
internal class ReconnectPolicy(
    private val autoRetryDelayMs: Long = AUTO_RETRY_DELAY_MS,
    private val minReconnectIntervalMs: Long = MIN_RECONNECT_INTERVAL_MS,
    private val maxAutoRetries: Int = MAX_AUTO_RETRIES,
) {

    private var autoRetryCount = 0
    private var lastReconnectAt = 0L

    /** 连接成功：清零退避，下一次失败可以重新自动重连。 */
    fun onConnected() {
        autoRetryCount = 0
    }

    /** 用户手动重试：清零退避计数。 */
    fun onManualRetry() {
        autoRetryCount = 0
    }

    /**
     * 失败后请求下一次自动重连的延迟毫秒数；返回 null 表示已达上限，不再自动重连。
     *
     * 调用一次即消耗一次重试额度，因此调用方必须先确认「确实要排一次重连」，
     * 不能拿它做纯查询（否则重复排期会把额度白白吃掉，见 ViewModel 的 job 去重）。
     *
     * 同时满足两个约束：失败后先等 [autoRetryDelayMs]；且距离上一次重连发起
     * 至少 [minReconnectIntervalMs]（快速失败时不会高频重连）。
     */
    fun nextAutoRetryDelayMs(nowMs: Long): Long? {
        if (autoRetryCount >= maxAutoRetries) return null
        autoRetryCount++
        val sinceLast = nowMs - lastReconnectAt
        return maxOf(autoRetryDelayMs, minReconnectIntervalMs - sinceLast)
    }

    /** 记录一次重连真正发起的时刻。 */
    fun onReconnectStarted(nowMs: Long) {
        lastReconnectAt = nowMs
    }

    /** 已消耗的连续自动重连次数（测试/日志用）。 */
    val consecutiveAutoRetries: Int get() = autoRetryCount

    companion object {
        /**
         * 失败后延迟多久再自动重连。取 1.5s：既给 native 线程留出收尾时间，
         * 又不让用户干等太久。
         */
        const val AUTO_RETRY_DELAY_MS = 1_500L

        /**
         * 两次自动重连发起之间的最小间隔。上一版是 2s，实测太密；提到 5s 后
         * 即使连接瞬时失败，也不会把释放/重建 native 实例的频率拉满。
         */
        const val MIN_RECONNECT_INTERVAL_MS = 5_000L

        /**
         * 连续自动重连上限（3 次以内）。达到后彻底停手，交给用户手动点「重试」；
         * 手动重试会清零计数。
         */
        const val MAX_AUTO_RETRIES = 3
    }
}
