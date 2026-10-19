package com.otodone.app.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
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
    /** 长按会话页键盘浮标，是否把手机剪贴板粘贴到远端（默认开启）。 */
    val quickPaste: Boolean = true,
    /** 会话页左下角「鼠标右键」浮标是否显示（默认显示）。 */
    val showMouseButton: Boolean = true,
    /** 会话页右下角「键盘」浮标是否显示（默认显示）。 */
    val showKeyboardButton: Boolean = true,
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
    // 快捷粘贴：独立新 key，默认开启；不动上面已有的任何 key。
    private val quickPasteKey = booleanPreferencesKey("session_quick_paste")
    // 会话页「三」菜单 -> 布局：两个浮标的显示开关。同样是独立新 key，默认开启。
    private val showMouseButtonKey = booleanPreferencesKey("session_show_mouse_button")
    private val showKeyboardButtonKey = booleanPreferencesKey("session_show_keyboard_button")

    // 读失败（文件损坏/磁盘问题）不能让 UI 崩：记一条日志后回落到"全部默认值"。
    // DataStore 的 data 流在 IO 异常时会抛，这里必须 catch，否则 SettingsViewModel 的收集会挂掉。
    @Suppress("TooGenericExceptionCaught")
    val settings: Flow<ThemeSettings> = context.settingsDataStore.data
        .catch { t ->
            Log.w(TAG, "read settings failed, fallback to defaults", t)
            emit(emptyPreferences())
        }
        .map { preferences ->
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
            quickPaste = preferences[quickPasteKey] ?: true,
            showMouseButton = preferences[showMouseButtonKey] ?: true,
            showKeyboardButton = preferences[showKeyboardButtonKey] ?: true,
        )
    }

    /**
     * 统一写入口：DataStore 写失败（磁盘满、权限、文件损坏）只记日志。
     *
     * 这些 setter 都从 `viewModelScope.launch` 里调用，异常不接住会变成未捕获协程异常。
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun update(block: (MutablePreferences) -> Unit) {
        runCatching { context.settingsDataStore.edit { block(it) } }
            .onFailure { Log.w(TAG, "write settings failed", it) }
    }

    suspend fun setFollowSystem(enabled: Boolean) = update { it[followSystemKey] = enabled }

    suspend fun setDarkMode(enabled: Boolean) = update { it[darkModeKey] = enabled }

    suspend fun setDynamicColor(enabled: Boolean) = update { it[dynamicColorKey] = enabled }

    suspend fun setOledBlack(enabled: Boolean) = update { it[oledBlackKey] = enabled }

    suspend fun setPasteShortcut(shortcut: PasteShortcut) = update { it[pasteShortcutKey] = shortcut.name }

    suspend fun setSessionOrientation(orientation: SessionOrientation) =
        update { it[sessionOrientationKey] = orientation.name }

    suspend fun setKeepScreenOn(enabled: Boolean) = update { it[keepScreenOnKey] = enabled }

    suspend fun setQuickPaste(enabled: Boolean) = update { it[quickPasteKey] = enabled }

    suspend fun setShowMouseButton(enabled: Boolean) = update { it[showMouseButtonKey] = enabled }

    suspend fun setShowKeyboardButton(enabled: Boolean) = update { it[showKeyboardButtonKey] = enabled }

    private companion object {
        private const val TAG = "ThemeSettings"
    }
}
