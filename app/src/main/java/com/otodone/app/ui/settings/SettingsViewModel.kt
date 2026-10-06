package com.otodone.app.ui.settings

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.otodone.app.data.PasteShortcut
import com.otodone.app.data.ThemeSettings
import com.otodone.app.data.ThemeSettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 外观设置的 ViewModel。MainActivity 用 Activity 级实例做主题来源，
 * 这样设置页改动后会立刻反映到整个 Compose 树。
 */
class SettingsViewModel(private val repository: ThemeSettingsRepository) : ViewModel() {

    val settings: StateFlow<ThemeSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeSettings())

    fun setFollowSystem(enabled: Boolean) {
        viewModelScope.launch { repository.setFollowSystem(enabled) }
    }

    fun setDarkMode(enabled: Boolean) {
        viewModelScope.launch { repository.setDarkMode(enabled) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { repository.setDynamicColor(enabled) }
    }

    fun setOledBlack(enabled: Boolean) {
        viewModelScope.launch { repository.setOledBlack(enabled) }
    }

    fun setPasteShortcut(shortcut: PasteShortcut) {
        viewModelScope.launch { repository.setPasteShortcut(shortcut) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                SettingsViewModel(ThemeSettingsRepository(app))
            }
        }
    }
}
