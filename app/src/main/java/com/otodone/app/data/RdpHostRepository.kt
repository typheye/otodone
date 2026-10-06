package com.otodone.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.hostDataStore by preferencesDataStore(name = "rdp_hosts")

/** 电脑列表的唯一数据源；UI 只订阅 [hosts]。 */
class RdpHostRepository(private val context: Context) {

    private val hostsKey = stringPreferencesKey("hosts_json")

    val hosts: Flow<List<RdpHost>> = context.hostDataStore.data.map { preferences ->
        RdpHostJson.decode(preferences[hostsKey])
    }

    suspend fun upsert(host: RdpHost) {
        update { current ->
            val index = current.indexOfFirst { it.id == host.id }
            if (index >= 0) current.toMutableList().also { it[index] = host }
            else current + host
        }
    }

    suspend fun delete(id: String) {
        update { current -> current.filterNot { it.id == id } }
    }

    suspend fun markConnected(id: String, at: Long = System.currentTimeMillis()) {
        update { current ->
            current.map { if (it.id == id) it.copy(lastConnectedAt = at) else it }
        }
    }

    private suspend fun update(transform: (List<RdpHost>) -> List<RdpHost>) {
        context.hostDataStore.edit { preferences ->
            val current = RdpHostJson.decode(preferences[hostsKey])
            preferences[hostsKey] = RdpHostJson.encode(transform(current))
        }
    }
}
