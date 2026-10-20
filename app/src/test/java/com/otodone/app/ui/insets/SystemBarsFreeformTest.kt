package com.otodone.app.ui.insets

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 小窗/分屏判定的纯 JVM 回归测试。
 *
 * 对应 wgpro `SystemBars.isFreeformOrSplit()` 里的 WindowMetrics 兜底：
 * **当前窗口外框与最大窗口外框任一方向更小**即视为自由窗/分屏，不能使用百分比阈值
 * （MIUI 小窗拉伸/展开后会变大，越过分分钟被误判成全屏）。
 *
 * 这里只测几何比较本身；Activity / WindowManager 的真实取值路径在模拟器上验证，
 * 见 `_guide/verify-sheets/REPORT.md` 的本轮追加记录。
 */
class SystemBarsFreeformTest {

    private val screenWidth = 1280
    private val screenHeight = 2856

    @Test
    fun smallerInBothDirectionsIsFreeform() {
        assertTrue(
            isWindowSmallerThanMaximum(980, 1680, screenWidth, screenHeight),
        )
    }

    @Test
    fun smallerWidthOnlyIsSplitOrFreeform() {
        // 左右分屏：高度与屏幕相等，只有宽度更小。
        assertTrue(
            isWindowSmallerThanMaximum(screenWidth / 2, screenHeight, screenWidth, screenHeight),
        )
    }

    @Test
    fun smallerHeightOnlyIsSplitOrFreeform() {
        // 上下分屏：宽度与屏幕相等，只有高度更小。
        assertTrue(
            isWindowSmallerThanMaximum(screenWidth, screenHeight / 2, screenWidth, screenHeight),
        )
    }

    @Test
    fun anySmallerPixelsCountWithoutPercentageThreshold() {
        // 只小 1px 也算小窗：wgpro 原文就是严格小于，没有百分比阈值。
        assertTrue(isWindowSmallerThanMaximum(1279, 2855, screenWidth, screenHeight))
        // 即便“看起来很大”，只要比最大窗口小就仍算自由窗/分屏。
        assertTrue(isWindowSmallerThanMaximum(1200, 2800, screenWidth, screenHeight))
    }

    @Test
    fun equalBoundsAreFullscreen() {
        assertFalse(isWindowSmallerThanMaximum(screenWidth, screenHeight, screenWidth, screenHeight))
    }
}
