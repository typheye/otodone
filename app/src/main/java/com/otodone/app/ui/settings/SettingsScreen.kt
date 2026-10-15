package com.otodone.app.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.lazy.LazyColumn
import com.otodone.app.ui.components.ConfirmSheet
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ContentPasteGo
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Palette
import com.otodone.app.ui.icons.ScreenOrientationIcon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.otodone.app.R
import com.otodone.app.data.PasteShortcut
import com.otodone.app.data.SessionOrientation
import com.otodone.app.data.ThemeSettings
import com.otodone.app.ui.insets.rememberSystemBarInsets
import com.otodone.app.ui.components.ActionPreferenceRow
import com.otodone.app.ui.components.PreferenceCard
import com.otodone.app.ui.components.PreferenceDivider
import com.otodone.app.ui.components.SectionLabel
import com.otodone.app.ui.components.SingleChoiceItem
import com.otodone.app.ui.components.SingleChoiceSheet
import com.otodone.app.ui.components.SwitchPreferenceRow
import com.otodone.app.ui.components.ValuePreferenceRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页：布局复刻 wgpro 的 fragment_settings_md3.xml（分组标题 + 卡片行 + 分隔线）。
 *
 * 用户点名的两项在这里：顶部"自动切换主题"开关，底部"返回"条目；
 * "深色模式"只在关闭"自动切换主题"后出现（与 wgpro 一致），
 * "动态取色"对应 wgpro 的"启用高斯模糊"这类外观开关。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: ThemeSettings,
    onFollowSystemChange: (Boolean) -> Unit,
    onDarkModeChange: (Boolean) -> Unit,
    onOledBlackChange: (Boolean) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onPasteShortcutChange: (PasteShortcut) -> Unit,
    onSessionOrientationChange: (SessionOrientation) -> Unit,
    onKeepScreenOnChange: (Boolean) -> Unit,
    onQuickPasteChange: (Boolean) -> Unit,
    onOpenAbout: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val windowInsets = rememberSystemBarInsets()
    val cacheClearedMessage = stringResource(R.string.settings_cache_cleared)
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showPasteShortcutSheet by remember { mutableStateOf(false) }
    var showSessionOrientationSheet by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = windowInsets.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Top,
                ),
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // 统一的 SystemBars inset：小窗取 max 兜底，底部不会多出一截、也不会被压住。
        contentWindowInsets = windowInsets,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // 用 contentPadding 而不是 Modifier.padding：背景可以铺到系统栏底下，
            // 同时首尾项也能完整滚进安全区（Modifier.padding 会把整个列表视口缩小）。
            contentPadding = PaddingValues(
                start = 16.dp,
                top = padding.calculateTopPadding() + 12.dp,
                end = 16.dp,
                bottom = padding.calculateBottomPadding() + 28.dp,
            ),
        ) {
            item { SectionLabel(stringResource(R.string.settings_section_general)) }
            item {
                PreferenceCard {
                    SwitchPreferenceRow(
                        icon = Icons.Filled.Brightness6,
                        title = stringResource(R.string.settings_follow_system),
                        subtitle = stringResource(R.string.settings_follow_system_desc),
                        checked = settings.followSystem,
                        onCheckedChange = onFollowSystemChange,
                    )
                    if (!settings.followSystem) {
                        PreferenceDivider()
                        SwitchPreferenceRow(
                            icon = Icons.Filled.DarkMode,
                            title = stringResource(R.string.settings_dark_mode),
                            subtitle = stringResource(R.string.settings_dark_mode_desc),
                            checked = settings.darkMode,
                            onCheckedChange = onDarkModeChange,
                        )
                    }
                    PreferenceDivider()
                    SwitchPreferenceRow(
                        icon = Icons.Filled.Palette,
                        title = stringResource(R.string.settings_dynamic_color),
                        subtitle = stringResource(R.string.settings_dynamic_color_desc),
                        checked = settings.dynamicColor,
                        onCheckedChange = onDynamicColorChange,
                    )
                }
            }

            // 会话相关设置单独分组
            item { SectionLabel(stringResource(R.string.settings_section_session), topSpace = true) }
            item {
                PreferenceCard {
                    SwitchPreferenceRow(
                        icon = Icons.Filled.Lightbulb,
                        title = stringResource(R.string.settings_keep_screen_on),
                        subtitle = stringResource(R.string.settings_keep_screen_on_desc),
                        checked = settings.keepScreenOn,
                        onCheckedChange = onKeepScreenOnChange,
                    )
                    PreferenceDivider()
                    SwitchPreferenceRow(
                        icon = Icons.Filled.ContentPasteGo,
                        title = stringResource(R.string.settings_quick_paste),
                        subtitle = stringResource(R.string.settings_quick_paste_desc),
                        checked = settings.quickPaste,
                        onCheckedChange = onQuickPasteChange,
                    )
                    PreferenceDivider()
                    ValuePreferenceRow(
                        icon = ScreenOrientationIcon,
                        title = stringResource(R.string.settings_screen_orientation),
                        subtitle = stringResource(R.string.settings_screen_orientation_desc),
                        value = screenOrientationLabel(settings.sessionOrientation),
                        onClick = { showSessionOrientationSheet = true },
                    )
                    PreferenceDivider()
                    ValuePreferenceRow(
                        icon = Icons.Filled.ContentPaste,
                        title = stringResource(R.string.settings_paste_shortcut),
                        subtitle = stringResource(R.string.settings_paste_shortcut_desc),
                        value = pasteShortcutLabel(settings.pasteShortcut),
                        onClick = { showPasteShortcutSheet = true },
                    )
                }
            }

            item { SectionLabel(stringResource(R.string.settings_section_app), topSpace = true) }
            item {
                PreferenceCard {
                    ActionPreferenceRow(
                        icon = Icons.Filled.Info,
                        title = stringResource(R.string.about_title),
                        subtitle = stringResource(R.string.about_summary),
                        onClick = onOpenAbout,
                    )
                    PreferenceDivider()
                    ActionPreferenceRow(
                        icon = Icons.Filled.CleaningServices,
                        title = stringResource(R.string.settings_clear_cache),
                        subtitle = stringResource(R.string.settings_clear_cache_desc),
                        onClick = { showClearCacheDialog = true },
                    )
                }
            }

        }
    }

    if (showClearCacheDialog) {
        ConfirmSheet(
            title = stringResource(R.string.settings_clear_cache),
            message = stringResource(R.string.settings_clear_cache_confirm),
            confirmLabel = stringResource(R.string.action_clear),
            dismissLabel = stringResource(R.string.action_cancel),
            onConfirm = {
                showClearCacheDialog = false
                val appContext = context.applicationContext
                scope.launch {
                    withContext(Dispatchers.IO) { clearCache(appContext) }
                    snackbarHostState.showSnackbar(cacheClearedMessage)
                }
            },
            onDismiss = { showClearCacheDialog = false },
        )
    }

    if (showSessionOrientationSheet) {
        SingleChoiceSheet(
            title = stringResource(R.string.settings_screen_orientation),
            items = SessionOrientation.entries.map { orientation ->
                SingleChoiceItem(
                    value = orientation,
                    label = screenOrientationLabel(orientation),
                    description = screenOrientationDescription(orientation),
                )
            },
            selected = settings.sessionOrientation,
            onSelect = { orientation ->
                onSessionOrientationChange(orientation)
                showSessionOrientationSheet = false
            },
            onDismiss = { showSessionOrientationSheet = false },
        )
    }

    if (showPasteShortcutSheet) {
        SingleChoiceSheet(
            title = stringResource(R.string.settings_paste_shortcut),
            items = PasteShortcut.entries.map { shortcut ->
                SingleChoiceItem(
                    value = shortcut,
                    label = pasteShortcutLabel(shortcut),
                    description = pasteShortcutDescription(shortcut),
                )
            },
            selected = settings.pasteShortcut,
            onSelect = { shortcut ->
                onPasteShortcutChange(shortcut)
                showPasteShortcutSheet = false
            },
            onDismiss = { showPasteShortcutSheet = false },
        )
    }
}

