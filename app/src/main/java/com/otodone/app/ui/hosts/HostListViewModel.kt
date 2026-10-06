package com.otodone.app.ui.hosts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.otodone.app.OtoDoneApp
import com.otodone.app.data.RdpHost
import com.otodone.app.data.RdpHostRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HostListViewModel(private val repository: RdpHostRepository) : ViewModel() {

    val hosts: StateFlow<List<RdpHost>> = repository.hosts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun save(host: RdpHost) = viewModelScope.launch { repository.upsert(host) }

    fun delete(id: String) = viewModelScope.launch { repository.delete(id) }

    fun markConnected(id: String) = viewModelScope.launch { repository.markConnected(id) }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as OtoDoneApp
                HostListViewModel(app.hostRepository)
            }
        }
    }
}
