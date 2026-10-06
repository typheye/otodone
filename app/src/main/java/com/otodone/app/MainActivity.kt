package com.otodone.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.otodone.app.data.PasteShortcut
import com.otodone.app.data.ThemeSettings
import com.otodone.app.ui.about.AboutScreen
import com.otodone.app.ui.hosts.HostListRoute
import com.otodone.app.ui.session.SessionRoute
import com.otodone.app.ui.settings.SettingsScreen
import com.otodone.app.ui.settings.SettingsViewModel
import com.otodone.app.ui.splash.SplashMask
import com.otodone.app.ui.theme.OtoDoneTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Activity 级 ViewModel：启动时就从 DataStore 读出主题设置，
            // 设置页改动后整个 Compose 树立即重组，实现"改完立刻生效"。
            val settingsViewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)
            val settings by settingsViewModel.settings.collectAsState()

            OtoDoneTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
            ) {
                OtoDoneRoot(
                    settings = settings,
                    onFollowSystemChange = settingsViewModel::setFollowSystem,
                    onDarkModeChange = settingsViewModel::setDarkMode,
                    onOledBlackChange = settingsViewModel::setOledBlack,
                    onDynamicColorChange = settingsViewModel::setDynamicColor,
                    onPasteShortcutChange = settingsViewModel::setPasteShortcut,
                )
            }
        }
    }

}

private object Routes {
    const val HOSTS = "hosts"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
    const val SESSION = "session/{hostId}"
    fun session(hostId: String) = "session/$hostId"
}

@Composable
private fun OtoDoneRoot(
    settings: ThemeSettings,
    onFollowSystemChange: (Boolean) -> Unit,
    onDarkModeChange: (Boolean) -> Unit,
    onOledBlackChange: (Boolean) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onPasteShortcutChange: (PasteShortcut) -> Unit,
) {
    val navController = rememberNavController()
    var splashVisible by remember { mutableStateOf(true) }

    Box(Modifier.fillMaxSize()) {
        NavHost(navController = navController, startDestination = Routes.HOSTS) {
            composable(Routes.HOSTS) {
                HostListRoute(
                    onConnect = { hostId -> navController.navigate(Routes.session(hostId)) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    settings = settings,
                    onFollowSystemChange = onFollowSystemChange,
                    onDarkModeChange = onDarkModeChange,
                    onOledBlackChange = onOledBlackChange,
                    onDynamicColorChange = onDynamicColorChange,
                    onPasteShortcutChange = onPasteShortcutChange,
                    onOpenAbout = { navController.navigate(Routes.ABOUT) },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.ABOUT) {
                AboutScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = Routes.SESSION,
                arguments = listOf(navArgument("hostId") { type = NavType.StringType }),
            ) { entry ->
                SessionRoute(
                    hostId = entry.arguments?.getString("hostId").orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
        }

        // 启动遮罩叠在导航之上，动画结束后淡出（复刻 wgpro 的闪屏观感）
        AnimatedVisibility(
            visible = splashVisible,
            enter = EnterTransition.None,
            exit = fadeOut(animationSpec = tween(durationMillis = 250)),
        ) {
            SplashMask(onFinished = { splashVisible = false })
        }
    }
}
