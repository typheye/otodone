package com.otodone.app.ui.session

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.otodone.app.data.PasteShortcut
import com.otodone.app.data.RdpHost
import com.otodone.app.data.ThemeSettingsRepository
import com.otodone.app.rdp.RdpSession
import com.otodone.app.rdp.RdpSurfaceView
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** 会话页 ViewModel：持有一次 [RdpSession]，UI 只读 StateFlow。 */
class RdpSessionViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsRepository = ThemeSettingsRepository(app)

    /**
     * 当前的粘贴快捷键。会话运行中改设置也能立刻生效：
     * onText() 发送粘贴时读取的是这个 StateFlow 的最新值，无需重启会话。
     */
    private val pasteShortcut: StateFlow<PasteShortcut> = settingsRepository.settings
        .map { it.pasteShortcut }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PasteShortcut.CTRL_SHIFT_V)

    private val session = RdpSession(app, pasteShortcut)

    val state = session.state
    val bitmap = session.bitmap
    val frameTick = session.frameTick
    val inputListener: RdpSurfaceView.Listener = session

    private var startedHostId: String? = null

    fun connect(host: RdpHost, width: Int, height: Int) {
        if (startedHostId == host.id) return
        startedHostId = host.id
        session.connect(host, width, height)
    }

    fun retry(host: RdpHost, width: Int, height: Int) {
        startedHostId = null
        connect(host, width, height)
    }

    override fun onCleared() {
        session.close()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                RdpSessionViewModel(app)
            }
        }
    }
}