/** 屏幕方向的展示文本。 */
@Composable
private fun screenOrientationLabel(orientation: SessionOrientation): String = stringResource(
    when (orientation) {
        SessionOrientation.AUTO -> R.string.screen_orientation_auto
        SessionOrientation.LANDSCAPE -> R.string.screen_orientation_landscape
        SessionOrientation.PORTRAIT -> R.string.screen_orientation_portrait
    },
)

/** 屏幕方向的说明文本。 */
@Composable
private fun screenOrientationDescription(orientation: SessionOrientation): String = stringResource(
    when (orientation) {
        SessionOrientation.AUTO -> R.string.screen_orientation_auto_desc
        SessionOrientation.LANDSCAPE -> R.string.screen_orientation_landscape_desc
        SessionOrientation.PORTRAIT -> R.string.screen_orientation_portrait_desc
    },
)

/** 粘贴快捷键的展示文本。 */
@Composable
private fun pasteShortcutLabel(shortcut: PasteShortcut): String = stringResource(
    when (shortcut) {
        PasteShortcut.CTRL_SHIFT_V -> R.string.paste_shortcut_ctrl_shift_v
        PasteShortcut.CTRL_V -> R.string.paste_shortcut_ctrl_v
        PasteShortcut.SHIFT_INSERT -> R.string.paste_shortcut_shift_insert
    },
)

/** 粘贴快捷键的适用场景说明。 */
@Composable
private fun pasteShortcutDescription(shortcut: PasteShortcut): String = stringResource(
    when (shortcut) {
        PasteShortcut.CTRL_SHIFT_V -> R.string.paste_shortcut_ctrl_shift_v_desc
        PasteShortcut.CTRL_V -> R.string.paste_shortcut_ctrl_v_desc
        PasteShortcut.SHIFT_INSERT -> R.string.paste_shortcut_shift_insert_desc
    },
)

/** 清空应用的缓存目录内容；失败也不抛出，避免影响界面。 */
private fun clearCache(context: Context) {
    runCatching {
        context.cacheDir?.listFiles()?.forEach { it.deleteRecursively() }
        context.externalCacheDir?.listFiles()?.forEach { it.deleteRecursively() }
    }
}
