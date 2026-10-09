package com.otodone.app.ui.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ReconnectPolicy] 纯 JVM 单元测试。
 *
 * 覆盖保守重连策略的核心约束：
 * 1) 连续失败时的退避、两次重连之间的最小间隔、次数上限（3 次以内）；
 * 2) 手动重试 / 连接成功后的计数清零。
 *
 * 说明：这里不触碰 RdpSession/FreeRDP native，也不覆盖 ViewModel 与生命周期的接线，
 * 那部分见报告里的「验证边界」。
 */
class ReconnectPolicyTest {

    private val policy = ReconnectPolicy()

    @Test
    fun maxAutoRetriesIsThreeOrLess() {
        // 需求：自动重试 3 次以内，三次失败后彻底停手。
        assertTrue(ReconnectPolicy.MAX_AUTO_RETRIES <= 3)
    }

    @Test
    fun autoRetryStopsAtMaxRetries() {
        repeat(ReconnectPolicy.MAX_AUTO_RETRIES) {
            assertNotNull("still retrying before the cap", policy.nextAutoRetryDelayMs(0L))
        }
        assertNull("no more auto retry at the cap", policy.nextAutoRetryDelayMs(0L))
        assertEquals(ReconnectPolicy.MAX_AUTO_RETRIES, policy.consecutiveAutoRetries)
    }

    @Test
    fun manualRetryResetsBackoff() {
        repeat(ReconnectPolicy.MAX_AUTO_RETRIES) { policy.nextAutoRetryDelayMs(0L) }
        assertNull(policy.nextAutoRetryDelayMs(0L))

        policy.onManualRetry()
        assertEquals(0, policy.consecutiveAutoRetries)
        assertNotNull(policy.nextAutoRetryDelayMs(0L))
    }

    @Test
    fun connectedResetsBackoff() {
        repeat(ReconnectPolicy.MAX_AUTO_RETRIES) { policy.nextAutoRetryDelayMs(0L) }
        policy.onConnected()
        assertEquals(0, policy.consecutiveAutoRetries)
        assertNotNull(policy.nextAutoRetryDelayMs(0L))
    }

    @Test
    fun firstRetryWaitsBaseDelay() {
        // 首次失败（还没发起过任何重连）：只等 1.5s 的防抖。
        val delay = policy.nextAutoRetryDelayMs(nowMs = 100_000L)
        assertEquals(1_500L, delay)
        assertEquals(ReconnectPolicy.AUTO_RETRY_DELAY_MS, delay)
    }

    @Test
    fun fastFailureStretchesDelayToMinInterval() {
        // 上一次重连在 t=0 发起，t=200 就失败：距离 5000ms 的最小间隔还差 4800ms，
        // 比 1500ms 的失败延迟大，取 4800ms，因此下一次发起距上次恰好 5000ms。
        policy.onReconnectStarted(nowMs = 0L)
        val delay = policy.nextAutoRetryDelayMs(nowMs = 200L)
        assertEquals(4_800L, delay)
    }

    @Test
    fun pastMinIntervalOnlyWaitsBaseDelay() {
        policy.onReconnectStarted(nowMs = 0L)
        val delay = policy.nextAutoRetryDelayMs(nowMs = 5_000L)
        assertEquals(ReconnectPolicy.AUTO_RETRY_DELAY_MS, delay)
    }

    @Test
    fun consecutiveFastFailuresKeepMinIntervalBetweenStarts() {
        // 最坏情况：每次重连刚发起就失败。第一次是首败的 1.5s 防抖，
        // 之后任意两次重连发起的间隔都不小于 MIN_RECONNECT_INTERVAL_MS。
        var now = 100_000L
        var lastStart = Long.MIN_VALUE
        repeat(ReconnectPolicy.MAX_AUTO_RETRIES) {
            val delay = policy.nextAutoRetryDelayMs(now)!!
            val start = now + delay
            if (lastStart != Long.MIN_VALUE) {
                assertTrue(
                    "retry starts must be >= min interval apart",
                    start - lastStart >= ReconnectPolicy.MIN_RECONNECT_INTERVAL_MS,
                )
            }
            // 记录发起时刻，并模拟「刚发起就失败」。
            policy.onReconnectStarted(start)
            lastStart = start
            now = start
        }
        assertNull(policy.nextAutoRetryDelayMs(now))
    }
}
