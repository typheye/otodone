package com.otodone.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 主题模式：跟随系统 / 浅色 / 深色。 */
enum class ThemeMode { FOLLOW_SYSTEM, LIGHT, DARK }

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

    val settings: Flow<ThemeSettings> = context.settingsDataStore.data.map { preferences ->
        ThemeSettings(
            followSystem = preferences[followSystemKey] ?: true,
            darkMode = preferences[darkModeKey] ?: false,
            dynamicColor = preferences[dynamicColorKey] ?: true,
            oledBlack = preferences[oledBlackKey] ?: false,
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
}
