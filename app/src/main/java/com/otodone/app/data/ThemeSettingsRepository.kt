package com.otodone.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 主题模式：跟随系统 / 浅色 / 深色。 */
enum class ThemeMode { FOLLOW_SYSTEM, LIGHT, DARK }

/**
 * 中文输入走「远端剪贴板 + 快捷键粘贴」，而不同系统/终端的粘贴组合键不同：
 * Linux 终端是 Ctrl+Shift+V，Windows/多数 GTK 应用是 Ctrl+V，经典 X11 是 Shift+Insert。
 */
enum class PasteShortcut { CTRL_SHIFT_V, CTRL_V, SHIFT_INSERT }

/**
 * 会话页的屏幕方向。
 *
 * AUTO 只是"跟随系统自动旋转"：会话页运行时会显式把 requestedOrientation 设为
 * SCREEN_ORIENTATION_UNSPECIFIED，用来覆盖 AndroidManifest 里 MainActivity 的 portrait；
 * 其余两项强制横/竖屏。退出会话时统一还原成进入前的方向。
 */
enum class SessionOrientation { AUTO, LANDSCAPE, PORTRAIT }

/**
 * 主题相关设置的快照。
 *
 * 语义与 wgpro 保持一致：wgpro 用 SharedPreferences 里的两个布尔值
 * "theme_follow_system" 与 "theme_dark_mode" 来描述主题；这里沿用同样的语义，
 * 另外多存一个"动态取色"开关（相当于 wgpro 的"启用高斯模糊"，属于外观开关）。
 */
data class ThemeSettings(
    val followSystem: Boolean = true,
    val darkMode: Boolean = false,
    val dynamicColor: Boolean = true,
    /** 最终解析为深色时，是否把表面/背景压成纯黑（OLED 省电）。 */
    val oledBlack: Boolean = false,
    /** 发送到远端用于粘贴剪贴板的组合键（默认 Linux 终端的 Ctrl+Shift+V）。 */
    val pasteShortcut: PasteShortcut = PasteShortcut.CTRL_SHIFT_V,
    /** 进入会话页时的屏幕方向（默认自适应，跟随系统自动旋转）。 */
    val sessionOrientation: SessionOrientation = SessionOrientation.AUTO,
    /** 会话期间是否保持屏幕常亮（默认开启）。 */
    val keepScreenOn: Boolean = true,
) {
    val themeMode: ThemeMode
        get() = when {
            followSystem -> ThemeMode.FOLLOW_SYSTEM
            darkMode -> ThemeMode.DARK
            else -> ThemeMode.LIGHT
        }
}

// DataStore 文件独立于 "rdp_hosts"，避免和电脑列表互相影响。
private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/** 应用外观设置的唯一数据源。 */
class ThemeSettingsRepository(private val context: Context) {

    private val followSystemKey = booleanPreferencesKey("theme_follow_system")
    private val darkModeKey = booleanPreferencesKey("theme_dark_mode")
    private val dynamicColorKey = booleanPreferencesKey("theme_dynamic_color")
    private val oledBlackKey = booleanPreferencesKey("theme_oled_black")

    // 新增 key，不动已有 key；存枚举名，读到未知值时回落到默认值。
    private val pasteShortcutKey = stringPreferencesKey("paste_shortcut")
    private val sessionOrientationKey = stringPreferencesKey("session_orientation")
    private val keepScreenOnKey = booleanPreferencesKey("session_keep_screen_on")

    val settings: Flow<ThemeSettings> = context.settingsDataStore.data.map { preferences ->
        ThemeSettings(
            followSystem = preferences[followSystemKey] ?: true,
            darkMode = preferences[darkModeKey] ?: false,
            dynamicColor = preferences[dynamicColorKey] ?: true,
            oledBlack = preferences[oledBlackKey] ?: false,
            pasteShortcut = preferences[pasteShortcutKey]
                ?.let { name -> PasteShortcut.entries.firstOrNull { it.name == name } }
                ?: PasteShortcut.CTRL_SHIFT_V,
            sessionOrientation = preferences[sessionOrientationKey]
                ?.let { name -> SessionOrientation.entries.firstOrNull { it.name == name } }
                ?: SessionOrientation.AUTO,
            keepScreenOn = preferences[keepScreenOnKey] ?: true,
        )
    }

    suspend fun setFollowSystem(enabled: Boolean) {
        context.settingsDataStore.edit { it[followSystemKey] = enabled }
    }

    suspend fun setDarkMode(enabled: Boolean) {
        context.settingsDataStore.edit { it[darkModeKey] = enabled }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.settingsDataStore.edit { it[dynamicColorKey] = enabled }
    }

    suspend fun setOledBlack(enabled: Boolean) {
        context.settingsDataStore.edit { it[oledBlackKey] = enabled }
    }

    suspend fun setPasteShortcut(shortcut: PasteShortcut) {
        context.settingsDataStore.edit { it[pasteShortcutKey] = shortcut.name }
    }

    suspend fun setSessionOrientation(orientation: SessionOrientation) {
        context.settingsDataStore.edit { it[sessionOrientationKey] = orientation.name }
    }

    suspend fun setKeepScreenOn(enabled: Boolean) {
        context.settingsDataStore.edit { it[keepScreenOnKey] = enabled }
    }
}
