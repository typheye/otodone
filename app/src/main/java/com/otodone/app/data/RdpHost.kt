package com.otodone.app.data

import java.util.UUID

/**
 * 一台可通过 RDP 连接的电脑。
 *
 * [password] 目前明文保存在 DataStore 中，仅用于打通链路；
 * TODO: 迁移到 Keystore 加密存储（或改为每次连接时输入）。
 */
data class RdpHost(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val address: String = "",
    val port: Int = DEFAULT_PORT,
    val username: String = "",
    val domain: String = "",
    val password: String = "",
    val lastConnectedAt: Long = 0L,
) {
    val displayName: String
        get() = name.ifBlank { address }

    val endpoint: String
        get() = "$address:$port"

    companion object {
        const val DEFAULT_PORT = 3389
    }
}
