package com.otodone.app.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 加粗版「屏幕方向」图标。
 *
 * Material 自带的 ScreenRotation 线条太细，跟设置页里相邻的实心图标（调色盘 / 实心 i / 刷子）
 * 放在一起不搭。这里自绘一版：粗描边手机 + 左侧上下双箭头（表示可切换横竖屏）。
 * 刻意不用圆弧——弧线参数容易画歪，直线+圆角端点更稳。
 */
val ScreenOrientationIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "ScreenOrientation",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        val stroke = 2.2f

        // 竖屏手机外框
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = stroke,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(8.6f, 2.8f)
            lineTo(16.4f, 2.8f)
            lineTo(16.4f, 21.2f)
            lineTo(8.6f, 21.2f)
            close()
        }

        // 屏幕底部的小横线（听筒/指示）
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = stroke,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(11.2f, 18.4f)
            lineTo(13.8f, 18.4f)
        }

        // 左侧上下双箭头：切换竖屏/横屏
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = stroke,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(4.4f, 7.6f)
            lineTo(4.4f, 16.4f)
            // 上箭头
            moveTo(2.4f, 9.6f)
            lineTo(4.4f, 7.6f)
            lineTo(6.4f, 9.6f)
            // 下箭头
            moveTo(2.4f, 14.4f)
            lineTo(4.4f, 16.4f)
            lineTo(6.4f, 14.4f)
        }
    }.build()
}
