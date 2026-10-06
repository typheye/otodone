package com.otodone.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.otodone.app.data.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF2F6BFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE6FF),
    onPrimaryContainer = Color(0xFF001A41),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9DB6FF),
    onPrimary = Color(0xFF002B75),
    primaryContainer = Color(0xFF1B3E86),
    onPrimaryContainer = Color(0xFFDCE6FF),
)

/**
 * 应用主题。
 *
 * - [themeMode] 决定跟随系统 / 浅色 / 深色（值来自 DataStore，设置页改动后立即重组）；
 * - [dynamicColor] 控制是否使用莫奈动态取色（Android 12+）。
 *
 * 默认值保持原行为（跟随系统 + 动态取色），方便预览或其他调用方。
 *
 * 注：曾经有「启用纯黑背景（OLED）」开关，因会把界面细节压成一片纯黑、观感变差，已移除。
 */
@Composable
fun OtoDoneTheme(
    themeMode: ThemeMode = ThemeMode.FOLLOW_SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val baseScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = baseScheme, content = content)
}

/**
 * 把深色方案里的表面/背景系列整体压成纯黑。
 *
 * 覆盖 background/surface 保证 Scaffold 与 TopAppBar 变黑；
 * 覆盖 surfaceContainer 系列保证设置卡片等容器跟着变黑；
 * 只改容器色、不动 on* 前景色，所以文字/图标的对比度不受影响。
 */
private fun ColorScheme.withOledBlack(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceBright = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color.Black,
    surfaceContainer = Color.Black,
    surfaceContainerHigh = Color.Black,
    surfaceContainerHighest = Color.Black,
)
