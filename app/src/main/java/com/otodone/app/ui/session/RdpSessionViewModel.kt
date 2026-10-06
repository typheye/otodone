package com.otodone.app.ui.session

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.otodone.app.data.RdpHost
import com.otodone.app.rdp.RdpSession
import com.otodone.app.rdp.RdpSurfaceView

/** 会话页 ViewModel：持有一次 [RdpSession]，UI 只读 StateFlow。 */
class RdpSessionViewModel(app: Application) : AndroidViewModel(app) {

    private val session = RdpSession(app)

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
